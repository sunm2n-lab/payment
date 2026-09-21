# Phase 0 — 기반 구축 계획

> 상위 문서: [SCENARIO.md](../SCENARIO.md)

## 1. 배경

`docs/SCENARIO.md`는 "처음부터 잘 만들지 않는다. 문제를 만들고, 원인을 찾고, 고친다"는 방식으로 PG(토스페이먼츠) 입장의 결제 모듈을 학습하는 계획서다. Phase 1(S1~S11)은 중복 승인, 차감 유실, FK 락 승격 데드락 같은 **실제 결함을 재현하고 고치는 실습**이고, 그러려면 결함을 품은 코드가 먼저 있어야 한다.

현재 저장소는 Spring Boot 3.5.16 / Java 17 스켈레톤이 전부다 (`PaymentApplication.java`, `spring.application.name`과 `server.port`만 있는 `application.yml`, `contextLoads` 테스트). docker-compose도, Flyway도, 엔티티도, JPA 의존성도 없다. Phase 1을 시작할 수 없는 상태이므로 이 문서는 SCENARIO 2절 "Phase 0 : 기반 구축"을 구현하는 계획을 정한다.

**이 단계의 목표는 좋은 코드가 아니다.** 상태 전이 보호·잔액 동시성 제어·요청 멱등성을 의도적으로 넣지 않고, "정상 케이스에서는 동작하는" naive 구현을 만든다. S1~S10이 무너뜨릴 대상이 바로 이 코드다.

**완료 기준** (SCENARIO 90행): 정상 케이스 통합 테스트 통과 + 서로 다른 CARD 결제를 생성·승인하는 k6 100 VU 1분 테스트에서 실패 0 (4xx·5xx·네트워크 오류·타임아웃 전부 포함, 4.8).

## 2. 확정된 설계 결정

| 항목 | 결정 | 이유 |
|---|---|---|
| 가맹점 식별 | `X-API-Key` 헤더 + 인터셉터 → 요청 스코프 `MerchantContext` | PG 입장 전제에 맞고, S5의 `(merchant_id, operation, idempotency_key)` unique와 S6의 `merchant_id` 검색이 자연스럽게 이어진다 |
| 실패 버전 보존 | naive/improved 구현을 **코드에 공존**시키고 테스트가 빈을 골라 쓴다 | 과거 실습 기록을 언제든 재실행할 수 있다. 컨테이너는 JVM당 하나를 static으로 공유하므로(4.7) 테스트 격리는 컨테이너가 아니라 **테스트 간 정리 규약**이 담당한다. 단 **Phase 0에서는 naive 하나뿐** — 인터페이스 추출은 두 번째 구현이 생기는 S1에서 한다 |
| 과거 스키마 복원 | Flyway `target` 프로퍼티로 특정 버전까지만 마이그레이션 | 재현 테스트가 "S4 이전 = FK 없음" 같은 조건을 코드로 고정할 수 있다. 한 Spring 컨텍스트에 V1까지만 올린 DB와 최신 DB를 같이 둘 수 없으므로, **과거 스키마 재현 테스트는 최신 스키마 컨테이너를 공유하지 않고 자기 컨텍스트와 컨테이너를 따로 띄운다** (SCENARIO 94행). Phase 0에서는 쓰지 않고 S1에서 전용 컨테이너와 함께 처음 쓴다 |

로컬 환경 확인: Java 17.0.16, Docker 29.3.1, Docker Compose v5.1.1, k6 설치됨. `mysql:8.0.44` 태그 존재 확인 완료.

## 3. 범위

SCENARIO 2절의 8개 항목. **S1 이후는 이 계획에 포함하지 않는다.**

의도적으로 **만들지 않는** 것 (각각 나중 시나리오의 실습 대상):

