# Account management API

Import `postman-collection.json` into Postman. Set `baseUrl` to `http://localhost:8080`. Register or log in an account holder with the authentication collection, then copy its JWT into `accessToken`. Account IDs created by the collection are stored in `accountId`.

## Endpoints

| Method | Path | Access | Behavior |
|---|---|---|---|
| `POST` | `/api/accounts` | Account holder or admin | Create an account owned by the authenticated user; balance starts at zero. |
| `GET` | `/api/accounts` | Authenticated | List accounts owned by the authenticated user only. |
| `GET` | `/api/accounts/summary` | Account holder, admin, or auditor | Get backend-calculated customer balances and current-UTC-month deposit/withdrawal totals grouped by currency. |
| `GET` | `/api/accounts/{accountId}` | Owner, account permission, admin, or auditor | Get account details after server-side authorization. |
| `GET` | `/api/accounts/{accountId}/balance` | Owner, account permission, admin, or auditor | Read balance as a decimal with account currency. |
| `GET` | `/api/accounts/{accountId}/statement` | Owner, account permission, admin, or auditor | Read immutable ledger entries and running balances; optional date/type filters. |
| `PATCH` | `/api/accounts/{accountId}` | Owner, account `ACCOUNT_ADMIN`, or global admin | Change account name only. |
| `POST` | `/api/accounts/{accountId}/deposits` | Account holder with transaction permission | Requires `Idempotency-Key`; deposit funds and post balanced ledger entries. |
| `POST` | `/api/accounts/{accountId}/withdrawals` | Account holder with transaction permission | Requires `Idempotency-Key`; withdraw available funds, auditing refusals. |
| `POST` | `/api/transfers` | Account holder with transaction permission on the sender | Requires `Idempotency-Key`; atomically transfer funds between active customer accounts. |
| `GET` | `/api/admin/accounts` | Admin | List every account with owner metadata. |
| `GET` | `/api/admin/accounts/{accountId}` | Admin | Get account and owner details. |
| `PATCH` | `/api/admin/accounts/{accountId}` | Admin | Change name and/or status (`ACTIVE`, `FROZEN`, or `CLOSED`). |
| `GET` | `/api/auditor/accounts` | Auditor or admin | Read all accounts for audit; no mutation. |

Creation input accepts only `name`, `accountType` (`CHECKING`, `SAVINGS`, or `CASH`), and a three-letter currency code. The service derives the owner from the authenticated principal, creates an `LL`-prefixed UUID account number, and initializes a `BigDecimal` balance to `0.0000`. The unique database constraint on `account_number` is authoritative.

Owner updates can only change the account name. Admin updates can change name and status. Account number, owner, account type, currency, and balance are never accepted as update fields.

Every deposit, withdrawal, and transfer requires an `Idempotency-Key` header: 1–128 printable ASCII characters. The key is scoped to the authenticated user. The server stores its SHA-256 request hash and the resulting transaction or replayable business failure in `idempotency_records`. Repeating a completed request with the same key and payload returns the original outcome; reusing the key with different request details returns `409 Conflict`. Keep the same key when retrying after a timeout. Do not generate a new key until initiating a new operation.

The idempotency claim, transaction record, ledger entries, balance updates, and outcome commit in one database transaction. A unique constraint on `(user_id, idempotency_key)` arbitrates simultaneous retries: one request posts the operation; contenders wait on the unique index and then replay its result. A successful response is associated with its transaction. Insufficient-balance and inactive-account refusals persist a `FAILED` record and replay the same `409` outcome for the same key and payload. Invalid requests, authorization failures, and unexpected database failures do not commit a claim; financial writes and the key roll back together. Keys currently have no automatic expiry and must not be intentionally reused.

Deposit/withdrawal JSON accepts `amount` and an optional `description`; amounts must be positive and fit `DECIMAL(19,4)`. Currency comes from the account; the API does not accept a client-supplied owner or currency. Configure the external clearing account as `CLEARING_ACCOUNT_NUMBER` (default `LL-CLEARING-USD`). Fresh databases created with `../database/schema.sql` already provision this required account; apply `../database/money-movement-migration.sql` once for databases from the earlier schema.

