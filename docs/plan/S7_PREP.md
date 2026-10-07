# S7 착수 전 정비 — 작업 계획

> 상위 문서: [SCENARIO.md](../SCENARIO.md) · 선행 기록: [phase1/S6.md](../phase1/S6.md) · 구조: [STRUCTURE.md](../STRUCTURE.md) · 이슈: [#33](https://github.com/sunm2n-lab/payment/issues/33)
>
> 이 문서는 **작업 지시서**다. 기대값은 전부 현재 코드에서 **실측한 값**이다 (2026-09-29, 브랜치 `main`, 커밋 `4262276`). 코드 위치의 행 번호도 이 커밋 기준이다.
>
> SCENARIO 참조는 행 번호 없이 **절과 항목명**으로만 적는다 (9절).

## 0. 한눈에

| 항목 | 내용 |
|---|---|
| 목적 | S7 이 **격리 수준 하나만** 바꿔 비교할 수 있게, 그 전에 (1) 기존 실험의 격리 수준을 명시 고정하고 (2) 테스트가 실패할 때 원인이 하나로 남게 하고 (3) 기록의 오류와 검증하지 않은 단정을 바로잡는다 |
| 바꾸지 않는 것 | main 의 동작 전부 — 트랜잭션·락·재시도·빈 배선·스키마·API 계약·`application.yml`. 기존 테스트의 단언과 동기화 지점 |
| 바꾸는 것 | 테스트 지원 코드, 테스트의 실행·정리 경로, 문서, main 의 Javadoc 한 곳 |
| 완료 기준 | `./gradlew spotlessCheck test` green. **기존 329개가 단언 수정 없이 통과**하고 신규 테스트가 더해진다. 10절 |
| 다음 | 이 문서의 7절(예측)을 채워 커밋한 뒤 S7 이슈와 `docs/plan/S7.md` 를 만든다 |

## 1. 배경

S1~S6 은 모두 REPEATABLE READ 에서 돌았지만 그 사실은 **MySQL 서버 기본값에 기대고 있다.** 테스트 어디에도 격리 수준을 정하는 설정이 없고, 실제 세션 값을 확인하는 테스트는 최신 컨텍스트의 `IsolationLevelTest` 하나다. 테스트 베이스는 스키마 버전으로만 갈린다 (STRUCTURE.md 5.1).

S7 은 여기에 두 번째 축을 더한다. 본선을 READ COMMITTED 로 내리고, 과거 실험 일부를 RC 로 다시 돌려 RR 결과와 비교한다 (SCENARIO S7 "작업"·"관찰"). 지금 구조에서 그대로 시작하면 세 가지가 어긋난다.

- **SCENARIO 의 방법이 테스트에 닿지 않는다.** S7 "작업" 은 `my.cnf` 의 `transaction_isolation` 을 바꾸라고 하는데, `my.cnf` 는 `docker-compose.yml` 만 마운트한다. Testcontainers 의 컨테이너(`MySqlTestContainer`)는 설정 파일 없이 뜬다. `my.cnf` 를 바꿔도 테스트는 RR 로 남고 `IsolationLevelTest` 는 계속 green 이다
- **서버 전역으로 바꾸면 비교 기준이 사라진다.** 최신·`payment_v2`·`v4`·`v5`·`v6` 이 컨테이너 하나를 공유한다. 전역 값을 RC 로 바꾸면 RR 을 전제로 쓴 재현이 전부 함께 바뀐다
- **RC 에서는 기대가 실제로 바뀐다.** 대기하던 후속이 통과하거나 데드락이 사라질 수 있다. 관측이 실패했을 때 워커를 기다리지 않고 빠지는 경로가 남아 있으면, 실패 하나가 다음 테스트의 TRUNCATE 와 겹쳐 엉뚱한 테스트까지 깨진다 (4절)

기록 쪽에도 S7 이 그대로 물려받으면 안 되는 것이 있다. S1·S2·S3·S5 는 "RC 에서도 같다" 고 적었는데 RC 로 돌린 적이 없다. S7 이 확인할 **예측**이 이미 확인된 사실로 적혀 있다 (6절).

### 1.1 착수 전 확인 (실측)

| 확인 | 결과 |
|---|---|
| 전체 테스트 (`--rerun`) | **329개, 실패 0, 스킵 0.** 테스트 시간 합 70초, 벽시계 87~89초 |
| Spring 컨텍스트(Hikari 풀) | **7개** — 최신, 최신+MockMvc, 최신+RANDOM_PORT, V2, V4, V5, V6 |
| 테스트 컨테이너의 연결 수 | 전체 실행 중 `Max_used_connections` **72** / `max_connections` 151. 컨텍스트가 뜰 때마다 10씩 늘었다 (3 → 12 → 21 → 31 → 41 → 61 → 71). 측정용 연결 1개 포함 |
| 테스트 컨테이너의 전역 격리 수준 | `REPEATABLE-READ` (서버 기본값) |
| 변이 실험 — 본선의 `payment`·`wallet` 의 `findByIdForUpdate` 를 `findById` 로 바꾼 복사본 | 잠금 회귀 5개 클래스 16개 중 **10개 실패, 3회 모두 같다.** `IdempotentCancelConcurrencyTest` 5개와 `WalletLockRegressionTest` 회귀 3(같은 결제, S1 CAS 가 막는다)은 통과 |
| 1205 의 예외 타입 (테스트 출력) | JPA: `PessimisticLockingFailureException`, JdbcTemplate: `CannotAcquireLockException` |
| 라이브러리 | spring-jdbc 6.2.19, mysql-connector-j 9.7.0, hibernate-core 6.6.53.Final, HikariCP 6.3.3 |

**변이 실험의 해석.** 회귀 테스트는 잠금이 빠진 것을 잡아냈다. 그러나 잠금 조회가 빠지면 게이트 지점(`*_LOCK_ATTEMPT`)에 도달하지 않으므로, 잡아낸 근거는 게이트가 아니라 **워커가 우연히 겹친 것**이다. 실패 메시지도 불변식 위반과 데드락이지 "게이트에 모이지 않았다" 가 아니다. 4.4 가 이 근거를 타이밍에서 단언으로 옮긴다.

**연결 수의 해석.** 72 는 한계(151)에 가깝지 않다. HikariCP 는 `minimum-idle` 기본값이 `maximum-pool-size` 와 같아, 컨텍스트가 뜨는 즉시 10개를 연다. S7 이 RC 컨텍스트를 몇 개 더하면 그만큼 10씩 늘어난다. 3.4 는 그 대비다.

### 1.2 유지해야 할 학습 조건

- main 의 트랜잭션 경계, SQL 순서, 락 획득 순서(`payment → wallet`), 재시도 범위, 빈 이름·`@Primary`·`@Qualifier`
- 스키마와 마이그레이션. V1~V7 을 건드리지 않는다 (주석도 — checksum 이 바뀐다)
- `src/main/resources/application.yml`. 풀 크기 10 은 S8 의 기준값이고, 본선의 격리 수준을 어디에 둘지는 S7 의 결정이다
- 기존 테스트의 단언, 게이트 지점, 워커 수, 준비 데이터
- 실패 구현과 개선 구현의 공존

## 2. 확정된 결정

| 항목 | 결정 | 이유 |
|---|---|---|
| 격리 수준을 정하는 곳 | **Spring 컨텍스트별 프로퍼티** `spring.datasource.hikari.transaction-isolation` | 풀이 커넥션을 만들 때 세션 값으로 정한다. 세션 범위라 같은 컨테이너의 다른 컨텍스트로 새지 않는다 |
| 쓰지 않는 방법 | 컨테이너 기동 옵션, `SET GLOBAL`, SQL 로 보내는 `SET SESSION TRANSACTION ISOLATION LEVEL`, `@Transactional(isolation = ...)` | 앞의 둘은 모든 DB 가 함께 바뀐다. SQL 로 바꾼 세션 값은 HikariCP 가 반환 때 되돌리지 않는다 (`FOREIGN_KEY_CHECKS` 와 같은 함정, `DatabaseCleaner`). 애너테이션은 main 을 바꾸고, 기존 트랜잭션에 참여하면 무시된다 (SCENARIO 2절 "실험 실행 시 주의사항") |
| 이번에 고정하는 값 | 기존 컨텍스트 **전부 REPEATABLE READ** | 동작을 바꾸지 않고 전제만 명시한다. RC 컨텍스트는 S7 이 만든다 |
| RC 베이스를 만드는 방식 (S7 에 넘기는 규칙) | 기존 베이스를 상속해 값을 덮어쓰지 않고 **형제 베이스**로 만든다 | 상위·하위 클래스의 `@DynamicPropertySource` 가 같은 키를 등록할 때 어느 쪽이 이기는지 확인하지 않았다. 확인하지 않은 순서에 실험 전제를 걸지 않는다 |
| 풀 크기 | `maximum-pool-size` 10 유지, 테스트 컨텍스트만 `minimum-idle` **2** | `OrderLockMatrix` 11번이 선행 1 + 후속 7 = 워커 8개로 연결을 동시에 쓴다. 최대를 줄이면 실험이 커넥션 획득 대기로 바뀐다. 2 는 출발값이고 3.4 의 측정으로 확정한다 |
| 실패 경로의 원칙 | 게이트 해제 → **상한 안에 시작한 워커 전부의 종료 확인** → 그 뒤에 단언. 종료를 확인하지 못하면 runaway 를 기록하고 후속 DB 작업을 차단한다. 정리 중 예외는 최초 실패에 `addSuppressed` 로 붙인다 | 강제 종료를 보장할 수는 없다. `finally` 안의 단언이 실패하면 `try` 의 예외를 덮어써 원인이 사라진다 |
| DB 를 쓰는 스레드 | 전부 `ConcurrentRunner` 를 거친다. 테스트가 직접 `CompletableFuture.supplyAsync` 로 DB 작업을 돌리지 않는다 | 러너 밖의 스레드는 종료 확인도 runaway 검사도 받지 않는다 |
| 과거 계획 문서 | 고치지 않고 **안내를 단다** | S6 의 선례 (`6df5b59`). 계획은 당시의 기록이다 |
| RC 에 대한 서술 | 돌려 보지 않은 것은 **예측**으로 적는다 | 관측한 사실과 추론을 섞지 않는다 |

## 3. 작업 ① — 격리 수준을 컨텍스트별로 고정한다

### 3.1 장치

`support` 에 격리 수준 값을 하나로 모은다. Hikari 프로퍼티 값과 MySQL 세션 값의 표기가 달라 두 곳에 문자열로 흩어지면 어긋난다.

| 값 | Hikari 프로퍼티 | `@@transaction_isolation` |
|---|---|---|
| `REPEATABLE_READ` | `TRANSACTION_REPEATABLE_READ` | `REPEATABLE-READ` |
| `READ_COMMITTED` (S7 이 쓴다) | `TRANSACTION_READ_COMMITTED` | `READ-COMMITTED` |

- `PastSchemaDatabase.register(registry, database, flywayTarget)` 에 **격리 수준 인자를 더한다.** 기본값을 둔 오버로드는 만들지 않는다 — 베이스마다 값을 눈에 보이게 적는다
- `AbstractIntegrationTest` 는 `@ServiceConnection` 을 유지하고, 격리 수준만 `@DynamicPropertySource` 로 등록한다. 연결 정보는 서비스 연결이, Hikari 설정은 프로퍼티가 정한다
- `AbstractApiTest` 와 RANDOM_PORT 테스트 셋(`ErrorResponseServerTest`, `IdempotencyKeyHeaderServerTest`, `OrderPathServerTest`)은 `AbstractIntegrationTest` 를 상속하므로 따로 등록하지 않는다

### 3.2 베이스별 값

| 베이스 | DB | Flyway | 격리 수준 |
|---|---|---|---|
| `AbstractIntegrationTest` | 컨테이너 기본 | 전체 | REPEATABLE READ |
| `AbstractV2SchemaTest` | `payment_v2` | 2 | REPEATABLE READ |
| `AbstractV4SchemaTest` | `payment_v4` | 4 | REPEATABLE READ |
| `AbstractV5SchemaTest` | `payment_v5` | 5 | REPEATABLE READ |
| `AbstractV6SchemaTest` | `payment_v6` | 6 | REPEATABLE READ |

### 3.3 전제 단언

베이스마다 실제 세션 값을 확인한다. 배선이 조용히 어긋나면 재현이 다른 이유로 깨지거나, 더 나쁘게는 다른 이유로 통과한다.

| 테스트 | 추가 |
|---|---|
| `V2SchemaContextTest`, `V4SchemaContextTest`, `V5SchemaContextTest`, `V6SchemaContextTest` | "세션 격리 수준은 REPEATABLE-READ 다" 각 1개 |
| `IsolationLevelTest` | 그대로 둔다. Javadoc 의 "S7 에서 … 기준선이 된다" 는 유지 |

- 단언은 풀에서 받은 커넥션의 `SELECT @@transaction_isolation` 이다. 전역 값(`@@global`)이 아니다
- **풀을 거치지 않는 연결이 하나 있다.** `MerchantIndexGapObservationTest.pageOfEntry` (124~156행)는 root `DriverManager` 연결로 `FOR SHARE` 를 실행하고, 주석이 "갭락끼리라 기다리지 않는다" 에 기댄다. 이 연결은 서버 기본값을 따른다. `Connection.setTransactionIsolation` 으로 REPEATABLE READ 를 명시한다 (JDBC API 로 바꾼다 — SQL 로 보내지 않는다)
- `LockWaitProbe` 와 `OrderUniqueMigrationRehearsalTest` 의 root 연결은 고치지 않는다. 앞은 `performance_schema` 만 읽고, 뒤는 단일 세션의 DDL·정리라 격리 수준이 결과를 바꾸지 않는다

### 3.4 `minimum-idle`

- 3.1 의 등록과 같은 자리에서 `spring.datasource.hikari.minimum-idle` 을 준다. `src/test/resources/application.yml` 은 만들지 않는다 — 같은 이름이라 main 의 설정을 통째로 가린다
- **별도 커밋**으로 둔다. 격리 수준 고정과 섞이면 연결 수 변화를 어느 쪽이 만들었는지 알 수 없다
- 측정: 전체 실행 동안 테스트 컨테이너의 `Threads_connected`·`Max_used_connections` 를 2초 간격으로 읽는다 (1.1 과 같은 방법). 기준선은 72 다
- 확인할 것: 연결 수가 줄었는가, 전체 실행 시간이 늘지 않았는가, 대기 시간을 단언하는 테스트(`IdempotentCancelConcurrencyTest` 의 3초 이상·10초 미만, `LockWaitTimeoutObservationTest` 의 1초 이상)가 그대로인가. 풀이 연결을 늦게 만들어 단언이 흔들리면 값을 올리고 이유를 적는다

## 4. 작업 ② — 실패해도 원인이 하나로 남게 한다

### 4.1 지금의 결함

선행을 hold 로 붙잡고 테스트 스레드가 관측하는 테스트는 아래 순서를 손으로 다섯 번 적었다.

```
armHold → supplyAsync(ConcurrentRunner.run(...)) → awaitArrival → 관측 → release → join → 단언
```

관측(`awaitArrival`, `awaitWaiters`, `soleHolder`)이 예외를 던지면 `finally` 가 게이트만 풀고 `join()` 은 건너뛴다. 워커는 살아 있는 채 테스트가 끝난다. `ConcurrentRunner.verifyNoRunawayWorkers` 는 러너가 **끝날 때** 세우는 플래그만 보므로 다음 `@BeforeEach` 를 통과시키고, TRUNCATE 가 살아 있는 워커와 겹친다.

| # | 위치 | 상태 |
|---|---|---|
| 1 | `OrderLockMatrix.run` (136~182행) | 관측이 실패하면 `running.join()` 에 도달하지 않는다 |
| 2 | `OrderLockMatrix.leaderLocks` (119~133행) | `finally` 에서 join 하지만 **같은 자리에 단언**이 있다. 관측 실패 뒤 단언까지 실패하면 최초 원인이 사라진다 |
| 3 | `IdempotentCancelConcurrencyTest` — `releaseLeaderAfterWaiters` 를 쓰는 회귀 7·8·10 | join 이 헬퍼 호출 **뒤**에 있다 |
| 4 | `OrderCancelConcurrencyTest.orderCancelFirst` (65~88행) | 같다 |
| 5 | `UniqueKeyWaiterDeadlockObservationTest` (60~100행) | 같다. 5회 반복이라 한 회차의 잔여 워커가 다음 회차와 겹친다 |
| 6 | `CompositeUniqueOrderLockRegressionTest.missingOrderLocksTheGap` (107~159행) | 같다. 추가로 `inGap` 이 러너 밖의 맨 스레드에서 `paymentService.create` 를 돌린다 |

`OrderCancelConcurrencyTest.paymentKeyCancelFirst` 와 `IdempotentCancelConcurrencyTest` 의 회귀 9 둘은 관측과 해제를 워커 안에서 하고 `ConcurrentRunner.run` 을 직접 부른다. 러너가 워커 종료를 확인하므로 해당하지 않는다.

### 4.2 관측 실행 헬퍼

`support` 에 헬퍼 하나를 두고 여섯 곳이 같은 것을 쓴다. 이름과 시그니처는 구현에서 정하되 아래 계약을 지킨다.

1. hold 무장, 워커 시작, `awaitArrival`, 관측 블록 실행을 맡는다
2. 관측 블록이 어떻게 끝나든 **게이트를 풀고, 상한 안에 시작한 워커 전부의 종료를 확인한다.** 한 작업의 종료 확인이 실패해도 나머지 작업의 정리를 건너뛰지 않는다
3. 관측 블록이 실패했으면 **최초 실패를 다시 던진다.** 단언 실패(`AssertionError`)도 포함한다. 게이트 해제·워커 종료 확인에서 난 예외와 수집된 워커 실패는 `addSuppressed` 로 붙인다
4. 관측과 종료 확인이 성공했을 때만 `Results` 를 돌려준다. 이때 워커의 업무 실패는 `failures()` 에 담는다. **헬퍼 안에서 결과를 단언하지 않는다**
5. 관측 중에 보내는 추가 작업(6번의 `inGap`)도 헬퍼가 러너로 돌리고 종료를 확인한다. 선행이 실행 중이어도 같은 테스트의 추가 러너를 시작할 수 있어야 한다 (4.3)
6. 러너의 기존 상한(30초 + 취소 5초)을 그대로 쓴다. **상한 안에 종료를 확인하지 못하면 runaway 를 기록하고 후속 DB 작업을 차단한다.** 최초 실패가 있으면 종료 실패를 suppressed 로 붙이고, 없으면 종료 실패를 던진다. 상한은 강제 종료 보장이 아니다

헬퍼 자체의 테스트를 `ConcurrencySupportTest` 에 더한다 (DB 없이 돈다).

| 사례 | 기대 |
|---|---|
| 관측이 예외 또는 단언 실패를 던진다 (워커는 해제·취소에 응답한다) | 최초 실패가 나오고, 헬퍼를 빠져나올 때 워커가 모두 끝나 있다 |
| 관측이 예외를 던지고 워커도 실패했다 | 관측의 예외가 나온다. 워커 쪽은 suppressed 다 |
| 관측이 성공한다 | `Results` 가 나오고 워커의 실패는 `failures()` 에 있다 |
| 아무도 hold 에 도착하지 않는다 (워커는 취소에 응답한다) | 상한에서 실패하고, 워커가 모두 끝나 있다 |
| 선행 실행 중 추가 작업을 시작한다 | 추가 러너가 거절되지 않고, 두 작업 모두 종료를 확인한다 |
| 한 작업의 종료 확인이 실패한다 | 나머지 작업도 정리한다. 종료 불가는 runaway 로 기록하고, 최초 실패가 있으면 보존한다 |

runaway 를 의도적으로 만드는 검증은 별도 JVM 에서 실행한다. 실제 워커는 검증 뒤 해제해 종료시키고, 공유 테스트 JVM 의 영구 플래그를 오염시키지 않는다.

### 4.3 정리 규약

`AbstractDatabaseTest.resetDatabaseAndFakes` 의 순서를 바꾼다.

```
현재   verifyNoRunawayWorkers → TRUNCATE → 시드 → Fake 리셋 → 게이트 해제
변경   테스트 경계의 미종료 실행 검사 → 게이트 해제 → TRUNCATE → 시드 → Fake 리셋
```

- **게이트 해제가 워커 종료를 보장하지는 않는다.** 풀린 워커가 커밋까지 가는 데 시간이 걸린다. 4.2 가 종료를 확인하고, 확인할 수 없으면 후속 DB 작업을 차단한다
- **러너 시작 검사와 테스트 경계 검사를 분리한다.** `ConcurrentRunner.run` 이 시작할 때 호출하는 `verifyNoRunawayWorkers` 는 기존 `runaway` 플래그만 검사한다. 정상 실행 중인 다른 러너가 있다는 이유로 거절하지 않는다
- `@BeforeEach` 에서만 호출하는 별도 검사는 `runaway` 와 **미종료 실행**을 함께 검사한다. 하나라도 남아 있으면 TRUNCATE 전에 실패하고 앞 테스트의 실행이 남았다는 메시지를 남긴다
- 실행 추적은 워커 본문 진입 시점이 아니라 **비동기 작업을 제출하기 전**에 등록한다. 아직 시작하지 않은 예약 작업도 포함하고, 제출 실패 시 등록을 해제한다. 정상 경로에서는 워커 전부의 실제 종료를 확인한 뒤 해제한다. 종료를 확인하지 못한 실행은 남겨 두며, 기존 영구 `runaway` 플래그도 유지한다
- 검사 테스트에는 선행 실행 중 추가 러너 허용, 아직 시작하지 않은 예약 작업의 경계 검사 실패, 전체 종료 후 경계 검사 통과를 포함한다

### 4.4 게이트 도달 단언

barrier 로 무장한 회귀 테스트는 실행 뒤 그 게이트가 **실제로 열렸는지** 단언한다. `ConcurrencyGate.trippedAtNanos` 가 이미 있고, 쓰는 곳은 `FkPromotionDeadlockReproductionTest` 하나다.

| 클래스 | 무장 위치 (행) | 지점 |
|---|---|---|
| `DuplicateConfirmRegressionTest` | 54, 69 | `PAYMENT_READ` |
| `WalletLockRegressionTest` | 73, 129 | `PAYMENT_READ` |
| 〃 | 102 | `WALLET_LOCK_ATTEMPT` |
| `CancelLockRegressionTest` | 54, 83, 135 | `PAYMENT_LOCK_ATTEMPT` |
| 〃 | 109 | `WALLET_LOCK_ATTEMPT` |
| `FkDeadlockRegressionTest` | 64, 86 | `WALLET_LOCK_ATTEMPT` |

- 단언은 게이트에 메서드로 둔다 (예: `assertTripped(name)`). 열한 곳이 같은 문장을 쓴다
- **워커 종료 후, 기존 성공 수·실패 타입·불변식 단언보다 먼저** 게이트 도달을 검사한다. 기존 단언이 먼저 실패해 새 단언에 도달하지 못하는 것을 막는다. 게이트를 reset 하기 전에 검사한다
- 일부만 도착한 경우는 지금도 잡힌다 — 도착한 워커가 10초 상한에서 예외를 던진다. 새 단언이 잡는 것은 **아무도 도착하지 않은** 경우다. 1.1 의 변이가 정확히 그 경우다
- 설계상 열리지 않는 게이트가 있으면 단언을 넣지 않고 그 이유를 테스트 주석에 적는다
- 이번에는 회귀 테스트에 우선 적용한다. 재현의 깨진 결과(건수·잔액)는 동기화 없이 우연한 경합으로도 나올 수 있으므로, 결과 단언만으로 게이트 도달을 보장하지는 않는다. 재현 테스트로의 도달 단언 확대는 별도로 검토한다

**검증은 변이로 한다.** 1.1 과 같은 복사본 변이를 다시 돌려, 실패 메시지에 게이트 단언이 나오는지 확인한다. 작업 트리에서는 하지 않는다.

## 5. 작업 ③ — 기록의 오류를 고친다

### 5.1 S4 — 1205 의 예외 번역 경로

| 위치 | 현재 | 고칠 내용 |
|---|---|---|
| `docs/phase1/S4.md` 5.3 "예외 타입은 번역 경로가 정한다" 표의 셋째 행 | `JdbcTemplate` (Spring 의 SQL 에러 코드 변환) | 에러 코드 변환이 아니다. 아래 경로로 고친다 |

jar 에서 확인한 경로:

- spring-jdbc 6.2.19 의 `JdbcAccessor.getExceptionTranslator()` 는 **사용자가 둔 `sql-error-codes.xml` 이 있을 때만** `SQLErrorCodeSQLExceptionTranslator` 를 쓴다. 이 프로젝트에는 그 파일이 없어 기본값은 `SQLExceptionSubclassTranslator` 다
- mysql-connector-j 9.7.0 의 `MysqlErrorNumbers` 는 1205 와 1213 을 모두 SQLState `40001` 로 바꾼다. SQLState 가 `40` 으로 시작하면 `MySQLTransactionRollbackException`(`SQLTransactionRollbackException` 의 하위 타입)이 된다
- `SQLExceptionSubclassTranslator` 는 `SQLTransactionRollbackException` 이고 SQLState 가 잠금 획득 실패를 가리키면 `CannotAcquireLockException`, 아니면 `PessimisticLockingFailureException` 을 만든다

결론("타입이 아니라 에러 코드로 판정한다")은 바뀌지 않는다. 드라이버가 1205 와 1213 에 **같은 SQLState** 를 주므로 오히려 더 강해진다.

**타입 구분을 단언으로 고정한다.** `LockWaitTimeoutObservationTest` 의 JdbcTemplate 테스트는 DisplayName 이 "다른 타입이 된다" 인데 단언은 JPA 테스트와 같은 부모 타입(`PessimisticLockingFailureException`) 하나다. 표의 구분이 `System.out.printf` 에만 남아 있다.

| 테스트 | 현재 단언 | 추가 |
|---|---|---|
| JPA 1205 | `isInstanceOf(PessimisticLockingFailureException)` | `CannotAcquireLockException` 이 **아니다** |
| JdbcTemplate 1205 | 같다 | `CannotAcquireLockException` **이다** |

라이브러리를 올려 번역 경로가 바뀌면 이 테스트가 알린다. 그때 고칠 것은 테스트와 표이지 `MySqlLockErrors` 가 아니다.

### 5.2 S5 — JDBC 를 쓴 이유

| 위치 | 현재 | 고칠 내용 |
|---|---|---|
| `docs/phase1/S5.md` 5절 "SQL 순서로 보장한다" 항목 | 키 INSERT 가 JPA flush 를 기다리지 않고 실제로 먼저 나간다. 엔티티를 만들지 않은 이유다 | 이유를 아래로 바꾼다 |
| `IdempotencyKeyStore` 클래스 Javadoc | 쓰기는 호출 즉시 SQL 로 나가야 한다 — 영속성 컨텍스트에 쌓였다가 flush 때 나가면 … | 같은 내용으로 맞춘다. main 의 변경은 이 주석 하나다 |
| `docs/plan/S5.md` 2절 "키 저장소" 행, 4.3 조건 1 | 같은 서술 | 본문은 두고 안내를 단다 (S6 선례) |

`idempotency_key.id` 는 AUTO_INCREMENT 다. 엔티티로 매핑하면 IDENTITY 전략이 되고 `save` 가 곧 INSERT 다 — S4 가 `WalletLedger` 에서 쓴 사실이다 (`phase1/S4.md` 1.2). "JPA 라서 선점 INSERT 가 늦어진다" 는 틀렸다.

바꿔 적을 이유, 단단한 순서로:

1. **과거 컨텍스트가 기동하지 못한다.** `idempotency_key` 테이블은 V4 에서 생긴다. 엔티티로 매핑하면 `ddl-auto: validate` 가 V2 컨텍스트의 기동을 막는다 (`PastSchemaDatabase` Javadoc, `plan/S5.md` 5절)
2. **선점·완료의 SQL, 갱신 건수, 예외가 나는 시점을 명시적으로 다룬다.** `claim` 은 세션 변수 변경·INSERT·복원을 한 문장 단위로 감싸고 1062·1205 를 그 자리에서 가른다. `complete` 는 갱신 건수 1 을 확인한다
3. JPA 로도 flush 를 제어해 같은 순서를 만들 수 있다. "JDBC 가 필수" 라고 적지 않는다

## 6. 작업 ④ — RC 에 대한 단정을 예측으로 바꾼다

네 기록과 테스트 주석 하나가 RC 로 돌려 보지 않고 결과를 단정한다. 논리가 틀렸다는 것이 아니다. RR 에서 관측한 것과 RC 에 대한 추론이 같은 문장에 있다.

| 위치 | 현재 문장 |
|---|---|
| `docs/phase1/S1.md` 4절 (106행) | 읽기가 잠금을 잡지 않으므로 RR 과 RC 모두에서 동일하게 발생한다 |
| `docs/phase1/S2.md` 4절 (122행) | 읽기가 잠그지 않으므로 RC 에서도 같다 |
| `docs/phase1/S3.md` 4절 (129행) | S2 와 마찬가지로 RC 에서도 같다 |
| `docs/phase1/S5.md` 3절 (69행) | 조회와 저장 사이의 틈이 원인이라 RR 이든 RC 든 같다 |
| `CheckThenInsertReproductionTest` 클래스 Javadoc (30행) | RR 이든 RC 든 같다 |

- 고치는 모양: "RC 에서도 같을 것으로 **예상한다.** S7 에서 확인한다." 뒤따르는 "격리 수준 전환은 해결책이 아니다" 는 SCENARIO 의 결정이므로 남긴다
- `docs/phase1/S4.md` 의 "격리 수준과 무관하다. RC 에서도 FK 검사의 공유 락은 그대로다" 는 MySQL 문서가 직접 말하는 내용이라 단정을 유지하되, 실측은 S7 이라고 덧붙인다
- 과거 기록을 고치는 것이므로 각 기록 끝에 고친 날짜와 이유를 한 줄 남긴다

## 7. 작업 ⑤ — 예측을 먼저 적는다

S7 의 코드를 쓰기 전에 아래 표의 **RC 예측**과 **근거**를 채워 커밋한다. 목적은 학습 점검이다. 맞은 예측은 이해한 것이고, 틀린 예측이 실제로 배울 곳이다.

규칙:

- **직접 손으로 쓴다.** RC 로 아무것도 돌리기 전에 쓴다
- 근거는 "어떤 락이 남고 어떤 락이 사라지는가" 로 쓴다. "RC 라서" 는 근거가 아니다
- **"쥔 락이 줄었는가" 와 "후속이 기다리는가" 는 따로 예측한다.** 둘은 같은 질문이 아니다
- 모르면 모른다고 쓴다. 그 칸이 S7 에서 가장 먼저 볼 곳이다
- S7 의 결과가 나온 뒤에도 이 표는 고치지 않는다. 비교는 `docs/phase1/S7.md` 에 쓴다

| # | 실험 | 측정 조건 | RR 실측 | RC 예측 | 근거 |
|---|---|---|---|---|---|
| 1a | S5 2차 **최소 실험** — 두 세션이 없는 키를 `FOR UPDATE` 로 찾은 뒤 각자 INSERT (`GapLockDeadlockReproductionTest` ①) | `payment_v4`. 1213 발생 여부, 남은 키 행 수 | 한쪽 1213 (`phase1/S5.md` 3.3) | | |
| 1b | S5 2차 **서비스 적용** — 같은 키 취소 2건 (`GapLockDeadlockReproductionTest` ②) | `payment_v4`. 성공 수, 1213 수, 취소 이력 수, 키 행 수 | 성공 1, 1213 1, 취소 1, 키 1 | | |
| 2a | S5 5.6 — 선행이 롤백하고 대기자가 둘 (`UniqueKeyWaiterDeadlockObservationTest`) | 최신 스키마(unique). 회차마다 성공 수, 1213 수 | 성공 1, 1213 1 (5회 모두) | | |
| 2b | S4 재현 1 — 원장 INSERT 뒤 지갑 UPDATE 둘 (`FkPromotionDeadlockReproductionTest`) | 최신 스키마(FK). 1213 발생 여부 | 한쪽 1213 | | |
| 3a | S6 1 단계 — 선행이 **쥔 락** | `payment_v5`. 선행의 잠금 조회 **반환 직후**, 선행 트랜잭션의 `payment` RECORD 락 수 | 10,076 (`phase1/S6.md` 3.3) | | |
| 3b | S6 1 단계 — 후속의 **대기 여부** | `payment_v5`. 대기 행렬 1~11번 각각 대기 / 통과 | 1~9번 대기, 10번 통과, 11번 대기자 7 | | |
| 3c | S6 2 단계 — 같은 가맹점의 다른 주문, 이웃 가맹점의 생성 | `payment_v6`. 행렬 2·4·7·9번 | 넷 다 대기 | | |
| 3d | S6 없는 주문을 잠그며 찾기 | 최신 스키마. 선행의 락, 같은 자리의 생성이 대기하는가 | `X,GAP` 1개, 대기 (`phase1/S6.md` 3.7) | | |
| 4a | S1 — CAS 탈락자의 재조회 값 | 0건 갱신 뒤 **같은 트랜잭션**에서 `JdbcTemplate` 스칼라 조회. JPA 엔티티로 읽지 않는다 | `READY` (`DuplicateConfirmRegressionTest`) | | |
| 4b | S1 — CAS 탈락자가 쥔 락 | 0건 갱신 **직후**, 탈락자 트랜잭션의 `payment` RECORD 락 | **측정한 적 없다** (11절) | RR: / RC: | |

## 8. 작업 순서 (커밋 단위, 커밋마다 green)

| # | 커밋 | 내용 |
|---|---|---|
| 1 | `docs:` | 이 계획 |
| 2 | `test:` | 격리 수준 값과 등록 (3.1), 베이스 다섯의 RR 고정 (3.2), 전제 단언 4개, `pageOfEntry` (3.3). STRUCTURE.md 5.1 의 표에 격리 수준 열을 더한다 |
| 3 | `test:` | 테스트 컨텍스트의 `minimum-idle` (3.4). 측정값을 커밋 메시지에 적는다 |
| 4 | `test:` | 관측 실행 헬퍼와 그 테스트 (4.2) |
| 5 | `test:` | 여섯 곳을 헬퍼로 바꾼다 (4.1). 단언은 옮기기만 하고 고치지 않는다 |
| 6 | `test:` | 정리 순서, 러너 시작 검사와 테스트 경계 검사 분리, 예약 작업을 포함한 미종료 실행 추적 (4.3) |
| 7 | `test:` | 회귀 테스트의 게이트 도달 단언 (4.4) |
| 8 | `test:` | 1205 예외 타입 단언 (5.1) |
| 9 | `docs:` | S4·S5 기록 수정, `plan/S5.md` 안내, `IdempotencyKeyStore` Javadoc (5절) |
| 10 | `docs:` | RC 단정을 예측으로 (6절) |
| 11 | `docs:` | 7절의 예측 기입 — **직접 작성** |

4번과 5번을 나눈다. 헬퍼가 먼저 자기 테스트로 green 이 된 뒤에 기존 테스트를 옮긴다.

## 9. 범위 — 의도적으로 하지 않는 것

| 항목 | 언제 | 이유 |
|---|---|---|
| RC 컨텍스트 만들기, 본선을 RC 로 전환 | S7 | 이 작업은 동작을 바꾸지 않는다 |
| 최신 컨텍스트의 RR 전제 테스트 옮기기 — `DuplicateConfirmRegressionTest` 의 스냅샷 단언, `CompositeUniqueOrderLockRegressionTest` 의 없는 주문 갭락, `UniqueKeyWaiterDeadlockObservationTest`, `FkPromotionDeadlockReproductionTest` 등 | S7, 본선 전환 커밋과 함께 | 본선이 RR 인 동안에는 지금 자리가 맞다 |
| SCENARIO 행 번호 참조 137곳을 절 이름으로 바꾸기 | S7 에서 SCENARIO 를 고칠 때 | 96~145행대 참조가 이미 한 줄씩 어긋나 있다. 어차피 S7 이 SCENARIO 를 고치므로 그때 한 번에 한다. **이 문서와 이후 문서는 행 번호를 쓰지 않는다** |
| 게이트 매핑(메서드 이름 문자열)을 실제 리포지터리와 대조하는 테스트 | 필요해지면 | 매핑이 빠진 결과는 4.4 의 단언이 잡는다 |
| 테스트 헬퍼 중복 정리 (`balanceOf` 15곳 등), 버전별 베이스 클래스 통합 | S7 계획에서 판단 | RC 변형을 클래스 복사로 만들지, `OrderLockMatrix` 처럼 시나리오와 기대를 가를지에 달렸다 |
| 테스트 전체의 `@Timeout` | S7 계획에서 판단 | 지금 코드에 무한 대기 경로는 없다. 모든 대기에 상한이 있다 |
| 로컬 docker compose DB 정리 (V1, 중복 주문 1,844묶음) | S8 전 | k6 를 쓰는 단계 전까지 앱을 띄우지 않는다 (`phase1/S6.md` 5.4) |
| `s4.cnf`·`s6.cnf` 가 로컬에서 항상 켜지는 것 | S8 전 | slow log 가 부하 측정에 섞인다 |
| main 에 공존하는 실패 구현의 위치 | Phase 2 | 교재의 규약이다 |

## 10. 검증

| 확인 | 기준 |
|---|---|
| `./gradlew spotlessCheck test` | 커밋마다 green |
| 기존 테스트 | 329개가 **단언 수정 없이** 통과. 5번 커밋은 단언의 위치만 옮긴다 |
| 신규 테스트 | 전제 단언 4 + 헬퍼 테스트 + 미종료 실행 검사 테스트. 전체 개수는 329 보다 늘어난다 |
| 격리 수준 | 베이스 다섯 모두 풀 커넥션의 `@@transaction_isolation` 이 `REPEATABLE-READ` |
| 연결 수 | 3번 커밋 전후의 `Max_used_connections`. 기준선 72 |
| 실행 시간 | 벽시계 기준선 87~89초. 눈에 띄게 늘면 원인을 적는다 |
| 반복 | S5·S6 의 동시성·관측 클래스를 `--rerun` 으로 5회. 결과가 모두 같다 |
| 변이 (복사본) | 1.1 의 변이에서 실패 메시지에 게이트 도달 단언이 나온다 |
| 실패 경로 (복사본) | 해제·취소에 응답하는 워커를 두고 관측이 실패하도록 기대를 일부러 틀리게 준 뒤 전체를 돌린다. **정리가 성공하면 실패는 그 테스트 하나**이고 뒤 테스트로 번지지 않는다 |
| 종료 불가 경로 (별도 JVM) | 종료 상한을 넘는 작업으로 runaway 기록과 후속 DB 정리 차단을 확인한다. 최초 실패를 보존하며, 검증 뒤 실제 워커는 해제해 종료한다. 이 경우 후속 테스트 차단은 의도한 동작이다 |
| main 의 diff | `IdempotencyKeyStore` 의 Javadoc 뿐이다 |

## 11. S7 에 넘기는 것

- **격리 수준은 컨텍스트의 프로퍼티다.** RC 실험은 형제 베이스를 만들어 값만 다르게 등록한다. `PastSchemaDatabase.register` 가 인자로 받는다
- **본선의 RC 를 어디에 둘지는 S7 이 정한다.** SCENARIO S7 "작업" 은 `my.cnf` 인데 테스트 컨테이너는 그 파일을 쓰지 않는다. `application.yml` 의 Hikari 프로퍼티로 두면 로컬과 테스트가 같은 경로를 탄다. 어느 쪽이든 최신 베이스의 등록값과 어긋나지 않게 한다
- **연결 예산.** 컨텍스트 하나가 풀 하나다. RC 컨텍스트를 더한 뒤 `Max_used_connections` 를 다시 잰다
- **RR 기준선이 없는 관측이 하나 있다.** CAS 탈락자가 0건 갱신 뒤 쥔 락(7절 4b)은 S1 이 캡처하지 않았다. S7 은 RR 과 RC 를 함께 측정한다
- **`LockWaitProbe.soleHolder` 는 락을 쥔 트랜잭션이 정확히 하나가 아니면 예외를 던진다.** RC 에서 선행이 락을 쥐지 않게 되면 단언이 아니라 헬퍼에서 실패한다. 4.2 의 헬퍼가 그 실패를 가리지 않고 전달하지만, "락이 없다" 를 기대로 쓸 수 있는 조회가 따로 필요하다
- **S6 은 같은 조건의 `UPDATE` 문을 만들지 않았다** (`phase1/S6.md` 8절). semi-consistent read 를 `SELECT ... FOR UPDATE` 와 비교하려면 S7 이 만든다
- **7절의 예측**이 S7 기록의 비교 대상이다
- **6절에서 예측으로 바꾼 주장도 검증 목록으로 넘긴다.** `docs/plan/S7.md` 에 S1 중복 승인, S2 Lost Update, S3 두 재현(초과 취소·환불과 지갑 갱신의 충돌), S5 check-then-insert 의 RC 재실행을 명시한다. 각 실험의 RR 스키마·데이터·워커 수·동기화 조건을 유지해 비교한다. 7절의 CAS 재조회나 갭락 실험으로 이 검증을 대신하지 않는다
