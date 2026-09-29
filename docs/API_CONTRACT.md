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
| 주문 id 로 찾은 결제 없음 — 다른 가맹점의 주문 포함 (S6) | 404 | `NOT_FOUND` | `결제를 찾을 수 없습니다. orderId=...` |
| 잔액 부족 | 409 | `INSUFFICIENT_BALANCE` | 예외 메시지 |
| 잔액 상한 초과 | 409 | `BALANCE_LIMIT_EXCEEDED` | 예외 메시지 |
| 승인할 수 없는 결제 상태 | 409 | `INVALID_PAYMENT_STATUS` | 예외 메시지 |
| 승인 값 불일치 | 409 | `PAYMENT_MISMATCH` | 예외 메시지 |
| 취소 가능 금액 초과 | 409 | `CANCEL_AMOUNT_EXCEEDED` | 예외 메시지 |
| 같은 가맹점에 같은 주문 id 의 결제가 이미 있음 (S6) | 409 | `DUPLICATE_ORDER` | 예외 메시지. 내용이 같아도 409 |
| 같은 멱등키에 다른 요청 내용 (S5) | 409 | `IDEMPOTENCY_KEY_REUSED` | 예외 메시지 |
| 같은 멱등키의 선행 요청 처리 중, 선점 대기 상한 초과 (S5) | 409 | `IDEMPOTENCY_KEY_IN_USE` | 예외 메시지. 같은 키로 다시 보내면 되는 재시도 가능한 충돌 |
| `Idempotency-Key` 헤더 형식 오류 (S5) | 400 | `INVALID_REQUEST` | `Idempotency-Key: <사유>` |
| 경로 변수의 주문 id 형식 오류 (S6) | 400 | `INVALID_REQUEST` | `orderId: 영문 대소문자, 숫자, '-', '_' 로 이루어진 6~64자여야 합니다.` |
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

- **5xx 로 응답한 예외는 원인과 스택 트레이스가 서버 로그에 ERROR 로 한 번 남는다.** 핸들러에 없는 예외뿐 아니라 Spring MVC 가 500 으로 판정한 예외(`MissingPathVariableException` 등)도 포함한다. 클라이언트에는 고정 문구만 간다
- 업무 예외는 공통 부모 하나로 뭉뚱그리지 않고 구체 예외마다 매핑한다. 새 업무 예외를 추가하면 핸들러에도 명시해야 하며, 빠뜨리면 500 이 된다 — 그것이 "예상 밖" 의 정의다
- 기술 예외(`OptimisticLockingFailureException` 등)는 본선 전략이 아니므로 개별 코드 없이 500 이다

### 2.1 구현 규칙

- `ApiExceptionHandler` 는 `ResponseEntityExceptionHandler` 를 상속한다. Spring MVC 예외의 상태와 헤더는 부모가 판정한다
- **단일 출구**: `ResponseEntity` 를 만드는 곳은 `createResponseEntity` 훅 하나뿐이다. 모든 `@ExceptionHandler` 와 override 는 `handleExceptionInternal` 을 거친다. 확인: `grep -n "ResponseEntity\." ApiExceptionHandler.java` 결과 없음
- 5xx 로깅은 예외와 상태를 함께 받는 `handleExceptionInternal` override 한 곳에서 하고 부모로 위임한다. 부모는 Spring MVC 가 판정한 500 을 로그 없이 응답하므로 catch-all 에 두면 그 경로가 빠진다
- 훅이 Content-Type 을 `application/json` 으로 **미리 지정**한다. 응답에 구체적인 Content-Type 이 있으면 Spring 은 `Accept` 협상을 건너뛴다 (`AbstractMessageConverterMethodProcessor.writeWithMessageConverters`)

### 2.2 범위

DispatcherServlet 이 처리하는 요청까지다. 필터 단계의 오류, 연결 단절, 응답 전송 실패는 JSON 을 보장하지 않는다. 서블릿 컨테이너가 MVC 전에 거절하는 요청도 마찬가지다 — 예: 경로의 인코딩된 슬래시(`%2F`)·NUL(`%00`)은 Tomcat 이 400 과 HTML 본문으로 응답한다 (2.4).

### 2.3 멱등키 — 취소 (S5)

`POST /v1/payments/{paymentKey}/cancel` 은 `Idempotency-Key` 헤더를 **선택**으로 받는다. 상세와 근거는 [phase1/S5.md](phase1/S5.md) 5절.

