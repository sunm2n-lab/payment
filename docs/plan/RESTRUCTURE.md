# 구조 정리와 오류 응답 규약 — 작업 계획

> 상위 문서: [SCENARIO.md](../SCENARIO.md) · 선행 기록: [phase1/S2.md](../phase1/S2.md)
>
> 이 문서는 **작업 지시서**다. 다른 에이전트가 이 문서만 읽고 PR 을 만들 수 있어야 한다. 결정된 것과 미결인 것을 구분해 두었고, 기대값은 전부 현재 코드에서 **실측한 값**이다 (2026-09-23, 브랜치 `experiment/s2-wallet-locking`, 커밋 `166e194`).

## 0. 한눈에

| 항목 | 내용 |
|---|---|
| 목적 | 학습 동작을 그대로 둔 채 (1) 코드를 업무별 패키지로 재배치하고 (2) HTTP 실패 응답 형식을 통일한다 |
| 바꾸지 않는 것 | 트랜잭션·락·재시도·빈 이름·`@Primary`·`@Qualifier`·스키마·테스트 동기화 지점·오류 판정 순서·기존 오류 코드와 HTTP 상태 |
| PR | 3개, 순서대로. ① 기준선 테스트 ② 구조 이동 ③ 오류 응답 |
| 완료 기준 | `spotlessCheck` + 전체 테스트 green, S1·S2 재현 테스트가 계속 결함을 재현, 회귀 테스트 통과, 새 계약 테스트 통과 |
| 루트 패키지 | **`com.sunm2n.pay`** — #21 에서 확정했다. 이 문서에 남은 "미결" 표기보다 이슈의 결정이 우선한다 (5.1) |

## 1. 배경

S1·S2 를 거치며 `application` 패키지에 결제 승인 구현 3개, 잔액 변경 전략 5개, 조합 설정, 서비스 2개가 한 폴더에 쌓였다. S3(취소 전략)·S5(멱등키)·S8/S9(외부 호출)·S10(정산 배치)이 더해지면 계층별 폴더 하나에 30개 이상이 들어간다. 새 코드를 어디에 두는지가 매번 판단거리가 되기 전에, 업무 단위로 갈라 둔다.

같은 시점에 실패 응답도 정리한다. 지금은 업무 예외와 입력 검증만 `{"code","message"}` JSON 이고, 잘못된 URL·메서드·미디어 타입은 본문이 비어 있으며, 예상 밖 오류는 Spring Boot 기본 오류 응답이다. 그리고 **클라이언트가 JSON 을 수용하지 않는 `Accept` 를 보내면 기존 4xx 가 500 으로 바뀌는 결함**이 있다 (4.3 실측). "예상한 실패(4xx) / 예상 밖(5xx)" 구분이 모든 시나리오의 완료 기준이므로 (SCENARIO 114행) 이 결함은 지금 고친다.

### 1.1 확정된 결정

| 결정 | 내용 | 근거 |
|---|---|---|
| 패키지 축 | 업무(payment / wallet / merchant / settlement) 우선, 각 업무 안에 api / application / domain / infrastructure | 업무 간 의존이 결제→지갑 한 방향뿐이라 순환 없이 나뉜다 (지갑 쪽이 결제 쪽을 import 하는 곳 0건) |
| 단일 모듈 유지 | Gradle 모듈을 나누지 않는다 | Phase 2 의 일이다 (SCENARIO 318행). 이번 변경은 단일 모듈 안의 응집도 정리이며, Phase 2 의 모듈 경계는 기존 계층별 분리안을 출발점으로 그 단계에서 검토한다 |
| `common` 의 정의 | **업무를 모르는** 공통 코드만 둔다 | 이 정의 때문에 아래 두 항목이 갈린다 |
| `Amounts` 위치 | `wallet.domain` | `Amounts.add` 는 `BalanceOverflowException`(지갑 예외)을 던지고, 사용처 3곳이 전부 지갑 잔액 덧셈이다. `common.money` 에 두면 common→wallet 의존이 생긴다 |
| 전역 예외 핸들러 위치 | `bootstrap.web` 에 **한 클래스** | 모든 업무의 예외를 알아야 하므로 common 의 정의에 맞지 않는다. 업무별 advice 분리는 advice 순서 관리가 새로 생기므로 이번에 하지 않는다 |
| `ErrorResponse` 위치 | `common.web.error` | 업무를 모르는 응답 DTO 다 |
| `WebConfig` 위치 | `bootstrap.config` | 애플리케이션 전체의 MVC 배선이다 |
| 타입 불일치 핸들러 | 현재 `@ExceptionHandler(MethodArgumentTypeMismatchException)` 를 **그대로 둔다** | 부모 `ResponseEntityExceptionHandler` 는 상위 타입 `TypeMismatchException` 을 다루므로 같은 타입 중복이 아니고, Spring 은 더 가까운 타입의 핸들러를 고른다 |
| 오류 응답의 Content-Type | **`Accept` 와 무관하게 `application/json` 으로 고정** | API 정책 선택이다. 정상 응답은 기존 협상을 유지한다. 4.3 참조 |
| 성공 응답 | 현재 형식 유지. `ApiResponse<T>` 나 `data` 래퍼를 넣지 않는다 | 테스트와 k6 가 `paymentKey`, `status` 등 최상위 필드를 읽는다 |

