# Phase 0 실습 기록

> 상위 문서: [SCENARIO.md](../SCENARIO.md) · 계획: [plan/PHASE0.md](../plan/PHASE0.md)

SCENARIO 7절이 요구하는 기록의 Phase 0 판이다. **이 단계의 목표는 좋은 코드가 아니다.** 상태 전이 보호·잔액 동시성 제어·요청 멱등성을 의도적으로 넣지 않고, "정상 케이스에서는 동작하는" naive 구현을 만든다. S1~S10 이 무너뜨릴 대상이 바로 이 코드다.

## 1. 사용한 버전

| 항목 | 버전 | 확인 방법 |
|---|---|---|
| MySQL 서버 | 8.0.44 | `docker run --rm mysql:8.0.44 mysqld --version` |
| Java | 17 (toolchain), 로컬 실행 17.0.20.1 | `java -version` |
| Spring Boot | 3.5.16 | `build.gradle` |
| Hibernate ORM | 6.6.53.Final | `./gradlew dependencies --configuration runtimeClasspath` |
| Flyway | 11.7.2 (`flyway-core` + `flyway-mysql`) | 〃 |
| mysql-connector-j | 9.7.0 | 〃 |
| Testcontainers | 1.21.4 | `--configuration testRuntimeClasspath` |
| Gradle | 8.14.3 (wrapper) | `gradle-wrapper.properties` |

Flyway 10 부터 DB별 모듈이 분리돼 MySQL 을 쓰려면 `flyway-mysql` 이 필요하다. 세 버전 모두 Spring Boot BOM 이 관리하므로 `build.gradle` 에 버전을 적지 않았다.

## 2. 스키마 버전과 시드 방식

### V1 (`db/migration/V1__init.sql`)

SCENARIO 1절의 엔티티 6개 — `merchant`, `payment`, `payment_cancel`, `wallet`, `wallet_ledger`, `settlement`.

인덱스는 **각 테이블 PK + unique 3개** 뿐이다.

- `payment(payment_key)` — `uk_payment_payment_key`
- `wallet(member_id)` — `uk_wallet_member_id`
- `merchant(api_key)` — `uk_merchant_api_key`

금액 컬럼은 전부 **signed `BIGINT`**(원 단위 정수)다. 상태·타입은 `VARCHAR` + `@Enumerated(STRING)` 이다. 단 `settlement.status` 는 값 집합을 S10 에서 정하므로 enum 으로 굳히지 않고 `String` 으로 매핑했다.

Hibernate 자동 DDL 은 쓰지 않는다 (`spring.jpa.hibernate.ddl-auto: validate`). 엔티티와 V1 의 불일치는 컨텍스트 기동 시점에 잡힌다.

### 시드 (`db/seed/seed_local.sql`)

**Flyway 이력에 넣지 않는다.** location 을 분리해도 `flyway_schema_history` 는 하나이므로 `V900__seed_local.sql` 같은 방식은 이력에 기록된다. 로컬 DB(named volume)에 V900 까지 적용된 뒤 S1 에서 V2 를 추가하면, 기본 `outOfOrder=false` 에서 V2 는 최고 적용 버전보다 낮아 적용되지 않고 기본 `validateOnMigrate` 가 "resolved migration not applied" 로 기동을 막는다. `R__`(repeatable)도 `target` 과 무관하게 항상 적용되므로, 시드가 나중 버전의 컬럼을 건드리는 순간 target 을 낮춘 재현 컨테이너에서 깨진다.

- **내용**: 가맹점 2건(`mk_test_merchant_1`, `mk_test_merchant_2`) + 지갑 5건(`member_id` 1~5)
- **지갑은 잔액 0, 원장 없음**으로 심는다. "잔액 == 원장 합계" 불변식이 시드 데이터에서도 유지되게 하기 위함이다. 초기 잔액은 charge API 로 넣고 CHARGE 원장을 남긴다
- **두 번째 가맹점**은 "다른 가맹점의 결제 → 404" 테스트 전용이며 k6 는 첫 번째만 쓴다
- **실행 경로는 하나**다. `local`/`load` 프로파일에서만 등록되는 `SeedRunner`(`ApplicationRunner`)와 테스트 정리 훅이 같은 파일을 `ScriptUtils` 로 실행한다
- **멱등 구문**: V1 의 unique 에 기대는 `INSERT ... ON DUPLICATE KEY UPDATE id = id`. `INSERT IGNORE` 는 중복 키 외의 오류(데이터 절단 등)까지 경고로 삼키므로 쓰지 않는다

### 프로파일

