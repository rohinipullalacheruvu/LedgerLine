import cors from "cors";
import express from "express";
import mongoose from "mongoose";
import {
  authenticate,
  issueToken,
  login,
  register,
  requireRole,
  userSummary,
} from "./auth.js";
import {
  adminOverview,
  createCustomerAccount,
  getAccount,
  getAccountBalance,
  ledgerIntegrity,
  listAccounts,
  listAllAccountsForOversight,
  listLedgerEntries,
  listTransactions,
  portfolioSummary,
  postMoneyOperation,
  recentAuditRecords,
  renameAccount,
  statement,
  transfer,
  updateAdminAccount,
} from "./ledger.js";
import { ApiError } from "./validation.js";

export function createApp() {
  const app = express();
  const allowedOrigins = (process.env.FRONTEND_ORIGIN
    || "http://localhost:5173,http://127.0.0.1:5173")
    .split(",")
    .map((origin) => origin.trim())
    .filter(Boolean);

  app.disable("x-powered-by");
  app.use(cors({
    origin(origin, callback) {
      if (!origin || allowedOrigins.includes(origin)) return callback(null, true);
      callback(new ApiError(403, "This origin is not allowed", "Forbidden"));
    },
    allowedHeaders: ["Content-Type", "Authorization", "Idempotency-Key"],
    methods: ["GET", "POST", "PATCH", "OPTIONS"],
  }));
  app.use(express.json({ limit: "1mb", strict: true }));
  app.use((req, _res, next) => {
    if (req.method === "POST" || req.method === "PATCH") {
      if (req.body == null) req.body = {};
      if (typeof req.body !== "object" || Array.isArray(req.body)) {
        return next(new ApiError(400, "Request body must be a JSON object", "Invalid request"));
      }
    }
    next();
  });

  app.get("/health", (_req, res) => {
    const state = mongoose.connection.readyState;
    res.status(state === 1 ? 200 : 503).json({
      status: state === 1 ? "UP" : "DOWN",
      database: state === 1 ? "connected" : "disconnected",
    });
  });

  app.post("/api/auth/register", async (req, res) => {
    res.status(201).json(await register(req.body));
  });
  app.post("/api/auth/login", async (req, res) => {
    res.json(await login(req.body));
  });
  app.get("/api/auth/me", authenticate, (req, res) => {
    res.json(userSummary(req.user));
  });
  app.post("/api/auth/logout", authenticate, async (req, res) => {
    req.user.tokenVersion += 1;
    await req.user.save();
    res.status(204).end();
  });

  app.get("/api/accounts/summary", authenticate, async (req, res) => {
    res.json(await portfolioSummary(req.user));
  });
  app.get("/api/accounts", authenticate, async (req, res) => {
    res.json(await listAccounts(req.user));
  });
  app.post("/api/accounts", authenticate, async (req, res) => {
    res.status(201).json(await createCustomerAccount(req.user, req.body));
  });
  app.get("/api/accounts/:accountId/statement", authenticate, async (req, res) => {
    res.json(await statement(req.user, req.params.accountId, req.query));
  });
  app.get("/api/accounts/:accountId/balance", authenticate, async (req, res) => {
    res.json(await getAccountBalance(req.user, req.params.accountId));
  });
  app.get("/api/accounts/:accountId", authenticate, async (req, res) => {
    res.json(await getAccount(req.user, req.params.accountId));
  });
  app.patch("/api/accounts/:accountId", authenticate, async (req, res) => {
    res.json(await renameAccount(req.user, req.params.accountId, req.body));
  });
  app.post("/api/accounts/:accountId/deposits", authenticate, async (req, res) => {
    res.status(201).json(await postMoneyOperation(
      req.user, req.params.accountId, "DEPOSIT", req.body, req.get("Idempotency-Key"),
    ));
  });
  app.post("/api/accounts/:accountId/withdrawals", authenticate, async (req, res) => {
    res.status(201).json(await postMoneyOperation(
      req.user, req.params.accountId, "WITHDRAWAL", req.body, req.get("Idempotency-Key"),
    ));
  });

  app.post("/api/transfers", authenticate, async (req, res) => {
    res.status(201).json(await transfer(req.user, req.body, req.get("Idempotency-Key")));
  });

  app.get("/api/admin/overview", authenticate, requireRole("ADMIN"), async (req, res) => {
    res.json(await adminOverview(req.user));
  });
  app.get("/api/admin/accounts", authenticate, requireRole("ADMIN"), async (req, res) => {
    res.json(await listAllAccountsForOversight(req.user));
  });
  app.get("/api/admin/accounts/:accountId", authenticate, requireRole("ADMIN"), async (req, res) => {
    res.json(await getAccount(req.user, req.params.accountId));
  });
  app.patch("/api/admin/accounts/:accountId", authenticate, requireRole("ADMIN"), async (req, res) => {
    res.json(await updateAdminAccount(req.user, req.params.accountId, req.body));
  });
  app.get("/api/admin/audit-logs", authenticate, requireRole("ADMIN"), async (req, res) => {
    res.json(await recentAuditRecords(req.user));
  });
  app.get("/api/admin/suspicious-transactions", authenticate, requireRole("ADMIN"), async (req, res) => {
    const records = await recentAuditRecords(req.user);
    res.json(records.filter((record) => record.action.endsWith("_REFUSED")));
  });

  app.get("/api/auditor/accounts", authenticate, requireRole("AUDITOR"), async (req, res) => {
    res.json(await listAllAccountsForOversight(req.user));
  });
  app.get("/api/auditor/audit-logs", authenticate, requireRole("AUDITOR"), async (req, res) => {
    res.json(await recentAuditRecords(req.user));
  });
  app.get("/api/auditor/ledger-entries", authenticate, requireRole("AUDITOR"), async (req, res) => {
    res.json(await listLedgerEntries(req.user));
  });
  app.get("/api/auditor/ledger-integrity", authenticate, requireRole("AUDITOR"), async (req, res) => {
    res.json(await ledgerIntegrity(req.user));
  });
  app.get("/api/transactions", authenticate, async (req, res) => {
    res.json(await listTransactions(req.user));
  });
  app.get("/api/ledger-entries", authenticate, requireRole("ADMIN"), async (req, res) => {
    res.json(await listLedgerEntries(req.user));
  });

  app.use((_req, _res, next) => next(new ApiError(404, "The requested resource was not found", "Not found")));
  app.use((error, _req, res, _next) => {
    if (res.headersSent) return;
    if (error instanceof ApiError) {
      return res.status(error.status).json({ error: error.error, message: error.message });
    }
    if (error instanceof mongoose.Error.ValidationError || error instanceof mongoose.Error.CastError) {
      return res.status(400).json({ error: "Invalid request", message: error.message });
    }
    if (error.type === "entity.parse.failed" || error.type === "entity.too.large") {
      return res.status(error.status || 400).json({
        error: "Invalid request",
        message: error.type === "entity.too.large" ? "Request body is too large" : "Request body must be valid JSON",
      });
    }
    if (error.code === 11000) {
      return res.status(409).json({ error: "Conflict", message: "A record with the same unique value already exists" });
    }
    console.error("Unhandled API error", error);
    return res.status(500).json({ error: "Internal server error", message: "The request could not be completed" });
  });

  return app;
}

export default createApp();