### 1.2 유지해야 할 학습 조건

이동과 응답 변경 어느 쪽도 아래를 바꾸면 안 된다. PR 리뷰에서 diff 로 확인한다.

- 트랜잭션의 위치·전파·격리 수준 (`@Transactional` 이 붙은 메서드와 그 클래스)
- SQL 실행 순서와 엔티티 조회·변경 순서
- 락 획득 순서(`payment → wallet`)와 재시도 범위(트랜잭션 바깥)
- 빈 이름, `@Primary`, `@Qualifier("...")` 문자열 — 빈 이름은 클래스 단순 이름과 `@Bean` 메서드 이름에서 오므로 패키지 이동으로 바뀌지 않는다
- naive 구현과 개선 구현의 공존
- 테스트 동기화 지점 (`ConcurrencyGateConfig` 가 감싸는 리포지터리와 메서드 이름)
- DB 스키마·인덱스·FK (마이그레이션 추가 없음)
- 오류 판정 순서와 응답 의미 (4.2 실측표)
- 결제가 지갑 리포지터리에 직접 접근하는 것 (`PaymentSupport`, `PaymentService.cancel`) — **이번에 남겨 둔다.** 환불 서비스 추출은 S3 에서 한다

## 2. 목표 구조

`<root>` 는 루트 패키지이며 #21 에서 `com.sunm2n.pay` 로 확정했다 (5.1).

```
<root>
├── PaymentApplication
├── payment
│   ├── api                  PaymentController
│   │   └── dto              CreatePaymentRequest, ConfirmPaymentRequest, CancelPaymentRequest, PaymentResponse
│   ├── application          PaymentService, PaymentSupport
│   │   └── confirmation     PaymentConfirmer, NaivePaymentConfirmer, CasPaymentConfirmer,
│   │                        RetryingPaymentConfirmer, RetryMetrics
│   ├── domain               Payment, PaymentCancel, PaymentMethod, PaymentStatus
│   │   └── exception        PaymentNotFoundException, PaymentMismatchException,
│   │                        InvalidPaymentStatusException, CancelAmountExceededException
│   └── infrastructure       PaymentRepository, PaymentCancelRepository
│       └── card             CardApprovalClient, CardApproval, FakeCardApprovalClient
├── wallet
│   ├── api                  WalletController
│   │   └── dto              ChargeWalletRequest, WalletResponse
│   ├── application          WalletService
│   │   └── balance          WalletBalanceUpdater, NaiveWalletBalanceUpdater,
│   │                        AtomicDecrementWalletBalanceUpdater, GuardedDecrementWalletBalanceUpdater,
│   │                        OptimisticLockWalletBalanceUpdater, PessimisticLockWalletBalanceUpdater
│   ├── domain               Wallet, VersionedWallet, WalletLedger, LedgerType, Amounts
│   │   └── exception        WalletNotFoundException, InsufficientBalanceException, BalanceOverflowException
│   └── infrastructure       WalletRepository, VersionedWalletRepository, WalletLedgerRepository
├── merchant
│   ├── api
│   │   └── auth             MerchantAuthInterceptor, MerchantContext, UnauthorizedMerchantException
│   ├── domain               Merchant
│   └── infrastructure       MerchantRepository
├── settlement
│   ├── domain               Settlement
│   └── infrastructure       SettlementRepository
├── common
│   ├── exception            DomainException
│   └── web
│       └── error            ErrorResponse
└── bootstrap
    ├── config               ConcurrencyStrategyConfig, WebConfig
    ├── seed                 SeedRunner
    └── web                  ApiExceptionHandler
```

