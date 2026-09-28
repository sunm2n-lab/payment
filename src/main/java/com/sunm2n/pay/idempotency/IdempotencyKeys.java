package com.sunm2n.pay.idempotency;

/**
 * {@code Idempotency-Key} 헤더 값의 규칙 ({@code docs/plan/S5.md} 4.1).
 *
 * <ul>
 *   <li>헤더가 없으면 키 없음(기존 경로). 이 클래스는 <b>전달된</b> 값만 검사한다
 *   <li>1~64자, 출력 가능한 ASCII({@code 0x21}~{@code 0x7E})만. 빈 값·공백뿐인 값도 여기서 걸린다
 *   <li>쉼표({@code 0x2C}) 금지. 같은 헤더가 여러 번 오면 값이 쉼표로 합쳐져 전달되므로, 이 규칙 하나로 "복수 헤더" 와 "쉼표가 든 단일 값" 이 모두
 *       400 이 된다. 받은 값을 쉼표로 나누지 않는다
 *   <li>대소문자를 구분한다. 컬럼도 {@code ascii_bin} 이다
 * </ul>
 */
public final class IdempotencyKeys {

  public static final String HEADER = "Idempotency-Key";

  public static final int MAX_LENGTH = 64;

  private IdempotencyKeys() {}

  /** 형식이 맞으면 그대로 돌려준다. 다듬지 않는다 — 앞뒤 공백을 자르는지는 서블릿 컨테이너의 몫이다. */
  public static String validate(String key) {
    if (key.isEmpty()) {
      throw new InvalidIdempotencyKeyException("값이 비어 있습니다.");
    }
    if (key.length() > MAX_LENGTH) {
      throw new InvalidIdempotencyKeyException(MAX_LENGTH + "자 이하여야 합니다.");
    }
    for (int i = 0; i < key.length(); i++) {
      char c = key.charAt(i);
      if (c < 0x21 || c > 0x7E) {
        throw new InvalidIdempotencyKeyException("출력 가능한 ASCII 문자만 쓸 수 있습니다.");
      }
      if (c == ',') {
        throw new InvalidIdempotencyKeyException("쉼표를 쓸 수 없고, 헤더는 한 번만 보내야 합니다.");
      }
    }
    return key;
  }
}
