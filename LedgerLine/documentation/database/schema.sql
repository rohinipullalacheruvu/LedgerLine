CREATE DATABASE IF NOT EXISTS ledgerline
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_0900_ai_ci;

USE ledgerline;

CREATE TABLE IF NOT EXISTS users (
    id BIGINT NOT NULL AUTO_INCREMENT,
    username VARCHAR(50) NOT NULL,
    email VARCHAR(254) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    full_name VARCHAR(120) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    role VARCHAR(20) NOT NULL DEFAULT 'ACCOUNT_HOLDER',
    token_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uq_users_username UNIQUE (username),
    CONSTRAINT uq_users_email UNIQUE (email),
    CONSTRAINT ck_users_role CHECK (role IN ('ADMIN', 'ACCOUNT_HOLDER', 'AUDITOR'))
) ENGINE = InnoDB;

CREATE TABLE IF NOT EXISTS accounts (
    id BIGINT NOT NULL AUTO_INCREMENT,
    owner_user_id BIGINT NULL,
    account_number VARCHAR(34) NOT NULL,
    name VARCHAR(100) NOT NULL,
    account_type VARCHAR(20) NOT NULL,
    currency CHAR(3) NOT NULL,
    balance DECIMAL(19, 4) NOT NULL DEFAULT 0.0000,
    version BIGINT NOT NULL DEFAULT 0,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uq_accounts_account_number UNIQUE (account_number),
    CONSTRAINT fk_accounts_owner FOREIGN KEY (owner_user_id) REFERENCES users (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_accounts_type CHECK (
        account_type IN ('CHECKING', 'SAVINGS', 'CASH', 'EXTERNAL_CLEARING')
    ),
    CONSTRAINT ck_accounts_status CHECK (status IN ('ACTIVE', 'FROZEN', 'CLOSED')),
    CONSTRAINT ck_accounts_balance CHECK (
        account_type = 'EXTERNAL_CLEARING' OR balance >= 0
    ),
    CONSTRAINT ck_accounts_owner_type CHECK (
        (account_type = 'EXTERNAL_CLEARING' AND owner_user_id IS NULL)
        OR (account_type <> 'EXTERNAL_CLEARING' AND owner_user_id IS NOT NULL)
    )
) ENGINE = InnoDB;

CREATE INDEX ix_accounts_owner ON accounts (owner_user_id);

INSERT INTO accounts (
    owner_user_id,
    account_number,
    name,
    account_type,
    currency,
    balance,
    version,
    status
)
SELECT
    NULL,
    'LL-CLEARING-USD',
    'External Clearing (USD)',
    'EXTERNAL_CLEARING',
    'USD',
    0.0000,
    0,
    'ACTIVE'
WHERE NOT EXISTS (
    SELECT 1 FROM accounts WHERE account_number = 'LL-CLEARING-USD'
);

CREATE TABLE IF NOT EXISTS account_permissions (
    id BIGINT NOT NULL AUTO_INCREMENT,
    account_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    role VARCHAR(20) NOT NULL,
    granted_by_user_id BIGINT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uq_account_permissions_account_user UNIQUE (account_id, user_id),
    CONSTRAINT fk_account_permissions_account FOREIGN KEY (account_id) REFERENCES accounts (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_account_permissions_user FOREIGN KEY (user_id) REFERENCES users (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_account_permissions_granted_by FOREIGN KEY (granted_by_user_id) REFERENCES users (id)
        ON DELETE SET NULL ON UPDATE RESTRICT,
    CONSTRAINT ck_account_permissions_role CHECK (role IN ('ACCOUNT_ADMIN', 'TRANSACT', 'VIEW'))
) ENGINE = InnoDB;

CREATE INDEX ix_account_permissions_user ON account_permissions (user_id);

CREATE TABLE IF NOT EXISTS transactions (
    id BIGINT NOT NULL AUTO_INCREMENT,
    initiated_by_user_id BIGINT NOT NULL,
    source_account_id BIGINT NULL,
    destination_account_id BIGINT NULL,
    transaction_type VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    amount DECIMAL(19, 4) NOT NULL,
    currency CHAR(3) NOT NULL,
    description VARCHAR(500) NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    posted_at TIMESTAMP(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_transactions_initiator FOREIGN KEY (initiated_by_user_id) REFERENCES users (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_transactions_source_account FOREIGN KEY (source_account_id) REFERENCES accounts (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_transactions_destination_account FOREIGN KEY (destination_account_id) REFERENCES accounts (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_transactions_type CHECK (
        transaction_type IN ('TRANSFER', 'DEPOSIT', 'WITHDRAWAL', 'ADJUSTMENT')
    ),
    CONSTRAINT ck_transactions_status CHECK (
        status IN ('PENDING', 'POSTED', 'FAILED', 'REVERSED')
    ),
    CONSTRAINT ck_transactions_amount CHECK (amount > 0),
    CONSTRAINT ck_transactions_distinct_accounts CHECK (
        source_account_id IS NULL
        OR destination_account_id IS NULL
        OR source_account_id <> destination_account_id
    )
) ENGINE = InnoDB;

CREATE INDEX ix_transactions_initiator_created ON transactions (initiated_by_user_id, created_at);
CREATE INDEX ix_transactions_source_created ON transactions (source_account_id, created_at);
CREATE INDEX ix_transactions_destination_created ON transactions (destination_account_id, created_at);

CREATE TABLE IF NOT EXISTS ledger_entries (
    id BIGINT NOT NULL AUTO_INCREMENT,
    transaction_id BIGINT NOT NULL,
    account_id BIGINT NOT NULL,
    entry_side VARCHAR(10) NOT NULL,
    amount DECIMAL(19, 4) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_ledger_entries_transaction FOREIGN KEY (transaction_id) REFERENCES transactions (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_ledger_entries_account FOREIGN KEY (account_id) REFERENCES accounts (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_ledger_entries_side CHECK (entry_side IN ('DEBIT', 'CREDIT')),
    CONSTRAINT ck_ledger_entries_amount CHECK (amount > 0)
) ENGINE = InnoDB;

CREATE INDEX ix_ledger_entries_transaction ON ledger_entries (transaction_id);
CREATE INDEX ix_ledger_entries_account_created ON ledger_entries (account_id, created_at);

CREATE TABLE IF NOT EXISTS idempotency_records (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    idempotency_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PROCESSING',
    transaction_id BIGINT NULL,
    failure_code VARCHAR(64) NULL,
    failure_message VARCHAR(500) NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    expires_at TIMESTAMP(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_idempotency_user_key UNIQUE (user_id, idempotency_key),
    CONSTRAINT uq_idempotency_transaction UNIQUE (transaction_id),
    CONSTRAINT fk_idempotency_user FOREIGN KEY (user_id) REFERENCES users (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_idempotency_transaction FOREIGN KEY (transaction_id) REFERENCES transactions (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_idempotency_status CHECK (status IN ('PROCESSING', 'COMPLETED', 'FAILED')),
    CONSTRAINT ck_idempotency_result CHECK (
        (status = 'PROCESSING' AND transaction_id IS NULL
            AND failure_code IS NULL AND failure_message IS NULL)
        OR (status = 'COMPLETED' AND transaction_id IS NOT NULL
            AND failure_code IS NULL AND failure_message IS NULL)
        OR (status = 'FAILED' AND transaction_id IS NULL
            AND failure_code IS NOT NULL AND failure_message IS NOT NULL)
    )
) ENGINE = InnoDB;

CREATE INDEX ix_idempotency_expires_at ON idempotency_records (expires_at);

CREATE TABLE IF NOT EXISTS audit_logs (
    id BIGINT NOT NULL AUTO_INCREMENT,
    actor_user_id BIGINT NULL,
    action VARCHAR(64) NOT NULL,
    entity_type VARCHAR(64) NOT NULL,
    entity_id BIGINT NULL,
    details JSON NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_audit_logs_actor FOREIGN KEY (actor_user_id) REFERENCES users (id)
        ON DELETE SET NULL ON UPDATE RESTRICT
) ENGINE = InnoDB;

CREATE INDEX ix_audit_logs_entity ON audit_logs (entity_type, entity_id, created_at);
CREATE INDEX ix_audit_logs_actor_created ON audit_logs (actor_user_id, created_at);
