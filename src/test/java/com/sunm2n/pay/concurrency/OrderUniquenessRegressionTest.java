package com.sunm2n.pay.concurrency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.sunm2n.pay.payment.application.PaymentService;
import com.sunm2n.pay.payment.domain.PaymentMethod;
import com.sunm2n.pay.payment.domain.exception.DuplicateOrderException;
import com.sunm2n.pay.support.AbstractApiTest;
import com.sunm2n.pay.support.ConcurrentRunner;
import com.sunm2n.pay.support.LockWaitProbe;
import com.sunm2n.pay.support.Seeds;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * S6 회귀 8·10 — 복합 unique 가 만드는 주문 유일성과 대소문자 구분 ({@code docs/plan/S6.md} 4.4, 6절).
 *
 * <p>0·0' 단계({@link DuplicateOrderReproductionTest}, V5)와 대비된다. 같은 주문의 두 번째 생성은 409 이고, 대소문자만 다른
 * 주문은 서로 다른 주문이라 조회·취소가 정확한 결제를 고른다.
 */
class OrderUniquenessRegressionTest extends AbstractApiTest {

  private static final String M1 = Seeds.MERCHANT_1_API_KEY;
  private static final String M2 = Seeds.MERCHANT_2_API_KEY;
  private static final long AMOUNT = 10_000L;

  @Autowired private PaymentService paymentService;
  @Autowired private PlatformTransactionManager transactionManager;

  @AfterEach
  void invariantsHold() {
    invariants.assertAll();
  }

  @Test
  @DisplayName("회귀 8 - 같은 가맹점이 같은 orderId 로 다시 만들면 내용이 같아도 409 DUPLICATE_ORDER 이고 결제는 1건이다")
  void sequentialDuplicateIsRejected() throws Exception {
    createPayment(M1, createBody("order-s6u-8", AMOUNT, "CARD")).andExpect(status().isOk());

    createPayment(M1, createBody("order-s6u-8", AMOUNT, "CARD"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("DUPLICATE_ORDER"));
    createPayment(M1, createBody("order-s6u-8", 20_000L, "CARD"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("DUPLICATE_ORDER"));

    assertThat(paymentsWithOrder("order-s6u-8")).isEqualTo(1);
  }

  @Test
  @DisplayName("회귀 8 - 다른 가맹점은 같은 orderId 로 각자 만든다")
  void sameOrderIdAcrossMerchantsIsAllowed() throws Exception {
    createPayment(M1, createBody("order-s6u-8b", AMOUNT, "CARD")).andExpect(status().isOk());
    createPayment(M2, createBody("order-s6u-8b", AMOUNT, "CARD")).andExpect(status().isOk());

    assertThat(paymentsWithOrder("order-s6u-8b")).isEqualTo(2);
  }

  @Test
  @DisplayName("회귀 8 - 같은 주문 동시 생성 2건은 후속 INSERT 가 선행 커밋을 기다렸다가 409 - 성공 1, 결제 1건")
  void concurrentDuplicateWaitsThenConflicts() {
    Long merchantId = merchantIdOf(M1);
    String database = jdbcTemplate.queryForObject("SELECT DATABASE()", String.class);
    CountDownLatch inserted = new CountDownLatch(1);
    AtomicInteger turn = new AtomicInteger();

    ConcurrentRunner.Results<Object> results =
        ConcurrentRunner.run(
            2,
            () -> {
              if (turn.getAndIncrement() == 0) {
                // 선행은 INSERT 뒤, 후속이 unique 중복 검사에서 기다리는 것을 확인한 다음에 커밋한다.
                return new TransactionTemplate(transactionManager)
                    .execute(
                        status -> {
                          Object created = create(merchantId, "order-s6u-8c");
                          inserted.countDown();
                          LockWaitProbe.awaitWaiters(database, "payment", 1);
                          return created;
                        });
              }
              assertThat(inserted.await(10, TimeUnit.SECONDS)).isTrue();
              return create(merchantId, "order-s6u-8c");
            });

    assertThat(results.successCount()).isEqualTo(1);
    assertThat(results.failures()).singleElement().isInstanceOf(DuplicateOrderException.class);
    assertThat(paymentsWithOrder("order-s6u-8c")).isEqualTo(1);
  }

  @Test
  @DisplayName("회귀 10 - 대소문자만 다른 두 주문은 각자 생성되고, 조회·취소가 요청한 쪽의 결제만 고른다")
  void caseDifferentOrdersAreDistinct() throws Exception {
    String lower = confirmedCard("order-s6u-10");
    String upper = confirmedCard("ORDER-S6U-10");

    getPaymentByOrder(M1, "order-s6u-10").andExpect(jsonPath("$.paymentKey").value(lower));
    getPaymentByOrder(M1, "ORDER-S6U-10").andExpect(jsonPath("$.paymentKey").value(upper));
    getPaymentByOrder(M1, "Order-S6U-10").andExpect(status().isNotFound());

    cancelPaymentByOrder(M1, "ORDER-S6U-10", cancelBody(4_000L))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.paymentKey").value(upper))
        .andExpect(jsonPath("$.balanceAmount").value(6_000L));

    getPayment(M1, lower)
        .andExpect(jsonPath("$.balanceAmount").value(AMOUNT))
        .andExpect(jsonPath("$.status").value("DONE"));
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payment_cancel c JOIN payment p ON p.id = c.payment_id"
                    + " WHERE p.payment_key = ?",
                Integer.class,
                lower))
        .as("다른 쪽의 취소 이력은 그대로다")
        .isZero();
  }

  private Object create(Long merchantId, String orderId) {
    return paymentService.create(merchantId, orderId, AMOUNT, PaymentMethod.CARD, null);
  }

  private String confirmedCard(String orderId) throws Exception {
    String response =
        createPayment(M1, createBody(orderId, AMOUNT, "CARD"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String paymentKey = JsonPath.read(response, "$.paymentKey");
    confirmPayment(M1, confirmBody(paymentKey, orderId, AMOUNT)).andExpect(status().isOk());
    return paymentKey;
  }

  private int paymentsWithOrder(String orderId) {
    return jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM payment WHERE order_id = ?", Integer.class, orderId);
  }
}
