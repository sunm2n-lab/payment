package com.sunm2n.pay.payment.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sunm2n.pay.payment.domain.Payment;
import com.sunm2n.pay.payment.domain.PaymentMethod;
import com.sunm2n.pay.payment.domain.PaymentStatus;
import com.sunm2n.pay.payment.domain.exception.CancelAmountExceededException;
import com.sunm2n.pay.payment.domain.exception.InvalidPaymentStatusException;
import com.sunm2n.pay.payment.domain.exception.PaymentMismatchException;
import com.sunm2n.pay.payment.domain.exception.PaymentNotFoundException;
import com.sunm2n.pay.support.AbstractIntegrationTest;
import com.sunm2n.pay.support.Seeds;
import com.sunm2n.pay.wallet.application.WalletService;
import com.sunm2n.pay.wallet.domain.LedgerType;
import com.sunm2n.pay.wallet.domain.exception.InsufficientBalanceException;
import com.sunm2n.pay.wallet.domain.exception.WalletNotFoundException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * naive 결제 서비스의 도메인 동작 검증.
 *
 * <p>HTTP 상태 코드 매핑(401/404/400/409)은 인터셉터와 {@code ApiExceptionHandler} 가 결정하므로 별도의 HTTP 계층 테스트에서
 * 확인한다.
 *
 * <p>테스트 메서드에 {@code @Transactional} 을 붙이지 않는다 (SCENARIO 96행).
 */
class PaymentServiceTest extends AbstractIntegrationTest {

  private static final long AMOUNT = 10_000L;

  @Autowired private PaymentService paymentService;
  @Autowired private WalletService walletService;

  private Long merchantId;
  private Long otherMerchantId;

  @BeforeEach
  void resolveMerchants() {
    merchantId = merchantIdOf(Seeds.MERCHANT_1_API_KEY);
    otherMerchantId = merchantIdOf(Seeds.MERCHANT_2_API_KEY);
  }

  @Nested
  @DisplayName("생성")
  class Create {

    @Test
    @DisplayName("CARD 결제는 READY 로 생성되고 paymentKey 가 발급된다")
    void createCardPayment() {
      Payment payment = createCard("order-1");

      assertThat(payment.getPaymentKey()).isNotBlank();
      assertThat(payment.getStatus()).isEqualTo(PaymentStatus.READY);
      assertThat(payment.getAmount()).isEqualTo(AMOUNT);
      assertThat(payment.getBalanceAmount()).isEqualTo(AMOUNT);
      assertThat(payment.getWalletId()).isNull();
      assertThat(payment.getApprovedAt()).isNull();
      assertThat(payment.getCardApprovalNo()).isNull();
    }

    @Test
    @DisplayName("MONEY 결제는 memberId 로 지갑을 찾아 wallet_id 를 저장한다")
    void createMoneyResolvesWallet() {
      Payment payment = createMoney("order-2", Seeds.MEMBER_ID_1);

      Long walletId =
          jdbcTemplate.queryForObject(
              "SELECT id FROM wallet WHERE member_id = ?", Long.class, Seeds.MEMBER_ID_1);
      assertThat(payment.getWalletId()).isEqualTo(walletId);
    }

    @Test
    @DisplayName("결제마다 서로 다른 paymentKey 가 발급된다")
    void paymentKeysAreUnique() {
      assertThat(createCard("order-3").getPaymentKey())
          .isNotEqualTo(createCard("order-4").getPaymentKey());
    }

    @Test
    @DisplayName("존재하지 않는 회원의 MONEY 결제는 WalletNotFoundException")
    void createMoneyWithUnknownMember() {
      assertThatThrownBy(() -> createMoney("order-5", 9_999L))
          .isInstanceOf(WalletNotFoundException.class);

      assertThat(paymentCount()).isZero();
    }
  }

  @Nested
  @DisplayName("승인")
  class Confirm {

