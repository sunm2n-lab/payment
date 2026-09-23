# API 응답 규약

> 상위 문서: [SCENARIO.md](SCENARIO.md) · 작업 지시서: [plan/RESTRUCTURE.md](plan/RESTRUCTURE.md) 4·6절 · 이슈: #21

"예상한 실패(4xx) / 예상 밖(5xx)" 구분이 모든 시나리오의 완료 기준이다 (SCENARIO 114행). 이 문서는 그 구분이 HTTP 에서 어떻게 보이는지를 정한다. HTTP 변환은 `bootstrap.web.ApiExceptionHandler` 한 곳에서만 하며, 도메인 예외는 `HttpStatus` 를 모른다.

## 1. 성공

DTO 를 직접 반환한다. `data` 같은 래퍼를 두지 않는다. 테스트와 k6 가 최상위 필드를 읽는다.

```json
{ "paymentKey": "...", "status": "DONE", "amount": 10000 }
```

정상 응답은 `Accept` 협상을 따른다. JSON 을 수용하지 않는 `Accept` 로 정상 조회를 하면 406 이다 (그 406 은 아래 실패 규약을 따른다).

## 2. 실패

본문은 항상 `{"code","message"}` 이고 **`Content-Type: application/json` 이다. `Accept` 와 무관하다.**

```json
{ "code": "NOT_FOUND", "message": "결제를 찾을 수 없습니다. paymentKey=..." }
```

| 원인 | 상태 | `code` | `message` |
|---|---|---|---|
| 인증 실패 | 401 | `UNAUTHORIZED` | 예외 메시지 |
| 결제·지갑 없음 (소유권 위반 포함) | 404 | `NOT_FOUND` | 예외 메시지 |
| 잔액 부족 | 409 | `INSUFFICIENT_BALANCE` | 예외 메시지 |
| 잔액 상한 초과 | 409 | `BALANCE_LIMIT_EXCEEDED` | 예외 메시지 |
| 승인할 수 없는 결제 상태 | 409 | `INVALID_PAYMENT_STATUS` | 예외 메시지 |
| 승인 값 불일치 | 409 | `PAYMENT_MISMATCH` | 예외 메시지 |
| 취소 가능 금액 초과 | 409 | `CANCEL_AMOUNT_EXCEEDED` | 예외 메시지 |
| Bean Validation | 400 | `INVALID_REQUEST` | 첫 필드 오류 `field: message`, 없으면 첫 전역 오류, 없으면 `요청 값이 올바르지 않습니다.` |
| 본문 파싱 (깨진 JSON, 알 수 없는 enum, 소수 금액 등) | 400 | `INVALID_REQUEST` | `요청 본문을 해석할 수 없습니다.` |
| 경로 변수 타입 불일치 | 400 | `INVALID_REQUEST` | `<변수명>: 값의 형식이 올바르지 않습니다.` |
| 그 밖에 Spring MVC 가 400 으로 판정한 것 (파라미터 누락 등) | 400 | `INVALID_REQUEST` | `요청 값이 올바르지 않습니다.` |
| 리소스 없음 (`NoResourceFoundException`, `NoHandlerFoundException`) | 404 | `NOT_FOUND` | `요청한 리소스를 찾을 수 없습니다.` |
| 메서드 불일치 | 405 | `METHOD_NOT_ALLOWED` | `지원하지 않는 HTTP 메서드입니다.` — `Allow` 헤더 보존 |
| Accept 불일치 (정상 응답을 만들 수 없음) | 406 | `NOT_ACCEPTABLE` | `응답할 수 있는 형식이 없습니다.` |
| 미디어 타입 불일치 | 415 | `UNSUPPORTED_MEDIA_TYPE` | `지원하지 않는 Content-Type 입니다.` — 지원 타입(`Accept`) 헤더 보존 |
| Spring MVC 가 판정한 그 밖의 상태 | 판정된 상태 | `HttpStatus` 이름 (`SERVICE_UNAVAILABLE` 등) | `HttpStatus.getReasonPhrase()` |
| Spring MVC 가 500 으로 판정한 것 | 500 | `INTERNAL_SERVER_ERROR` | `서버 내부 오류가 발생했습니다.` |
| 핸들러에 없는 예외 | 500 | `INTERNAL_SERVER_ERROR` | `서버 내부 오류가 발생했습니다.` |

- **500 의 실제 예외와 스택 트레이스는 서버 로그에 ERROR 로 남는다.** 클라이언트에는 고정 문구만 간다
- 업무 예외는 공통 부모 하나로 뭉뚱그리지 않고 구체 예외마다 매핑한다. 새 업무 예외를 추가하면 핸들러에도 명시해야 하며, 빠뜨리면 500 이 된다 — 그것이 "예상 밖" 의 정의다
- 기술 예외(`OptimisticLockingFailureException` 등)는 본선 전략이 아니므로 개별 코드 없이 500 이다

### 2.1 구현 규칙

