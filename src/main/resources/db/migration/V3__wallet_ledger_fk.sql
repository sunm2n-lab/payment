-- S4 FK 락 승격 데드락의 실습 대상. 이 스키마의 첫 FK 다 (docs/plan/S4.md).
--
-- FK 는 자식 INSERT 때 부모 행에 공유(S) 락을 건다. 원장을 먼저 INSERT 하고 지갑을 나중에 UPDATE 하는
-- 트랜잭션 둘이 같은 지갑에서 만나면 S -> X 승격이 서로를 기다려 데드락이 된다. 본선은 원장 INSERT 전에
-- wallet 을 FOR UPDATE 로 잡으므로 이 모양이 생기지 않는다.
--
-- 과거 재현(S1·S2·S2-a·S3)은 이 FK 가 있으면 결과가 바뀐다 - Lost Update 대신 데드락이 난다. 그래서
-- 그 재현들은 V2 까지만 적용한 별도 DB 에서 돈다 (AbstractV2SchemaTest). SCENARIO 94행의 "좁은 예외"
-- (additive·DEFAULT·미매핑 컬럼)에 해당하지 않는다.
--
-- 적용 전 고아 참조가 없어야 한다. 있으면 ADD CONSTRAINT 가 실패한다. 확인 쿼리:
--   SELECT l.id, l.wallet_id FROM wallet_ledger l LEFT JOIN wallet w ON w.id = l.wallet_id WHERE w.id IS NULL;
--
-- 인덱스를 이름 붙여 먼저 만든다. 없으면 InnoDB 가 FK 이름으로 자동 생성하는데, 스키마 전제 테스트가
-- 인덱스 목록을 보므로 이름을 우리가 정한다. 다른 FK(payment_cancel -> payment, payment -> merchant 등)는
-- 추가하지 않는다 (SCENARIO 161행).

CREATE INDEX idx_wallet_ledger_wallet_id ON wallet_ledger (wallet_id);

ALTER TABLE wallet_ledger
    ADD CONSTRAINT fk_wallet_ledger_wallet FOREIGN KEY (wallet_id) REFERENCES wallet (id);
