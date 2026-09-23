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

## 2. 배치 규칙

- **업무 하나에 속하는 코드는 그 업무 아래에 둔다.** 새 전략 구현은 해당 업무의 `application` 하위 폴더(`confirmation`, `balance`)에 둔다
- **`common` 은 업무를 모르는 코드만 둔다.** 업무 예외를 던지거나 업무 타입을 아는 코드는 들어오지 못한다. 그래서 지갑 예외를 던지는 `Amounts` 는 `wallet.domain` 에 있다
- **`bootstrap` 은 조립하는 코드다.** 여러 업무를 엮는 빈 조합(`ConcurrencyStrategyConfig`), MVC 배선(`WebConfig`), 시드, 모든 업무의 예외를 HTTP 로 바꾸는 `ApiExceptionHandler` 가 여기 있다
- 클래스가 없는 계층은 폴더를 만들지 않는다
- 빈 이름은 클래스 단순 이름과 `@Bean` 메서드 이름에서 오므로 패키지를 옮겨도 바뀌지 않는다. `@Qualifier` 문자열은 패키지와 무관하다

## 3. 의존 방향

```
payment ──> wallet ──> common
   │           │
   ├──> merchant.api.auth (MerchantContext, 컨트롤러에서만)
   └──> common
bootstrap ──> 모든 업무      (조립하는 쪽이므로 허용)
common   ──> 아무 업무도 모른다
wallet   ──> payment 를 import 하지 않는다
```

- `WalletLedger` 와 `WalletBalanceUpdater.debit` 가 `paymentId` 를 받지만 `Long` 이다. 결제 타입을 import 하지 않는 한 방향이 유지된다
- 업무 코드의 Javadoc 이 반대 방향 클래스(예: `ConcurrencyStrategyConfig`, `PaymentConfirmer`)를 가리킬 때는 **import 대신 FQN** 으로 적는다. 링크 하나 때문에 import 가 생기면 아래 확인이 거짓 양성을 낸다

확인:

```bash
B=src/main/java/com/sunm2n/pay
grep -rn 'import com.sunm2n.pay.payment' $B/wallet                                    # 0건
grep -rnE 'import com.sunm2n.pay.(payment|wallet|merchant|settlement|bootstrap)' $B/common  # 0건
grep -rn 'import com.sunm2n.pay.bootstrap' $B/payment $B/wallet $B/merchant $B/settlement   # 0건
```

## 4. 남겨 둔 의존성

- **결제가 지갑 리포지터리에 직접 접근한다** (`PaymentSupport`, `PaymentService.cancel`). 결제 승인·취소가 지갑을 바꾸는 경로가 지갑 서비스를 거치지 않는다. 환불 서비스 추출은 취소 경로의 락을 다루는 S3 에서 한다. 이번 재배치는 동작을 바꾸지 않는 것이 조건이라 남겨 둔다

## 5. 테스트 배치

업무 하나만 검증하는 테스트는 main 과 같은 패키지에 둔다. 여러 업무를 함께 검증하는 테스트와 공통 도구는 루트 아래 **목적별** 패키지에 둔다.

| 패키지 | 테스트 | 이유 |
|---|---|---|
| 루트 | `PaymentApplicationTests` | 컨텍스트 기동 |
| `api` | `PaymentFlowApiTest`, `FailureContractApiTest`, `ApiInputValidationTest`, `RoutingContractApiTest` | HTTP 계약. 결제·지갑·인증을 함께 지난다 |
| `payment.application` | `PaymentServiceTest` | 결제 전용 |
| `payment.application.confirmation` | `RetryPolicyTest` | 재시도 데코레이터 전용, 컨테이너 없이 돈다 |
| `wallet.application` | `WalletServiceTest` | 지갑 전용 |
| `wallet.domain` | `AmountBoundaryTest` | `Amounts` 오버플로 경계. 결제 서비스도 쓰지만 검증 대상은 지갑 잔액 산술이다 |
| `concurrency` | `DuplicateConfirmReproductionTest`, `DuplicateConfirmRegressionTest`, `WalletLostUpdateReproductionTest`, `WalletDecrementComparisonTest`, `WalletLockRegressionTest`, `OptimisticLockConcurrencyTest`, `LockContentionObservationTest` | S1·S2 재현·비교·회귀·관측. 승인 구현과 지갑 전략을 조합해 쓴다 |
| `schema` | `IsolationLevelTest`, `SchemaConstraintTest` | 스키마 전제 |
| `support` | 베이스 클래스, 게이트, 러너, 시드, 불변식 | 공통 도구 |

## 6. 과거 문서

`docs/plan/PHASE0.md` 4.3, `docs/plan/S1.md`, `docs/plan/S2.md`, `docs/phase1/*` 에 나오는 패키지·경로(`com.sunm2n.payment.application...` 등)는 **당시 구조를 기록한 것이므로 고치지 않는다.** 현재 위치는 이 문서를 본다.