- `ApiExceptionHandler` 는 `ResponseEntityExceptionHandler` 를 상속한다. Spring MVC 예외의 상태와 헤더는 부모가 판정한다
- **단일 출구**: `ResponseEntity` 를 만드는 곳은 `createResponseEntity` 훅 하나뿐이다. 모든 `@ExceptionHandler` 와 override 는 `handleExceptionInternal` 을 거친다. 확인: `grep -n "ResponseEntity\." ApiExceptionHandler.java` 결과 없음
- 훅이 Content-Type 을 `application/json` 으로 **미리 지정**한다. 응답에 구체적인 Content-Type 이 있으면 Spring 은 `Accept` 협상을 건너뛴다 (`AbstractMessageConverterMethodProcessor.writeWithMessageConverters`)

### 2.2 범위

DispatcherServlet 이 처리하는 요청까지다. 필터 단계의 오류, 연결 단절, 응답 전송 실패는 JSON 을 보장하지 않는다.

## 3. 판정 순서

아래는 현재 MVC 설정과 엔드포인트 매핑에서 실측한 값이다 (`RoutingContractApiTest`). **애플리케이션 전체 규칙이 아니라 이 설정의 결과다.** 예를 들어 매핑에 `consumes` 를 붙이면 415 는 핸들러 매핑 단계로 올라가 인증보다 먼저 판정된다.

| 요청 | `X-API-Key` | 상태 | 비고 |
|---|---|---|---|
| `GET /v1/payments/confirm` | 없음 | 401 | |
| `GET /v1/payments/confirm` | 유효 | 404 | `GET /{paymentKey}` 에 매칭되어 `paymentKey=confirm` 을 찾는다 |
| `PUT /v1/payments/confirm` | 없음 / 유효 | 405 | `Allow` = {POST, GET} (서로 다른 두 패턴의 합, 순서 무관) |
| `GET /v1/nope` | 없음 | 401 | |
| `GET /v1/nope` | 유효 | 404 | |
| `GET /nope` | 없음 | 404 | 인터셉터 범위(`/v1/**`) 밖 |
| `POST /v1/payments`, `Content-Type: text/plain` | 없음 | 401 | |
| `POST /v1/payments`, `Content-Type: text/plain` | 유효 | 415 | |
| `POST /v1/wallets/1` | 유효 | 405 | `Allow` = {GET} |

- **405 는 인증보다 먼저다.** 핸들러 매핑 단계에서 나므로 인터셉터에 닿지 않는다
- **`/v1/**` 아래의 404·415 는 인증 뒤다.** 정적 리소스 핸들러 매핑에도 인터셉터가 적용되므로 없는 경로도 키가 없으면 401 이다

## 4. 변경 전 기록

규약 이전(#21 PR ③ 전)에는 업무 예외와 입력 검증만 JSON 이었고, 404·405·415 는 본문이 비었으며, 예상 밖 오류는 Spring Boot 기본 오류 응답이었다. 그리고 **JSON 을 수용하지 않는 `Accept` 에서 4xx 가 500 으로 바뀌었다.**

실측 (내장 Tomcat + `TestRestTemplate`, `Accept: text/plain`):

| 요청 | MockMvc | 실제 서버 |
|---|---|---|
| 없는 결제 조회, 유효한 키 | 예외가 DispatcherServlet 밖으로 탈출 | **500**, 본문 없음 |
| 키 없음 | 예외 탈출 | **500**, 본문 없음 |
| `GET /v1/wallets/abc` (타입 불일치) | — | 400, 본문 없음 |
| `GET /v1/nope`, 유효한 키 | — | 404, 본문 없음 |
| 정상 지갑 조회 | 406 | 406, 본문 없음 |

원인: `@ExceptionHandler` 가 만든 `ResponseEntity<ErrorResponse>` 를 JSON 으로 쓸 수 없어 핸들러 안에서 `HttpMediaTypeNotAcceptableException` 이 나고, 그 핸들러의 결과가 버려졌다. Spring 이 아는 예외(타입 불일치 등)는 기본 resolver 가 받아 상태가 살았지만, 업무·인증 예외는 아무도 받지 않아 500 이 됐다.

검증: `ErrorResponseContractTest` 를 옛 핸들러로 돌리면 24건 중 19건이 실패하고, `ErrorResponseServerTest` 는 404 대신 500 을 받는다.

## 5. 테스트

| 테스트 | 고정하는 것 |
|---|---|
| `FailureContractApiTest` | 업무 실패의 상태·code, 거절 후 부작용 없음 |
| `ApiInputValidationTest` | 입력 길이·형식 400, 잔액 상한 409 |
| `RoutingContractApiTest` | 3절 판정 순서와 `Allow` |
| `ErrorResponseContractTest` | 2절 본문·Content-Type 을 기본 `Accept` 와 `text/plain` 모두에서. 500 은 standalone |
| `ErrorResponseServerTest` | 내장 서버에서 `Accept: text/plain` 404 + JSON |

## 6. 범위 밖

| 하지 않는 것 | 비고 |
|---|---|
| 멱등키·멱등 응답 | S5 |
| 기술 예외의 개별 오류 코드 | 지금은 500 |
| 업무별 advice 분리, 추적 ID, 검증 오류 목록, RFC 7807 `ProblemDetail` | 미정 |
