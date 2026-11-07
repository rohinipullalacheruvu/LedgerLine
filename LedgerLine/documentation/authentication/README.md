# Authentication API

## What is implemented

- `POST /api/auth/register` creates an `ACCOUNT_HOLDER` and returns a JWT. Registration never accepts a role, ID, or password hash from the caller.
- `POST /api/auth/login` authenticates by username or email and password, then returns a JWT.
- `GET /api/auth/me` returns the authenticated profile.
- `POST /api/auth/logout` increments the current user's token version, immediately invalidating all of that user's JWTs.
- Account management routes are documented in [the account API guide](../accounts/README.md), including ownership checks for account detail and balance reads.

Passwords are stored using BCrypt with work factor 12. Passwords must be at least 12 characters and no more than 72 UTF-8 bytes (the BCrypt input limit). JWTs are signed with an environment-supplied Base64-encoded key of at least 256 bits and expire after 15 minutes by default. Roles are loaded from the database for each request; they are not trusted from token claims.

## Authorization model

- `ADMIN` can access `/api/admin/**` and view system accounts, transactions, and ledger records. Admin account status/name changes are restricted to this role.
- `ACCOUNT_HOLDER` can read and rename accounts they own or have been granted the corresponding account permission. Financial-operation services must call `assertCanTransactOnAccount` to check ownership or an account permission using the authenticated principal's database ID.
- `AUDITOR` can read accounts, transactions, and ledger endpoints, but cannot create or mutate records.
- Never accept a user ID as proof of identity. Account ownership and permissions must be checked server-side for account-specific operations.

Public registration always creates an account holder. Assign `ADMIN` and `AUDITOR` only through a trusted operator process, never through an unauthenticated endpoint. For example, an authorized database operator can update a user's role after verifying the identity:

```sql
UPDATE users SET role = 'ADMIN' WHERE username = 'verified-operator';
```

Role changes take effect on the next request because authorities are read from MySQL. Do not expose this SQL operation through a public or account-holder API.

## Database setup

For a fresh database, apply `../database/schema.sql`. If the earlier seven-table schema already exists, apply `../database/authentication-migration.sql` exactly once before running the updated backend. Both the role and token version columns are required.

## Configure and run

In the VS Code PowerShell terminal:

```powershell
cd .\ledgerline-backend
$env:DB_USERNAME = "your_mysql_username"
$env:DB_PASSWORD = "your_mysql_password"
$jwtBytes = New-Object byte[] 32
[Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($jwtBytes)
$env:JWT_SECRET = [Convert]::ToBase64String($jwtBytes)
mvn spring-boot:run
```

`JWT_SECRET` is required; the generated value above is for the current terminal session. Keep it private and use a managed secret for deployment. Changing the signing secret invalidates all existing tokens. `JWT_EXPIRATION_MS` optionally overrides the default token lifetime.

## Test with Postman

Import `postman-collection.json` into Postman. Set the collection's `baseUrl` variable to `http://localhost:8080`. Then:

1. Send **Register account holder** with a unique username/email and a password of at least 12 characters. Its test script stores the returned JWT in `accessToken`.
2. Send **Get current user** to verify the bearer token and profile.
3. Send **Login** with the same username or email and password. Its test script replaces `accessToken` with the newly issued JWT.
4. Send **Logout**. The stored token is invalidated server-side; sending **Get current user** with that old token should return `401`.

The collection has no real user credentials or signing secrets. Do not commit exported Postman environments containing real credentials.
