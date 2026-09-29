-- S6 V7 적용 전 주문 id 확인·정리 (docs/plan/S6.md 4.6). 범위는 로컬 실습 데이터다.
--
-- 주문 id 는 가맹점이 발급한 식별자다. 운영 데이터였다면 임의로 바꿀 수 없고 건별 확인과 가맹점 협의가 필요하다.
-- 로컬은 볼륨을 지우고 다시 만드는 쪽이 더 간단하다 (docker compose down -v). 이 스크립트는 그러지 않고 데이터를 남기는 경우다.
--
-- 순서가 있다. 형식을 먼저 정상화하고, 그 다음에 중복을 센다. ascii_bin(V7)은 뒤 공백을 무시하고 비교하지만 확인 2 의
-- utf8mb4_0900_bin 은 뒤 공백을 구분한다. 'a' 와 'a ' 가 함께 있으면 확인 2 는 0행인데 V7 은 1062 로 실패한다.
--
-- 형식 검사는 길이와 허용하지 않는 문자를 따로 본다. '^...{6,64}$' 한 정규식으로 쓰면 안 된다 - MySQL(ICU) 정규식의
-- $ 는 문자열 끝뿐 아니라 마지막 줄바꿈 앞에서도 일치해 'order-1' + LF / CR / CRLF 가 검사를 통과한다. 그 값은 V7 도
-- 그대로 통과하고, 애플리케이션의 OrderIds 는 거절하므로 새 API 로 접근할 수 없는 결제가 남는다. \z 로 끝을 고정하는
-- 방법도 있지만 SQL 문자열의 역슬래시 이스케이프가 sql_mode(NO_BACKSLASH_ESCAPES)에 따라 달라진다.
--
-- 한 번에 실행하지 않는다. @step 단위로 하나씩 실행하고 결과를 본다. 리허설 테스트(OrderUniqueMigrationRehearsalTest)도
-- 이 파일을 @step 단위로 읽어 같은 순서로 실행한다.
--
-- 각 정리 단계(①, ②):
--   1. prepare    새 값(fixed-{id})을 s6_order_fix 에 만든다. 가장 짧아도 7자(fixed-1)라 형식 규칙 안이다
--   2. collisions 두 충돌 검사가 모두 0행이어야 한다. 0행이 아니면 apply 하지 않고 멈춘다. 접두어를 바꿔 prepare 부터 다시 한다
--   3. apply      한 트랜잭션에서 UPDATE 하고, 그 단계의 확인 쿼리가 0행인지 본다
-- 마지막에 cleanup 으로 작업 테이블을 지운다.

-- @step check1
-- 확인 1. 형식 밖의 주문 id. 0행이어야 한다 - V7 적용의 필수 사전 조건. ASCII 형식 위반은 V7 이 막지 못한다
SELECT id, merchant_id, order_id FROM payment
WHERE CHAR_LENGTH(order_id) NOT BETWEEN 6 AND 64
   OR REGEXP_LIKE(order_id, '[^A-Za-z0-9_-]', 'c');

-- @step check2
-- 확인 2. 중복 주문. 0행이어야 한다. 전제: 확인 1 이 0행이다
SELECT merchant_id, order_id COLLATE utf8mb4_0900_bin AS order_id, COUNT(*), MIN(id), MAX(id)
FROM payment
GROUP BY merchant_id, order_id COLLATE utf8mb4_0900_bin HAVING COUNT(*) > 1;

-- @step format.prepare
-- ① 형식 정상화: 확인 1 에 나온 행 전부
DROP TABLE IF EXISTS s6_order_fix;
CREATE TABLE s6_order_fix (
    id           BIGINT      NOT NULL,
    merchant_id  BIGINT      NOT NULL,
    new_order_id VARCHAR(64) NOT NULL,
    PRIMARY KEY (id)
);
INSERT INTO s6_order_fix (id, merchant_id, new_order_id)
SELECT id, merchant_id, CONCAT('fixed-', id) FROM payment
WHERE CHAR_LENGTH(order_id) NOT BETWEEN 6 AND 64
   OR REGEXP_LIKE(order_id, '[^A-Za-z0-9_-]', 'c');

-- @step duplicates.prepare
-- ② 중복 정리: 확인 2 의 묶음마다 id 가 가장 작은 행을 남기고 나머지
DROP TABLE IF EXISTS s6_order_fix;
CREATE TABLE s6_order_fix (
    id           BIGINT      NOT NULL,
    merchant_id  BIGINT      NOT NULL,
    new_order_id VARCHAR(64) NOT NULL,
    PRIMARY KEY (id)
);
INSERT INTO s6_order_fix (id, merchant_id, new_order_id)
SELECT p.id, p.merchant_id, CONCAT('fixed-', p.id)
FROM payment p
JOIN (SELECT merchant_id, order_id COLLATE utf8mb4_0900_bin AS order_id, MIN(id) AS keep_id
      FROM payment
      GROUP BY merchant_id, order_id COLLATE utf8mb4_0900_bin HAVING COUNT(*) > 1) d
  ON d.merchant_id = p.merchant_id
 AND d.order_id = p.order_id COLLATE utf8mb4_0900_bin
 AND p.id <> d.keep_id;

-- @step collisions.existing
-- 충돌 검사 1. 새 값 ↔ 기존 주문. 0행이어야 한다
-- 같은 가맹점에 그 값을 이미 쓰는 결제(바뀌지 않는 행)가 있으면 정리한 뒤에도 V7 이 실패한다.
-- 뒤 공백을 무시하는 쪽(utf8mb4_bin, PAD SPACE - V7 의 ascii_bin 과 같은 쪽)으로 비교한다. ① 단계에서는 아직 공백이 든 값이 남아 있다
SELECT f.id, f.merchant_id, f.new_order_id, p.id AS existing_id, p.order_id AS existing_order_id
FROM s6_order_fix f
JOIN payment p
  ON p.merchant_id = f.merchant_id
 AND p.order_id COLLATE utf8mb4_bin = f.new_order_id COLLATE utf8mb4_bin
LEFT JOIN s6_order_fix changing ON changing.id = p.id
WHERE changing.id IS NULL;

-- @step collisions.new
-- 충돌 검사 2. 새 값 ↔ 새 값. 0행이어야 한다
-- fixed-{id} 끼리는 id 가 PK 라 겹칠 수 없지만, 새 값을 만드는 규칙을 바꿔도 검사가 남도록 둔다
SELECT merchant_id, new_order_id COLLATE utf8mb4_bin AS new_order_id, COUNT(*)
FROM s6_order_fix
GROUP BY merchant_id, new_order_id COLLATE utf8mb4_bin HAVING COUNT(*) > 1;

-- @step apply
-- 두 충돌 검사가 0행일 때만 실행한다. 끝나면 그 단계의 확인 쿼리(check1 또는 check2)가 0행인지 본다
START TRANSACTION;
UPDATE payment p JOIN s6_order_fix f ON f.id = p.id SET p.order_id = f.new_order_id;
COMMIT;

-- @step cleanup
DROP TABLE IF EXISTS s6_order_fix;
