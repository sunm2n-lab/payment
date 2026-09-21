-- 로컬/부하 실행과 테스트가 공유하는 시드. Flyway 이력(flyway_schema_history) 밖에서 실행한다.
--
-- Flyway locations 에 넣지 않는 이유: location 을 나눠도 이력 테이블은 하나이므로 V900 같은 시드가
-- 기록되면, S1 에서 V2 를 추가할 때 outOfOrder=false 기본값에서 V2 가 최고 적용 버전보다 낮아
-- 적용되지 않고 validateOnMigrate 가 기동을 막는다. R__(repeatable) 도 target 과 무관하게 항상
-- 적용되므로 나중 버전의 컬럼을 건드리는 순간 과거 스키마 재현 컨테이너에서 깨진다.
--
-- 멱등 규칙: 기존 행은 유지하고 없는 행만 추가한다. 특히 이미 존재하는 지갑의 잔액은 건드리지 않는다.
-- 재기동 때 잔액을 0 으로 덮으면 기존 원장과 어긋나 "잔액 == 원장 합계" 불변식이 시드에서 깨진다.
-- INSERT IGNORE 는 중복 키 외의 오류(데이터 절단 등)까지 경고로 바꿔 삼키므로 쓰지 않는다.

-- 가맹점 2건. 두 번째는 "다른 가맹점의 결제 -> 404" 테스트 전용이며 k6 는 첫 번째만 쓴다.
INSERT INTO merchant (name, api_key, created_at)
VALUES ('테스트 가맹점 1', 'mk_test_merchant_1', NOW(6))
ON DUPLICATE KEY UPDATE id = id;

INSERT INTO merchant (name, api_key, created_at)
VALUES ('테스트 가맹점 2', 'mk_test_merchant_2', NOW(6))
ON DUPLICATE KEY UPDATE id = id;

-- 지갑은 잔액 0, 원장 없음으로 심는다. "잔액 == 원장 합계" 가 시드 데이터에서도 유지되게 하기 위함이다.
-- 초기 잔액은 charge API 로 넣고 CHARGE 원장을 남긴다 (SCENARIO 42행).
INSERT INTO wallet (member_id, balance, created_at) VALUES (1, 0, NOW(6))
ON DUPLICATE KEY UPDATE id = id;
INSERT INTO wallet (member_id, balance, created_at) VALUES (2, 0, NOW(6))
ON DUPLICATE KEY UPDATE id = id;
INSERT INTO wallet (member_id, balance, created_at) VALUES (3, 0, NOW(6))
ON DUPLICATE KEY UPDATE id = id;
INSERT INTO wallet (member_id, balance, created_at) VALUES (4, 0, NOW(6))
ON DUPLICATE KEY UPDATE id = id;
INSERT INTO wallet (member_id, balance, created_at) VALUES (5, 0, NOW(6))
ON DUPLICATE KEY UPDATE id = id;