| 요청 | 응답 |
|---|---|
| 헤더 없음 | 기존과 같다. 중복 방지 없음 — 다시 보내면 다시 처리된다 |
| 새 키 | 처리하고 성공 응답(200)을 저장한다 |
| 같은 키·같은 내용, 선행 완료 | **저장된 응답** (200, 당시 본문). 결제를 다시 조회하지 않는다 |
| 같은 키·같은 내용, 선행 처리 중 | 선행이 끝날 때까지 기다린다. 선행이 커밋하면 저장된 응답, 롤백하면 이 요청이 처리된다 |
| 선행 처리 중이고 대기가 3초를 넘음 | 409 `IDEMPOTENCY_KEY_IN_USE` |
| 같은 키·다른 내용 (`paymentKey`·`cancelAmount`·`reason`, `reason` 의 `null` 과 `""` 는 다르다) | 409 `IDEMPOTENCY_KEY_REUSED`. 저장된 응답을 주지 않는다 |
| 업무 실패 (409 등) | 기존과 같다. 키는 저장되지 않는다 — 같은 키로 다시 보내면 재실행된다 |
| 형식 오류 | 400 `INVALID_REQUEST` |

- **키 규칙**: 1~64자, 출력 가능한 ASCII(`0x21`~`0x7E`)만, 쉼표 금지, 대소문자 구분. 빈 값(`Idempotency-Key:`)은 헤더 없음이 아니라 400 이다. 같은 헤더를 두 번 보내면 값이 쉼표로 합쳐져 400 이다. 앞뒤 공백은 서블릿 컨테이너가 잘라서 넘긴다
- **범위**: 키는 가맹점별이다. 다른 가맹점이 같은 문자열을 써도 무관하다
- **동일 응답**의 기준은 HTTP 상태와 JSON 필드·값이다. 필드 순서와 공백은 계약이 아니다
- **보장 범위**: 같은 키로 커밋되는 취소는 최대 한 건이고 그 성공 응답이 재생된다. 동시 요청이 **모두** 성공 응답을 받는다는 보장은 아니다 — 선행이 롤백할 때 대기자가 둘 이상이면 한 명이 데드락(1213)으로 **500** 을 받을 수 있다. 그 요청도 전체 롤백되므로 같은 키로 다시 보내면 안전하다
- 다른 API(생성·승인·충전)에는 아직 멱등키가 없다 (Phase 1 종료 점검)
- 주문 기반 취소(2.4)도 같은 규칙이다. 요청 내용은 `orderId`·`cancelAmount`·`reason` 이고 키 범위(가맹점, `CANCEL`)를 `paymentKey` 취소와 공유한다. 같은 키를 두 취소 경로에 쓰면 같은 결제라도 409 `IDEMPOTENCY_KEY_REUSED` 다

### 2.4 주문 id — 형식, 유일성, 주문 기반 API (S6)

상세와 근거는 [phase1/S6.md](phase1/S6.md) 5절.

**형식.** 영문 대소문자, 숫자, `-`, `_` 로 이루어진 **6~64자.** 대소문자를 구분한다. 결제 생성의 `orderId` 와 아래 두 API 의 경로 변수가 같은 규칙을 쓴다. 위반은 400 `INVALID_REQUEST` 이고 생성·조회·취소를 실행하지 않는다. 주문 id 는 가맹점이 발급하며 이 규칙은 형식만 본다. 승인 요청의 `orderId` 에는 형식 제한이 없다 — 저장된 값과 다르면 지금처럼 409 `PAYMENT_MISMATCH` 다.

**유일성.** 한 가맹점 안에서 주문 id 는 하나의 결제만 가리킨다. 같은 가맹점이 같은 주문 id 로 다시 생성하면 409 `DUPLICATE_ORDER` 다 — 요청 내용이 같아도 409 이고, 기존 결제로 응답하지 않는다 (재전송 응답은 Phase 1 종료 점검). 다른 가맹점은 같은 주문 id 를 쓸 수 있다. `Order-1` 과 `order-1` 은 다른 주문이다.

| 메서드 | 경로 | 동작 |
|---|---|---|
| GET | `/v1/payments/orders/{orderId}` | 요청 가맹점의 그 주문의 결제. 응답 본문은 `GET /v1/payments/{paymentKey}` 와 같다. 잠그지 않는다 |
| POST | `/v1/payments/orders/{orderId}/cancel` | 그 결제를 잠그며 읽은 뒤 취소한다. 본문·`Idempotency-Key`·오류는 `POST /v1/payments/{paymentKey}/cancel` 과 같다 |