| 프로파일 | 시드 러너 | SQL 로깅 | 용도 |
|---|---|---|---|
| (없음) | 미등록 | ON | 인자 없는 기동. 시드가 없다 |
| `local` | 실행 | ON | 수동 스모크 |
| `load` | 실행 | **OFF** | k6 부하. 100 VU 에서 Hibernate SQL DEBUG 는 처리량을 눈에 띄게 깎는다 |
| 테스트 | 미등록 | ON | 정리 훅이 같은 SQL 을 심는다. `@ServiceConnection` 이 datasource 를 덮어쓴다 |

## 3. 의도적으로 뺀 제약과 장치

| 뺀 것 | 이유 | 대상 시나리오 |
|---|---|---|
| `payment` 상태 전이 원자성 보호 (조건부 UPDATE / `FOR UPDATE`) | READY 검사와 DONE 갱신이 원자적이지 않아야 중복 승인이 재현된다 | **S1** |
| 지갑·결제에 대한 비관적/낙관적 락 | read-modify-write 의 Lost Update 가 관찰 대상 | **S2, S3** |
| **모든** FK | 원장 INSERT 의 FK 검사 락이 S1/S2 의 차감 유실 관찰에 섞이는 것을 막는다. S4 에서는 `wallet_ledger.wallet_id → wallet.id` **하나만** 추가하고 나머지 FK 는 그 단계에서도 추가하지 않는다 | **S4** |
| `Idempotency-Key` 와 `idempotency_key` 테이블 | 요청 단위 식별자 부재로 재전송 중복이 재현된다 | **S5** |
| `payment(merchant_id)`, `(order_id)`, `(merchant_id, order_id)` 인덱스 | 인덱스 없는 locking read 의 잠금 범위가 관찰 대상 | **S6** |
| 격리 수준 변경 (REPEATABLE READ 로 시작) | RC 전환 전후 비교의 기준선 | **S7** |
| 트랜잭션 경계 분리, `IN_PROGRESS` 커밋 | 승인 전체가 단일 트랜잭션이어야 외부 호출 중 커넥션·락 보유 문제가 드러난다. 단 Phase 0 의 일반 SELECT 는 consistent non-locking read 라 락을 잡지 않으므로, 이 시점에 카드사 호출이 붙들고 있는 것은 **커넥션뿐**이다. payment 행 락까지 함께 보유하는 것은 S1 의 조건부 UPDATE 가 들어간 누적 구현부터다 | **S8** |
| WireMock, `UNKNOWN` 상태, 대사 스케줄러 | 외부 타임아웃 시 상태 불확정 문제 | **S9** |
| `settlement (merchant_id, settlement_date)` unique, `payment.settled` | 정산 중복 반영 문제 | **S10** |
| `wallet.balance` / `payment.balance_amount` 의 UNSIGNED·`CHECK (>= 0)` | S2 비교 실험 1 의 잔액 **-2,000** 이 관찰 대상이다. DB 가 막으면 그 시점에 SQL 에러가 나서 실험이 사라진다 (MySQL 8.0.16+ 는 CHECK 를 실제로 강제한다). 음수는 `Invariants` 가 검출한다 | **S2** |

`settlement` 는 SCENARIO 1절대로 V1 에 만들지만 Phase 0 에서 쓰는 코드는 없다. `status` 의 값 집합도 S10 에서 정하므로 아직 enum 으로 굳히지 않았다.

`PaymentStatus.IN_PROGRESS` 는 enum 에 두되 Phase 0 의 상태 전이는 `READY → DONE` 직행이다. S1 에서 처음 쓴다.

## 4. 확인한 세션 격리 수준

```
SELECT @@transaction_isolation  ->  REPEATABLE-READ
```

`IsolationLevelTest` 가 테스트로 고정한다. S7 에서 READ COMMITTED 로 내릴 때 이 값이 기준선이 된다.

## 5. API 와 에러 응답 규약

| 메서드 | 경로 |
|---|---|
| POST | `/v1/payments` |
| POST | `/v1/payments/confirm` |
| POST | `/v1/payments/{paymentKey}/cancel` |
| GET | `/v1/payments/{paymentKey}` |
| POST | `/v1/wallets/{memberId}/charge` |
| GET | `/v1/wallets/{memberId}` |

가맹점 식별은 `X-API-Key` 헤더 + `MerchantAuthInterceptor` → 요청 스코프 `MerchantContext` 다. 인터셉터가 던진 예외도 `DispatcherServlet` 의 `HandlerExceptionResolver` 를 타므로 `ApiExceptionHandler` 가 401 로 매핑한다 (수동 스모크로 확인).

**서비스 계층은 `MerchantContext` 를 직접 읽지 않는다.** 컨트롤러가 꺼내 `merchantId` 를 파라미터로 넘긴다. application 계층을 웹 관심사에서 분리해 Phase 2 의 헥사고널 리팩터링에 대비한 것이다.

### 4xx 매핑

