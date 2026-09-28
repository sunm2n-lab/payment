# 패키지 구조

> 상위 문서: [SCENARIO.md](SCENARIO.md) · 작업 지시서: [plan/RESTRUCTURE.md](plan/RESTRUCTURE.md) · 이슈: #21

루트 패키지는 `com.sunm2n.pay` 다. 업무(payment / wallet / merchant / settlement)로 먼저 나누고, 각 업무 안에서 api / application / domain / infrastructure 로 나눈다.

이번 변경은 단일 모듈 안에서 업무별 응집도를 높이는 패키지 재배치다. Phase 2 의 모듈 경계는 기존 계층별 분리안을 출발점으로 해당 단계에서 검토한다.

## 1. 트리

```
com.sunm2n.pay
├── PaymentApplication
├── payment
│   ├── api                  PaymentController
│   │   └── dto              CreatePaymentRequest, ConfirmPaymentRequest, CancelPaymentRequest, PaymentResponse
│   ├── application          PaymentService, PaymentSupport,
│   │   │                    IdempotentCancelCoordinator, CancelFingerprint, PaymentSnapshot, CancelOutcome
│   │   ├── confirmation     PaymentConfirmer, NaivePaymentConfirmer, CasPaymentConfirmer,
│   │   │                    RetryingPaymentConfirmer, RetryMetrics
│   │   └── cancellation     PaymentCanceller, NaivePaymentCanceller, PessimisticLockPaymentCanceller,
│   │                        CheckThenInsertIdempotentCanceller, LockingLookupIdempotentCanceller
│   ├── domain               Payment, PaymentCancel, PaymentMethod, PaymentStatus
│   │   └── exception        PaymentNotFoundException, PaymentMismatchException,
│   │                        InvalidPaymentStatusException, CancelAmountExceededException
│   └── infrastructure       PaymentRepository, PaymentCancelRepository, PaymentOwnership
│       └── card             CardApprovalClient, CardApproval, FakeCardApprovalClient
├── wallet
│   ├── api                  WalletController
│   │   └── dto              ChargeWalletRequest, WalletResponse
│   ├── application          WalletService
│   │   └── balance          WalletBalanceUpdater, NaiveWalletBalanceUpdater,
│   │                        AtomicDecrementWalletBalanceUpdater, GuardedDecrementWalletBalanceUpdater,
│   │                        OptimisticLockWalletBalanceUpdater, PessimisticLockWalletBalanceUpdater,
│   │                        LedgerFirstWalletBalanceUpdater
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
├── idempotency              IdempotencyKeyStore, JdbcIdempotencyKeyStore, IdempotencyRecord, IdempotencyStatus,
│                            IdempotencyKeys, RequestHash, IdempotencyKeyClaimedException,
│                            IdempotencyKeyReusedException, IdempotencyKeyInUseException,
│                            InvalidIdempotencyKeyException
├── common
│   ├── exception            DomainException
│   ├── persistence          MySqlLockErrors
│   └── web
│       └── error            ErrorResponse
└── bootstrap
    ├── config               ConcurrencyStrategyConfig, WebConfig
    ├── seed                 SeedRunner
    └── web                  ApiExceptionHandler
```

## 2. 배치 규칙

- **업무 하나에 속하는 코드는 그 업무 아래에 둔다.** 새 전략 구현은 해당 업무의 `application` 하위 폴더(`confirmation`, `cancellation`, `balance`)에 둔다
- **`common` 은 업무를 모르는 코드만 둔다.** 업무 예외를 던지거나 업무 타입을 아는 코드는 들어오지 못한다. 그래서 지갑 예외를 던지는 `Amounts` 는 `wallet.domain` 에 있다
- **`bootstrap` 은 조립하는 코드다.** 여러 업무를 엮는 빈 조합(`ConcurrencyStrategyConfig`), MVC 배선(`WebConfig`), 시드, 모든 업무의 예외를 HTTP 로 바꾸는 `ApiExceptionHandler` 가 여기 있다
- 클래스가 없는 계층은 폴더를 만들지 않는다
- **`idempotency` 는 최상위 패키지다 (S5).** 충전이 `operation=CHARGE` 로 재사용할 예정인데 `wallet` 은 `payment` 를 import 하지 못하므로 `payment` 아래에 둘 수 없다. 가맹점 범위와 멱등 예외를 아는 코드라 `common` 에도 넣지 않는다. 요청 fingerprint 의 **구성**(어떤 필드를 어떤 순서로)은 업무 쪽(`CancelFingerprint`)이 갖고, `idempotency` 에는 문자열 해시(`RequestHash`)만 둔다
- 빈 이름은 클래스 단순 이름과 `@Bean` 메서드 이름에서 오므로 패키지를 옮겨도 바뀌지 않는다. `@Qualifier` 문자열은 패키지와 무관하다

