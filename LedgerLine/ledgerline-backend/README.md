# LedgerLine API

The backend is Node.js, Express, and MongoDB (Mongoose). It preserves the React application's `/api` endpoints and uses MongoDB replica-set transactions for money movements.

## Setup

1. Use Node.js 20 or newer and a MongoDB replica set. Standalone MongoDB is not sufficient for financial operations.
2. Copy `.env.example` to `.env`; set a strong `JWT_SECRET` (at least 32 characters in production), `MONGO_URI`, `FRONTEND_ORIGIN`, and `PORT`.
3. Install and start:

   ```powershell
   npm ci
   npm run start
   ```

4. Create the initial administrator once using the documented `ADMIN_USERNAME`, `ADMIN_EMAIL`, `ADMIN_FULL_NAME`, and `ADMIN_PASSWORD` environment variables:

   ```powershell
   npm run create-admin
   ```

   The command refuses to bootstrap another administrator if an admin already exists. Never commit credentials or `.env`.

The server creates the configured clearing account at startup. Additional currency-specific clearing accounts are created as needed. Public registration creates only `ACCOUNT_HOLDER` users. Administrators and auditors must be provisioned by trusted operators; there is no public role-escalation API.

## Evaluation accounts

For a disposable local evaluation database only, generate the `demo-admin`, `demo-holder`, and `demo-auditor` users and two holder accounts:

```powershell
$env:ALLOW_DEMO_SEED = "true"
npm run demo:seed
```

Set `NODE_ENV` to a non-production value and configure `MONGO_URI` first. The seeder refuses to run in production or if its local credential file already exists. It generates random passwords and stores them in the ignored `.demo-credentials.json` file; it never prints passwords. The primary holder account is funded through a balanced opening transaction and matching ledger entries.

With the API running, `npm run demo:verify` retries a deposit with one idempotency key and sends two withdrawals in parallel. It exits unsuccessfully if the duplicate does not replay the same transaction or if both withdrawals post.

The root `compose.yaml` keeps demo credentials in a private local Docker volume. This unauthenticated single-node MongoDB stack is for evaluation only, not production.

## API access

- Authentication: `POST /api/auth/register`, `POST /api/auth/login`, `GET /api/auth/me`, and `POST /api/auth/logout`.
- Accounts and statements: `/api/accounts`; financial changes require an account-holder token and an `Idempotency-Key` header.
- Transfers: `POST /api/transfers`, also requiring `Idempotency-Key`.
- Admin: `/api/admin/overview`, `/api/admin/accounts`, `/api/admin/audit-logs`, and `/api/admin/suspicious-transactions`.
- Auditor: read-only `/api/auditor/accounts`, `/api/auditor/audit-logs`, `/api/auditor/ledger-entries`, and `/api/auditor/ledger-integrity`.
- Health: `GET /health`.

Money operations, account balances, transaction records, ledger entries, and idempotency results are committed atomically. Reusing an idempotency key with changed request details returns a conflict. Ledger entries have no application update or delete endpoint and are guarded against Mongoose mutation methods; corrections require a new reversing posting.

## Tests

`npm test` runs HTTP integration tests against an ephemeral, real MongoDB replica set, including concurrent withdrawals/transfers and persisted balance, transaction, and ledger checks. The first run may download a MongoDB binary for `mongodb-memory-server`.