| 넣지 않는 것 | 대상 시나리오 |
|---|---|
| `payment` 상태 전이 원자성 보호 | S1 |
| 지갑·결제에 대한 비관적/낙관적 락 | S2, S3 |
| `wallet_ledger.wallet_id → wallet.id` FK | S4 |
| `Idempotency-Key`와 `idempotency_key` 테이블 | S5 |
| `payment(merchant_id)`, `(merchant_id, order_id)` 인덱스 | S6 |
| 격리 수준 변경 (REPEATABLE READ로 시작) | S7 |
| 트랜잭션 경계 분리, `IN_PROGRESS` 커밋 | S8 |
| WireMock, `UNKNOWN` 상태, 대사 스케줄러 | S9 |
| `settlement (merchant_id, settlement_date)` unique, `payment.settled` | S10 |

## 4. 구현 계획

### 4.1 빌드·인프라

**`build.gradle`** — 의존성 추가:

```
implementation 'org.springframework.boot:spring-boot-starter-data-jpa'
implementation 'org.springframework.boot:spring-boot-starter-validation'
implementation 'org.springframework.boot:spring-boot-starter-actuator'   // S8의 Hikari 메트릭 대비
implementation 'org.flywaydb:flyway-core'
implementation 'org.flywaydb:flyway-mysql'                               // Flyway 10+ 는 MySQL 모듈 분리 필요
runtimeOnly   'com.mysql:mysql-connector-j'

testImplementation 'org.springframework.boot:spring-boot-testcontainers'
testImplementation 'org.testcontainers:mysql'
testImplementation 'org.testcontainers:junit-jupiter'
```

**`docker-compose.yml`** (신규) — `mysql:8.0.44` (패치 버전 고정):

- named volume으로 데이터 영속화
- `./docker/mysql/conf.d:/etc/mysql/conf.d` 마운트 — **S7/S11에서 쓸 자리를 지금 확보**한다. `docker/mysql/conf.d/my.cnf`는 charset 정도만 두고 격리 수준은 건드리지 않는다
- 포트 3306, DB명 `payment`

**`src/main/resources/application.yml`**:

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/payment   # 로컬 docker-compose. 테스트는 @ServiceConnection이 덮어쓴다
    username: payment
    password: payment
    hikari:
      maximum-pool-size: 10          # S8 실험의 기준값. 명시 필수
      connection-timeout: 30000      # 획득 타임아웃. 명시 필수
  jpa:
    open-in-view: false              # SCENARIO 102행 — Phase 0부터 고정
    hibernate.ddl-auto: validate     # Hibernate 자동 DDL 미사용 (SCENARIO 81행)
  flyway:
    enabled: true                    # 시드는 Flyway 이력 밖에서 실행한다 (4.2)

management:
  endpoints.web.exposure.include: health,metrics   # S8의 Hikari 메트릭 관측. 한 줄이라 지금 넣는다

logging:
  level:
    org.hibernate.SQL: DEBUG                 # 코드 순서가 아닌 실제 SQL 순서로 분석한다 (SCENARIO 98행)
    org.hibernate.orm.jdbc.bind: TRACE       # 바인딩 파라미터
