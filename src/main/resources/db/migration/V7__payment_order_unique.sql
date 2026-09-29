-- S6 3 단계(본선): (merchant_id, order_id) 복합 unique + order_id 를 ascii_bin 으로 (docs/plan/S6.md 4.2).
--
-- 주문 기반 잠금 읽기가 unique 등치가 되어 잠금 범위가 대상 결제 한 행으로 좁아진다. 같은 가맹점의 다른 주문도 더 이상
-- 막히지 않는다. 동시에 주문 유일성이 생긴다 - 같은 가맹점이 같은 주문으로 결제를 두 번 만들 수 없다 (SCENARIO 84행).
--
-- ALTER 한 문장으로 쓴다 (V5 와 같은 이유). MySQL 8.0 InnoDB 는 DDL 한 문장의 원자성을 지원하지만 여러 DDL 문장을 함께
-- 되돌리지는 못한다. unique 추가나 문자 집합 변환이 실패하면 단일 인덱스와 collation 이 그대로 남아야 한다.
--
-- 단일 인덱스를 남기지 않는다. 복합 unique 의 선두 컬럼이 merchant_id 라 가맹점 검색도 이 인덱스가 받는다.
--
-- order_id 는 ascii / ascii_bin 이다. 테이블 기본값(utf8mb4_0900_ai_ci)은 대소문자를 무시해 order-1 과 ORDER-1 이
-- 같은 주문이 되고, 승인의 orderId 검사(String.equals)와도 어긋난다. 허용 문자는 OrderIds 규칙([A-Za-z0-9_-], 6~64자)이다.
-- ascii_bin 은 뒤 공백을 무시하고 비교한다(PAD SPACE). 뒤 공백은 형식 규칙이 새 입력에서 막는다.
--
-- ============================================================================================================
-- 적용 전 필수 확인 - Flyway 는 이 확인을 대신 돌려 주지 않는다 (docs/plan/S6.md 4.6)
-- ============================================================================================================
-- 중복 주문과 비ASCII 주문 id 는 이 ALTER 가 실패로 알린다. 그러나 ASCII 형식 위반(a/b, 'a b', 뒤 공백, 5자 이하)은
-- 그대로 통과한다. 그래서 확인 1 이 0행인 것이 적용의 필수 사전 조건이다. 순서를 지킨다 - 형식을 먼저 정상화하고 중복을 센다.
--
-- 확인 1. 형식 밖의 주문 id. 0행이어야 한다
--   SELECT id, merchant_id, order_id FROM payment
--   WHERE NOT REGEXP_LIKE(order_id, '^[A-Za-z0-9_-]{6,64}$', 'c');
--
-- 확인 2. 중복 주문. 0행이어야 한다. 전제: 확인 1 이 0행이다
--   SELECT merchant_id, order_id COLLATE utf8mb4_0900_bin AS order_id, COUNT(*), MIN(id), MAX(id)
--   FROM payment
--   GROUP BY merchant_id, order_id COLLATE utf8mb4_0900_bin HAVING COUNT(*) > 1;
--
-- 이 마이그레이션은 데이터를 지우거나 고치지 않는다. 정리 절차는 docs/plan/S6.md 4.6 과 리허설 테스트
-- (OrderUniqueMigrationRehearsalTest)에 있다.

ALTER TABLE payment
    MODIFY order_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    DROP INDEX idx_payment_merchant_id,
    ADD UNIQUE KEY uk_payment_merchant_order (merchant_id, order_id);
