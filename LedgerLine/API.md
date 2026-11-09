# LedgerLine API reference

Base URL for the local Compose stack: `http://localhost:8080`. All responses use JSON unless the status is `204`. Authenticated requests use `Authorization: Bearer <accessToken>`.

The API returns errors as `{ "error": "…", "message": "…" }`. Common statuses are `400` invalid input, `401` missing/invalid/expired token, `403` insufficient role or account permission, `404` resource/route not found, `409` business/idempotency conflict, `503` database/financial transaction unavailable, and `500` unexpected server failure.

Amounts are positive JSON numbers or strings with at most four fractional digits and at most 15 integer digits; they are stored as MongoDB Decimal128. Responses serialize money as JSON numbers. Timestamps are ISO-8601.

## Authentication

| Method and path | Permission | Request | Response / financial impact |
|---|---|---|---|
| `POST /api/auth/register` | Public | `{ "username": "demo-user", "email": "user@example.test", "fullName": "Demo User", "password": "<12+ characters>" }`. Registration ignores any `role` field and always creates `ACCOUNT_HOLDER`. | `201 { accessToken, tokenType: "Bearer", expiresIn, user: { username, email, fullName, role } }`. No account or financial record is created. Duplicate username/email is `409`. |
| `POST /api/auth/login` | Public | `{ "usernameOrEmail": "demo-user", "password": "…" }` | `200` with the same token/profile shape as registration. No financial impact. Invalid credentials are `401`. |
| `GET /api/auth/me` | Any authenticated role | No body | `200 { username, email, fullName, role }`. No financial impact. |
| `POST /api/auth/logout` | Any authenticated role | No body | `204`, empty body. Increments the user's token version, invalidating issued tokens. |
| `GET /health` | Public | No body | `200 { "status": "UP", "database": "connected" }`, or `503` when MongoDB is not connected. |

## Accounts and statements

`accountId` is the MongoDB ObjectId returned in the account response. Account types are `CHECKING`, `SAVINGS`, `CASH`, and system-only `EXTERNAL_CLEARING`; statuses are `ACTIVE`, `FROZEN`, and `CLOSED`.

| Method and path | Permission | Request | Response / financial impact |
|---|---|---|---|
| `GET /api/accounts` | Authenticated. Account holders see owned accounts; admins see all accounts. | No body | `200` array of `{ id, accountNumber, name, accountType, currency, balance, status }`. Admin records also include `ownerId`, `ownerUsername`, and `createdAt`. |
| `POST /api/accounts` | `ACCOUNT_HOLDER` or `ADMIN` | `{ "name": "Everyday", "accountType": "CHECKING", "currency": "USD" }` | `201` account-summary object. Creates an owned zero-balance account; no transaction/ledger entry. |
| `GET /api/accounts/summary` | `ACCOUNT_HOLDER`, `ADMIN`, or `AUDITOR` | No body | `200` array of `{ currency, totalBalance, accountCount, monthlyDeposits, monthlyDepositCount, monthlyWithdrawals, monthlyWithdrawalCount }`. Holder totals are limited to owned accounts. Read-only. |
| `GET /api/accounts/{accountId}` | Account owner, account permission, `ADMIN`, or `AUDITOR` | No body | `200` account summary. Read-only. Unauthorized ownership/permission is `403`. |
| `GET /api/accounts/{accountId}/balance` | Account owner, account permission, `ADMIN`, or `AUDITOR` | No body | `200 { accountId, accountNumber, currency, balance }`. Read-only. |
| `PATCH /api/accounts/{accountId}` | Owner, account `ACCOUNT_ADMIN`, or global `ADMIN` | `{ "name": "New account name" }`; no other fields allowed | `200` account summary. Changes metadata only; no balance or ledger impact. |
| `GET /api/accounts/{accountId}/statement` | Account owner, account permission, `ADMIN`, or `AUDITOR` | Optional query: `fromDate=YYYY-MM-DD`, `toDate=YYYY-MM-DD`, `transactionType=DEPOSIT\|WITHDRAWAL\|TRANSFER\|ADJUSTMENT` | `200 { accountId, accountNumber, currency, currentBalance, fromDate, toDate, entries: [{ transactionId, transactionDate, transactionType, status, entrySide, amount, currency, description, initiatedByUsername, initiatedByFullName, runningBalance }] }`. Read-only; running balances use full history before filters. |

## Money movement