```

프로파일은 세 가지로 나눈다.

| 프로파일 | 시드 러너 | SQL 로깅 | 용도 |
|---|---|---|---|
| `local` | 실행 | ON | `bootRun --args='--spring.profiles.active=local'` 수동 스모크. 인자 없는 기동(default 프로파일)에는 시드가 없다 |
| `load` | 실행 | **OFF** | k6 부하 실행. 100 VU에서 Hibernate SQL DEBUG는 처리량을 눈에 띄게 깎으므로 반드시 끈다 |
| 테스트 | 없음 (4.7의 정리 훅이 같은 SQL을 심는다) | ON | `@ServiceConnection`이 `datasource.url`을 덮어쓴다 |

외부 호출 타임아웃(SCENARIO 102행)은 Phase 0의 인프로세스 Fake에는 해당 사항이 없다. S8(지연 Fake)과 S9(WireMock)에서 connect/read timeout을 명시한다.

### 4.2 Flyway V1 — 스키마

`src/main/resources/db/migration/V1__init.sql`. SCENARIO 1절 엔티티 6개(`merchant`, `payment`, `payment_cancel`, `wallet`, `wallet_ledger`, `settlement`)를 그대로 만든다.

**인덱스는 PK와 아래 unique 3개만:**

- `payment(payment_key)` unique
- `wallet(member_id)` unique
- `merchant(api_key)` unique

**FK는 하나도 만들지 않는다.** (SCENARIO 83행 — 원장 INSERT의 FK 검사 락이 S1/S2의 차감 유실 관찰에 섞이는 것을 막는다)

**`payment`의 `merchant_id` / `order_id` 인덱스도 만들지 않는다.** (SCENARIO 84행 — S6의 인덱스 부재 실험이 사라지지 않게)

금액 컬럼은 **전부 signed `BIGINT`**(원 단위 정수). **UNSIGNED와 `CHECK (balance >= 0)` 류 제약은 금지한다.** `wallet_ledger.amount`가 부호 있는 값(CHARGE/REFUND 양수, PAY 음수)이기도 하지만, 더 중요한 이유는 S2 비교 실험 1의 잔액 -2,000(SCENARIO 139행)이 **관찰 대상**이라는 점이다. `wallet.balance`가 UNSIGNED이거나 CHECK가 걸려 있으면 그 시점에 SQL 에러가 나서 실험이 사라진다 (MySQL 8.0.16+는 CHECK를 실제로 강제한다). `payment.balance_amount`도 같은 이유로 signed. 음수는 DB가 막는 것이 아니라 4.7의 `Invariants`가 검출한다. 상태·타입은 `VARCHAR` + `@Enumerated(STRING)`.

**시드는 Flyway 이력에 넣지 않는다.** location을 분리해도 `flyway_schema_history`는 하나이므로, `V900__seed_local.sql`을 `spring.flyway.locations`에 추가하면 같은 이력에 기록된다. 로컬 DB(named volume)에 V900까지 적용된 뒤 S1에서 V2를 추가하면, 기본 `outOfOrder=false`에서 V2는 최고 적용 버전(900)보다 낮아 적용되지 않고 기본 `validateOnMigrate`가 "resolved migration not applied" 오류로 기동을 막는다. 시나리오마다 마이그레이션이 늘어나므로 매번 로컬 DB를 지워야 하는 방식은 쓰지 않는다. `R__`(repeatable)도 `target`과 무관하게 모든 versioned 다음에 항상 적용되므로, 시드가 나중 버전의 컬럼(예: S10의 `settled`)을 건드리는 순간 target을 낮춘 재현 컨테이너에서 깨진다. 따라서 versioned도 repeatable도 쓰지 않는다.

- **시드 내용**: 가맹점 2건(`api_key`, k6와 통합 테스트가 `X-API-Key`를 쓰려면 필요. 두 번째 가맹점은 4.7의 "다른 가맹점 결제 → 404" 테스트 전용이며 k6는 첫 번째만 쓴다) + 테스트용 지갑 N건. **지갑은 잔액 0, 원장 없음**으로 심어 "잔액 == 원장 합계" 불변식이 시드 데이터에서도 유지되게 한다. 초기 잔액은 charge API로 넣고 CHARGE 원장을 남긴다 (SCENARIO 42행). Phase 0에서 지갑은 시드로만 생기고, 지갑 생성 API는 SCENARIO API 표에 없으므로 추가하지 않는다
- **파일**: `src/main/resources/db/seed/seed_local.sql`. Flyway `locations`에 넣지 않으며 V1의 컬럼만 참조한다
- **실행 (로컬/k6)**: `local`/`load` 프로파일에서만 등록되는 `SeedRunner`(`ApplicationRunner`, `@Profile({"local","load"})`)가 실행한다. 러너는 컨텍스트 초기화가 끝난 뒤 돌므로 Flyway 완료 후라는 순서가 보장된다. 가맹점·지갑 생성은 `TransactionTemplate`으로 한 트랜잭션에 묶는다
- **멱등 규칙**: 기존 행은 유지하고 없는 행만 추가한다. **이미 존재하는 지갑의 잔액은 건드리지 않는다.** 재기동 때 잔액을 0으로 덮으면 기존 원장과 어긋나 첫 번째 불변식이 시드에서 깨진다. 구문은 V1의 unique(`merchant.api_key`, `wallet.member_id`)에 기대는 `INSERT ... ON DUPLICATE KEY UPDATE id = id`를 쓴다. `INSERT IGNORE`는 중복 키 외의 오류(데이터 절단 등)까지 경고로 바꿔 삼키므로 쓰지 않는다. unique 충돌 외의 SQL 오류는 숨기지 않고 기동을 실패시킨다
- **테스트**: Flyway는 Spring 컨텍스트당 한 번 실행되므로 테스트마다 TRUNCATE한 뒤 다시 들어올 방법이 없다. 4.7의 정리 훅이 같은 `seed_local.sql`을 `ScriptUtils`로 직접 실행한다. TRUNCATE 뒤라 항상 삽입 경로를 타며, 멱등 구문이 있어도 해가 없다. 러너와 훅이 같은 파일을 쓰므로 시드 경로는 하나다

`settlement`는 SCENARIO 1절대로 V1에 만들지만 Phase 0에서 쓰는 코드는 없다. 엔티티는 `ddl-auto: validate` 외에는 검증되지 않은 채 S10까지 남는다. 의도한 것이다.

### 4.3 레이어드 패키지 (SCENARIO 85행)

```
com.sunm2n.payment
├── api             PaymentController, WalletController, dto/, ApiExceptionHandler,
│                   MerchantAuthInterceptor, MerchantContext, WebConfig
├── application     PaymentService(create/confirm/cancel/get), WalletService(charge/get)
├── domain          Payment, PaymentCancel, Wallet, WalletLedger, Merchant, Settlement,
│                   PaymentStatus·PaymentMethod·LedgerType enum, 도메인 예외
└── infrastructure  *Repository (Spring Data JPA), CardApprovalClient, FakeCardApprovalClient,
                    SeedRunner (local/load 프로파일 한정, 4.2)
