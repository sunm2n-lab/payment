-- S6 2 단계: payment(merchant_id) 단일 인덱스 (docs/plan/S6.md 4.2).
--
-- 주문 기반 잠금 읽기(WHERE merchant_id = ? AND order_id = ? FOR UPDATE)는 인덱스가 없으면(V5) 클러스터 인덱스
-- 전체를 스캔하고 전부 잠근다. 이 인덱스가 있으면 스캔 범위가 그 가맹점의 엔트리로 좁아진다. 다른 가맹점끼리는 더
-- 이상 막히지 않지만, 같은 가맹점의 다른 주문은 여전히 막힌다 - order_id 는 인덱스에 없어 가맹점의 모든 행을 읽어
-- 비교하고, REPEATABLE READ 는 조건에 맞지 않은 행의 락도 풀지 않는다.
--
-- V7 이 이 인덱스를 (merchant_id, order_id) 복합 unique 로 교체한다. 2 단계 비교는 V6 까지만 적용한 별도
-- DB(payment_v6)에서 돈다.
--
-- 엔티티 매핑과 코드는 바뀌지 않는다. 세 단계(V5·V6·V7)의 차이는 스키마 하나다.

CREATE INDEX idx_payment_merchant_id ON payment (merchant_id);