클래스가 없는 계층은 폴더를 만들지 않는다. 클래스 이름은 하나도 바꾸지 않는다.

### 2.1 허용되는 의존 방향

```
payment ──> wallet ──> common
   │           │
   ├──> merchant.api.auth (MerchantContext, 컨트롤러에서만)
   └──> common
bootstrap ──> 모든 업무      (조립하는 쪽이므로 허용)
common   ──> 아무 업무도 모른다
wallet   ──> payment 를 import 하지 않는다  (지금 0건, 유지)
```

`WalletLedger` 와 `WalletBalanceUpdater.debit` 가 `paymentId` 를 받지만 `Long` 이다. 결제 타입을 import 하지 않는 한 유지된다.

### 2.2 테스트 배치 규칙

업무 하나만 검증하는 테스트는 main 과 같은 패키지로 옮긴다. 여러 업무를 함께 검증하는 테스트와 공통 도구는 루트 아래 **목적별** 패키지에 둔다.

| 현재 | 이동 후 | 이유 |
|---|---|---|
| `PaymentApplicationTests` | `<root>` | 그대로 |
| `api/PaymentFlowApiTest`, `api/FailureContractApiTest`, `api/ApiInputValidationTest` | `<root>/api` | HTTP 계약. 결제·지갑·인증을 함께 지난다 |
| PR ① 이 추가하는 `api/RoutingContractApiTest`, PR ③ 이 추가하는 `api/ErrorResponseContractTest` | `<root>/api` | 같은 이유 |
| `application/PaymentServiceTest` | `payment/application` | 결제 전용 |
| `application/RetryPolicyTest` | `payment/application/confirmation` | 재시도 데코레이터 전용, 컨테이너 없이 돈다 |
| `application/WalletServiceTest` | `wallet/application` | 지갑 전용 |
| `application/AmountBoundaryTest` | `wallet/domain` | `Amounts` 오버플로 경계. 결제 서비스도 쓰지만 검증 대상은 지갑 잔액 산술이다 |
| `application/DuplicateConfirmReproductionTest`, `DuplicateConfirmRegressionTest`, `WalletLostUpdateReproductionTest`, `WalletDecrementComparisonTest`, `WalletLockRegressionTest`, `OptimisticLockConcurrencyTest`, `LockContentionObservationTest` | `<root>/concurrency` | S1·S2 재현·비교·회귀·관측. 승인 구현과 지갑 전략을 조합해 쓴다 |
| `schema/*` | `<root>/schema` | 그대로 |
| `support/*` | `<root>/support` | 그대로. `ConcurrencyGateConfig` 는 결제·지갑 리포지터리 import 만 새 경로로 바꾼다 |

## 3. PR ① — 기준선 테스트

**목적**: 현재의 오류 판정 순서·상태 코드·`Allow` 를 테스트로 고정한다. PR ②·③ 은 이 테스트가 green 인 상태로 시작하고 끝나야 한다. 이 PR 은 main 코드를 건드리지 않는다.

**파일**: `src/test/java/<현재 루트>/api/RoutingContractApiTest.java`, `AbstractApiTest` 상속.

**기대값** — 아래 표는 현재 MVC 설정과 엔드포인트 매핑에서 MockMvc 로 실측한 값이다. **애플리케이션 전체 규칙이 아니라 이번 변경의 회귀 검증 기준**이다. 예를 들어 나중에 매핑에 `consumes` 를 붙이면 415 는 핸들러 매핑 단계로 올라가 인증보다 먼저 판정된다.

