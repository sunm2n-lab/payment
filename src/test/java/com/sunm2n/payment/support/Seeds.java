package com.sunm2n.payment.support;

/** {@code db/seed/seed_local.sql} 이 심는 값. 테스트가 시드 내용을 문자열로 중복 적지 않게 한다. */
public final class Seeds {

  public static final String MERCHANT_1_API_KEY = "mk_test_merchant_1";

  /** "다른 가맹점의 결제 -> 404" 테스트 전용. k6 는 쓰지 않는다. */
  public static final String MERCHANT_2_API_KEY = "mk_test_merchant_2";

  public static final int MERCHANT_COUNT = 2;

  /** 시드 지갑의 member_id 는 1..5 이고 잔액은 전부 0, 원장은 없다. */
  public static final long MEMBER_ID_1 = 1L;

  public static final long MEMBER_ID_2 = 2L;

  public static final long MEMBER_ID_3 = 3L;

  public static final int WALLET_COUNT = 5;

  private Seeds() {}
}
