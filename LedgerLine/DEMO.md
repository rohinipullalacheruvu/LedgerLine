# Five-minute evaluation demo

The demo accounts are generated locally on demand. The usernames are stable; their high-entropy passwords are written only to the ignored `ledgerline-backend/.demo-credentials.json` file (or a private Docker volume). Never commit or share that file. Demo seeding is disabled in production and requires the explicit `ALLOW_DEMO_SEED=true` opt-in.

## One-time setup

### Docker Compose (recommended)

1. Install Docker Desktop with Compose.
2. From the repository root, copy `.env.example` to `.env`. Replace `JWT_SECRET` with a private random value of at least 32 characters. Example generation command:

   ```powershell
   node -e "console.log(require('node:crypto').randomBytes(48).toString('base64url'))"
   ```

3. Start the evaluation-only MongoDB replica set and application:

   ```powershell
   docker compose up --build -d
   docker compose ps
   ```

4. Seed the admin, holder, and auditor users and holder accounts:

   ```powershell
   docker compose exec -e NODE_ENV=development -e ALLOW_DEMO_SEED=true backend npm run demo:seed
   docker compose exec backend cat /demo/.demo-credentials.json
   ```

   Record the generated passwords privately for the session. The holder's primary account starts with a balanced, ledger-recorded opening deposit of USD 1,000. The secondary account starts at USD 0.

5. Open `http://localhost:5173`. Check the health endpoint at `http://localhost:8080/health`.

### Without Docker

Configure `MONGO_URI` to a running MongoDB replica set, set a private `JWT_SECRET`, and set `NODE_ENV=development`. In PowerShell:

```powershell
cd .\ledgerline-backend
npm ci
$env:ALLOW_DEMO_SEED = "true"
npm run demo:seed
npm run start
```

In another terminal:

```powershell
cd .\ledgerline-frontend
npm ci
npm run dev
```

Local seed credentials are saved to the ignored `ledgerline-backend/.demo-credentials.json` file. No demo passwords are committed.

## Five-minute run-through

| Time | Demo action |
|---|---|
| 0:00–0:40 | Sign in as `demo-admin`. Show the overview, registered users/accounts, audit records, suspicious-activity flags, and account status controls. Change a non-clearing account status only if you intend to restore it afterward. |
| 0:40–1:20 | Log out; sign in as `demo-holder`. Show the two seeded USD accounts and the primary account's USD 1,000 opening ledger entry. |
| 1:20–2:10 | Create a third account in the UI. Deposit a small amount into the primary account, then withdraw a smaller amount. Show updated balances and a statement. |
| 2:10–2:50 | Transfer a small amount from the primary account to the secondary account. Open both account statements and show the matching debit/credit posting. |
| 2:50–3:35 | Run the automated duplicate/concurrency demonstration below. It sends the same deposit twice with one idempotency key, then issues two withdrawals concurrently whose combined amount is greater than the available balance. Expected: same transaction response for the duplicate requests, one successful withdrawal, one `409` refusal, and a nonnegative persisted balance. |
| 3:35–4:20 | Log out; sign in as `demo-auditor`. Show account/transaction history, statements, audit records, and balanced-ledger verification. Point out that mutation controls are absent and auditor write requests are denied by the API. |
| 4:20–5:00 | Return to the test results: `npm test` uses real HTTP requests and a MongoDB replica-set database. Do not claim a production deployment or clean-clone verification unless those were separately completed. |

### Duplicate and concurrent withdrawal check

With Docker Compose running, run:

```powershell
docker compose exec backend npm run demo:verify
```

For a non-Docker setup:

```powershell
cd .\ledgerline-backend
npm run demo:verify
```

The script logs in using the generated holder credentials, confirms both same-key deposits return the same transaction ID, then sends two parallel withdrawals sized so that both cannot succeed. It checks final persisted balance and exits nonzero if the expected behavior is not observed. It does change the demo balances and adds normal transaction/audit records.

## Reset

Stop the local evaluation stack and delete its data only when you are certain the volume contains no needed data:

```powershell
docker compose down
docker volume ls
```

For a clean, disposable evaluation reset, inspect the exact Compose volume name first, then remove that one volume explicitly. The demo credentials volume is separate from MongoDB data; keep or remove it deliberately.
