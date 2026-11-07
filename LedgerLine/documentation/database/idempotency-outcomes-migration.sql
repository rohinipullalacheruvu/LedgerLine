USE ledgerline;

ALTER TABLE idempotency_records
    ADD COLUMN failure_code VARCHAR(64) NULL AFTER transaction_id,
    ADD COLUMN failure_message VARCHAR(500) NULL AFTER failure_code,
    ADD CONSTRAINT ck_idempotency_result CHECK (
        (status = 'PROCESSING' AND transaction_id IS NULL
            AND failure_code IS NULL AND failure_message IS NULL)
        OR (status = 'COMPLETED' AND transaction_id IS NOT NULL
            AND failure_code IS NULL AND failure_message IS NULL)
        OR (status = 'FAILED' AND transaction_id IS NULL
            AND failure_code IS NOT NULL AND failure_message IS NOT NULL)
    );
