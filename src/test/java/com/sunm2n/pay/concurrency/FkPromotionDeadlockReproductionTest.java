package com.sunm2n.pay.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

import com.sunm2n.pay.common.persistence.MySqlLockErrors;
import com.sunm2n.pay.payment.application.PaymentService;
import com.sunm2n.pay.payment.application.confirmation.PaymentConfirmer;
import com.sunm2n.pay.payment.application.confirmation.RetryMetrics;
import com.sunm2n.pay.payment.domain.Payment;
import com.sunm2n.pay.payment.domain.PaymentMethod;
import com.sunm2n.pay.support.AbstractIntegrationTest;
import com.sunm2n.pay.support.ConcurrencyGate;
import com.sunm2n.pay.support.ConcurrentRunner;
import com.sunm2n.pay.support.Seeds;
import com.sunm2n.pay.wallet.application.WalletService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.CannotAcquireLockException;

/**
 * S4 재현 — FK 검사의 S 락이 지갑 UPDATE 의 X 락 승격을 막아 데드락이 된다.
 *
 * <p><b>최신 스키마(V3, FK 있음)</b>에서 돈다. 전제는 "본선의 wallet 선행 잠금을 뺀 독립 비교 실험" 이다 (SCENARIO 161행). 결제 CAS 는
 * 유지하고, 결제는 워커마다 다르다 — payment 락은 겹치지 않고 경쟁은 같은 wallet 행에서만 일어난다.
 *
 * <pre>
 * T1  INSERT wallet_ledger   FK 검사 → wallet S
 * T2  INSERT wallet_ledger   FK 검사 → wallet S      (공존) ── LEDGER_INSERTED barrier
 * T1  UPDATE wallet          X 필요 → T2 의 S 에 막힘
 * T2  UPDATE wallet          X 필요 → T1 의 S 에 막힘 → 순환 → 한쪽 전체 롤백 (1213)
 * </pre>
 *
 * <p>S1~S3 의 재현과 달리 <b>정합성은 깨지지 않는다.</b> 피해 트랜잭션은 통째로 롤백되고 불변식은 유지된다. 데드락은 요청 하나가 실패하는 가용성 문제다.
 *
 * <p><b>이 테스트는 데드락이 재현될 때 green 이다.</b>
 */
class FkPromotionDeadlockReproductionTest extends AbstractIntegrationTest {

  private static final int WORKERS = 2;
  private static final String ORDER_ID = "order-s4";
  private static final long AMOUNT = 3_000L;

  /** 잔액 부족이 섞이지 않게 넉넉히 둔다. */
  private static final long INITIAL_BALANCE = 100_000L;

  /**
   * 데드락 감지가 락 대기 타임아웃(기본 50초)을 기다리지 않았다고 볼 상한. 게이트 상한(10초)과 같다 — 측정 구간이 스케줄링·SQL 실행·예외 전달을 포함하므로
   * 정밀한 값이 아니라 "타임아웃과 자릿수가 다르다" 는 판정이다.
   */
  private static final Duration DETECTION_UPPER_BOUND = Duration.ofSeconds(10);

  @Autowired private PaymentService paymentService;
  @Autowired private WalletService walletService;

  @Autowired
  @Qualifier("ledgerFirstDebitConfirmer")
  private PaymentConfirmer ledgerFirstDebitConfirmer;

  @Autowired
  @Qualifier("retryingLedgerFirstDebitConfirmer")
  private PaymentConfirmer retryingLedgerFirstDebitConfirmer;

  @Autowired
  @Qualifier("deadlockRetryMetrics")
  private RetryMetrics deadlockRetryMetrics;

  @Autowired
  @Qualifier("atomicDebitConfirmer")
  private PaymentConfirmer atomicDebitConfirmer;

  private Long merchantId;

  @BeforeEach
  void setUp() {
    merchantId = merchantIdOf(Seeds.MERCHANT_1_API_KEY);
    deadlockRetryMetrics.reset();
  }

  @Test
  @DisplayName("재현 1 - 원장 INSERT 뒤 지갑 UPDATE 가 서로의 S 락을 기다려 한 건이 1213 으로 전체 롤백된다")
  void ledgerFirstDebitsDeadlock() {
    walletService.charge(Seeds.MEMBER_ID_1, INITIAL_BALANCE);
    List<String> paymentKeys = createPayments(WORKERS);
    Queue<String> queue = new ConcurrentLinkedQueue<>(paymentKeys);
    AtomicLong failedAt = new AtomicLong();

    concurrencyGate.arm(ConcurrencyGate.LEDGER_INSERTED, WORKERS);
    ConcurrentRunner.Results<Payment> results =
        ConcurrentRunner.run(
            WORKERS,
            () -> {
              try {
                return ledgerFirstDebitConfirmer.confirm(
                    merchantId, queue.poll(), ORDER_ID, AMOUNT);
              } catch (RuntimeException e) {
                failedAt.set(System.nanoTime());
                throw e;
              }
            });

    assertThat(results.successCount()).as("순환은 한 번, 피해자는 하나").isEqualTo(1);
    assertThat(results.failures()).hasSize(1);
    Throwable victim = results.failures().get(0);
    assertThat(victim).isInstanceOf(CannotAcquireLockException.class);
    assertThat(MySqlLockErrors.isDeadlock(victim)).as("타입이 아니라 에러 코드로 판정한다 - 원인 체인에 1213").isTrue();

    Duration detection =
        Duration.ofNanos(
            failedAt.get()
                - concurrencyGate.trippedAtNanos(ConcurrencyGate.LEDGER_INSERTED).orElseThrow());
    System.out.printf("[S4 재현 1] barrier 해제 → 피해자 예외 수신: %d ms%n", detection.toMillis());
    assertThat(detection)
        .as("락 대기 타임아웃(50초)까지 기다리지 않는다. 스케줄링·SQL·예외 전달을 포함한 상한")
        .isLessThan(DETECTION_UPPER_BOUND);

    assertThat(statusCount("DONE")).isEqualTo(1);
    assertThat(statusCount("READY"))
        .as("피해자는 CAS 의 IN_PROGRESS 까지 함께 롤백되어 READY 로 돌아간다")
        .isEqualTo(1);
    assertThat(payLedgerCount()).as("피해자의 원장 INSERT 도 롤백됐다").isEqualTo(1);
    assertThat(balanceOf(Seeds.MEMBER_ID_1)).isEqualTo(INITIAL_BALANCE - AMOUNT);
    invariants.assertAll();
  }