`@RestControllerAdvice` 로 도메인 예외를 4xx 에 매핑하는 것은 선택이 아니라 필수다. 이후 모든 시나리오의 완료 기준이 "예상한 실패 응답과 예상 밖 오류를 구분했는지"(SCENARIO 114행), 즉 **4xx(예상된 실패) / 5xx(예상 밖)** 구분에 의존한다. 그래서 공통 부모 하나로 뭉뚱그리지 않고 구체 예외마다 명시했다. **핸들러에 없는 예외는 5xx 로 나간다 — 그것이 "예상 밖"의 정의다.**

| 상태 | `code` | 원인 |
|---|---|---|
| 401 | `UNAUTHORIZED` | `X-API-Key` 누락, 등록되지 않은 키 |
| 400 | `INVALID_REQUEST` | 금액 0·음수, MONEY 인데 `memberId` 누락, 해석 불가한 본문 |
| 404 | `NOT_FOUND` | 없는 `paymentKey`/지갑, **그리고 다른 가맹점의 결제** |
| 409 | `INSUFFICIENT_BALANCE` | 지갑 잔액 부족 |
| 409 | `INVALID_PAYMENT_STATUS` | READY 아닌 결제의 승인, DONE/PARTIAL_CANCELED 아닌 결제의 취소 |
| 409 | `PAYMENT_MISMATCH` | 승인 요청의 `orderId`/`amount` 불일치 |
| 409 | `CANCEL_AMOUNT_EXCEEDED` | 취소 금액이 잔여 금액 초과 |

다른 가맹점의 결제는 조회·승인·취소 모두 404 다. 결제의 존재를 노출하지 않기 위해서이며, `payment_key` 로 찾은 뒤 메모리에서 가맹점을 비교하므로 S6 의 `merchant_id` 인덱스 부재 실험에는 영향이 없다.

승인 응답은 `status`(DONE)를 포함한다. k6 가 승인 결과를 응답만으로 검증한다.

### 수동 스모크 결과 (`local` 프로파일)

`docker compose up -d` + `bootRun --args='--spring.profiles.active=local'` 로 확인했다.

- CARD: 생성(READY) → 승인(DONE, `cardApprovalNo` 발급) → 부분취소 3,000(PARTIAL_CANCELED, 잔여 7,000) → 조회
- MONEY: 충전 30,000 → 승인 10,000(잔액 20,000) → 부분취소 4,000(잔액 24,000)
- 401 / 400 / 404 / 409 전부 의도한 코드로 응답
- 로컬 DB 직접 확인: 잔액 == 원장 합계, `balance_amount == amount - 취소 합계`, FK 0건

## 6. 테스트 규약

- 컨테이너는 **JVM 당 하나**를 모든 테스트 클래스가 공유한다. `@Testcontainers`/`@Container` 를 붙이면 Jupiter 확장이 클래스 단위로 start/stop 하므로, 첫 클래스가 끝나면 컨테이너가 멈추고 캐시된 컨텍스트의 DataSource 가 죽은 포트를 가리킨다. 그래서 base 클래스의 **static 초기화 블록에서 `start()` 를 직접 호출**하고 종료는 Ryuk 에 맡긴다
- `@ServiceConnection` 을 추상 상위 클래스의 static 필드에 붙이는 방식이 Spring Boot 3.5.16 에서 정상 동작하는 것을 확인했다 (`@DynamicPropertySource` 폴백 불필요)
- DB 는 테스트 간에 오염되므로 격리는 **매 테스트 전 TRUNCATE + 시드 재삽입 + Fake 카운터 리셋**이 담당한다. `@Transactional` 롤백은 쓰지 않는다 — 동시성 테스트가 작업 스레드별 독립 트랜잭션을 써야 하기 때문이다 (SCENARIO 96행)
- 공유 DB 를 TRUNCATE 로 정리하므로 JUnit 병렬 실행을 켜지 않고 Gradle `maxParallelForks` 도 1 로 명시했다

## 7. 완료 기준 진행 상황

- [x] FK **0건** — `SchemaConstraintTest.noForeignKeys`
- [x] `payment` 에 `merchant_id`/`order_id` 인덱스 없음 — `SchemaConstraintTest.paymentHasNoSearchIndexes`
- [x] 세션 격리 수준 `REPEATABLE-READ` — `IsolationLevelTest`
- [x] `Invariants` 3개 헬퍼 + 시드 상태 검증 — `SeedFixtureTest`
- [x] naive 서비스와 v1 API — 서비스 계층 테스트 26개 + `local` 프로파일 수동 스모크
- [ ] 정상 흐름 통합 테스트 (생성 → 승인 → 부분취소 → 조회, CARD/MONEY)
- [ ] 실패 규약 테스트 (401/404/400/409 + 거절 후 부작용 없음)
- [ ] k6 100 VU 1분, `http_req_failed` 0 / `checks` 100%