## 3. 의존 방향

```
payment ──> wallet ──> common
   │           │
   ├──> merchant.api.auth (MerchantContext, 컨트롤러에서만)
   ├──> idempotency ──> common
   └──> common
bootstrap   ──> 모든 업무      (조립하는 쪽이므로 허용)
common      ──> 아무 업무도 모른다
wallet      ──> payment 를 import 하지 않는다
idempotency ──> 결제·지갑 타입을 모른다 (common 만)
```

- **결제는 지갑의 application 계층만 쓴다** (`WalletService`, `WalletBalanceUpdater`). 지갑 잔액을 바꾸는 창구는 `WalletBalanceUpdater` 하나(차감·충전·환불)이고, 결제 생성의 지갑 id 조회는 `WalletService.findIdByMemberId` 다 (S3)
- `WalletLedger` 와 `WalletBalanceUpdater.debit` / `refund` 가 `paymentId` 를 받지만 `Long` 이다. 결제 타입을 import 하지 않는 한 방향이 유지된다
- 업무 코드의 Javadoc 이 반대 방향 클래스(예: `ConcurrencyStrategyConfig`, `PaymentConfirmer`)를 가리킬 때는 **import 대신 FQN** 으로 적는다. 링크 하나 때문에 import 가 생기면 아래 확인이 거짓 양성을 낸다

확인:

```bash
B=src/main/java/com/sunm2n/pay
grep -rn 'import com.sunm2n.pay.payment' $B/wallet                                    # 0건
grep -rnE 'import com.sunm2n.pay.wallet.(infrastructure|domain)' $B/payment            # 0건 (S3)
grep -rnE 'import com.sunm2n.pay.(payment|wallet|merchant|settlement|bootstrap)' $B/common  # 0건
grep -rn 'import com.sunm2n.pay.bootstrap' $B/payment $B/wallet $B/merchant $B/settlement $B/idempotency   # 0건
grep -rnE 'import com.sunm2n.pay.(payment|wallet|merchant|settlement)' $B/idempotency     # 0건 (S5)
grep -rn 'import com.sunm2n.pay.idempotency' $B/common                                    # 0건 (S5)
```

## 4. 남겨 둔 의존성

없음. 재배치 때 남겨 둔 "결제가 지갑 리포지터리에 직접 접근한다"(`PaymentSupport.loadWallet`, `PaymentService.cancel`·`create`)는 S3 에서 걷어냈다. 환불은 `WalletBalanceUpdater.refund` 로, 결제 생성의 지갑 조회는 `WalletService.findIdByMemberId` 로 옮겼다 (`docs/plan/S3.md` 2절).

## 5. 테스트 배치

업무 하나만 검증하는 테스트는 main 과 같은 패키지에 둔다. 여러 업무를 함께 검증하는 테스트와 공통 도구는 루트 아래 **목적별** 패키지에 둔다.

