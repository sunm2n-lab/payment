package com.sunm2n.pay.wallet.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sunm2n.pay.payment.application.PaymentService;
import com.sunm2n.pay.payment.domain.Payment;
import com.sunm2n.pay.payment.domain.PaymentMethod;
import com.sunm2n.pay.support.AbstractIntegrationTest;
import com.sunm2n.pay.support.Seeds;
import com.sunm2n.pay.wallet.application.WalletService;
import com.sunm2n.pay.wallet.domain.exception.BalanceOverflowException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 금액 경계값.
 *
 * <p>오버플로를 막는 이유는 "음수 잔액을 금지" 하기 위해서가 아니다. 순차 요청만으로 생긴 가짜 음수가 S2 의 Lost Update 가 만든 음수와 섞이면 동시성
 * 실습에서 원인을 구분할 수 없기 때문이다. 관찰 대상인 음수는 그대로 허용한다 — 이 클래스의 마지막 테스트가 그 전제를 고정한다.
 */
class AmountBoundaryTest extends AbstractIntegrationTest {

  @Autowired private WalletService walletService;
  @Autowired private PaymentService paymentService;

  private Long merchantId;

  @BeforeEach
  void resolveMerchant() {
    merchantId = merchantIdOf(Seeds.MERCHANT_1_API_KEY);
  }

  @Test
  @DisplayName("충전이 long 범위를 넘으면 BalanceOverflowException 이고 잔액·원장이 그대로다")
  void chargeOverflowIsRejected() {
    walletService.charge(Seeds.MEMBER_ID_1, Long.MAX_VALUE);

    assertThatThrownBy(() -> walletService.charge(Seeds.MEMBER_ID_1, 1L))
        .isInstanceOf(BalanceOverflowException.class);

    assertThat(balanceOf(Seeds.MEMBER_ID_1)).isEqualTo(Long.MAX_VALUE);
    assertThat(ledgerCountOf(Seeds.MEMBER_ID_1)).isEqualTo(1);
    invariants.assertAll();
  }

  @Test
  @DisplayName("취소 환불이 long 범위를 넘으면 BalanceOverflowException 이고 취소 이력도 남지 않는다")
  void refundOverflowIsRejected() {
    walletService.charge(Seeds.MEMBER_ID_2, 10_000L);
    Payment payment =
        paymentService.create(
            merchantId, "overflow-1", 10_000L, PaymentMethod.MONEY, Seeds.MEMBER_ID_2);
    paymentService.confirm(merchantId, payment.getPaymentKey(), "overflow-1", 10_000L);
    // 승인 후 잔액 0 -> 여기서 MAX 를 채우면 환불이 오버플로한다
    walletService.charge(Seeds.MEMBER_ID_2, Long.MAX_VALUE);

    assertThatThrownBy(
            () -> paymentService.cancel(merchantId, payment.getPaymentKey(), 10_000L, null))
        .isInstanceOf(BalanceOverflowException.class);

    assertThat(balanceOf(Seeds.MEMBER_ID_2)).isEqualTo(Long.MAX_VALUE);
    assertThat(cancelCountOf(payment.getPaymentKey())).isZero();
    assertThat(balanceAmountOf(payment.getPaymentKey())).isEqualTo(10_000L);
    invariants.assertAll();
  }

  @Test
  @DisplayName("음수 잔액은 앱도 DB 도 막지 않는다 - S2 의 관찰 대상이므로 Invariants 가 검출한다")
  void negativeBalanceStaysObservable() {
    // S2 비교 실험 1 의 잔액 -2,000 이 이 경로로 관찰된다. UNSIGNED 나 CHECK 가 걸려 있으면 실험이 사라진다.
    int updated =
        jdbcTemplate.update(
            "UPDATE wallet SET balance = -2000 WHERE member_id = ?", Seeds.MEMBER_ID_3);
    assertThat(updated).isEqualTo(1);
    assertThat(balanceOf(Seeds.MEMBER_ID_3)).isEqualTo(-2_000L);

    // 막는 것은 DB 가 아니라 Invariants 다
    assertThatThrownBy(() -> invariants.assertWalletBalanceMatchesLedger())
        .isInstanceOf(AssertionError.class);
  }

  private long balanceOf(long memberId) {
    return jdbcTemplate.queryForObject(
        "SELECT balance FROM wallet WHERE member_id = ?", Long.class, memberId);
  }

  private int ledgerCountOf(long memberId) {
    return jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM wallet_ledger l JOIN wallet w ON w.id = l.wallet_id"
            + " WHERE w.member_id = ?",
        Integer.class,
        memberId);
  }

  private int cancelCountOf(String paymentKey) {
    return jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM payment_cancel c JOIN payment p ON p.id = c.payment_id"
            + " WHERE p.payment_key = ?",
        Integer.class,
        paymentKey);
  }

  private long balanceAmountOf(String paymentKey) {
    return jdbcTemplate.queryForObject(
        "SELECT balance_amount FROM payment WHERE payment_key = ?", Long.class, paymentKey);
  }
}