    @Test
    @DisplayName("CARD 승인은 카드사를 한 번 호출하고 DONE 으로 바꾼다")
    void confirmCard() {
      Payment created = createCard("order-10");

      Payment confirmed =
          paymentService.confirm(merchantId, created.getPaymentKey(), "order-10", AMOUNT);

      assertThat(confirmed.getStatus()).isEqualTo(PaymentStatus.DONE);
      assertThat(confirmed.getApprovedAt()).isNotNull();
      assertThat(confirmed.getCardApprovalNo()).isNotBlank();
      assertThat(fakeCardApprovalClient.getApproveCount()).isEqualTo(1);
      invariants.assertAll();
    }

    @Test
    @DisplayName("MONEY 승인은 잔액을 차감하고 음수 PAY 원장을 남긴다")
    void confirmMoney() {
      walletService.charge(Seeds.MEMBER_ID_1, 30_000L);
      Payment created = createMoney("order-11", Seeds.MEMBER_ID_1);

      paymentService.confirm(merchantId, created.getPaymentKey(), "order-11", AMOUNT);

      assertThat(balanceOf(Seeds.MEMBER_ID_1)).isEqualTo(20_000L);
      List<Map<String, Object>> pays = ledgersOf(Seeds.MEMBER_ID_1, LedgerType.PAY);
      assertThat(pays).hasSize(1);
      assertThat(((Number) pays.get(0).get("amount")).longValue()).isEqualTo(-AMOUNT);
      // 카드사는 부르지 않는다 - 머니 결제는 외부 의존을 두지 않는다
      assertThat(fakeCardApprovalClient.getApproveCount()).isZero();
      invariants.assertAll();
    }

    @Test
    @DisplayName("잔액이 부족하면 InsufficientBalanceException 이고 잔액·원장·상태가 그대로다")
    void confirmMoneyWithoutEnoughBalance() {
      walletService.charge(Seeds.MEMBER_ID_1, 5_000L);
      Payment created = createMoney("order-12", Seeds.MEMBER_ID_1);

      assertThatThrownBy(
              () -> paymentService.confirm(merchantId, created.getPaymentKey(), "order-12", AMOUNT))
          .isInstanceOf(InsufficientBalanceException.class);

      assertThat(balanceOf(Seeds.MEMBER_ID_1)).isEqualTo(5_000L);
      assertThat(ledgersOf(Seeds.MEMBER_ID_1, LedgerType.PAY)).isEmpty();
      assertThat(statusOf(created.getPaymentKey())).isEqualTo(PaymentStatus.READY.name());
      invariants.assertAll();
    }

    @Test
    @DisplayName("orderId 가 다르면 PaymentMismatchException 이고 카드사를 부르지 않는다")
    void confirmWithWrongOrderId() {
      Payment created = createCard("order-13");

      assertThatThrownBy(
              () ->
                  paymentService.confirm(
                      merchantId, created.getPaymentKey(), "order-other", AMOUNT))
          .isInstanceOf(PaymentMismatchException.class);

      assertThat(fakeCardApprovalClient.getApproveCount()).isZero();
      assertThat(statusOf(created.getPaymentKey())).isEqualTo(PaymentStatus.READY.name());
    }

    @Test
    @DisplayName("amount 가 다르면 PaymentMismatchException 이고 카드사를 부르지 않는다")
    void confirmWithWrongAmount() {
      Payment created = createCard("order-14");

      assertThatThrownBy(
              () -> paymentService.confirm(merchantId, created.getPaymentKey(), "order-14", 9_999L))
          .isInstanceOf(PaymentMismatchException.class);

      assertThat(fakeCardApprovalClient.getApproveCount()).isZero();
    }

    @Test
    @DisplayName("이미 DONE 인 결제를 다시 승인하면 InvalidPaymentStatusException 이고 승인 횟수가 늘지 않는다")
    void confirmTwiceSequentially() {
      Payment created = createCard("order-15");
      paymentService.confirm(merchantId, created.getPaymentKey(), "order-15", AMOUNT);
      int approvesAfterFirst = fakeCardApprovalClient.getApproveCount();

      assertThatThrownBy(
              () -> paymentService.confirm(merchantId, created.getPaymentKey(), "order-15", AMOUNT))
          .isInstanceOf(InvalidPaymentStatusException.class);

      // 순차 재승인은 막힌다. 동시 승인이 뚫리는 것은 S1 의 재현 대상이다.
      assertThat(fakeCardApprovalClient.getApproveCount()).isEqualTo(approvesAfterFirst);
    }

