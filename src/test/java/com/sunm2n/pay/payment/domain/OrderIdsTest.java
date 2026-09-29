package com.sunm2n.pay.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OrderIdsTest {

  @ParameterizedTest
  @ValueSource(strings = {"abcdef", "Order-1_A", "000001", "ORDER-1"})
  void acceptsCharactersAndLengthInRule(String orderId) {
    assertThat(OrderIds.isValid(orderId)).isTrue();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "abcde",
        "café-order",
        "order-1 ",
        " order-1",
        "order/1",
        "order.1",
        "order-1\n",
        "order 1"
      })
  void rejectsOutsideRule(String orderId) {
    assertThat(OrderIds.isValid(orderId)).isFalse();
  }

  @Test
  void boundaryLengths() {
    assertThat(OrderIds.isValid("o".repeat(6))).isTrue();
    assertThat(OrderIds.isValid("o".repeat(64))).isTrue();
    assertThat(OrderIds.isValid("o".repeat(5))).isFalse();
    assertThat(OrderIds.isValid("o".repeat(65))).isFalse();
    assertThat(OrderIds.isValid(null)).isFalse();
  }
}
