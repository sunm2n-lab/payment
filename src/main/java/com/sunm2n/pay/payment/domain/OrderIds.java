package com.sunm2n.pay.payment.domain;

import com.sunm2n.pay.payment.domain.exception.InvalidOrderIdException;
import java.util.regex.Pattern;

/**
 * 주문 id 형식 규칙 ({@code docs/plan/S6.md} 4.1). 생성 요청의 {@code orderId} 와 주문 기반 API 의 경로 변수가 이 규칙 하나를
 * 쓴다.
 *
 * <ul>
 *   <li>영문 대소문자, 숫자, {@code -}, {@code _} 만. 6자 이상 64자 이하. 토스페이먼츠의 주문번호 규칙과 같다
 *   <li>대소문자를 구분한다. {@code Order-1} 과 {@code order-1} 은 다른 주문이다 — V7 의 {@code order_id} 컬럼도 {@code
 *       ascii_bin} 이다
 *   <li>다듬지 않는다. 앞뒤 공백은 규칙 밖이라 거절된다
 * </ul>
 *
 * <p>주문 id 는 가맹점이 발급한다. 여기서는 형식만 검증하고 발급 방식은 정하지 않는다. 경로·collation·정규화 문제를 입력 시점에 막는 것이 목적이고, 보안
 * 장치는 아니다 — 접근 권한은 가맹점 인증과 {@code merchant_id} 조건이 지킨다.
 *
 * <p>규칙은 넓히기는 쉽고 좁히기는 어렵다. 공개 규칙을 나중에 좁히면 기존 호출자가 깨진다. 그래서 문자 집합과 길이를 함께 정했다.
 */
public final class OrderIds {

  public static final int MIN_LENGTH = 6;

  public static final int MAX_LENGTH = 64;

  /** Bean Validation {@code @Pattern} 이 쓰는 정규식. 전체 일치로 검사한다. */
  public static final String REGEX = "[A-Za-z0-9_-]{" + MIN_LENGTH + "," + MAX_LENGTH + "}";

  public static final String RULE = "영문 대소문자, 숫자, '-', '_' 로 이루어진 6~64자여야 합니다.";

  private static final Pattern PATTERN = Pattern.compile(REGEX);

  private OrderIds() {}

  public static boolean isValid(String orderId) {
    return orderId != null && PATTERN.matcher(orderId).matches();
  }

  /**
   * 경로 변수 검증. 규칙 안이면 그대로 돌려준다. 거절 메시지에 받은 값을 넣지 않는다 — 규칙 밖의 값은 무엇이든 올 수 있다.
   *
   * <p>생성 요청 본문은 Bean Validation({@link #REGEX})으로 같은 규칙을 검사한다.
   */
  public static String validate(String orderId) {
    if (!isValid(orderId)) {
      throw new InvalidOrderIdException();
    }
    return orderId;
  }
}
