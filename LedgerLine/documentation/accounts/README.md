# Account and ledger API

The Express API listens on `http://localhost:8080` by default. Endpoints require a bearer token from the [authentication API](../authentication/README.md), except `GET /health` and public authentication routes.

## Endpoints

| Method | Path | Access | Behavior |
|---|---|---|---|
| `POST` | `/api/accounts` | Account holder or admin | Create a zero-balance account owned by the authenticated user. |
| `GET` | `/api/accounts` | Authenticated | List the caller's accounts; administrators can use `/api/admin/accounts`, auditors `/api/auditor/accounts`. |
| `GET` | `/api/accounts/summary` | Account holder, admin, auditor | Return current customer balances and current-UTC-month deposit/withdrawal totals grouped by currency. |
| `GET` | `/api/accounts/{accountId}` | Owner, account permission, admin, auditor | Read an account after server-side authorization. |
| `GET` | `/api/accounts/{accountId}/balance` | Owner, account permission, admin, auditor | Read its balance and currency. |
| `GET` | `/api/accounts/{accountId}/statement` | Owner, account permission, admin, auditor | Read ledger entries and running balances; optional date/type filters. |
| `PATCH` | `/api/accounts/{accountId}` | Owner or account `ACCOUNT_ADMIN` | Change account name only. |
| `POST` | `/api/accounts/{accountId}/deposits` | Account holder with transaction access | Deposit; requires `Idempotency-Key`. |
| `POST` | `/api/accounts/{accountId}/withdrawals` | Account holder with transaction access | Withdraw; requires `Idempotency-Key`. |
| `POST` | `/api/transfers` | Account holder authorized on sender | Transfer between distinct, active, same-currency customer accounts; requires `Idempotency-Key`. |
| `GET` | `/api/transactions` | Authenticated | View transactions available to the role. |
| `GET` | `/api/admin/overview` | Admin | Count registered users, accounts, and transactions. |
| `GET` | `/api/admin/accounts` | Admin | List all accounts with owner details. |
| `GET` | `/api/admin/accounts/{accountId}` | Admin | Read an account with owner details. |
| `PATCH` | `/api/admin/accounts/{accountId}` | Admin | Update account name and/or status. The change is audited. |
| `GET` | `/api/admin/audit-logs` | Admin | Read recent audit records. |
| `GET` | `/api/admin/suspicious-transactions` | Admin | Review refused financial-operation attempts; these are flags, not fraud findings. |
| `GET` | `/api/admin/ledger-entries` | Admin | Read ledger entries. |
| `GET` | `/api/auditor/accounts` | Auditor | Read all accounts. |
| `GET` | `/api/auditor/audit-logs` | Auditor | Read recent audit records. |
| `GET` | `/api/auditor/ledger-entries` | Auditor | Read debit and credit entries. |
| `GET` | `/api/auditor/ledger-integrity` | Auditor | Verify per-transaction debit/credit totals. |

Admin and auditor actions are checked by the backend independently of frontend route restrictions. Auditors cannot create accounts or perform any account, balance, transaction, or ledger mutations. Ledger entries have no update/delete HTTP endpoint and are guarded against Mongoose mutation methods.

## Accounts and money

Account creation accepts `name`, `accountType` (`CHECKING`, `SAVINGS`, or `CASH`), and a three-letter currency. The owner is derived from the authenticated user; account number, balance, and role are never accepted from the request. Balances and transaction amounts are stored as MongoDB Decimal128 and amounts are limited to four fractional digits.

Each currency uses a currency-matched external clearing account. Deposits, withdrawals, and transfers atomically persist the balances, posted transaction, debit and credit ledger entries, and idempotency outcome in a MongoDB transaction. MongoDB must run as a replica set; the API does not fall back to non-transactional financial writes.

All financial operations require an `Idempotency-Key` of 1–128 printable ASCII characters. The key is scoped to the caller. Repeating the same request returns its original result; reusing the key with different request details returns `409 Conflict`. Insufficient-balance and inactive-account refusals are persisted and replayed for the same key. Unexpected failures roll back the key and financial writes together.

Transfers accept `senderAccountNumber`, `receiverAccountNumber`, `amount`, and an optional `description`. Currency mismatches, same-account transfers, inactive accounts, and insufficient funds are rejected. The response includes a `transactionReference`.

## Statements and ledger integrity

`GET /api/accounts/{accountId}/statement` returns one row per ledger entry, including side, amount, transaction ID/type/status/date, description, initiator, currency, and running balance. The response includes the account's current balance. Running balances are calculated over the full history before applying filters.

Optional query parameters are `fromDate` and `toDate` in inclusive UTC `YYYY-MM-DD` form and `transactionType` (`DEPOSIT`, `WITHDRAWAL`, `TRANSFER`, or `ADJUSTMENT`). If both dates are supplied, `fromDate` must not follow `toDate`.

The auditor integrity endpoint reports transactions with equal, non-zero debit and credit totals, and identifies missing or mismatched postings. Admin account status changes require explicit confirmation in the frontend and are independently authorized and audited by the backend.