  @Test
  @DisplayName("재현 2 - 1213 한정 재시도로 피해 트랜잭션을 새 트랜잭션에서 다시 하면 둘 다 성공한다")
  void retryingTheVictimConverges() {
    walletService.charge(Seeds.MEMBER_ID_1, INITIAL_BALANCE);
    Queue<String> queue = new ConcurrentLinkedQueue<>(createPayments(WORKERS));

    // 재시도는 같은 워커 스레드에서 돈다. 게이트는 스레드당 한 번만 막으므로 두 번째 INSERT 는 그냥 지나간다.
    concurrencyGate.arm(ConcurrencyGate.LEDGER_INSERTED, WORKERS);
    ConcurrentRunner.Results<Payment> results =
        ConcurrentRunner.run(
            WORKERS,
            () ->
                retryingLedgerFirstDebitConfirmer.confirm(
                    merchantId, queue.poll(), ORDER_ID, AMOUNT));

    assertThat(results.failures()).isEmpty();
    assertThat(results.successCount()).isEqualTo(WORKERS);
    assertThat(deadlockRetryMetrics.conflicts()).as("데드락은 한 번").isEqualTo(1);
    assertThat(deadlockRetryMetrics.retries()).isEqualTo(1);
    assertThat(deadlockRetryMetrics.exhausted()).isZero();

    assertThat(statusCount("DONE")).isEqualTo(WORKERS);
    assertThat(payLedgerCount()).as("재시도가 원장을 중복시키지 않는다").isEqualTo(WORKERS);
    assertThat(balanceOf(Seeds.MEMBER_ID_1)).isEqualTo(INITIAL_BALANCE - WORKERS * AMOUNT);
    invariants.assertAll();
  }

  @Test
  @DisplayName("대조 - 같은 두 문장을 UPDATE → INSERT 순서로 보내면 UPDATE 가 X 락을 선점해 데드락이 나지 않는다")
  void updateFirstDoesNotDeadlock() {
    walletService.charge(Seeds.MEMBER_ID_1, INITIAL_BALANCE);
    Queue<String> queue = new ConcurrentLinkedQueue<>(createPayments(WORKERS));

    // 원자적 감산은 잔액을 잠그지 않고 읽은 뒤 UPDATE → 원장 INSERT 순서다. 읽은 시점을 맞춰 동시에 출발시킨다.
    concurrencyGate.arm(ConcurrencyGate.WALLET_READ, WORKERS);
    ConcurrentRunner.Results<Payment> results =
        ConcurrentRunner.run(
            WORKERS,
            () -> atomicDebitConfirmer.confirm(merchantId, queue.poll(), ORDER_ID, AMOUNT));

    assertThat(results.failures()).isEmpty();
    assertThat(results.successCount()).isEqualTo(WORKERS);
    assertThat(statusCount("DONE")).isEqualTo(WORKERS);
    assertThat(payLedgerCount()).isEqualTo(WORKERS);
    assertThat(balanceOf(Seeds.MEMBER_ID_1)).isEqualTo(INITIAL_BALANCE - WORKERS * AMOUNT);
    invariants.assertAll();
  }

  /** 같은 지갑, 서로 다른 결제. 같은 결제면 payment 행에서 먼저 직렬화되어 wallet 경쟁이 가려진다. */
  private List<String> createPayments(int count) {
    List<String> paymentKeys = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      paymentKeys.add(
          paymentService
              .create(merchantId, ORDER_ID, AMOUNT, PaymentMethod.MONEY, Seeds.MEMBER_ID_1)
              .getPaymentKey());
    }
    return paymentKeys;
  }

  private int statusCount(String status) {
    return jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM payment WHERE status = ?", Integer.class, status);
  }

  private int payLedgerCount() {
    return jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM wallet_ledger WHERE type = 'PAY'", Integer.class);
  }

  private long balanceOf(long memberId) {
    return jdbcTemplate.queryForObject(
        "SELECT balance FROM wallet WHERE member_id = ?", Long.class, memberId);
  }
}