```

Phase 2에서 헥사고널로 리팩터링할 때 `domain`이 그대로 코어가 되도록 domain은 Spring/JPA 외 의존을 두지 않는다.

### 4.4 naive 서비스 — 결함을 품은 구현

**절대 규칙: "읽고 → 검사하고 → 계산한 값을 저장한다"** (SCENARIO 24행). 특히 잔액 차감은 반드시
`wallet.setBalance(wallet.getBalance() - amount)` — JPA 더티체킹으로 계산값이 저장돼야 S2의 Lost Update가 재현된다. `UPDATE wallet SET balance = balance - ?` 같은 원자적 감산을 여기서 쓰면 **S2 실습이 사라진다.**

SCENARIO 55~74행 의사코드를 그대로 옮긴다:

- **create** : `paymentKey` 발급(UUID), `status = READY`, `balance_amount = amount`. MONEY면 `memberId`로 지갑을 찾아 `wallet_id` 저장
- **confirm** (단일 `@Transactional`) : payment 조회 → **`MerchantContext`의 가맹점이 `merchant_id`와 다르면 404** (다른 가맹점 결제의 존재를 노출하지 않는다. cancel/get도 같은 규칙. `payment_key`로 찾은 뒤 메모리에서 비교하므로 S6의 인덱스 부재 실험에는 영향이 없다) → `status == READY` / orderId / amount 일치 검사 → CARD면 **트랜잭션 안에서** 카드사 승인 호출, MONEY면 지갑 조회 → `balance >= amount` 검사 → 계산값 저장 + PAY 원장(음수) → `status = DONE`, `approved_at` 기록
- **cancel** (단일 `@Transactional`) : payment 조회 → DONE/PARTIAL_CANCELED 와 `balance_amount >= cancelAmount` 검사 → `payment_cancel` insert → `balance_amount -= cancelAmount`, 0이면 CANCELED 아니면 PARTIAL_CANCELED → MONEY면 환불 + REFUND 원장, CARD면 카드사 취소 호출
- **charge** (단일 `@Transactional`) : 지갑 조회(없으면 404, 지갑은 4.2의 시드로만 생긴다) → 계산값 저장 + CHARGE 원장. **조회 → 잔액 변경 → 원장 INSERT는 반드시 한 서비스 트랜잭션 안이어야 한다.** `open-in-view: false`라 서비스에 트랜잭션이 없으면 repository 호출이 끝나는 순간 지갑 엔티티가 detached 되어 `setBalance`가 더티체킹되지 않고, 원장만 INSERT돼 정상 흐름에서 "잔액 == 원장 합계"가 깨진다. 트랜잭션으로 묶어도 REPEATABLE READ의 일반 SELECT는 잠금이 없으므로 S2의 Lost Update(충전 vs 결제 경쟁 포함, SCENARIO 135행)는 그대로 보존된다

Phase 0의 상태 전이는 `READY → DONE` 직행이다. `IN_PROGRESS`는 enum에는 두되 S1에서 처음 쓴다.

요청 금액 양수 검증은 Bean Validation(`@Positive`)으로 API 경계에서 한다 (SCENARIO 42행).

### 4.5 CardApprovalClient + Fake (SCENARIO 86행)

```java
public interface CardApprovalClient {
    CardApproval approve(String paymentKey, long amount);   // 승인번호 발급
    void cancel(String cardApprovalNo, long cancelAmount);
}
```

`FakeCardApprovalClient`는 항상 성공하고 **호출 횟수를 기록**한다. S1의 "Fake 승인 호출·승인번호가 여러 개" 관찰과 Phase 0 완료 기준의 "승인 횟수" 검증이 이 카운터를 쓴다. 싱글턴 빈이므로 카운터는 4.7의 정리 규약에서 테스트마다 리셋한다. 지연 주입과 타임아웃 명시는 S8, WireMock 교체는 S9이므로 지금 넣지 않는다.

### 4.6 API (SCENARIO 44~53행) + 에러 응답 규약

| 메서드 | 경로 |
|---|---|
| POST | `/v1/payments` |
| POST | `/v1/payments/confirm` |
| POST | `/v1/payments/{paymentKey}/cancel` |
| GET | `/v1/payments/{paymentKey}` |
| POST | `/v1/wallets/{memberId}/charge` |
| GET | `/v1/wallets/{memberId}` |

`MerchantAuthInterceptor`가 `X-API-Key`로 merchant를 조회해 `MerchantContext`에 담고, 없으면 401.

**`@RestControllerAdvice`로 도메인 예외를 4xx에 매핑하는 것은 선택이 아니라 필수다.** 이후 모든 시나리오의 완료 기준("예상한 실패 응답과 예상 밖 오류를 구분했는지", SCENARIO 114행)이 **4xx(예상된 실패) / 5xx(예상 밖)** 구분에 의존한다. 잔액 부족·상태 불일치·금액 불일치는 409, 미존재는 404, 검증 실패는 400. 이 매핑은 4.7의 실패 규약 테스트로 검증한다.

confirm 응답은 승인 후 `status`(DONE)를 포함한다. k6가 승인 결과를 응답만으로 검증한다(4.8).

### 4.7 테스트 (Testcontainers)

- **`AbstractIntegrationTest`** : `@SpringBootTest` + `@ServiceConnection` + static `MySQLContainer(mysql:8.0.44)`. 컨테이너는 JVM당 하나를 모든 테스트 클래스가 공유하고 Spring 컨텍스트도 캐시되므로 Flyway는 한 번만 실행되고 **DB는 테스트 간에 오염된다.** 격리는 아래 정리 규약이 담당한다
  - **수명 관리는 singleton 방식** : static 필드 선언만으로는 JVM 공유 수명이 보장되지 않는다. `@Testcontainers` + `@Container`를 붙이면 Jupiter 확장이 **클래스 단위**로 start/stop 하므로 첫 클래스가 끝나면 컨테이너가 멈추고, 다음 클래스는 캐시된 컨텍스트의 DataSource가 죽은 포트를 가리켜 실패한다. 따라서 두 애너테이션을 쓰지 않고 base 클래스의 **static 초기화 블록에서 `start()`를 직접 호출**한다. 종료는 코드에서 하지 않고 Testcontainers의 Ryuk이 JVM 종료 시 정리한다
  - **병렬 실행 금지** : 공유 DB를 TRUNCATE로 정리하므로 같은 DB를 쓰는 테스트가 동시에 돌면 즉시 깨진다. JUnit 병렬 실행(`junit.jupiter.execution.parallel.enabled`)은 켜지 않고, Gradle `maxParallelForks`는 기본값 1을 유지한다. (fork가 늘면 JVM마다 컨테이너가 따로 뜨므로 DB 공유는 없지만, 이 프로젝트에서는 명시적으로 1로 둔다)
- **테스트 간 정리 규약** (S1~S10이 계속 쓰는 자산) : 매 테스트 전에 base 클래스의 `@BeforeEach`(또는 JUnit 확장)가 ① `flyway_schema_history`를 제외한 전 테이블 TRUNCATE → ② `seed_local.sql` 실행(4.2와 같은 파일, `ScriptUtils`) → ③ `FakeCardApprovalClient` 카운터 리셋. `@Transactional` 롤백은 SCENARIO 96행 규약상 쓰지 않는다
- **제약 부재 검증 테스트** : FK가 0건인지, `payment`에 `merchant_id`/`order_id` 인덱스가 없는지를 `information_schema.table_constraints` / `information_schema.statistics`로 확인한다. 스키마명은 `constraint_schema = DATABASE()` / `table_schema = DATABASE()`로 잡는다 (Testcontainers 기본 DB명은 `test`, 로컬은 `payment`)
- **정상 흐름 통합 테스트** : 생성 → 승인 → 부분취소 → 조회를 CARD와 MONEY 각각. 승인 횟수, 잔액·원장 합계, 취소 합계·잔여 금액까지 검증 (SCENARIO 90행)
- **실패 규약 테스트 (HTTP 계층)** : 4.6의 4xx 매핑은 인터셉터와 `ApiExceptionHandler`에서 결정되므로 서비스 테스트로는 검증되지 않는다. `MockMvc`(또는 `TestRestTemplate`)로 실제 상태 코드를 확인한다
  - API 키 누락·존재하지 않는 키 → 401
  - 다른 가맹점(4.2 시드의 두 번째 가맹점)의 결제 조회·승인·취소 → 404
  - 금액 0·음수, MONEY 생성 시 `memberId` 누락 → 400
  - 잔액 부족·승인 값(orderId/amount) 불일치·READY 아닌 상태 승인·`balance_amount` 초과 취소 → 409
  - **거절 후 부작용 없음** : 거절 요청 **직전과 직후를 비교**해 잔액·원장·`payment_cancel`·결제 상태가 변하지 않고, `FakeCardApprovalClient`의 승인·취소 호출 횟수가 **증가하지 않았는지** 확인한다. "호출 횟수 0"으로 두지 않는 이유는 READY 아닌 상태 승인, 초과 취소 케이스가 준비 단계에서 CARD 승인을 한 번 실행하기 때문이다. `Invariants` 헬퍼도 함께 검증한다
- **`Invariants` 검증 헬퍼** (SCENARIO 103행의 공통 불변식) — **S1~S10이 계속 재사용할 자산이므로 처음부터 별도 클래스로 뽑는다** :
  - `wallet.balance == SUM(wallet_ledger.amount)` 이고 음수가 아님
  - `payment.balance_amount == amount - SUM(payment_cancel.cancel_amount)`
  - 취소 합계 ≤ 승인 금액
- **매핑 검증** : `ddl-auto: validate`가 통과하는지 (엔티티와 V1 스키마의 불일치를 즉시 잡는다)
- **세션 격리 수준 어서션** : `SELECT @@transaction_isolation`이 `REPEATABLE-READ`인지 확인 (SCENARIO 102행 "실제 세션의 격리 수준도 확인한다" — S7에서 RC로 내릴 때 이 테스트가 기준선이 된다)

동시성 테스트 규약(SCENARIO 96~98행)은 S1부터 적용되지만, **테스트 메서드에 `@Transactional`을 붙이지 않는 관행은 Phase 0부터 지킨다.**

TDD로 진행한다: 각 서비스는 통합 테스트를 먼저 쓰고 실패를 확인한 뒤 구현한다.

### 4.8 k6 뼈대 (`load/`)

`load/create-confirm.js` — 서로 다른 CARD 결제를 생성하고 승인하는 루프. VU마다 고유 `orderId`를 만들어 결제끼리 경쟁하지 않게 한다 (MONEY 공유 지갑 경쟁은 S2의 주제). `X-API-Key`는 환경변수로 주입. 서버는 SQL 로깅을 끈 `load` 프로파일로 띄운다(4.1). 100 VU / 1분.

**합격 기준: 실패율 0.** 이 부하는 정상 경로만 타므로 4xx가 나올 이유가 없다. 4xx도 결함(시드 키 오류, 검증 실패, 상태 불일치)이며 5xx·네트워크 오류·타임아웃과 함께 전부 실패로 센다. k6는 `check` 실패만으로는 실패 종료하지 않으므로 반드시 `thresholds`로 건다. 기본 `http_req_failed`는 4xx·5xx·status 0(네트워크 오류·타임아웃)을 모두 실패로 집계한다.

```js
export const options = {
  vus: 100, duration: '1m',
  thresholds: {
    http_req_failed: ['rate==0'],   // 4xx·5xx·네트워크 오류·타임아웃 전부
    checks: ['rate==1'],
  },
};
// check: 생성 응답 200 + paymentKey 존재, 승인 응답 200 + status == 'DONE'
```

`load/README.md`에 실행법을 적는다.

### 4.9 실습 기록 (`docs/phase0/README.md`)

SCENARIO 7절이 요구하는 기록의 Phase 0 판:

- 사용한 버전 — MySQL 8.0.44, Spring Boot 3.5.16, Hibernate/Flyway 실제 버전
- 스키마 버전(V1)과 시드 방식(Flyway 이력 밖 `seed_local.sql` + 프로파일 한정 러너), **의도적으로 뺀 제약 목록** + 각각이 어느 시나리오의 대상인지
- 확인한 세션 격리 수준
- k6 100 VU 1분 결과 (처리량, 에러율)

## 5. 작업 순서 (커밋 단위)

1. 빌드 의존성 + `docker-compose.yml` + `docker/mysql/conf.d/my.cnf` + `application.yml` + **`AbstractIntegrationTest`(컨테이너 베이스)**. JPA와 MySQL 드라이버를 추가하는 순간 컨텍스트 기동이 실제 DB 커넥션을 요구한다 (url이 없으면 드라이버 판별 실패, url이 있으면 Flyway와 EntityManagerFactory가 로컬 MySQL을 요구). 컨테이너 베이스가 없으면 이 커밋에서 기존 `contextLoads`가 깨지거나 외부 상태에 의존하게 된다
2. Flyway `V1__init.sql` + `db/seed/seed_local.sql` + `SeedRunner`(local/load) + 도메인 엔티티 + repository → `ddl-auto: validate` 통과 테스트 + 제약 부재 검증 테스트
3. `CardApprovalClient` + `FakeCardApprovalClient`
4. 테스트 간 정리 규약(TRUNCATE + 시드 + Fake 리셋) + `Invariants` 헬퍼 + 격리 수준 어서션
5. `WalletService`(charge/조회) — 테스트 먼저
6. `PaymentService`(create/confirm/cancel/조회) naive — 테스트 먼저
7. API 컨트롤러 + DTO + `MerchantAuthInterceptor` + `ApiExceptionHandler`
8. 정상 흐름 통합 테스트 (CARD/MONEY 각각, 생성→승인→부분취소→조회) + 실패 규약 테스트 (401/404/400/409, 거절 후 부작용 없음)
9. k6 스크립트 + 실행, 결과를 `docs/phase0/README.md`에 기록

## 6. 검증

```bash
# 1) 단위·통합 테스트 (Testcontainers가 MySQL 8.0.44 컨테이너를 띄운다)
./gradlew test