    @Test
    @DisplayName("다른 가맹점의 결제는 PaymentNotFoundException - 존재를 노출하지 않는다")
    void confirmOtherMerchantsPayment() {
      Payment created = createCard("order-16");

      assertThatThrownBy(
              () ->
                  paymentService.confirm(
                      otherMerchantId, created.getPaymentKey(), "order-16", AMOUNT))
          .isInstanceOf(PaymentNotFoundException.class);

      assertThat(fakeCardApprovalClient.getApproveCount()).isZero();
    }
  }

  @Nested
  @DisplayName("취소")
  class Cancel {

    @Test
    @DisplayName("부분취소는 PARTIAL_CANCELED 로 바꾸고 잔여 금액을 줄인다")
    void partialCancel() {
      Payment confirmed = confirmedCard("order-20");

      Payment canceled =
          paymentService.cancel(merchantId, confirmed.getPaymentKey(), 3_000L, "부분 취소");

      assertThat(canceled.getStatus()).isEqualTo(PaymentStatus.PARTIAL_CANCELED);
      assertThat(canceled.getBalanceAmount()).isEqualTo(7_000L);
      assertThat(cancelCountOf(confirmed.getPaymentKey())).isEqualTo(1);
      assertThat(fakeCardApprovalClient.getCancelCount()).isEqualTo(1);
      invariants.assertAll();
    }

    @Test
    @DisplayName("잔여 금액 전액을 취소하면 CANCELED 가 된다")
    void fullCancel() {
      Payment confirmed = confirmedCard("order-21");
      paymentService.cancel(merchantId, confirmed.getPaymentKey(), 3_000L, null);

      Payment canceled = paymentService.cancel(merchantId, confirmed.getPaymentKey(), 7_000L, null);

      assertThat(canceled.getStatus()).isEqualTo(PaymentStatus.CANCELED);
      assertThat(canceled.getBalanceAmount()).isZero();
      assertThat(cancelCountOf(confirmed.getPaymentKey())).isEqualTo(2);
      invariants.assertAll();
    }

    @Test
    @DisplayName("MONEY 취소는 지갑에 환불하고 REFUND 원장을 남긴다")
    void cancelMoneyRefunds() {
      walletService.charge(Seeds.MEMBER_ID_1, 30_000L);
      Payment created = createMoney("order-22", Seeds.MEMBER_ID_1);
      paymentService.confirm(merchantId, created.getPaymentKey(), "order-22", AMOUNT);

      paymentService.cancel(merchantId, created.getPaymentKey(), 4_000L, null);

      assertThat(balanceOf(Seeds.MEMBER_ID_1)).isEqualTo(24_000L);
      List<Map<String, Object>> refunds = ledgersOf(Seeds.MEMBER_ID_1, LedgerType.REFUND);
      assertThat(refunds).hasSize(1);
      assertThat(((Number) refunds.get(0).get("amount")).longValue()).isEqualTo(4_000L);
      // 머니 결제는 카드사를 부르지 않는다
      assertThat(fakeCardApprovalClient.getCancelCount()).isZero();
      invariants.assertAll();
    }

    @Test
    @DisplayName("잔여 금액을 넘는 취소는 CancelAmountExceededException 이고 부작용이 없다")
    void cancelBeyondBalanceAmount() {
      Payment confirmed = confirmedCard("order-23");
      int cancelsBefore = fakeCardApprovalClient.getCancelCount();

      assertThatThrownBy(
              () -> paymentService.cancel(merchantId, confirmed.getPaymentKey(), 10_001L, null))
          .isInstanceOf(CancelAmountExceededException.class);

      assertThat(cancelCountOf(confirmed.getPaymentKey())).isZero();
      assertThat(balanceAmountOf(confirmed.getPaymentKey())).isEqualTo(AMOUNT);
      assertThat(statusOf(confirmed.getPaymentKey())).isEqualTo(PaymentStatus.DONE.name());
      assertThat(fakeCardApprovalClient.getCancelCount()).isEqualTo(cancelsBefore);
      invariants.assertAll();
    }