| 패키지 | 테스트 | 이유 |
|---|---|---|
| 루트 | `PaymentApplicationTests` | 컨텍스트 기동 |
| `api` | `PaymentFlowApiTest`, `FailureContractApiTest`, `ApiInputValidationTest`, `RoutingContractApiTest`, `IdempotencyKeyHeaderServerTest` | HTTP 계약. 결제·지갑·인증을 함께 지난다. 멱등키 헤더의 컨테이너 전달은 내장 Tomcat 에서 본다 (S5) |
| `payment.application` | `PaymentServiceTest`, `CancelFingerprintTest` | 결제 전용 |
| `idempotency` | `JdbcIdempotencyKeyStoreTest`, `IdempotencyKeysTest` | 키 저장소의 트랜잭션 계약·선점 판정, 헤더 값 규칙 (S5) |
| `payment.application.confirmation` | `RetryPolicyTest` | 재시도 데코레이터 전용, 컨테이너 없이 돈다 |
| `wallet.application` | `WalletServiceTest` | 지갑 전용 |
| `wallet.domain` | `AmountBoundaryTest` | `Amounts` 오버플로 경계. 결제 서비스도 쓰지만 검증 대상은 지갑 잔액 산술이다 |
| `concurrency` | `DuplicateConfirmReproductionTest`, `DuplicateConfirmRegressionTest`, `WalletLostUpdateReproductionTest`, `WalletDecrementComparisonTest`, `WalletLockRegressionTest`, `OptimisticLockConcurrencyTest`, `LockContentionObservationTest`, `OverRefundReproductionTest`, `CancelLockRegressionTest`, `FkPromotionDeadlockReproductionTest`, `FkDeadlockRegressionTest`, `LockWaitTimeoutObservationTest`, `CancelResendReproductionTest`, `CheckThenInsertReproductionTest`, `GapLockDeadlockReproductionTest`, `IdempotentCancelRegressionTest`, `IdempotentCancelConcurrencyTest`, `UniqueKeyWaiterDeadlockObservationTest` | S1~S5 재현·비교·회귀·관측. 승인·취소 구현과 지갑 전략을 조합해 쓴다 |
| `schema` | `IsolationLevelTest`, `SchemaConstraintTest`, `LatestSchemaContextTest`, `V2SchemaContextTest`, `V4SchemaContextTest` | 스키마 전제. 최신·V2·V4 스키마를 각각 지킨다 |
| `support` | 베이스 클래스, 게이트, 러너, 시드, 불변식, `LockWaitProbe` | 공통 도구 |

### 5.1 스키마 버전별 베이스 (S4, S5)

과거 실패 재현은 그 실험이 전제한 스키마 버전의 DB 에서 돈다 (SCENARIO 94행). 컨테이너는 하나(`MySqlTestContainer`)를 공유하고, 버전마다 데이터베이스를 따로 둔다. 과거 베이스는 `PastSchemaDatabase.register(registry, database, flywayTarget)` 한 줄로 등록한다 — 버전을 공유 static 상태로 두지 않아 컨텍스트 캐시 키가 버전별로 갈린다.

| 베이스 | DB | 쓰는 테스트 |
|---|---|---|
| `AbstractIntegrationTest` | 컨테이너 기본 DB, 전체 마이그레이션 | 본선 회귀·계약·서비스 테스트, S4 재현 |
| `AbstractV2SchemaTest` | `payment_v2`, Flyway `target=2` | S1·S2·S2-a·S3 재현·비교 (`DuplicateConfirmReproductionTest`, `WalletLostUpdateReproductionTest`, `WalletDecrementComparisonTest`, `OptimisticLockConcurrencyTest`, `LockContentionObservationTest`, `OverRefundReproductionTest`), `V2SchemaContextTest` |
| `AbstractV4SchemaTest` | `payment_v4`, Flyway `target=4` | S5 1·2차 재현 (`CheckThenInsertReproductionTest`, `GapLockDeadlockReproductionTest`), `V4SchemaContextTest`. 멱등키 검색 인덱스가 비유일이다. S7 의 RC 재실행도 여기서 |

모든 베이스가 `AbstractDatabaseTest` 의 정리 규약을 쓴다. Spring 컨텍스트는 베이스마다 따로 뜬다. **새 테스트는 "어느 스키마를 전제로 쓴 실험인가" 로 베이스를 고른다** — 결과가 우연히 같아도 과거 스키마 전제 실험은 과거 베이스를 쓴다 (`docs/plan/S4.md` 3.1).

## 6. 과거 문서

`docs/plan/PHASE0.md` 4.3, `docs/plan/S1.md`, `docs/plan/S2.md`, `docs/phase1/S1.md`, `docs/phase1/S2.md` 에 나오는 패키지·경로(`com.sunm2n.payment.application...` 등)는 **당시 구조를 기록한 것이므로 고치지 않는다.** 현재 위치는 이 문서를 본다.
