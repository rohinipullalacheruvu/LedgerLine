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

## Deploying on Render

The repository-root `render.yaml` defines the API as a Docker web service and the Vite frontend as a static site. To deploy:

1. Push the repository to GitHub and connect it to Render.
2. In Render, choose **New > Blueprint** and select this repository. Review the two services before deploying.
3. Before deploying, provide the prompted `MONGO_URI` and `FRONTEND_ORIGIN` values:
   - `MONGO_URI` must point to an authenticated, TLS-enabled MongoDB replica set (for example, a MongoDB Atlas cluster). Allow network access from Render as narrowly as your database provider supports. Do not use a standalone Mongo server; money operations require replica-set transactions.
   - `FRONTEND_ORIGIN` must be the exact HTTPS origin of the `ledgerline-frontend` Render static site, with no trailing slash (for example, `https://your-site.onrender.com`). If Render assigns a different hostname, update this variable on the API service and redeploy it.
4. Deploy the Blueprint. Render generates `JWT_SECRET`; the frontend receives the API hostname from the API service. Check `https://<api-host>/health` and confirm it returns HTTP 200 with `"database": "connected"`.
5. Create the initial administrator with the backend's `npm run create-admin` script, supplying `MONGO_URI`, `ADMIN_USERNAME`, `ADMIN_EMAIL`, `ADMIN_FULL_NAME`, and `ADMIN_PASSWORD` as temporary private environment values. Run it from a trusted environment with database access; never enable the demo seeder or put credentials in the Blueprint, repository, frontend, or logs.

The Blueprint uses Render's Free web-service plan for the API, which can sleep when idle and is not appropriate for production workloads. Upgrade the API to a paid plan for always-on availability. A static frontend is free. Render does not provide MongoDB in this Blueprint; provision the replica set separately and account for its costs, backups, access controls, and recovery testing.

## Secret handling

- `.env`, nested `.env` files, and generated demo credentials are ignored by Git.
- Commit `.env.example` only; it contains placeholders, not usable secrets.
- Never bake secrets into a Docker image, frontend `VITE_*` setting, Docker build argument, source file, screenshot, Postman export, or public log.
- Use a managed secrets mechanism for production and restrict secret access to the backend runtime.

## Clean-checkout verification

This repository does not claim that a public deployment exists. Docker-based deployment validation must be run on a host with Docker Compose. Before submission, clone the submitted commit into a new directory, follow the evaluation steps, run `docker compose up --build`, seed the demo users, verify `/health`, walk through [DEMO.md](./DEMO.md), run `npm test`, and record the actual commit and command results. Do not describe the current uncommitted workspace as a verified GitHub clone.