| # | 요청 | `X-API-Key` | 기대 상태 | 단언할 것 |
|---|---|---|---|---|
| 1 | `GET /v1/payments/confirm` | 없음 | 401 | `code == UNAUTHORIZED` |
| 2 | `GET /v1/payments/confirm` | 유효 | 404 | `code == NOT_FOUND`, message 에 `paymentKey=confirm` — `@GetMapping("/{paymentKey}")` 에 매칭된다 |
| 3 | `PUT /v1/payments/confirm` | 없음 | 405 | `Allow` 를 **집합**으로 `{POST, GET}` 과 비교 |
| 4 | `PUT /v1/payments/confirm` | 유효 | 405 | 동일 |
| 5 | `GET /v1/nope` | 없음 | 401 | `code == UNAUTHORIZED` |
| 6 | `GET /v1/nope` | 유효 | 404 | 상태만 |
| 7 | `GET /nope` | 없음 | 404 | 상태만 |
| 8 | `POST /v1/payments`, `Content-Type: text/plain` | 없음 | 401 | `code == UNAUTHORIZED` |
| 9 | `POST /v1/payments`, `Content-Type: text/plain` | 유효 | 415 | 상태만 |
| 10 | `POST /v1/wallets/1` | 유효 | 405 | `Allow == {GET}` |

읽는 법:

- **405 는 인증보다 먼저** 판정된다. 핸들러 매핑 단계에서 나므로 인터셉터에 닿지 않는다.
- **`/v1/**` 아래의 404·415 는 인증 뒤**다. 정적 리소스 핸들러 매핑에도 인터셉터가 적용되므로 없는 경로도 키가 없으면 401 이다.
- `Allow` 의 `POST, GET` 은 요청 경로에 매칭되는 **서로 다른 두 패턴**(`POST /v1/payments/confirm`, `GET /v1/payments/{paymentKey}`)의 합이다. 문자열 순서는 보존 대상이 아니므로 집합으로 비교한다.
- 6·7·9·10 의 본문은 지금 비어 있다. **빈 본문을 단언하지 않는다.** PR ③ 이 채울 부분이다.

**커밋**: `test: 오류 판정 순서와 Allow 를 기준선으로 고정` 1개.

## 4. 응답 규약 (PR ③ 이 구현하는 목표 상태)

### 4.1 성공

현재 형식 그대로. DTO 를 직접 반환하고 필드명·HTTP 상태를 바꾸지 않는다.

```json
{ "paymentKey": "...", "status": "DONE", "amount": 10000 }
```

### 4.2 실패

본문은 항상 `{"code","message"}` 이고 `Content-Type: application/json` 이다.

| 원인 | 상태 | `code` | `message` |
|---|---|---|---|
| 기존 업무 예외 | 기존 그대로 | 기존 그대로 | 기존 그대로 (예외 메시지) |
| 인증 실패 | 401 | `UNAUTHORIZED` | 기존 그대로 |
| Bean Validation, 본문 파싱, 경로 변수 타입 불일치 | 400 | `INVALID_REQUEST` | 기존 그대로 |
| 부모 핸들러가 400 으로 판정한 그 밖의 것 (파라미터 누락 등) | 400 | `INVALID_REQUEST` | `요청 값이 올바르지 않습니다.` |
| 리소스 없음 (`NoResourceFoundException`, `NoHandlerFoundException`) | 404 | `NOT_FOUND` | `요청한 리소스를 찾을 수 없습니다.` |
| 메서드 불일치 | 405 | `METHOD_NOT_ALLOWED` | `지원하지 않는 HTTP 메서드입니다.` + `Allow` 헤더 보존 |
| Accept 불일치 (정상 응답을 만들 수 없음) | 406 | `NOT_ACCEPTABLE` | `응답할 수 있는 형식이 없습니다.` |
| 미디어 타입 불일치 | 415 | `UNSUPPORTED_MEDIA_TYPE` | `지원하지 않는 Content-Type 입니다.` + 부모가 넣는 헤더 보존 |
| 부모 핸들러가 다루는 그 밖의 상태 | 부모가 준 상태 | `HttpStatus` 이름 (`SERVICE_UNAVAILABLE` 등) | 상태별 고정 문구 하나. 새로 정하지 말고 `HttpStatus.getReasonPhrase()` 를 쓴다 |
| 핸들러에 없는 예외 | 500 | `INTERNAL_SERVER_ERROR` | `서버 내부 오류가 발생했습니다.` |

- 도메인 예외에 `HttpStatus` 를 넣지 않는다. HTTP 변환은 `bootstrap.web.ApiExceptionHandler` 만 한다.
- 500 의 실제 예외와 스택 트레이스는 서버 로그에 ERROR 로 남긴다. 클라이언트에는 고정 문구만 간다.
- 범위는 DispatcherServlet 이 처리하는 요청이다. 필터 단계의 오류, 연결 단절, 응답 전송 실패까지 JSON 을 보장하지 않는다.
- 추적 ID, 상세 검증 오류 목록, 기술 예외별 개별 코드는 넣지 않는다.

