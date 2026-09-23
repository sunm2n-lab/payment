-- S2-a 낙관적 락용 버전 컬럼.
--
-- 기존 Wallet 엔티티는 이 컬럼을 매핑하지 않는다. @Version 을 Wallet 에 붙이면 naive 구현까지 낙관적 락을
-- 갖게 되어 S2 재현이 사라지기 때문이다 (docs/plan/S2.md 2.4). 같은 테이블을 가리키는 두 번째 엔티티
-- VersionedWallet 만 이 컬럼을 쓴다. ddl-auto: validate 는 매핑되지 않은 컬럼을 문제 삼지 않는다.
--
-- additive 하고 DEFAULT 가 있어 시드·테스트의 기존 INSERT 를 그대로 덮는다. 그래서 이 컬럼을 읽지도 쓰지도
-- 않는 과거 재현(S1, S2 naive)은 V2 이후에도 같은 결과를 낸다 - 회귀 테스트로 확인한다.
--
-- FK·인덱스는 추가하지 않는다 (S4 / S6 의 실습 대상).

ALTER TABLE wallet ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
