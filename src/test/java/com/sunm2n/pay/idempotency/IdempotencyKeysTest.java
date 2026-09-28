package com.sunm2n.pay.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.format.support.DefaultFormattingConversionService;

class IdempotencyKeysTest {

  @Test
  @DisplayName("출력 가능한 ASCII 1~64자는 그대로 통과한다 - 대소문자를 바꾸거나 다듬지 않는다")
  void validKeyPassesUnchanged() {
    assertThat(IdempotencyKeys.validate("Abc-123_!~")).isEqualTo("Abc-123_!~");
    assertThat(IdempotencyKeys.validate("k".repeat(64))).hasSize(64);
  }

  @Test
  @DisplayName("쉼표는 거절한다 - 복수 헤더가 합쳐진 값과 구분하지 않는다")
  void commaIsRejected() {
    assertThatThrownBy(() -> IdempotencyKeys.validate("a,b"))
        .isInstanceOf(InvalidIdempotencyKeyException.class);
  }

  /**
   * {@code @RequestHeader} 는 같은 헤더의 값이 둘 이상이면 {@code String[]} 을 만들고, 선언 타입으로 변환한다. {@code String}
   * 으로 받으면 변환 서비스가 쉼표로 이어 붙이고, 배열로 받으면 값이 따로 온다. 컨트롤러는 {@code String} 으로 받으므로 쉼표 규칙 하나로 복수 헤더가 막힌다.
   */
  @Test
  @DisplayName("복수 헤더 값은 String 바인딩에서 쉼표로 합쳐지고 배열 바인딩에서는 따로 온다")
  void repeatedValuesAreJoinedForStringBinding() {
    DefaultFormattingConversionService conversion = new DefaultFormattingConversionService();
    String[] values = {"key-x", "key-y"};

    assertThat(conversion.convert(values, String.class)).isEqualTo("key-x,key-y");
    assertThat(conversion.convert(values, String[].class)).containsExactly("key-x", "key-y");
  }
}
