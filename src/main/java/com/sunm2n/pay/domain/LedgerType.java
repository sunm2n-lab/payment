package com.sunm2n.pay.domain;

/** 지갑 원장의 유형. CHARGE/REFUND 는 양수, PAY 는 음수 금액으로 기록한다 (SCENARIO 42행). */
public enum LedgerType {
  CHARGE,
  PAY,
  REFUND
}
