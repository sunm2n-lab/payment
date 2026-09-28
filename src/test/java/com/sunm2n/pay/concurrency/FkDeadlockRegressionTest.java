package com.sunm2n.pay.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

import com.sunm2n.pay.payment.application.PaymentService;
import com.sunm2n.pay.payment.domain.PaymentMethod;
import com.sunm2n.pay.support.AbstractIntegrationTest;
import com.sunm2n.pay.support.ConcurrencyGate;
import com.sunm2n.pay.support.ConcurrentRunner;
import com.sunm2n.pay.support.Seeds;
import com.sunm2n.pay.wallet.application.WalletService;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * S4 회귀 — 본선은 FK 아래에서도 승격 데드락을 만들지 않는다.
 *
 * <p>{@link PaymentService}·{@link WalletService} 의 기본 빈을 그대로 호출한다. 본선의 세 경로(차감·충전·환불)는 모두 {@code
 * PessimisticLockWalletBalanceUpdater} 를 거쳐 원장 INSERT <b>전에</b> wallet 을 {@code FOR UPDATE} 로 잠근다.
 * 원장 INSERT 의 FK 검사는 이미 X 락을 쥔 트랜잭션 안에서 일어나므로 S 락 경쟁이 생기지 않는다.
 *
 * <p>동기화는 {@code WALLET_LOCK_ATTEMPT} — wallet 락을 잡기 <b>전</b>이다. 재현의 {@code LEDGER_INSERTED} 는 쓰지
 * 않는다 (SCENARIO 170행). 본선은 X 락을 먼저 잡으므로 두 번째 참가자가 원장 INSERT 에 도달하지 못해 barrier 가 영영 모이지 않는다.
 */
class FkDeadlockRegressionTest extends AbstractIntegrationTest {

  private static final String ORDER_ID = "order-s4r";
  private static final long AMOUNT = 3_000L;
  private static final long INITIAL_BALANCE = 100_000L;
  private static final long CHARGE_AMOUNT = 5_000L;
  private static final long PARTIAL_CANCEL = 2_000L;

  @Autowired private PaymentService paymentService;
  @Autowired private WalletService walletService;

  private Long merchantId;

  @BeforeEach
  void resolveMerchant() {
    merchantId = merchantIdOf(Seeds.MERCHANT_1_API_KEY);
  }

  @Test
  @DisplayName("회귀 1 - 같은 지갑의 서로 다른 결제 2건을 본선으로 승인하면 데드락 없이 둘 다 성공한다")
  void mainlineDebitsDoNotDeadlock() {
    walletService.charge(Seeds.MEMBER_ID_1, INITIAL_BALANCE);
    String p1 = created();
    String p2 = created();
    AtomicInteger turn = new AtomicInteger();

    concurrencyGate.arm(ConcurrencyGate.WALLET_LOCK_ATTEMPT, 2);
    ConcurrentRunner.Results<Object> results =
        ConcurrentRunner.run(
            2,
            () ->
                paymentService.confirm(
                    merchantId, turn.getAndIncrement() == 0 ? p1 : p2, ORDER_ID, AMOUNT));

    assertThat(results.failures()).as("데드락도, 잔액 부족도 없다").isEmpty();
    assertThat(results.successCount()).isEqualTo(2);
    assertThat(payLedgerCount()).isEqualTo(2);
    assertThat(balanceOf(Seeds.MEMBER_ID_1)).isEqualTo(INITIAL_BALANCE - 2 * AMOUNT);
    invariants.assertAll();
  }

  @Test
  @DisplayName("회귀 2 - 원장을 쓰는 세 경로(차감·충전·환불)가 같은 지갑에서 겹쳐도 데드락이 없다")
  void allLedgerWritingPathsLockTheWalletFirst() {
    walletService.charge(Seeds.MEMBER_ID_1, INITIAL_BALANCE);
    String toCancel = created();
    paymentService.confirm(merchantId, toCancel, ORDER_ID, AMOUNT);
    String toConfirm = created();
    AtomicInteger turn = new AtomicInteger();

    // 셋 다 wallet 락을 잡기 직전에 모은다. 그 시점에 승인은 P1(CAS), 취소는 P3(FOR UPDATE) 의 X 락을 들고 있고 충전은 없다. 서로 다른
    // 행이다.
    concurrencyGate.arm(ConcurrencyGate.WALLET_LOCK_ATTEMPT, 3);
    ConcurrentRunner.Results<Object> results =
        ConcurrentRunner.run(
            3,
            () ->
                switch (turn.getAndIncrement()) {
                  case 0 -> paymentService.confirm(merchantId, toConfirm, ORDER_ID, AMOUNT);
                  case 1 -> walletService.charge(Seeds.MEMBER_ID_1, CHARGE_AMOUNT);
                  default -> paymentService.cancel(merchantId, toCancel, PARTIAL_CANCEL, "경쟁");
                });

    assertThat(results.failures()).isEmpty();
    assertThat(results.successCount()).isEqualTo(3);
    assertThat(balanceOf(Seeds.MEMBER_ID_1))
        .as("100,000 - 3,000(P3) - 3,000(P1) + 5,000(충전) + 2,000(환불)")
        .isEqualTo(INITIAL_BALANCE - 2 * AMOUNT + CHARGE_AMOUNT + PARTIAL_CANCEL);
    invariants.assertAll();
  }

  private String created() {
    return paymentService
        .create(merchantId, ORDER_ID, AMOUNT, PaymentMethod.MONEY, Seeds.MEMBER_ID_1)
        .getPaymentKey();
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