### 4.3 `Accept` 가 JSON 을 제외할 때 — 현재 결함과 목표

**현재 (실측, 내장 Tomcat + `TestRestTemplate`, `Accept: text/plain`)**

| 요청 | MockMvc | 실제 서버 |
|---|---|---|
| 없는 결제 조회, 유효한 키 | 예외가 DispatcherServlet 밖으로 탈출 | **500**, 본문 없음 |
| 키 없음 | 예외 탈출 | **500**, 본문 없음 |
| `GET /v1/wallets/abc` (타입 불일치) | — | 400, 본문 없음 |
| `GET /v1/nope`, 유효한 키 | — | 404, 본문 없음 |
| 정상 지갑 조회 | 406 | 406, 본문 없음 |

원인: `@ExceptionHandler` 가 만든 `ResponseEntity<ErrorResponse>` 를 JSON 으로 쓸 수 없어 핸들러 안에서 `HttpMediaTypeNotAcceptableException` 이 나고, 그 핸들러의 결과가 버려진다. Spring 이 아는 예외(타입 불일치 등)는 기본 resolver 가 받아 상태가 살지만, 업무·인증 예외는 아무도 받지 않아 500 이 된다. **404 가 500 으로 바뀌므로 4xx/5xx 구분이 `Accept` 헤더 하나로 무너진다.**

**목표**: 오류 응답은 `Accept` 와 무관하게 위 표의 상태와 JSON 본문을 유지한다. 정상 응답은 협상을 유지하므로 정상 조회에 `text/plain` 을 보내면 여전히 406 이고, 그 406 의 설명이 JSON 으로 나간다.

**방법**: 오류 `ResponseEntity` 에 `Content-Type: application/json` 을 **미리 지정**한다. Spring 은 응답에 구체적인 Content-Type 이 설정돼 있으면 `Accept` 협상을 건너뛰고 그 타입으로 쓴다 (`AbstractMessageConverterMethodProcessor.writeWithMessageConverters`).

## 5. PR ② — 구조 이동

**목적**: 2절의 구조로 옮긴다. 업무 동작을 바꾸지 않는다. package 선언과 import 만 바뀌므로 "순수 rename" 은 아니지만 유사도 기반 rename 감지는 동작한다. 리뷰는 `git diff -M --stat` 으로 전부 rename 인지 먼저 보고, `git diff -M` 으로 package/import 줄 외의 변경이 없는지 본다.

### 5.1 루트 패키지 (확정: `com.sunm2n.pay`)

> **정정 (PR ①)**: #21 에서 `com.sunm2n.pay` 로 확정했다. 아래 후보 표는 검토 기록으로 남기며, "확정할 때까지 시작하지 않는다" 는 조건은 충족됐다.

지금 그대로 가면 `com.sunm2n.payment.payment.domain.Payment` 처럼 말이 더듬어진다. 모든 파일이 이동하는 지금이 루트를 바꿀 최저 비용 시점이다.

| 후보 | 비고 |
|---|---|
| `com.sunm2n.pay` | 맥락 없이 읽어도 결제를 연상시킨다 |
| `com.sunm2n.pg` | 프로젝트가 PG(결제대행) 입장을 모델링한다. 결제 맥락 밖에서는 PostgreSQL 로 읽힐 수 있다 |
| `com.sunm2n.payment` (유지) | 더듬음을 감수한다 |

**저장소 소유자가 확정할 때까지 PR ② 를 시작하지 않는다.** 루트를 바꾸는 경우:

- `PaymentApplication` 을 새 루트로 옮긴다. `@SpringBootApplication` 스캔 범위가 새 루트가 된다
- 테스트도 전부 새 루트 아래로 옮긴다. `@SpringBootTest` 는 테스트 패키지에서 위로 올라가며 `@SpringBootConfiguration` 을 찾는다
- 패키지 문자열 참조는 자바 밖에 `build.gradle` 의 `group = 'com.sunm2n'` 하나뿐이며 **바꾸지 않는다**. yml 로깅 레벨에는 패키지 참조가 없다 (확인 완료)
- 저장소 이름, Gradle 프로젝트 이름, `PaymentApplication` 클래스 이름, `spring.application.name` 은 바꾸지 않는다

### 5.2 이동 방법

