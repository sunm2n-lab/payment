package com.sunm2n.payment.api;

import com.sunm2n.payment.api.dto.ErrorResponse;
import com.sunm2n.payment.domain.exception.CancelAmountExceededException;
import com.sunm2n.payment.domain.exception.DomainException;
import com.sunm2n.payment.domain.exception.InsufficientBalanceException;
import com.sunm2n.payment.domain.exception.InvalidPaymentStatusException;
import com.sunm2n.payment.domain.exception.PaymentMismatchException;
import com.sunm2n.payment.domain.exception.PaymentNotFoundException;
import com.sunm2n.payment.domain.exception.WalletNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 도메인 예외를 4xx 로 매핑한다. **선택이 아니라 필수다.**
 *
 * <p>이후 모든 시나리오의 완료 기준이 "예상한 실패 응답과 예상 밖 오류를 구분했는지"(SCENARIO 114행), 즉 4xx(예상된 실패) / 5xx(예상 밖) 구분에
 * 의존한다. 그래서 매핑을 공통 부모 하나로 뭉뚱그리지 않고 구체 예외마다 명시한다. 여기에 없는 예외는 5xx 로 나가야 한다 — 그것이 "예상 밖"의 정의다.
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
