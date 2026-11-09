# LedgerLine database design

The active backend stores data in MongoDB through Mongoose. Configure `MONGO_URI` to point to a MongoDB replica set; standalone MongoDB does not support the multi-document transactions required for financial writes. The backend creates its indexes during startup, including the unique user/idempotency-key constraint.

The SQL scripts in this directory document the former MySQL implementation. They are retained as historical artifacts and are not used by the Node.js backend.

## Collections

- **`users`** stores unique usernames/emails, bcrypt password hashes, global role, enabled state, and token version. Public registration only creates `ACCOUNT_HOLDER` users.
- **`accounts`** stores owner, account number, type, ISO currency, Decimal128 balance, status, and version. Customer balances cannot become negative. Unowned `EXTERNAL_CLEARING` accounts hold the signed settlement position.
- **`accountpermissions`** stores account-scoped `ACCOUNT_ADMIN`, `TRANSACT`, or `VIEW` permissions.
- **`transactions`** stores the initiator, source/destination accounts, transaction type/status, positive Decimal128 amount, currency, description, and posting time.
- **`ledgerentries`** stores immutable debit/credit rows with Decimal128 amounts. Each posted movement inserts a debit and credit in the same currency.
- **`idempotencyrecords`** stores a user-scoped unique key, SHA-256 request hash, outcome, and associated transaction. Reusing a key with changed request details is rejected.
- **`auditlogs`** stores actor, action, entity reference, details, and timestamp. Refused financial operations and admin account updates are recorded for review.

## Posting and consistency

The service validates amounts as positive values with at most four fractional digits and stores them as MongoDB Decimal128 (up to 15 integer digits). It does not use JavaScript floating-point arithmetic for balance changes.

Deposits and withdrawals post against a currency-matched external clearing account. Transfers require two distinct active customer accounts with the same currency. For all financial requests, the database transaction atomically writes account balances, the posted transaction, its debit/credit ledger entries, and the idempotency outcome. A failed operation rolls all those writes back. Concurrent writes to the same accounts conflict and are retried by MongoDB transaction handling; no process-local lock is relied on.

Ledger-entry Mongoose schemas reject document saves, query updates/deletes, and bulk writes after posting. There are no HTTP methods for editing or deleting ledger entries; corrections must use a reversing posting.

## Replica-set integration tests

Run `npm test` from `ledgerline-backend`. The suite starts an ephemeral real MongoDB replica set and issues concurrent authenticated HTTP requests. It verifies conservation, equal debit/credit postings, nonnegative balances, duplicate and changed idempotency keys, competing withdrawals and transfers, account authorization, transaction rollback, auditor/admin access, and ledger immutability. It checks persisted account balances and transaction/ledger records after the requests. The first run may download a MongoDB test binary.

For regular deployments, set a private `JWT_SECRET`, configure `MONGO_URI` with replica-set membership, and do not expose the MongoDB port publicly. See the [backend setup guide](../../ledgerline-backend/README.md).
