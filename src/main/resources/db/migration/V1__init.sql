-- Phase 0 초기 스키마.
--
-- 의도적으로 만들지 않는 것 (각각 나중 시나리오의 실습 대상):
--   * FK 전부            : 원장 INSERT 의 FK 검사 락이 S1/S2 의 차감 유실 관찰에 섞이는 것을 막는다.
--                          wallet_ledger.wallet_id -> wallet.id 는 S4 에서 처음 추가한다.
--   * payment(merchant_id), payment(order_id), payment(merchant_id, order_id)
--                        : S6 의 인덱스 부재 실험이 사라지지 않게 한다.
--   * settlement(merchant_id, settlement_date) unique : S10 의 대상.
--
-- 금액 컬럼은 전부 signed BIGINT(원 단위 정수)다. UNSIGNED 와 CHECK (balance >= 0) 류 제약은 금지한다.
-- S2 비교 실험 1 의 잔액 -2,000 이 관찰 대상이기 때문이다. MySQL 8.0.16+ 는 CHECK 를 실제로 강제하므로
-- 제약을 걸면 그 시점에 SQL 에러가 나서 실험 자체가 사라진다. 음수는 DB 가 아니라 Invariants 가 검출한다.

CREATE TABLE merchant (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    name       VARCHAR(100) NOT NULL,
    api_key    VARCHAR(64)  NOT NULL,
    created_at DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_merchant_api_key (api_key)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE payment (
    id               BIGINT      NOT NULL AUTO_INCREMENT,
    payment_key      VARCHAR(64) NOT NULL,
    order_id         VARCHAR(64) NOT NULL,
    merchant_id      BIGINT      NOT NULL,
    wallet_id        BIGINT      NULL,
    method           VARCHAR(20) NOT NULL,
    amount           BIGINT      NOT NULL,
    balance_amount   BIGINT      NOT NULL,
    status           VARCHAR(20) NOT NULL,
    approved_at      DATETIME(6) NULL,
    card_approval_no VARCHAR(64) NULL,
    created_at       DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_payment_payment_key (payment_key)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE payment_cancel (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    payment_id    BIGINT       NOT NULL,
    cancel_amount BIGINT       NOT NULL,
    reason        VARCHAR(255) NULL,
    canceled_at   DATETIME(6)  NOT NULL,
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE wallet (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    member_id  BIGINT      NOT NULL,
    balance    BIGINT      NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_wallet_member_id (member_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE wallet_ledger (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    wallet_id  BIGINT      NOT NULL,
    type       VARCHAR(20) NOT NULL,
    amount     BIGINT      NOT NULL,
    payment_id BIGINT      NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE settlement (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    merchant_id     BIGINT      NOT NULL,
    settlement_date DATE        NOT NULL,
    total_amount    BIGINT      NOT NULL,
    fee             BIGINT      NOT NULL,
    status          VARCHAR(20) NOT NULL,
    created_at      DATETIME(6) NOT NULL,
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