- 이동은 `git mv` 로 한다. 다만 Git 은 rename 을 기록하지 않고 **내용 유사도로 판정**하므로, `git mv` 여부가 rename 감지를 결정하지는 않는다. 감지와 `git log --follow` 를 지키는 것은 이동 커밋에서 package/import 외의 내용을 바꾸지 않는 것이다
- 각 파일의 `package` 선언과 모든 import 를 새 경로로 바꾼다. Javadoc 의 `{@link ...}` 가 다른 패키지를 가리키게 되면 import 를 추가하거나 FQN 으로 쓴다. `spotlessApply` 가 import 순서를 정리한다
- main 에 package-private 클래스나 생성자가 없다 (확인 완료). 접근 범위 때문에 깨질 곳은 없다
- 빈 이름은 바뀌지 않는다. `ConcurrencyStrategyConfig` 의 `@Bean` 메서드 이름과 `@Qualifier` 문자열을 그대로 둔다
- `ConcurrencyGateConfig` 의 `instanceof PaymentRepository` 등은 import 만 바꾼다. 감싸는 메서드 이름 맵은 그대로다

### 5.3 커밋 (커밋마다 컴파일·`spotlessCheck`·전체 테스트 green)

1. `refactor` — main 과 test **전체**의 루트 접두사를 `com.sunm2n.payment` → `com.sunm2n.pay` 로 함께 바꾼다. 하위 패키지 구조는 아직 그대로
   - **정정 (PR ①)**: `PaymentApplication` 과 테스트만 먼저 옮기면 기존 루트에 남은 서비스·리포지터리·엔티티가 `@SpringBootApplication` 의 기본 스캔(컴포넌트·JPA 엔티티·리포지터리) 밖으로 빠져 이 커밋이 green 일 수 없다
2. `refactor` — `common` (`DomainException`, `ErrorResponse`) 과 `bootstrap` (`ConcurrencyStrategyConfig`, `WebConfig`, `SeedRunner`, `ApiExceptionHandler`)
3. `refactor` — `merchant` 와 `settlement`
4. `refactor` — `wallet` (`Amounts` 포함)
5. `refactor` — `payment`
6. `refactor` — 테스트를 2.2 표대로 재배치
7. `docs` — `docs/STRUCTURE.md` 신설 (5.4), `README.md` 에 링크, `SCENARIO.md` 85행 아래에 한 줄 주석

### 5.4 `docs/STRUCTURE.md` 에 적을 것

- 2절의 트리와 배치 규칙, 2.1 의 의존 방향, 2.2 의 테스트 배치 규칙
- 남겨 둔 의존성: 결제의 지갑 리포지터리 직접 접근과 그 이유(S3 에서 환불 서비스 추출)
- Phase 2 에 대한 문장: "이번 변경은 단일 모듈 안에서 업무별 응집도를 높이는 패키지 재배치다. Phase 2 의 모듈 경계는 기존 계층별 분리안을 출발점으로 해당 단계에서 검토한다"
- 과거 문서(`docs/plan/PHASE0.md` 4.3, `docs/phase1/*`)는 당시 구조를 기록한 것이므로 **고치지 않는다**는 안내

## 6. PR ③ — 오류 응답

**목적**: 4절의 규약을 구현하고 계약 테스트로 고정한다. 업무 예외의 코드·상태·메시지·판정 순서는 바꾸지 않는다. 서비스 예외와 롤백에는 손대지 않는다 (advice 는 트랜잭션이 이미 끝난 뒤에 돈다).

### 6.1 `ApiExceptionHandler` 구현 지침

**상속**: `ResponseEntityExceptionHandler` 를 상속한다. 404·405·415 를 포함해 Spring MVC 예외 전부를 부모가 상태·헤더와 함께 판정한다.

**override 대상은 둘**: `handleMethodArgumentNotValid` 와 `handleHttpMessageNotReadable`. 지금의 `@ExceptionHandler(MethodArgumentNotValidException)` / `(HttpMessageNotReadableException)` 를 그대로 두면 부모와 같은 타입을 두 번 매핑해 **기동 시 ambiguous 오류**가 난다. 기존 메시지 조립(첫 필드 오류 `field: message`, 없으면 전역 오류, 없으면 고정 문구)을 override 안으로 옮기고, 만든 `ErrorResponse` 를 `body` 로 넘겨 `handleExceptionInternal(ex, body, headers, status, request)` 로 반환한다.

