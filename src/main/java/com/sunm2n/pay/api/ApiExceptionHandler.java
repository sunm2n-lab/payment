package com.sunm2n.pay.api;

import com.sunm2n.pay.api.dto.ErrorResponse;
import com.sunm2n.pay.domain.exception.BalanceOverflowException;
import com.sunm2n.pay.domain.exception.CancelAmountExceededException;
import com.sunm2n.pay.domain.exception.DomainException;
import com.sunm2n.pay.domain.exception.InsufficientBalanceException;
import com.sunm2n.pay.domain.exception.InvalidPaymentStatusException;
import com.sunm2n.pay.domain.exception.PaymentMismatchException;
import com.sunm2n.pay.domain.exception.PaymentNotFoundException;
import com.sunm2n.pay.domain.exception.WalletNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 도메인 예외를 4xx 로 매핑한다. **선택이 아니라 필수다.**
 *
 * <p>이후 모든 시나리오의 완료 기준이 "예상한 실패 응답과 예상 밖 오류를 구분했는지"(SCENARIO 114행), 즉 4xx(예상된 실패) / 5xx(예상 밖) 구분에
 * 의존한다. 그래서 매핑을 공통 부모 하나로 뭉뚱그리지 않고 구체 예외마다 명시한다.
 *
 * <p>여기가 책임지는 범위는 문서화된 엔드포인트에 들어온 요청의 업무·검증 실패다. 그 범위에서 핸들러에 없는 예외는 5xx 로 나간다 — 그것이 "예상 밖"의 정의다. 반면
 * 잘못된 경로(404)·메서드(405)·미디어 타입(415) 같은 프로토콜 수준 오류는 Spring MVC 가 자체 처리하며 기본 본문 형태로 나간다. 요청이 우리 엔드포인트에
 * 도달했는데 인자가 잘못된 경우(경로 변수 타입 불일치)는 amount=0 과 같은 범주이므로 여기서 400 으로 맞춘다.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

  /** 인증 실패. 인터셉터가 던진 예외도 HandlerExceptionResolver 를 타고 여기로 온다. */
  @ExceptionHandler(UnauthorizedMerchantException.class)
  public ResponseEntity<ErrorResponse> handleUnauthorized(UnauthorizedMerchantException e) {
    return response(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", e);
  }

  @ExceptionHandler({PaymentNotFoundException.class, WalletNotFoundException.class})
  public ResponseEntity<ErrorResponse> handleNotFound(DomainException e) {
    return response(HttpStatus.NOT_FOUND, "NOT_FOUND", e);
  }

  @ExceptionHandler(InsufficientBalanceException.class)
  public ResponseEntity<ErrorResponse> handleInsufficientBalance(InsufficientBalanceException e) {
    return response(HttpStatus.CONFLICT, "INSUFFICIENT_BALANCE", e);
  }

  @ExceptionHandler(BalanceOverflowException.class)
  public ResponseEntity<ErrorResponse> handleBalanceOverflow(BalanceOverflowException e) {
    return response(HttpStatus.CONFLICT, "BALANCE_LIMIT_EXCEEDED", e);
  }

  @ExceptionHandler(InvalidPaymentStatusException.class)
  public ResponseEntity<ErrorResponse> handleInvalidStatus(InvalidPaymentStatusException e) {
    return response(HttpStatus.CONFLICT, "INVALID_PAYMENT_STATUS", e);
  }

  @ExceptionHandler(PaymentMismatchException.class)
  public ResponseEntity<ErrorResponse> handleMismatch(PaymentMismatchException e) {
    return response(HttpStatus.CONFLICT, "PAYMENT_MISMATCH", e);
  }

  @ExceptionHandler(CancelAmountExceededException.class)
  public ResponseEntity<ErrorResponse> handleCancelExceeded(CancelAmountExceededException e) {
    return response(HttpStatus.CONFLICT, "CANCEL_AMOUNT_EXCEEDED", e);
  }

  /** Bean Validation 실패 - 금액 0·음수, MONEY 인데 memberId 누락 등. */
  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e) {
    String message =
        e.getBindingResult().getFieldErrors().stream()
            .findFirst()
            .map(error -> error.getField() + ": " + error.getDefaultMessage())
            .orElseGet(
                () ->
                    e.getBindingResult().getGlobalErrors().stream()
                        .findFirst()
                        .map(error -> error.getDefaultMessage())
                        .orElse("요청 값이 올바르지 않습니다."));

    return ResponseEntity.badRequest().body(new ErrorResponse("INVALID_REQUEST", message));
  }

  /** 경로 변수 타입 불일치 - 예: {@code GET /v1/wallets/abc}. 우리 엔드포인트에 온 잘못된 인자다. */
  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
    return ResponseEntity.badRequest()
        .body(new ErrorResponse("INVALID_REQUEST", e.getName() + ": 값의 형식이 올바르지 않습니다."));
  }

  /** 본문을 읽을 수 없는 경우 - 잘못된 JSON, 알 수 없는 enum 값 등. */
  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException e) {
    return ResponseEntity.badRequest()
        .body(new ErrorResponse("INVALID_REQUEST", "요청 본문을 해석할 수 없습니다."));
  }

  private ResponseEntity<ErrorResponse> response(HttpStatus status, String code, Exception e) {
    return ResponseEntity.status(status).body(new ErrorResponse(code, e.getMessage()));
  }
}
