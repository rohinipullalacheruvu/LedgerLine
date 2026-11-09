# Authentication API

The Node.js/Express backend uses bcryptjs password hashes and signed JWT bearer tokens. Configure MongoDB and JWT settings as described in the [backend setup guide](../../ledgerline-backend/README.md).

## Endpoints

- `POST /api/auth/register` creates an `ACCOUNT_HOLDER`. Role, ID, and password hash fields supplied by callers are ignored.
- `POST /api/auth/login` authenticates by username or email and returns an access token.
- `GET /api/auth/me` returns the authenticated profile.
- `POST /api/auth/logout` increments the user's token version, invalidating previously issued tokens.

Passwords must be at least 12 characters and no more than 72 UTF-8 bytes. JWTs use `JWT_SECRET` and expire after 15 minutes by default. The user and current role are loaded from MongoDB on each authenticated request.

## Authorization

- `ADMIN` can use `/api/admin/**`, view all accounts, and update account names/status. Admin account changes are audited.
- `ACCOUNT_HOLDER` can read/manage accounts they own or have been granted permission to access. Only account holders can deposit, withdraw, or transfer.
- `AUDITOR` can read accounts, statements, transactions, ledger entries, integrity results, and audit records. Auditor routes are read-only.

Never accept a user ID as proof of identity. Account ownership and permissions are checked server-side. Public registration cannot create administrators or auditors. Create the initial administrator using the backend's one-time `npm run create-admin` script; trusted operators provision any further role changes directly through controlled administrative procedures.

## Configure and run

From `ledgerline-backend`, copy `.env.example` to `.env`, set `MONGO_URI`, `JWT_SECRET`, and `FRONTEND_ORIGIN`, and run:

```powershell
npm install
npm run start
```

`MONGO_URI` must connect to a MongoDB replica set for financial writes. The API listens on port `8080` by default.

## Test with Postman

Import `postman-collection.json` and set `baseUrl` to `http://localhost:8080`. Register a unique account holder, use the returned token to fetch the current profile, then log out. A logged-out token should receive `401` on the next authenticated request. Do not commit exported Postman environments containing credentials or secrets.