**타입 불일치 핸들러는 그대로 둔다.** 매핑과 메시지는 그대로이고, 반환 방식만 아래 단일 출구 규칙을 따르게 바꾼다. 지금은 `ResponseEntity.badRequest().body(...)` 로 직접 만들고 있어 Content-Type 고정을 지나지 않는다 (4.3 실측에서 본문이 비는 경로).

**단일 출구 규칙**: 이 클래스 안에서 `ResponseEntity` 를 직접 만드는 곳은 `createResponseEntity` 훅 **하나뿐**이다. 부모의 핸들러들이 하는 방식 그대로, 우리가 쓰는 모든 `@ExceptionHandler` 도 `handleExceptionInternal(...)` 을 거쳐 반환한다. 그러면

- committed 응답 검사 등 부모의 앞 처리를 업무 예외 핸들러까지 공짜로 받는다
- Content-Type 고정(4.3)이 한곳에서 모든 경로에 적용된다
- 리뷰는 `grep -n "ResponseEntity\." ApiExceptionHandler.java` 로 확인한다. 훅 밖에 있으면 안 된다

**`createResponseEntity` 훅** (부모의 `handleExceptionInternal` 이 committed 검사와 `ProblemDetail` 본문 채우기를 마친 뒤 호출한다):

```java
@Override
protected ResponseEntity<Object> createResponseEntity(
    Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
  Object errorBody = body instanceof ErrorResponse ? body : toErrorResponse(statusCode);  // 4.2 표
  HttpHeaders merged = new HttpHeaders();
  merged.putAll(headers);                       // Allow 등 부모가 준 헤더 보존
  merged.setContentType(MediaType.APPLICATION_JSON);  // Accept 협상 우회 (4.3)
  return new ResponseEntity<>(errorBody, merged, statusCode);
}
```

여기서 `ErrorResponse` 는 우리 DTO(`common.web.error`)다. 부모 클래스가 쓰는 `org.springframework.web.ErrorResponse` 인터페이스와 **이름이 같다.** 같은 파일에서 둘을 함께 import 하지 않는다. 부모 것이 필요하면 FQN 으로 쓴다.

**500 catch-all**: `@ExceptionHandler(Exception.class)`. `log.error("처리되지 않은 예외", ex)` 로 원인과 스택을 남기고, `ErrorResponse("INTERNAL_SERVER_ERROR", "서버 내부 오류가 발생했습니다.")` 를 `handleExceptionInternal` 로 반환한다. 부모의 특정 예외 핸들러들보다 덜 구체적이므로 우선순위 문제는 없다. advice 가 하나뿐이므로 `@Order` 도 필요 없다.

**바꾸지 않는 것**: 업무 예외별 `@ExceptionHandler` 의 목록·상태·코드. "공통 부모 하나로 뭉뚱그리지 않고 구체 예외마다 명시한다" 는 기존 설계 결정을 유지한다.

### 6.2 계약 테스트

**`api/ErrorResponseContractTest`** (`AbstractApiTest`, 실제 MVC 구성): 아래 6개 부류 각각을 기본 `Accept` 와 `Accept: text/plain` 두 가지로 보낸다. 출구가 하나로 묶였는지를 이 12개 조합이 증명한다.

| 부류 | 트리거 | 기대 |
|---|---|---|
| 업무 예외 | 없는 결제 조회, 유효한 키 | 404, `NOT_FOUND`, JSON |
| 인증 | 키 없음 | 401, `UNAUTHORIZED`, JSON |
| 타입 불일치 | `GET /v1/wallets/abc`, 유효한 키 | 400, `INVALID_REQUEST`, 기존 메시지, JSON |
| 입력 검증 | `amount: 0`, 유효한 키 | 400, `INVALID_REQUEST`, 기존 메시지, JSON |
| 본문 파싱 | 깨진 JSON, 유효한 키 | 400, `INVALID_REQUEST`, 기존 메시지, JSON |
| MVC 오류 | 3절의 6·7·9·10 | 404/415/405, 4.2 의 코드, JSON, `Allow` 집합 보존 |

주의: `/v1/**` 의 404·415 는 **유효한 키를 보내야** 한다. 키 없이 보내면 401 만 본다 (3절).

