# LedgerLine

LedgerLine is a full-stack banking ledger demonstration built around traceable, balanced financial postings. Account holders manage accounts and move funds; administrators oversee operations; auditors inspect statements and ledger integrity without write access.

## Problem statement

Financial interfaces often make it difficult to trace how a balance changed or to distinguish a committed operation from a retried request. Concurrent requests can also race against the same balance. LedgerLine addresses these problems with transaction-linked debit/credit entries, atomic balance updates, retry-safe idempotency, and role-aware audit views.

## Objectives

- Keep account balances consistent with posted transaction and ledger records.
- Preserve money across internal transfers and prevent concurrent overdrafts.
- Make retried money requests safe using user-scoped idempotency keys.
- Restrict account and administrative access by role and server-side authorization.
- Give auditors read-only statements and debit/credit integrity checks.
- Provide repeatable replica-set-backed integration tests and a local evaluation setup.

## Features

- JWT authentication; public registration creates account holders only.
- Account creation, account summaries, status management, and account statements.
- Idempotent deposits, withdrawals, and internal transfers.
- Atomic MongoDB transaction writes for balances, transactions, ledger entries, and idempotency outcomes.
- Decimal.js arithmetic and MongoDB Decimal128 persistence with four fractional digits.
- Admin overview, account management, audit records, and refused-operation review flags.
- Read-only auditor account/history access and balanced-ledger verification.
- Ledger-entry mutation routes are absent; schema hooks reject Mongoose update/delete operations.
- Parallel-request tests use a real ephemeral MongoDB replica set.

## Technology stack

| Layer | Technology |
|---|---|
| Frontend | React 19, Vite, JavaScript, Axios, React Router |
| API | Node.js 20+, Express 5, Mongoose 8 |
| Database | MongoDB replica set; Decimal128 for persisted monetary values |
| Auth | bcryptjs and JWT bearer tokens |
| Tests | Node.js test runner, Supertest, MongoDB Memory Server replica set |
| Local deployment | Docker Compose, Nginx |

MongoDB is the backend database. The legacy MySQL schema files, if present under `documentation/database/`, are historical reference only and are not used by the Express service.

## System architecture

```text
Browser (React/Vite or static Nginx)
           |
           | HTTPS in production; same-origin /api proxy in Compose
           v
Express API (JWT auth, role checks, validation)
           |
           | Mongoose sessions / multi-document transactions
           v
MongoDB replica set
  users | accounts | transactions | ledger entries
  permissions | idempotency records | audit logs
```

In the Compose evaluation stack, Nginx serves the frontend and proxies `/api` and `/health` to Express. The local MongoDB container is a single-node replica set to enable transactions during evaluation; it is not a production database topology.

## Database structure

| Collection | Purpose |
|---|---|
| `users` | Unique login identity, bcrypt hash, role, enabled state, token version. |
| `accounts` | Owner, account number/type/currency, Decimal128 balance, status, optimistic version. External clearing accounts have no owner and may have signed balances. |
| `accountpermissions` | Account-scoped `ACCOUNT_ADMIN`, `TRANSACT`, and `VIEW` permissions. |
| `transactions` | Initiator, source/destination, type/status, amount/currency, description, posting timestamp. |
| `ledgerentries` | Immutable debit/credit lines linked to transaction and account. |
| `idempotencyrecords` | Unique `(userId, idempotencyKey)`, normalized request hash, outcome, and transaction reference. |
| `auditlogs` | Actor, action, entity reference, structured details, timestamp. |

A successful money movement writes the associated account balance(s), one posted transaction, equal debit and credit ledger entries, and the idempotency result atomically. See [database design](./documentation/database/README.md) for invariants and residual risks.

## Installation

### Requirements

- Node.js 20.19+ or 22.12+ and npm (Node 22 LTS is recommended).
- Docker Desktop with Docker Compose for the one-command evaluation setup; or MongoDB configured as a replica set.
- A private JWT signing secret of at least 32 characters.