- 없는 주문과 다른 가맹점의 주문은 모두 404 `NOT_FOUND` 이고 응답이 같다
- 경로의 주문 id 는 서블릿 컨테이너가 디코딩한 뒤 형식을 검사한다. 인코딩된 공백·악센트·마침표·세미콜론(`%20`, `%C3%A9`, `%2E`, `%3B`)은 400 `INVALID_REQUEST` 다. 인코딩된 슬래시·NUL(`%2F`, `%00`)은 컨테이너가 먼저 400(HTML)으로 거절한다. 인코딩하지 않은 경로 파라미터(`;x=1`)는 컨테이너가 떼어 내고 나머지로 처리한다 (`OrderPathServerTest`)

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
| `GET /v1/payments/orders` | 유효 | 404 | `GET /{paymentKey}` 에 매칭되어 `paymentKey=orders` 를 찾는다 (S6) |
| `POST /v1/payments/orders/cancel` | 유효 | 404 | `POST /{paymentKey}/cancel` 에 매칭되어 `paymentKey=orders` (S6) |
| `GET /v1/payments/orders/cancel` | 유효 | 404 | 주문 조회 — `orderId=cancel`(6자) (S6) |
| `GET /v1/payments/orders/abcde` (형식 밖) | 없음 | 401 | 인증이 주문 id 형식보다 먼저다 (S6) |
| `POST /v1/payments/orders/abcde/cancel`, `cancelAmount` 0 | 유효 | 400 | 메시지는 `cancelAmount...` — 본문 검증(인자 해석)이 주문 id 형식보다 먼저다 (S6) |
| `POST /v1/payments/orders/abcde/cancel`, 쉼표 든 멱등키 | 유효 | 400 | 메시지는 `orderId...` — 주문 id 형식이 멱등키 형식보다 먼저다 (S6) |
| `PUT /v1/payments/orders/order-1` | 유효 | 405 | `Allow` = {GET} (S6) |

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

검증: `ErrorResponseContractTest` 를 옛 핸들러로 돌리면 26건 중 20건이 실패하고 (통과하는 6건은 이전에도 JSON 이던 기본 `Accept` 업무·입력 오류 5건과 테스트 컨트롤러 스캔 제외 확인 1건), `ErrorResponseServerTest` 는 404 대신 500 을 받는다.

## 5. 테스트

| 테스트 | 고정하는 것 |
|---|---|
| `FailureContractApiTest` | 업무 실패의 상태·code, 거절 후 부작용 없음 |
| `ApiInputValidationTest` | 입력 길이·형식 400, 잔액 상한 409 |
| `RoutingContractApiTest` | 3절 판정 순서와 `Allow` |
| `ErrorResponseContractTest` | 2절 본문·Content-Type 을 기본 `Accept` 와 `text/plain` 모두에서. 500 은 standalone 으로 본문과 ERROR 로그 1건(catch-all, Spring MVC 판정 각각) |
| `ErrorResponseServerTest` | 내장 서버에서 `Accept: text/plain` 404 + JSON |
| `IdempotentCancelRegressionTest` | 2.3 의 순차 재생·내용 불일치·키 규칙·업무 실패 뒤 재실행 (S5) |
| `IdempotencyKeyHeaderServerTest` | 내장 서버에서 헤더 앞뒤 공백 제거, 복수 헤더 400, 빈 값 400 (S5) |
| `OrderIdFormatApiTest` | 생성 요청의 주문 id 형식 400, 6·64자 허용 (S6) |
| `OrderCancelRegressionTest` | 2.4 의 주문 기반 조회·취소, 404, 멱등키 교차 사용 409, 경로의 형식 400 (S6) |
| `OrderUniquenessRegressionTest` | 409 `DUPLICATE_ORDER` 순차·동시, 다른 가맹점 허용, 대소문자 구분 (S6) |
| `OrderPathServerTest` | 내장 서버에서 인코딩된 주문 id 경로의 처리 (S6) |

## 6. 범위 밖

| 하지 않는 것 | 비고 |
|---|---|
| 취소 외 API 의 멱등키 | Phase 1 종료 점검 (충전은 S5 구현을 `operation=CHARGE` 로 재사용) |
| 같은 내용의 생성 재전송에 기존 결제로 응답 | Phase 1 종료 점검. 지금은 내용과 무관하게 409 `DUPLICATE_ORDER` |
| 승인 요청 `orderId` 의 형식 제한 | 저장된 값과의 일치만 본다 |
| 키 INSERT 의 1213 을 재시도 가능한 409 로 번역 | 지금은 500. S5 관측 결과와 함께 후보로 남김 |
| 기술 예외의 개별 오류 코드 | 지금은 500 |
| 업무별 advice 분리, 추적 ID, 검증 오류 목록, RFC 7807 `ProblemDetail` | 미정 |
