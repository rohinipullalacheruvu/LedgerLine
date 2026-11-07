USE ledgerline;

ALTER TABLE accounts
    MODIFY owner_user_id BIGINT NULL,
    DROP CHECK ck_accounts_type,
    DROP CHECK ck_accounts_balance,
    ADD CONSTRAINT ck_accounts_type CHECK (
        account_type IN ('CHECKING', 'SAVINGS', 'CASH', 'EXTERNAL_CLEARING')
    ),
    ADD CONSTRAINT ck_accounts_balance CHECK (
        account_type = 'EXTERNAL_CLEARING' OR balance >= 0
    ),
    ADD CONSTRAINT ck_accounts_owner_type CHECK (
        (account_type = 'EXTERNAL_CLEARING' AND owner_user_id IS NULL)
        OR (account_type <> 'EXTERNAL_CLEARING' AND owner_user_id IS NOT NULL)
    );

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
