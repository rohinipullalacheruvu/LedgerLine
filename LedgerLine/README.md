# LedgerLine

**Money Movement with a Ledger You Can Prove**

LedgerLine is a full-stack financial transaction management application in development. Its goal is to make money movement understandable and auditable through a clear transaction history.

> The backend currently supports JWT authentication, account management, deposits, withdrawals, internal transfers, idempotent money-moving APIs, and read-only account ledger statements. The React frontend currently provides a responsive preview shell; account data and actions are not connected yet.

## Planned technology

- **Frontend:** React, HTML, CSS, and JavaScript
- **Backend:** Java, Spring Boot, and Maven
- **Database and persistence:** MySQL, Spring Data JPA, and Hibernate
- **Security:** Spring Security and JWT
- **Testing:** JUnit 5 and Mockito

## Project layout

```text
LedgerLine/
├── ledgerline-frontend/
│   └── src/
│       ├── assets/
│       ├── components/
│       ├── context/
│       ├── pages/
│       ├── services/
│       └── styles/
├── ledgerline-backend/
│   └── src/
│       ├── main/java/com/ledgerline/
│       │   ├── config/
│       │   ├── controller/
│       │   ├── dto/
│       │   ├── entity/
│       │   ├── enums/
│       │   ├── exception/
│       │   ├── repository/
│       │   ├── security/
│       │   └── service/
│       └── test/java/com/ledgerline/
└── documentation/
    ├── database/
    │   ├── README.md
    │   ├── schema.sql
    │   ├── money-movement-migration.sql
    │   └── idempotency-outcomes-migration.sql
    ├── authentication/
    │   ├── README.md
    │   └── postman-collection.json
    └── accounts/
        ├── README.md
        └── postman-collection.json
```

## Folder guide

### Frontend

- `ledgerline-frontend/` — React application and its frontend-specific configuration.
- `src/components/` — Reusable interface elements shared across screens.
- `src/pages/` — Page-level views and route targets.
- `src/services/` — API clients and frontend integration with backend endpoints.
- `src/context/` — Shared React context for application-wide state.
- `src/assets/` — Images, icons, fonts, and other static assets.
- `src/styles/` — Global styles, design tokens, and shared styling.

### Backend

- `ledgerline-backend/` — Spring Boot service and its Maven configuration.
- `src/main/java/com/ledgerline/config/` — Application and framework configuration.
- `src/main/java/com/ledgerline/controller/` — HTTP endpoints and request handling.
- `src/main/java/com/ledgerline/service/` — Business rules and application workflows.
- `src/main/java/com/ledgerline/repository/` — Spring Data persistence interfaces.
- `src/main/java/com/ledgerline/entity/` — JPA entities mapped to database records.
- `src/main/java/com/ledgerline/dto/` — Data transfer objects for API input and output.
- `src/main/java/com/ledgerline/security/` — Authentication, authorization, and JWT components.
- `src/main/java/com/ledgerline/exception/` — Application exceptions and API error handling.
- `src/main/java/com/ledgerline/enums/` — Shared backend enumerated domain values.
- `src/test/java/com/ledgerline/` — Backend unit and integration tests, including real-MySQL concurrency coverage.

### Documentation

- `documentation/` — Project design notes, API references, and supporting technical documentation.
- `documentation/database/` — MySQL schema, upgrade migrations, database design, and concurrency test instructions.
- `documentation/authentication/` — Authentication API setup, role model, and a Postman collection for manual testing.
- `documentation/accounts/` — Account API behavior, role permissions, and a Postman collection for endpoint testing.

## Run the backend

Requirements: Java 17 or newer, Maven 3.6.3 or newer, and MySQL running on `localhost:3306`. Apply `documentation/database/schema.sql` for a fresh database. For an existing database, apply the applicable money-movement and idempotency outcome migrations before using those modules.

From the VS Code terminal, open the backend directory and set credentials for the current PowerShell session:

```powershell
cd .\ledgerline-backend
$env:DB_USERNAME = "your_mysql_username"
$env:DB_PASSWORD = "your_mysql_password"
$jwtBytes = New-Object byte[] 32
[Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($jwtBytes)
$env:JWT_SECRET = [Convert]::ToBase64String($jwtBytes)
mvn spring-boot:run
```

The service listens on port `8080`. `SPRING_PROFILES_ACTIVE` defaults to `dev`, where Hibernate uses `ddl-auto=update`; use this only for local development, not production. Set `DB_URL`, `SERVER_PORT`, `SPRING_PROFILES_ACTIVE`, `JWT_EXPIRATION_MS`, or `CLEARING_ACCOUNT_NUMBER` to override defaults. The default clearing account is `LL-CLEARING-USD`; apply the money-movement migration on an existing database. Keep JWT signing keys private; changing the key invalidates issued tokens.

## Run the frontend

Requirements: Node.js 20.19+ or 22.12+. From a VS Code terminal:

```powershell
cd .\ledgerline-frontend
npm install
npm run dev
```

Open the local URL printed by Vite (normally `http://localhost:5173`). The frontend connects to the API at `http://localhost:8080` by default; set `VITE_API_BASE_URL` before `npm run dev` to use another backend URL. The backend allows the local Vite origin; set `FRONTEND_ORIGIN` to configure the primary allowed origin.

The client supports sign-in/registration, account and statement views, role-specific admin/auditor dashboards, and account-holder deposits, withdrawals, and transfers. Money movement uses a persistent idempotency key for retries of the same request body. Financial data comes from the API only; the client never creates transaction or balance data locally.

## Current status

The backend has its Maven/Spring Boot setup, MySQL configuration, application entry point, request-validation error responses, JPA entities, registration/login/JWT/logout, role-based security, account management APIs, transactional deposits and withdrawals, atomic internal transfers, idempotent money movement, and read-only account ledger statements. Existing databases must apply the [idempotency outcome migration](./documentation/database/idempotency-outcomes-migration.sql). Run the MySQL concurrency integration tests against a dedicated test database as described in the [database guide](./documentation/database/README.md). The React frontend includes role-aware account, statement, money-movement, and audit pages connected to the API. See [authentication setup](./documentation/authentication/README.md), [account API guide](./documentation/accounts/README.md), and [database design](./documentation/database/README.md).
