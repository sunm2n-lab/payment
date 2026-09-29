package com.sunm2n.pay.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * S6 대량 결제 픽스처 — 가맹점 100 × 가맹점당 CARD DONE 결제 100건 = 10,000건 ({@code docs/plan/S6.md} 4.5).
 *
 * <p><b>실행 계획을 결정적으로 만드는 것</b>이 목적이다. 시드 가맹점 둘에 결제를 반씩 넣으면 {@code merchant_id} 선택도가 50% 라 옵티마이저가
 * 인덱스를 버릴 수 있다. 100곳으로 나눠 1% 로 만들고, 끝에 {@code ANALYZE TABLE} 로 통계를 갱신한다 — InnoDB 의 통계 자동 갱신은 비동기다.
 *
 * <p><b>키 배치를 고정한다.</b> 가맹점 id 와 결제 id 를 명시해 넣는다. 가맹점 {@code m} 의 결제는 id {@code (m-1)*100+1 ..
 * m*100}, 주문 id {@code order-m-seq}, 결제 키 {@code pk-m-seq} 다. 그래서 {@code merchant_id} 인덱스의 엔트리 순서
 * {@code (merchant_id, id)} 가 가맹점 순서와 같고, 이후 새로 만드는 결제의 id 는 AUTO_INCREMENT 라 기존의 어느 id 보다 크다
 * (3.1).
 *
 * <p>API 가 아니라 JDBC 로 넣는다. 10,000건을 테스트마다 HTTP 로 만들지 않는다. 여러 행 INSERT 로 묶어 왕복을 줄인다. 모든 주문 id 는 형식
 * 규칙 안이고 서로 다르며 소문자·ASCII 다 — V7 의 collation 변경이 결과에 영향을 주지 않는다.
 */
public final class BulkPaymentFixture {

  public static final int MERCHANTS = 100;

  public static final int PAYMENTS_PER_MERCHANT = 100;

  public static final int TOTAL_PAYMENTS = MERCHANTS * PAYMENTS_PER_MERCHANT;

  /** 픽스처 결제의 금액. 행렬의 각 행이 선행 취소(1,000)를 한 번씩 해도 남는다. */
  public static final long AMOUNT = 100_000L;

  private static final int ROWS_PER_INSERT = 1_000;

  private BulkPaymentFixture() {}

  /** 가맹점 {@code m}(3..100)의 API 키. 1·2 는 시드 가맹점이다. */
  public static String apiKeyOf(long merchantId) {
    return merchantId <= Seeds.MERCHANT_COUNT
        ? "mk_test_merchant_" + merchantId
        : "mk_bulk_merchant_" + merchantId;
  }

  public static String orderIdOf(long merchantId, int seq) {
    return "order-" + merchantId + "-" + seq;
  }

  public static String paymentKeyOf(long merchantId, int seq) {
    return "pk-" + merchantId + "-" + seq;
  }

  /** 가맹점 {@code merchantId} 의 {@code seq} 번째 결제 id. */
  public static long paymentIdOf(long merchantId, int seq) {
    return (merchantId - 1) * PAYMENTS_PER_MERCHANT + seq;
  }

  /**
   * TRUNCATE + 시드 직후에 부른다. 시드 가맹점 1·2 의 id 가 1·2 인 것을 먼저 확인한다 — 테스트 간 정리가 TRUNCATE 라 AUTO_INCREMENT
   * 가 되돌아간다.
   */
  public static void load(JdbcTemplate jdbcTemplate) {
    assertThat(jdbcTemplate.queryForList("SELECT id FROM merchant ORDER BY id", Long.class))
        .as("시드 가맹점 id")
        .containsExactly(1L, 2L);
    assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM payment", Integer.class))
        .as("픽스처 전 결제")
        .isZero();

    List<String> merchants = new ArrayList<>();
    for (long m = Seeds.MERCHANT_COUNT + 1; m <= MERCHANTS; m++) {
      merchants.add("(" + m + ", '대량 가맹점 " + m + "', '" + apiKeyOf(m) + "', NOW(6))");
    }
    jdbcTemplate.update(
        "INSERT INTO merchant (id, name, api_key, created_at) VALUES "
            + String.join(",", merchants));

    List<String> rows = new ArrayList<>(ROWS_PER_INSERT);
    for (long m = 1; m <= MERCHANTS; m++) {
      for (int seq = 1; seq <= PAYMENTS_PER_MERCHANT; seq++) {
        rows.add(
            "("
                + paymentIdOf(m, seq)
                + ", '"
                + paymentKeyOf(m, seq)
                + "', '"
                + orderIdOf(m, seq)
                + "', "
                + m
                + ", NULL, 'CARD', "
                + AMOUNT
                + ", "
                + AMOUNT
                + ", 'DONE', NOW(6), 'CARD-BULK-"
                + paymentIdOf(m, seq)
                + "', NOW(6))");
        if (rows.size() == ROWS_PER_INSERT) {
          insertPayments(jdbcTemplate, rows);
          rows.clear();
        }
      }
    }
    if (!rows.isEmpty()) {
      insertPayments(jdbcTemplate, rows);
    }

    jdbcTemplate.queryForList("ANALYZE TABLE payment");
  }

  private static void insertPayments(JdbcTemplate jdbcTemplate, List<String> rows) {
    jdbcTemplate.update(
        "INSERT INTO payment (id, payment_key, order_id, merchant_id, wallet_id, method, amount,"
            + " balance_amount, status, approved_at, card_approval_no, created_at) VALUES "
            + String.join(",", rows));
  }
}
