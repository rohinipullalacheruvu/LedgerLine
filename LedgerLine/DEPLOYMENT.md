# Deployment preparation

## Local evaluation with Compose

The repository root `compose.yaml` builds the Node API and React/Nginx frontend and starts a MongoDB 8 single-node replica set. This enables Mongo transactions for the demo. It is intentionally an evaluation-only configuration:

- MongoDB has no authentication in this local stack.
- Published ports are bound to loopback.
- A single MongoDB member is not a high-availability production topology.
- Do not put real customer or financial data in this stack.

Follow [README installation](./README.md#installation) and [demo setup](./DEMO.md#one-time-setup). Compose persists database and generated demo credentials in named local volumes.

## Production target

Use independently managed services:

1. **Frontend:** build the `ledgerline-frontend/Dockerfile` image. Set the build argument `VITE_API_BASE_URL` to an empty string when the frontend host/reverse proxy forwards `/api` and `/health` to the backend on the same origin; otherwise set it to the public API origin. The Vite value is public configuration, never a secret.
2. **Backend:** build `ledgerline-backend/Dockerfile` and run the Express process as the non-root `node` user. Set `NODE_ENV=production`, `PORT`, `MONGO_URI`, a private `JWT_SECRET` (32+ characters), `JWT_EXPIRATION`, `CLEARING_ACCOUNT_NUMBER`, `CLEARING_CURRENCY`, and the exact `FRONTEND_ORIGIN` values through the hosting provider's secret/configuration manager.
3. **Database:** use a managed MongoDB replica set with authentication, TLS, restricted network access, encrypted backups, monitoring, and a tested recovery process. `MONGO_URI` must advertise reachable replica-set members. Standalone MongoDB is unsupported for financial writes.
4. **Ingress:** terminate HTTPS at a maintained reverse proxy or managed load balancer. Forward `/api/*` and `/health` to the backend and serve the frontend with SPA fallback. Restrict CORS to the real frontend origin. Do not expose MongoDB publicly.
5. **Bootstrap:** provision an administrator through `npm run create-admin` using temporary, private environment values. Do not use the demo seeder in production. Provision auditors through a controlled operator workflow; no public role escalation endpoint exists.
6. **Operations:** use separate staging/production databases, rotate JWT/database credentials, monitor failed health checks and transaction conflicts, review audit logs, rate-limit authentication and money endpoints at ingress, and test restore and key-rotation procedures.

## Secret handling

- `.env`, nested `.env` files, and generated demo credentials are ignored by Git.
- Commit `.env.example` only; it contains placeholders, not usable secrets.
- Never bake secrets into a Docker image, frontend `VITE_*` setting, Docker build argument, source file, screenshot, Postman export, or public log.
- Use a managed secrets mechanism for production and restrict secret access to the backend runtime.

## Clean-checkout verification

This repository does not claim that a public deployment exists. Docker-based deployment validation must be run on a host with Docker Compose. Before submission, clone the submitted commit into a new directory, follow the evaluation steps, run `docker compose up --build`, seed the demo users, verify `/health`, walk through [DEMO.md](./DEMO.md), run `npm test`, and record the actual commit and command results. Do not describe the current uncommitted workspace as a verified GitHub clone.
