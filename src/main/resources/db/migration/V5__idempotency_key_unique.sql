-- S5 본선: (merchant_id, operation, idempotency_key) unique + insert-first (docs/plan/S5.md 4.2).
--
-- ALTER 한 문장으로 쓴다. MySQL 8.0 InnoDB 는 DDL 한 문장의 원자성을 지원하지만 여러 DDL 문장을 한 트랜잭션으로
-- 함께 되돌리지는 못한다. DROP 과 ADD 를 두 문장으로 나누면 unique 추가가 실패했을 때 검색 인덱스만 사라진 채 남는다.
--
-- 적용 대상에 중복 키가 없어야 한다. 있으면 unique 추가가 실패한다. 1·2차 재현은 V4 까지만 적용한 payment_v4 에서
-- 돌고 최신 DB 에는 중복을 만들지 않는다. 확인 쿼리:
--   SELECT merchant_id, operation, idempotency_key, COUNT(*) FROM idempotency_key
--   GROUP BY merchant_id, operation, idempotency_key HAVING COUNT(*) > 1;
--
-- unique 는 중복 실행을 막는 장치이지 데드락을 없애는 장치가 아니다. 중복 검사도 락을 잡는다 (docs/plan/S5.md 4.6).

ALTER TABLE idempotency_key
    DROP INDEX idx_idempotency_key_lookup,
    ADD UNIQUE KEY uk_idempotency_key (merchant_id, operation, idempotency_key);