Transfer JSON accepts `senderAccountNumber`, `receiverAccountNumber`, `amount`, and an optional `description`. Both accounts must exist, be active customer accounts in the same currency, and be distinct. Authorization is checked against the authenticated user for the sender; the receiver does not require the sender to own it. The API never accepts account IDs or user IDs for a transfer.

Transfers lock both account rows with a pessimistic write lock in ascending account-ID order. The sender balance is checked after both locks are held. One posted transaction, a debit ledger entry for the sender, a matching credit entry for the receiver, and both balance updates commit together or roll back together. The response includes a unique `transactionReference` (`LLTX-` plus the generated transaction ID). Insufficient-balance attempts write a `TRANSFER_REFUSED` audit log separately and create no transaction or ledger posting. Internal transfers conserve value: the sender debit equals the receiver credit.

Customer balances cannot go negative. The external clearing account is a non-spendable system counter-account with a signed settlement position. It is not a customer account and is never eligible for customer balance operations.

## Account ledger statement

`GET /api/accounts/{accountId}/statement` returns one row per account ledger entry, ordered by posting time. Each row includes debit/credit side, amount, transaction ID/type/status/date, description, initiating user's username and display name, currency, and the resulting running balance. The response includes account number, currency, and current balance. Running balances are calculated over the full history before applying filters, so a filtered statement preserves the original balances.

Optional query parameters: `fromDate` and `toDate` in ISO `YYYY-MM-DD` form (inclusive UTC days), and `transactionType` (`DEPOSIT`, `WITHDRAWAL`, `TRANSFER`, or `ADJUSTMENT`). If both dates are supplied, `fromDate` must not be after `toDate`. Account authorization is checked server-side; account holders need ownership or a view permission, while admins and auditors have read-only access. The endpoint is GET-only. No API is provided to update/delete posted ledger entries; corrections must use new compensating transactions and append-only postings.

`GET /api/accounts/summary` calculates balances and posted deposit/withdrawal totals on the backend, grouped by currency. Account holders see their own customer-owned accounts; admins and auditors see customer-owned accounts across the system. External clearing accounts are excluded, currencies are not combined, and the monthly period uses UTC calendar-month boundaries.

Refused money operations do not create ledger entries. Insufficient-balance and inactive-account refusals are recorded in `audit_logs` by the money movement services.

Errors use the common API error format: `400` for invalid input, an invalid statement date range, missing/invalid `Idempotency-Key`, or an invalid transfer (same account or currency mismatch), `401` for missing/invalid authentication, `403` for insufficient account access, `404` for a missing account, `409` for an account-number collision, inactive account, insufficient balance, or key reuse with a different payload, and `503` if the clearing account is missing or misconfigured.

## Postman test flow

1. Register/login an account holder and set its token as `accessToken`.
2. Run **Create account** and **Create transfer receiver account**. The collection stores both account numbers.
3. Run **List my accounts**, **Get account details**, **Get account balance**, **Get account statement**, and **Rename own account**.
4. Run **Deposit**, **Withdraw**, and **Transfer between own accounts**. Postman supplies a unique `Idempotency-Key`; preserve and reuse the exact key and request body to simulate a network retry. A replay returns the same transaction reference without moving money again.
5. Run **Insufficient withdrawal is refused**. Verify the response is `409` and no balance is changed.
6. To test admin endpoints, provision a separate trusted user as `ADMIN`, log in, and set its token as `adminToken`. Run the admin list/detail/freeze requests.
7. To test auditor reads, provision a trusted user as `AUDITOR`, log in, and set its token as `auditorToken`.
8. To verify access denial, register/login a second account holder and set its token as `otherHolderToken`. Run **Security - another holder cannot read this account** with the first holder's account ID; it must return `403`. Also try balance and rename with that token; both must be denied.

Admin and auditor roles must be assigned through a trusted operator process, never registration. Do not store real credentials in exported collections.

## Transfer execution flow

1. Validate the request fields and resolve the sender account number.
2. Verify the authenticated account holder owns the sender account or has an account-level transaction role.
3. Acquire pessimistic write locks for sender and receiver in ascending database account-ID order.
4. Under the locks, confirm both are active customer accounts in the same currency, then check sender funds.
5. Persist the posted transfer transaction and its equal debit/credit ledger entries.
6. Debit the sender and credit the receiver, then flush the updates. The database transaction commits all changes together; any failure rolls all of them back.