    @Test
    @DisplayName("READY 상태는 취소할 수 없다")
    void cancelReadyPayment() {
      Payment created = createCard("order-24");

      assertThatThrownBy(
              () -> paymentService.cancel(merchantId, created.getPaymentKey(), 1_000L, null))
          .isInstanceOf(InvalidPaymentStatusException.class);

      assertThat(cancelCountOf(created.getPaymentKey())).isZero();
    }

    @Test
    @DisplayName("다른 가맹점의 결제는 취소할 수 없다")
    void cancelOtherMerchantsPayment() {
      Payment confirmed = confirmedCard("order-25");

      assertThatThrownBy(
              () -> paymentService.cancel(otherMerchantId, confirmed.getPaymentKey(), 1_000L, null))
          .isInstanceOf(PaymentNotFoundException.class);

      assertThat(cancelCountOf(confirmed.getPaymentKey())).isZero();
    }
  }

  @Nested
  @DisplayName("조회")
  class Get {

    @Test
    @DisplayName("자기 가맹점의 결제를 조회한다")
    void getOwnPayment() {
      Payment created = createCard("order-30");

      Payment found = paymentService.get(merchantId, created.getPaymentKey());

      assertThat(found.getPaymentKey()).isEqualTo(created.getPaymentKey());
      assertThat(found.getOrderId()).isEqualTo("order-30");
    }

    @Test
    @DisplayName("없는 paymentKey 는 PaymentNotFoundException")
    void getMissingPayment() {
      assertThatThrownBy(() -> paymentService.get(merchantId, "no-such-key"))
          .isInstanceOf(PaymentNotFoundException.class);
    }

    @Test
    @DisplayName("다른 가맹점의 결제는 조회할 수 없다")
    void getOtherMerchantsPayment() {
      Payment created = createCard("order-31");

      assertThatThrownBy(() -> paymentService.get(otherMerchantId, created.getPaymentKey()))
          .isInstanceOf(PaymentNotFoundException.class);
    }
  }

  private Payment createCard(String orderId) {
    return paymentService.create(merchantId, orderId, AMOUNT, PaymentMethod.CARD, null);
  }

  private Payment createMoney(String orderId, long memberId) {
    return paymentService.create(merchantId, orderId, AMOUNT, PaymentMethod.MONEY, memberId);
  }

  private Payment confirmedCard(String orderId) {
    Payment created = createCard(orderId);
    return paymentService.confirm(merchantId, created.getPaymentKey(), orderId, AMOUNT);
  }

  private long balanceOf(long memberId) {
    return jdbcTemplate.queryForObject(
        "SELECT balance FROM wallet WHERE member_id = ?", Long.class, memberId);
  }

  private List<Map<String, Object>> ledgersOf(long memberId, LedgerType type) {
    return jdbcTemplate.queryForList(
        "SELECT l.type, l.amount FROM wallet_ledger l JOIN wallet w ON w.id = l.wallet_id"
            + " WHERE w.member_id = ? AND l.type = ? ORDER BY l.id",
        memberId,
        type.name());
  }

  private String statusOf(String paymentKey) {
    return jdbcTemplate.queryForObject(
        "SELECT status FROM payment WHERE payment_key = ?", String.class, paymentKey);
  }

  private long balanceAmountOf(String paymentKey) {
    return jdbcTemplate.queryForObject(
        "SELECT balance_amount FROM payment WHERE payment_key = ?", Long.class, paymentKey);
  }

  private int cancelCountOf(String paymentKey) {
    return jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM payment_cancel c JOIN payment p ON p.id = c.payment_id"
            + " WHERE p.payment_key = ?",
        Integer.class,
        paymentKey);
  }

  private int paymentCount() {
    return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM payment", Integer.class);
  }
}