**`ErrorResponseContractTest` 의 500 케이스**: `@SpringBootTest` 컨텍스트에 예외를 던지는 컨트롤러를 얹으면 컨텍스트 캐시가 갈린다. 대신 `MockMvcBuilders.standaloneSetup(new ThrowingController()).setControllerAdvice(new ApiExceptionHandler())` 로 컨텍스트 없이 조립해 500, `INTERNAL_SERVER_ERROR`, 고정 문구, 내부 예외 메시지 비노출을 확인한다. 이 방식은 advice 의 등록 여부와 인터셉터를 검증하지 않으므로 위 통합 테스트가 그 몫을 맡는다.

**실제 서버 확인 1건**: 구현 후 `@SpringBootTest(webEnvironment = RANDOM_PORT)` + `TestRestTemplate` 로 "없는 결제 조회, 유효한 키, `Accept: text/plain`" 이 **404 + JSON** 인지 한 번 확인한다. 4.3 의 500 은 이 경로에서 실측된 것이므로 같은 경로로 닫는다. 컨텍스트가 하나 더 뜨므로 영구 테스트로 둘지는 실행 시간을 보고 정한다. 영구로 두지 않으면 PR 설명에 결과를 남긴다.

**기존 테스트**: `FailureContractApiTest`, `ApiInputValidationTest`, PR ① 의 `RoutingContractApiTest` 는 수정 없이 green 이어야 한다. 기존 단언을 약화하는 수정은 금지한다.

### 6.3 커밋

1. `feat` — `ResponseEntityExceptionHandler` 상속 + override 2개 + 단일 출구 규칙 + `createResponseEntity` 훅으로 4.2 의 본문과 Content-Type 고정. MVC 오류에 본문이 생기고 `Accept` 결함이 사라진다
   - **정정 (PR ①)**: 상속만 하는 커밋은 "응답 변화 없음" 이 아니다. 부모의 `handleExceptionInternal` 은 본문이 없고 예외가 `org.springframework.web.ErrorResponse` 이면 `ProblemDetail` 을 본문으로 채우므로, 지금 빈 본문인 404·405·415 에 `application/problem+json` 본문이 생긴다. 기존 테스트는 이 본문을 단언하지 않으므로 green 이 응답 보존을 증명하지 않는다. 그래서 상속과 최종 본문 변환을 한 커밋으로 묶는다
2. `feat` — 500 catch-all 과 로그
3. `test` — `ErrorResponseContractTest` (통합 + standalone 500)
4. `docs` — `docs/API_CONTRACT.md` 신설: 4.1·4.2·4.3 의 목표 상태, 3절의 판정 순서 실측표(적용 조건 명시), 변경 전 기록(4.3 현재 표), 범위 밖

## 7. 범위 밖 (예정된 단계에서 한다)

| 하지 않는 것 | 단계 |
|---|---|
| 환불 서비스 추출, 취소 경로 락 | S3 |
| 멱등키·멱등 응답 | S5 |
| 외부 호출의 트랜잭션 분리 | S8 |
| 기술 예외(`OptimisticLockingFailureException` 등)의 개별 오류 코드 | 본선 전략이 아니므로 지금은 500 |
| 멀티모듈, 포트·어댑터 | Phase 2 |
| 업무별 advice 분리, 추적 ID, 검증 오류 목록, RFC 7807 `ProblemDetail` | 미정 |

## 8. 검증

```bash
./gradlew spotlessCheck test
```

- PR ①: 기준선 테스트 10건 green. 기존 99건 그대로 (**정정 (PR ①)**: 실제 기존 테스트는 96건이다. main 의 `@Test` 96개, 기준선 추가 후 106건)
- PR ②: 커밋마다 위 명령 green. `git diff -M --stat main...HEAD` 에서 main 코드의 변경이 전부 rename 이어야 한다 (docs 제외)
- PR ③: 기존 테스트 수정 없이 green + `ErrorResponseContractTest` green. `DuplicateConfirmReproductionTest` 와 `WalletLostUpdateReproductionTest` 가 **여전히 결함을 재현**하는지 실행 로그로 확인한다. 회귀 테스트 `DuplicateConfirmRegressionTest`, `WalletLockRegressionTest` 통과

각 PR 설명에는 이 문서의 절 번호를 가리키고, 실측과 다른 결과가 나오면 **문서를 고치지 말고 PR 설명에 차이를 적는다.** 문서 수정은 소유자가 한다.