All three write operations require an `Idempotency-Key` header containing 1–128 printable ASCII characters. A key is scoped to the authenticated user. Same key and same normalized request replays the original response; same key with changed request details returns `409`. Financial writes require an active replica-set MongoDB connection; no non-transactional fallback exists.

| Method and path | Permission | Request | Response / financial impact |
|---|---|---|---|
| `POST /api/accounts/{accountId}/deposits` | `ACCOUNT_HOLDER` owning the account or with account transaction permission | Header `Idempotency-Key: <unique-key>`; body `{ "amount": "25.0000", "description": "Optional, max 500 characters" }` | `201 { transactionId, accountId, accountNumber, transactionType: "DEPOSIT", status: "POSTED", amount, currency, createdAt, postedAt }`. Atomically credits the customer and debits the same-currency clearing account, inserts equal debit/credit ledger entries, and stores the idempotency outcome. |
| `POST /api/accounts/{accountId}/withdrawals` | `ACCOUNT_HOLDER` owning the account or with account transaction permission | Header `Idempotency-Key`; same body as deposit | `201` financial-transaction response. Atomically debits the customer and credits clearing. Insufficient balance or inactive account returns `409`, adds a refusal audit record, and posts no transaction/ledger entries. |
| `POST /api/transfers` | `ACCOUNT_HOLDER` with transaction permission on sender | Header `Idempotency-Key`; body `{ "senderAccountNumber": "…", "receiverAccountNumber": "…", "amount": "10.0000", "description": "Optional" }` | `201 { transactionReference, senderAccountNumber, receiverAccountNumber, amount, currency, status: "POSTED", createdAt, postedAt }`. Atomically debits sender, credits receiver, inserts matching ledger entries, and stores the result. Accounts must be distinct, active, customer accounts with matching currency. |

## Administration

All `/api/admin/**` routes require `ADMIN`. `AUDITOR` and `ACCOUNT_HOLDER` requests are denied.

| Method and path | Request | Response / financial impact |
|---|---|---|
| `GET /api/admin/overview` | No body | `200 { totalRegisteredUsers, totalAccounts, totalTransactions, postedTransactions }`. Read-only counts. |
| `GET /api/admin/accounts` | No body | `200` array of account summaries including owner metadata. Read-only. |
| `GET /api/admin/accounts/{accountId}` | No body | `200` account summary including owner metadata. Read-only. |
| `PATCH /api/admin/accounts/{accountId}` | `{ "name": "Optional new name", "status": "ACTIVE\|FROZEN\|CLOSED" }`; at least one supported field, no others | `200` account summary. Changes account metadata/status and atomically records an audit event; cannot change owner, account number, currency, type, balance, or ledger. |
| `GET /api/admin/audit-logs` | No body | `200` array of at most the latest 100 `{ id, action, entityType, entityId, actorUsername, createdAt, details }`. Read-only. |
| `GET /api/admin/suspicious-transactions` | No body | `200` array of recent refused-operation audit records. Flags are for review, not confirmed fraud. Read-only. |
| `GET /api/admin/ledger-entries` | No body | `200` array of `{ id, transactionId, accountId, entrySide, amount, createdAt }`. Read-only. |

## Auditor and transaction history

Auditor routes require `AUDITOR` and are read-only. No API endpoint supports ledger-entry update or deletion; unsupported mutation paths return `404`.

| Method and path | Permission | Request | Response / financial impact |
|---|---|---|---|
| `GET /api/auditor/accounts` | `AUDITOR` | No body | `200` array of account summaries with owner metadata. Read-only. |
| `GET /api/auditor/audit-logs` | `AUDITOR` | No body | `200` array of at most the latest 100 audit records. Read-only. |
| `GET /api/auditor/ledger-entries` | `AUDITOR` | No body | `200` array of ledger-entry summaries. Read-only. |
| `GET /api/auditor/ledger-integrity` | `AUDITOR` | No body | `200 { totalTransactions, balancedTransactions, unbalancedTransactions: [{ transactionId, debitTotal, creditTotal, entryCount, balanced }] }`. Read-only integrity calculation. |
| `GET /api/transactions` | Any authenticated role | No body | `200` array of `{ transactionId, accountId, transactionType, status, amount, currency, createdAt, postedAt }`; account holders are limited to accounts they own or may access. Read-only, maximum 500 records. |

No endpoint exposes idempotency records, password hashes, user-role mutation, direct balance editing, or posted-ledger mutation.
