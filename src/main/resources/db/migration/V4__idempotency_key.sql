-- S5 취소 재전송 중복의 실습 대상 (docs/plan/S5.md 4.2).
--
-- 컬럼은 여기서 전부 만든다. V5 는 검색 인덱스를 unique 로 바꾸기만 한다 - 1·2차 실패 배선과 본선이 같은 컬럼을
-- 쓰고, V4 와 V5 는 인덱스 유일성만 다르다. 1·2차 재현은 V4 까지만 적용한 별도 DB(payment_v4)에서 돈다.
--
-- 검색 인덱스가 비유일인 것이 1·2차의 전제다. 1차는 중복 키 행이 막히지 않는 것을, 2차는 없는 키를 FOR UPDATE 로
-- 찾을 때 이 인덱스의 갭에 X 갭락이 걸리는 것을 본다.
--
-- idempotency_key 컬럼은 ascii / ascii_bin 이다. 테이블 기본값(utf8mb4, MySQL 8 기본 collation
-- utf8mb4_0900_ai_ci)은 대소문자를 무시하므로, 그대로 두면 unique 가 abc 와 ABC 를 같은 키로 막는다.
-- 허용 문자도 출력 가능한 ASCII 뿐이다 (0x21~0x7E, 쉼표 제외).
--
-- status 는 S5 에서 커밋된 값이 항상 COMPLETED 다 (키 INSERT·취소·결과 저장이 한 트랜잭션). IN_PROGRESS 가
-- 커밋된 채 보이는 것은 처리 중 상태를 분리 커밋하는 S8 부터다.
--
-- 정리 배치는 만들지 않는다. 정리·삭제 경로가 없다는 것이 "unique 위반 뒤 새 트랜잭션에는 행이 반드시 있다"
-- 는 전제의 근거다 (docs/plan/S5.md 4.3).

CREATE TABLE idempotency_key (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    merchant_id     BIGINT      NOT NULL,
    operation       VARCHAR(20) NOT NULL,
    idempotency_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_hash    CHAR(64)    NOT NULL,
    status          VARCHAR(20) NOT NULL,
    response_status INT         NULL,
    response_body   TEXT        NULL,
    created_at      DATETIME(6) NOT NULL,
    completed_at    DATETIME(6) NULL,
    PRIMARY KEY (id),
    KEY idx_idempotency_key_lookup (merchant_id, operation, idempotency_key)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