### Local evaluation stack

From PowerShell at the repository root:

```powershell
Copy-Item .env.example .env
node -e "console.log(require('node:crypto').randomBytes(48).toString('base64url'))"
```

Paste the generated value into the ignored `.env` as `JWT_SECRET`, then:

```powershell
docker compose up --build -d
docker compose ps
```

The local frontend is at `http://localhost:5173`, the API at `http://localhost:8080`, and health at `http://localhost:8080/health`. MongoDB is not published to the host. This Compose configuration has no database authentication and is for local evaluation only.

### Run without Docker

Configure `MONGO_URI` to a reachable MongoDB replica set, plus `JWT_SECRET`, `PORT` (optional), and `FRONTEND_ORIGIN`. Then in separate PowerShell terminals:

```powershell
cd .\ledgerline-backend
npm ci
npm run start
```

```powershell
cd .\ledgerline-frontend
npm ci
npm run dev
```

For production, use an authenticated TLS MongoDB replica set and inject secrets through the platform's secret manager. Do not copy the unauthenticated evaluation database configuration into production. See [deployment guidance](./DEPLOYMENT.md).

## Seeded demo credentials

Use the local-only demo seeder to create:

| Role | Username |
|---|---|
| Administrator | `demo-admin` |
| Account holder | `demo-holder` |
| Auditor | `demo-auditor` |

The seeder generates unique, high-entropy passwords and stores them only in the ignored `ledgerline-backend/.demo-credentials.json` file (or a private Compose volume). The account holder receives two USD accounts; the primary opens with a balanced, ledger-recorded USD 1,000 deposit. Read the generated file locally to sign in. Passwords are intentionally not included in this repository. Demo seeding requires `ALLOW_DEMO_SEED=true` and refuses to run with `NODE_ENV=production`.

Instructions for seeding and the timed demonstration are in [DEMO.md](./DEMO.md).

## Execution and tests

With Docker Compose:

```powershell
docker compose up --build -d
docker compose exec -e NODE_ENV=development -e ALLOW_DEMO_SEED=true backend npm run demo:seed
docker compose exec backend npm run demo:verify
```

Without Docker, from `ledgerline-backend` set `MONGO_URI`, `JWT_SECRET`, `NODE_ENV=development`, and `ALLOW_DEMO_SEED=true` before running `npm run demo:seed`; start the API and UI as above.

Run automated tests:

```powershell
cd .\ledgerline-backend
npm ci
npm test
```

Tests start a real ephemeral MongoDB replica set and exercise authenticated HTTP requests, concurrent money movement, persisted balances, ledger rows, idempotency, authorization, and rollback. Initial test setup may download the MongoDB test binary.

## API documentation

The complete route reference, permissions, request bodies, response shapes, error statuses, and financial effects are in [API.md](./API.md). API paths include:

- Authentication: `/api/auth/*`
- Accounts and statements: `/api/accounts/*`
- Money movement: `/api/accounts/{accountId}/deposits`, `/withdrawals`, and `/api/transfers`
- Admin governance: `/api/admin/*`
- Auditor read-only views: `/api/auditor/*`
- Transaction history: `/api/transactions`
- Health: `/health`

## Deployment and submission status

Dockerfiles and `compose.yaml` prepare a reproducible **local evaluation** stack. No public deployment URL is configured or claimed. A clean GitHub-clone verification has not been completed in this environment; Docker is unavailable here and the worktree contains uncommitted changes. Verify the exact submitted commit from a fresh clone before presenting it as reproducible.

Before publishing, review `git status`, run `npm audit` in both packages, ensure `.env` and generated demo credentials remain ignored, and publish only after verifying a clean checkout. See [THREATS.md](./THREATS.md) for mitigations and accepted risks and [PROMPTS.md](./PROMPTS.md) for the dated AI-development log and commit-link status.
