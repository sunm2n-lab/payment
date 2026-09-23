package com.sunm2n.pay.api.dto;

/** 실패 응답 본문. {@code code} 로 실패 종류를 구분한다. */
public record ErrorResponse(String code, String message) {}
