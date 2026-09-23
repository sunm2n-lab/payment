package com.sunm2n.pay.bootstrap.web;

import com.sunm2n.pay.common.exception.DomainException;
import com.sunm2n.pay.common.web.error.ErrorResponse;
import com.sunm2n.pay.merchant.api.auth.UnauthorizedMerchantException;
import com.sunm2n.pay.payment.domain.exception.CancelAmountExceededException;
import com.sunm2n.pay.payment.domain.exception.InvalidPaymentStatusException;
import com.sunm2n.pay.payment.domain.exception.PaymentMismatchException;
import com.sunm2n.pay.payment.domain.exception.PaymentNotFoundException;
import com.sunm2n.pay.wallet.domain.exception.BalanceOverflowException;
import com.sunm2n.pay.wallet.domain.exception.InsufficientBalanceException;
import com.sunm2n.pay.wallet.domain.exception.WalletNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * 실패를 HTTP 응답으로 바꾸는 유일한 곳이다. 도메인 예외는 HTTP 를 모른다.
 *
 * <p>이후 모든 시나리오의 완료 기준이 "예상한 실패 응답과 예상 밖 오류를 구분했는지"(SCENARIO 114행), 즉 4xx(예상된 실패) / 5xx(예상 밖) 구분에
 * 의존한다. 그래서 업무 예외 매핑을 공통 부모 하나로 뭉뚱그리지 않고 구체 예외마다 명시한다.
 *
 * <p>404·405·415 같은 Spring MVC 예외는 부모 {@link ResponseEntityExceptionHandler} 가 상태와 헤더({@code Allow}
 * 등)를 판정한다. 여기서는 그 판정을 그대로 받고 본문만 우리 형식으로 바꾼다.
 *
 * <p><b>단일 출구</b>: 이 클래스에서 {@code ResponseEntity} 를 만드는 곳은 {@link #createResponseEntity} 하나뿐이다. 모든
 * 핸들러가 부모의 {@code handleExceptionInternal} 을 거쳐 반환하므로, 이미 커밋된 응답 검사와 본문·Content-Type 고정이 모든 경로에 한
 * 번에 적용된다. 확인은 {@code grep -n "ResponseEntity\." ApiExceptionHandler.java} — 결과가 없어야 한다.
 *
 * <p><b>오류 응답의 Content-Type 은 {@code Accept} 와 무관하게 {@code application/json} 이다.</b> 응답에 구체적인
 * Content-Type 이 미리 지정돼 있으면 Spring 은 {@code Accept} 협상을 건너뛴다. 협상에 맡기면 JSON 을 수용하지 않는 클라이언트에게 오류 본문을
 * 쓸 수 없어 핸들러 결과가 버려지고, 업무 예외의 4xx 가 500 으로 바뀐다. 정상 응답은 기존 협상을 유지한다.
 *
 * <p>{@link ErrorResponse} 는 우리 DTO 다. 부모가 쓰는 {@code org.springframework.web.ErrorResponse} 와 이름이
 * 같으므로 부모 것이 필요하면 FQN 으로 쓴다.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

  private static final String INVALID_REQUEST_MESSAGE = "요청 값이 올바르지 않습니다.";
  private static final String INTERNAL_ERROR_MESSAGE = "서버 내부 오류가 발생했습니다.";

  /** 인증 실패. 인터셉터가 던진 예외도 HandlerExceptionResolver 를 타고 여기로 온다. */
  @ExceptionHandler(UnauthorizedMerchantException.class)
  public ResponseEntity<Object> handleUnauthorized(
      UnauthorizedMerchantException e, WebRequest request) {
    return error(e, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", e.getMessage(), request);
  }

  @ExceptionHandler({PaymentNotFoundException.class, WalletNotFoundException.class})
  public ResponseEntity<Object> handleNotFound(DomainException e, WebRequest request) {
    return error(e, HttpStatus.NOT_FOUND, "NOT_FOUND", e.getMessage(), request);
  }

  @ExceptionHandler(InsufficientBalanceException.class)
  public ResponseEntity<Object> handleInsufficientBalance(
      InsufficientBalanceException e, WebRequest request) {
    return error(e, HttpStatus.CONFLICT, "INSUFFICIENT_BALANCE", e.getMessage(), request);
  }

  @ExceptionHandler(BalanceOverflowException.class)
  public ResponseEntity<Object> handleBalanceOverflow(
      BalanceOverflowException e, WebRequest request) {
    return error(e, HttpStatus.CONFLICT, "BALANCE_LIMIT_EXCEEDED", e.getMessage(), request);
  }

  @ExceptionHandler(InvalidPaymentStatusException.class)
  public ResponseEntity<Object> handleInvalidStatus(
      InvalidPaymentStatusException e, WebRequest request) {
    return error(e, HttpStatus.CONFLICT, "INVALID_PAYMENT_STATUS", e.getMessage(), request);
  }

  @ExceptionHandler(PaymentMismatchException.class)
  public ResponseEntity<Object> handleMismatch(PaymentMismatchException e, WebRequest request) {
    return error(e, HttpStatus.CONFLICT, "PAYMENT_MISMATCH", e.getMessage(), request);
  }

  @ExceptionHandler(CancelAmountExceededException.class)
  public ResponseEntity<Object> handleCancelExceeded(
      CancelAmountExceededException e, WebRequest request) {
    return error(e, HttpStatus.CONFLICT, "CANCEL_AMOUNT_EXCEEDED", e.getMessage(), request);
  }

  /**
   * 경로 변수 타입 불일치 - 예: {@code GET /v1/wallets/abc}. 우리 엔드포인트에 온 잘못된 인자다.
   *
   * <p>부모는 상위 타입 {@code TypeMismatchException} 을 다루므로 같은 타입 중복이 아니고, Spring 은 더 가까운 타입인 이 핸들러를 고른다.
   */
  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  public ResponseEntity<Object> handleTypeMismatch(
      MethodArgumentTypeMismatchException e, WebRequest request) {
    return error(
        e, HttpStatus.BAD_REQUEST, "INVALID_REQUEST", e.getName() + ": 값의 형식이 올바르지 않습니다.", request);
  }

  /**
   * 어느 핸들러에도 없는 예외 - "예상 밖" 의 정의다.
   *
   * <p>클라이언트에는 고정 문구만 보낸다. 원인 로그는 {@link #handleExceptionInternal} 이 남긴다. 부모의 Spring MVC 예외 핸들러들보다
   * 덜 구체적이므로 그쪽 판정을 가로채지 않는다.
   */
  @ExceptionHandler(Exception.class)
  public ResponseEntity<Object> handleUnexpected(Exception e, WebRequest request) {
    return error(
        e,
        HttpStatus.INTERNAL_SERVER_ERROR,
        "INTERNAL_SERVER_ERROR",
        INTERNAL_ERROR_MESSAGE,
        request);
  }

  /**
   * Bean Validation 실패 - 금액 0·음수, MONEY 인데 memberId 누락 등.
   *
   * <p>부모가 같은 예외를 {@code @ExceptionHandler} 로 매핑하므로 별도 핸들러를 두면 기동 시 ambiguous 오류가 난다. override 한다.
   */
  @Override
  protected ResponseEntity<Object> handleMethodArgumentNotValid(
      MethodArgumentNotValidException ex,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    String message =
        ex.getBindingResult().getFieldErrors().stream()
            .findFirst()
            .map(error -> error.getField() + ": " + error.getDefaultMessage())
            .orElseGet(
                () ->
                    ex.getBindingResult().getGlobalErrors().stream()
                        .findFirst()
                        .map(error -> error.getDefaultMessage())
                        .orElse(INVALID_REQUEST_MESSAGE));

    return handleExceptionInternal(
        ex, new ErrorResponse("INVALID_REQUEST", message), headers, status, request);
  }

  /** 본문을 읽을 수 없는 경우 - 잘못된 JSON, 알 수 없는 enum 값 등. override 하는 이유는 위와 같다. */
  @Override
  protected ResponseEntity<Object> handleHttpMessageNotReadable(
      HttpMessageNotReadableException ex,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    return handleExceptionInternal(
        ex, new ErrorResponse("INVALID_REQUEST", "요청 본문을 해석할 수 없습니다."), headers, status, request);
  }

  /**
   * 5xx 로 응답하는 모든 예외의 원인과 스택을 ERROR 로 남긴다.
   *
   * <p>catch-all 만이 아니라 부모가 500 으로 판정하는 Spring MVC 예외({@code MissingPathVariableException} 등)도 여기를
   * 지난다. 부모는 이들을 로그 없이 응답하므로, 로깅을 catch-all 에 두면 클라이언트에는 고정 문구만 가고 서버에는 근거가 남지 않는다. 5xx 는 "예상 밖" 의
   * 정의이므로 500 에 한정하지 않는다.
   */
  @Override
  protected ResponseEntity<Object> handleExceptionInternal(
      Exception ex,
      Object body,
      HttpHeaders headers,
      HttpStatusCode statusCode,
      WebRequest request) {
    if (statusCode.is5xxServerError()) {
      log.error("서버 오류로 응답한 예외 (status={})", statusCode.value(), ex);
    }
    return super.handleExceptionInternal(ex, body, headers, statusCode, request);
  }

  /**
   * 단일 출구. 부모의 {@code handleExceptionInternal} 이 커밋 검사와 {@code ProblemDetail} 채우기를 마친 뒤 호출한다.
   *
   * <p>우리 핸들러가 만든 {@link ErrorResponse} 는 그대로 쓰고, 부모가 판정한 Spring MVC 예외는 상태로 본문을 정한다. 부모가 준 헤더
   * ({@code Allow} 등)는 보존하고 Content-Type 만 JSON 으로 고정한다.
   */
  @Override
  protected ResponseEntity<Object> createResponseEntity(
      Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
    Object errorBody = body instanceof ErrorResponse ? body : toErrorResponse(statusCode);
    HttpHeaders merged = new HttpHeaders();
    merged.putAll(headers);
    merged.setContentType(MediaType.APPLICATION_JSON);
    return new ResponseEntity<>(errorBody, merged, statusCode);
  }

  private ResponseEntity<Object> error(
      Exception e, HttpStatus status, String code, String message, WebRequest request) {
    return handleExceptionInternal(
        e, new ErrorResponse(code, message), new HttpHeaders(), status, request);
  }

  /** 부모가 판정한 상태의 본문. 표에 없는 상태는 새 문구를 만들지 않고 표준 이유 문구를 쓴다. */
  private static ErrorResponse toErrorResponse(HttpStatusCode statusCode) {
    HttpStatus status = HttpStatus.resolve(statusCode.value());
    if (status == null) {
      return new ErrorResponse("HTTP_" + statusCode.value(), "요청을 처리할 수 없습니다.");
    }
    return switch (status) {
      case BAD_REQUEST -> new ErrorResponse("INVALID_REQUEST", INVALID_REQUEST_MESSAGE);
      case NOT_FOUND -> new ErrorResponse("NOT_FOUND", "요청한 리소스를 찾을 수 없습니다.");
      case METHOD_NOT_ALLOWED -> new ErrorResponse("METHOD_NOT_ALLOWED", "지원하지 않는 HTTP 메서드입니다.");
      case NOT_ACCEPTABLE -> new ErrorResponse("NOT_ACCEPTABLE", "응답할 수 있는 형식이 없습니다.");
      case UNSUPPORTED_MEDIA_TYPE ->
          new ErrorResponse("UNSUPPORTED_MEDIA_TYPE", "지원하지 않는 Content-Type 입니다.");
      case INTERNAL_SERVER_ERROR ->
          new ErrorResponse("INTERNAL_SERVER_ERROR", INTERNAL_ERROR_MESSAGE);
      default -> new ErrorResponse(status.name(), status.getReasonPhrase());
    };
  }
}