# 2) 로컬 기동 (local 프로파일: 시드 + SQL 로깅)
docker compose up -d
./gradlew bootRun --args='--spring.profiles.active=local'

# 3) 수동 스모크 — 생성 → 승인 → 부분취소 → 조회
curl -XPOST localhost:8080/v1/payments -H 'X-API-Key: <seed>' -H 'Content-Type: application/json' \
  -d '{"orderId":"o-1","amount":10000,"method":"CARD"}'
# ... confirm, cancel, get

# 4) 부하 테스트 (Phase 0 완료 기준) — SQL 로깅을 끈 load 프로파일로 다시 띄운다
./gradlew bootRun --args='--spring.profiles.active=load'
k6 run -e API_KEY=<seed> -e BASE_URL=http://localhost:8080 load/create-confirm.js

# 5) (선택) 로컬 DB에서 수동 확인. 테스트로 이미 검증되는 항목이다
mysql -e "SELECT COUNT(*) FROM information_schema.table_constraints WHERE constraint_schema = DATABASE() AND constraint_type = 'FOREIGN KEY'" payment
mysql -e "SHOW INDEX FROM payment" payment
```

**통과 조건**

- `./gradlew test` 전부 green. 아래 다섯 가지가 **테스트로** 포함된다
  - `Invariants` 3개 모두 유지
  - 세션 격리 수준이 `REPEATABLE-READ`
  - FK **0건** (FK가 실수로 생기지 않았는지 — S1/S2 실습의 전제)
  - `payment`에 `merchant_id`/`order_id` 인덱스 없음 (S6의 전제)
  - 4.7의 실패 규약 테스트(401/404/400/409 + 거절 후 부작용 없음)
- k6 100 VU 1분에서 `http_req_failed` 0, `checks` 100% (thresholds가 통과해 k6가 exit 0으로 끝난다). 4xx도 실패다 (4.8)

FK와 인덱스 부재 테스트는 "제대로 만들지 않았음"을 검증하는, 이 프로젝트 특유의 완료 기준이다. 수동 쿼리가 아니라 테스트로 두어야 이후 시나리오의 마이그레이션이 이 전제를 조용히 깨지 못한다.

## 7. 다음 단계에 남기는 것

Phase 0이 끝나면 **S1(중복 승인)** 으로 간다. 같은 `paymentKey`로 confirm을 N개 보내면 모두 READY를 읽고 승인에 성공하는 것을 재현하고, 조건부 UPDATE(`WHERE status='READY'`)로 고친다. 그때 `PaymentService`에서 confirm 구현을 인터페이스로 추출해 naive/CAS 두 구현을 공존시킨다.
