import assert from "node:assert/strict";
import { execFile } from "node:child_process";
import { after, afterEach, before, beforeEach, test } from "node:test";
import bcrypt from "bcryptjs";
import { once } from "node:events";
import { mkdtemp, readFile, rm } from "node:fs/promises";
import mongoose from "mongoose";
import { MongoMemoryReplSet } from "mongodb-memory-server";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { promisify } from "node:util";
import request from "supertest";
import app from "../src/app.js";
import { issueToken } from "../src/auth.js";
import {
  Account,
  AccountPermission,
  AuditLog,
  IdempotencyRecord,
  LedgerEntry,
  Transaction,
  User,
  decimal,
} from "../src/models.js";

let replSet;
let holder;
let admin;
let auditor;
let holderToken;
let adminToken;
let auditorToken;
let account;
let recipient;
let clearing;
const secret = "ledgerline-test-signing-secret-123456";
const execFileAsync = promisify(execFile);
process.env.JWT_SECRET = secret;

function auth(token) {
  return { Authorization: `Bearer ${token}` };
}

async function createAccount(owner, accountNumber, balance = "0.0000", accountType = "CHECKING") {
  return Account.create({
    ownerId: owner?._id || null,
    accountNumber,
    name: accountNumber,
    accountType,
    currency: "USD",
    balance: decimal(balance),
  });
}

async function jsonRequest(method, path, token, body, key) {
  let call = request(app)[method](path).set(auth(token));
  if (key) call = call.set("Idempotency-Key", key);
  if (body !== undefined) call = call.send(body);
  return call;
}

before(async () => {
  replSet = await MongoMemoryReplSet.create({
    replSet: { count: 1, storageEngine: "wiredTiger" },
  });
  await mongoose.connect(replSet.getUri(), { serverSelectionTimeoutMS: 20000 });
});

after(async () => {
  await mongoose.disconnect();
  await replSet?.stop();
});

beforeEach(async () => {
  await Promise.all(Object.values(mongoose.connection.collections).map((collection) => collection.deleteMany({})));
  const passwordHash = await bcrypt.hash("correct-horse-battery-staple", 4);
  [holder, admin, auditor] = await User.create([
    { username: "holder", email: "holder@example.test", passwordHash, fullName: "Holder User", role: "ACCOUNT_HOLDER" },
    { username: "admin", email: "admin@example.test", passwordHash, fullName: "Admin User", role: "ADMIN" },
    { username: "auditor", email: "auditor@example.test", passwordHash, fullName: "Auditor User", role: "AUDITOR" },
  ]);
  holderToken = issueToken(holder);
  adminToken = issueToken(admin);
  auditorToken = issueToken(auditor);
  account = await createAccount(holder, "LL-HOLDER-001", "100.0000");
  recipient = await createAccount(holder, "LL-HOLDER-002");
  clearing = await createAccount(null, "LL-CLEARING-USD", "0.0000", "EXTERNAL_CLEARING");
});

afterEach(async () => {
  await Promise.all([
    AccountPermission.deleteMany({}),
    AuditLog.deleteMany({}),
    IdempotencyRecord.deleteMany({}),
    LedgerEntry.collection.deleteMany({}),
    Transaction.deleteMany({}),
    Account.deleteMany({}),
    User.deleteMany({}),
  ]);
});

test("money conservation holds across a deposit", async () => {
  const before = Number(account.balance.toString()) + Number(clearing.balance.toString());
  const response = await jsonRequest("post", `/api/accounts/${account.id}/deposits`, holderToken, {
    amount: "20.25",
  }, "conserve-deposit");
  assert.equal(response.status, 201);
  const customerAfter = await Account.findById(account.id);
  const clearingAfter = await Account.findById(clearing.id);
  assert.equal(
    Number(customerAfter.balance.toString()) + Number(clearingAfter.balance.toString()),
    before,
  );
});

test("deposits maintain separate balanced clearing accounts by currency", async () => {
  const euroAccount = await Account.create({
    ownerId: holder._id,
    accountNumber: "LL-HOLDER-EUR",
    name: "Euro account",
    accountType: "CHECKING",
    currency: "EUR",
    balance: decimal(0),
  });
  const response = await jsonRequest("post", `/api/accounts/${euroAccount.id}/deposits`, holderToken, {
    amount: "8.25",
  }, "euro-deposit");
  assert.equal(response.status, 201);
  const euroClearing = await Account.findOne({ accountNumber: "LL-CLEARING-EUR" });
  assert.ok(euroClearing);
  assert.equal(euroClearing.currency, "EUR");
  assert.equal((await Account.findById(euroAccount.id)).balance.toString(), "8.2500");
  assert.equal(euroClearing.balance.toString(), "-8.2500");
});

test("each money movement records equal debit and credit entries", async () => {
  const response = await jsonRequest("post", "/api/transfers", holderToken, {
    senderAccountNumber: account.accountNumber,
    receiverAccountNumber: recipient.accountNumber,
    amount: "12.34",
  }, "balanced-transfer");
  assert.equal(response.status, 201);
  const entries = await LedgerEntry.find({ transactionId: response.body.transactionReference.slice(5) });
  assert.equal(entries.length, 2);
  assert.deepEqual(entries.map((entry) => entry.entrySide).sort(), ["CREDIT", "DEBIT"]);
  assert.equal(entries[0].amount.toString(), entries[1].amount.toString());
});

test("auditors can verify statements, history, and balanced ledger postings", async () => {
  const transferResponse = await jsonRequest("post", "/api/transfers", holderToken, {
    senderAccountNumber: account.accountNumber,
    receiverAccountNumber: recipient.accountNumber,
    amount: "12.34",
  }, "auditor-verification");
  assert.equal(transferResponse.status, 201);
  const [accounts, history, ledgerEntries, integrity, statementResponse] = await Promise.all([
    jsonRequest("get", "/api/auditor/accounts", auditorToken),
    jsonRequest("get", "/api/transactions", auditorToken),
    jsonRequest("get", "/api/auditor/ledger-entries", auditorToken),
    jsonRequest("get", "/api/auditor/ledger-integrity", auditorToken),
    jsonRequest("get", `/api/accounts/${account.id}/statement`, auditorToken),
  ]);
  assert.equal(accounts.status, 200);
  assert.equal(history.body.length, 1);
  assert.equal(ledgerEntries.body.length, 2);
  assert.equal(integrity.body.totalTransactions, 1);
  assert.equal(integrity.body.balancedTransactions, 1);
  assert.equal(integrity.body.unbalancedTransactions.length, 0);
  assert.equal(statementResponse.body.entries.length, 1);
  assert.equal(statementResponse.body.currentBalance, 87.66);
});

test("withdrawals cannot create a negative customer balance", async () => {
  const response = await jsonRequest("post", `/api/accounts/${account.id}/withdrawals`, holderToken, {
    amount: "100.01",
  }, "overdraw");
  assert.equal(response.status, 409);
  assert.equal((await Account.findById(account.id)).balance.toString(), "100.0000");
  assert.equal(await Transaction.countDocuments(), 0);
  assert.equal(await LedgerEntry.countDocuments(), 0);
  const flags = await jsonRequest("get", "/api/admin/suspicious-transactions", adminToken);
  assert.equal(flags.status, 200);
  assert.equal(flags.body[0].action, "WITHDRAWAL_REFUSED");
});

test("duplicate transfer requests with the same key post only once", async () => {
  const payload = {
    senderAccountNumber: account.accountNumber,
    receiverAccountNumber: recipient.accountNumber,
    amount: "15.00",
  };
  const responses = await Promise.all([
    jsonRequest("post", "/api/transfers", holderToken, payload, "duplicate-key"),
    jsonRequest("post", "/api/transfers", holderToken, payload, "duplicate-key"),
  ]);
  assert.ok(responses.every((response) => response.status === 201), JSON.stringify(responses.map((item) => item.body)));
  assert.equal(responses[0].body.transactionReference, responses[1].body.transactionReference);
  assert.equal((await Account.findById(account.id)).balance.toString(), "85.0000");
  assert.equal(await Transaction.countDocuments(), 1);
  assert.equal(await LedgerEntry.countDocuments(), 2);
});

test("reusing an idempotency key with changed request details conflicts", async () => {
  const first = await jsonRequest("post", `/api/accounts/${account.id}/deposits`, holderToken, {
    amount: "10.00",
  }, "changed-request");
  const second = await jsonRequest("post", `/api/accounts/${account.id}/deposits`, holderToken, {
    amount: "11.00",
  }, "changed-request");
  assert.equal(first.status, 201);
  assert.equal(second.status, 409);
  assert.equal((await Account.findById(account.id)).balance.toString(), "110.0000");
  assert.equal(await Transaction.countDocuments(), 1);
});

test("two simultaneous withdrawals cannot overdraw an account", async () => {
  const [first, second] = await Promise.all([
    jsonRequest("post", `/api/accounts/${account.id}/withdrawals`, holderToken, { amount: "80" }, "withdraw-a"),
    jsonRequest("post", `/api/accounts/${account.id}/withdrawals`, holderToken, { amount: "80" }, "withdraw-b"),
  ]);
  assert.equal([first, second].filter((response) => response.status === 201).length, 1);
  assert.equal([first, second].filter((response) => response.status === 409).length, 1);
  assert.equal((await Account.findById(account.id)).balance.toString(), "20.0000");
  assert.equal(await Transaction.countDocuments({ status: "POSTED" }), 1);
  assert.equal(await LedgerEntry.countDocuments(), 2);
});

test("two simultaneous transfers preserve balances and ledger consistency", async () => {
  const secondRecipient = await createAccount(holder, "LL-HOLDER-003");
  const responses = await Promise.all([
    jsonRequest("post", "/api/transfers", holderToken, {
      senderAccountNumber: account.accountNumber,
      receiverAccountNumber: recipient.accountNumber,
      amount: "80",
    }, "transfer-a"),
    jsonRequest("post", "/api/transfers", holderToken, {
      senderAccountNumber: account.accountNumber,
      receiverAccountNumber: secondRecipient.accountNumber,
      amount: "80",
    }, "transfer-b"),
  ]);
  assert.equal(responses.filter((response) => response.status === 201).length, 1);
  assert.equal(responses.filter((response) => response.status === 409).length, 1);
  assert.equal((await Account.findById(account.id)).balance.toString(), "20.0000");
  const transactionCount = await Transaction.countDocuments();
  assert.equal(transactionCount, 1);
  assert.equal(await LedgerEntry.countDocuments(), 2);
  const [firstRecipient, secondRecipientBalance] = await Promise.all([
    Account.findById(recipient.id),
    Account.findById(secondRecipient.id),
  ]);
  assert.equal(
    Number(firstRecipient.balance.toString()) + Number(secondRecipientBalance.balance.toString()),
    80,
  );
});

test("users cannot access accounts they do not have permission to view", async () => {
  const otherUser = await User.create({
    username: "other",
    email: "other@example.test",
    passwordHash: await bcrypt.hash("correct-horse-battery-staple", 4),
    fullName: "Other User",
    role: "ACCOUNT_HOLDER",
  });
  const otherAccount = await createAccount(otherUser, "LL-OTHER-001", "999");
  const response = await jsonRequest("get", `/api/accounts/${otherAccount.id}`, holderToken);
  assert.equal(response.status, 403);
  const statementResponse = await jsonRequest("get", `/api/accounts/${otherAccount.id}/statement`, holderToken);
  assert.equal(statementResponse.status, 403);
  const portfolio = await jsonRequest("get", "/api/accounts/summary", holderToken);
  assert.equal(portfolio.status, 200);
  assert.equal(portfolio.body.find((item) => item.currency === "USD").totalBalance, 100);
  assert.equal(portfolio.body.find((item) => item.currency === "USD").accountCount, 2);
});

test("failed posting rolls back transaction, ledger, idempotency, and account updates", async () => {
  clearing.balance = decimal("-999999999999999.9999");
  await clearing.save();
  const response = await jsonRequest("post", `/api/accounts/${account.id}/deposits`, holderToken, {
    amount: "1",
  }, "rollback-deposit");
  assert.equal(response.status, 409);
  assert.equal((await Account.findById(account.id)).balance.toString(), "100.0000");
  assert.equal((await Account.findById(clearing.id)).balance.toString(), "-999999999999999.9999");
  assert.equal(await Transaction.countDocuments(), 0);
  assert.equal(await LedgerEntry.countDocuments(), 0);
  assert.equal(await IdempotencyRecord.countDocuments(), 0);
});

test("ledger entries cannot be modified through application APIs", async () => {
  const transferResponse = await jsonRequest("post", "/api/transfers", holderToken, {
    senderAccountNumber: account.accountNumber,
    receiverAccountNumber: recipient.accountNumber,
    amount: "1",
  }, "ledger-readonly");
  assert.equal(transferResponse.status, 201);
  const entry = await LedgerEntry.findOne();
  const before = entry.toObject();
  for (const [method, path] of [
    ["patch", `/api/ledger-entries/${entry.id}`],
    ["delete", `/api/ledger-entries/${entry.id}`],
    ["patch", `/api/auditor/ledger-entries/${entry.id}`],
    ["delete", `/api/auditor/ledger-entries/${entry.id}`],
  ]) {
    const response = await jsonRequest(method, path, auditorToken, { amount: 0 });
    assert.equal(response.status, 404);
  }
  const after = await LedgerEntry.findById(entry.id);
  assert.equal(after.amount.toString(), before.amount.toString());
  assert.equal(after.entrySide, before.entrySide);
  await assert.rejects(
    LedgerEntry.updateOne({ _id: entry.id }, { $set: { entrySide: "CREDIT" } }),
    /append-only/,
  );
  await assert.rejects(entry.deleteOne(), /append-only/);
  assert.ok(await LedgerEntry.exists({ _id: entry.id }));
});

test("admin, auditor, and account-holder permissions are enforced", async () => {
  assert.equal((await jsonRequest("get", "/api/admin/overview", adminToken)).status, 200);
  assert.equal((await jsonRequest("get", "/api/auditor/accounts", auditorToken)).status, 200);
  assert.equal((await jsonRequest("get", "/api/admin/overview", auditorToken)).status, 403);
  assert.equal((await jsonRequest("patch", `/api/accounts/${account.id}`, auditorToken, { name: "Changed" })).status, 403);
  assert.equal((await jsonRequest("post", `/api/accounts/${account.id}/deposits`, auditorToken, { amount: 1 }, "auditor-deposit")).status, 403);
  assert.equal((await jsonRequest("get", "/api/auditor/ledger-integrity", auditorToken)).status, 200);
  assert.equal((await jsonRequest("get", "/api/auditor/ledger-integrity", holderToken)).status, 403);
});

test("admin account changes are authorized and audited", async () => {
  const denied = await jsonRequest("patch", `/api/admin/accounts/${account.id}`, auditorToken, {
    status: "FROZEN",
  });
  assert.equal(denied.status, 403);
  const updated = await jsonRequest("patch", `/api/admin/accounts/${account.id}`, adminToken, {
    status: "FROZEN",
  });
  assert.equal(updated.status, 200);
  assert.equal(updated.body.status, "FROZEN");
  const audit = await AuditLog.findOne({ action: "ADMIN_ACCOUNT_UPDATED" });
  assert.ok(audit);
  assert.equal(String(audit.actorUserId), String(admin._id));
  const auditRecords = await jsonRequest("get", "/api/admin/audit-logs", adminToken);
  assert.equal(auditRecords.body[0].details.changedFields, "status");
});

test("registration and login keep public users in the account-holder role", async () => {
  const registered = await request(app).post("/api/auth/register").send({
    username: "new-holder",
    email: "new-holder@example.test",
    fullName: "New Holder",
    password: "correct-horse-battery-staple",
    role: "ADMIN",
  });
  assert.equal(registered.status, 201);
  assert.equal(registered.body.user.role, "ACCOUNT_HOLDER");
  const login = await request(app).post("/api/auth/login").send({
    usernameOrEmail: "new-holder",
    password: "correct-horse-battery-staple",
  });
  assert.equal(login.status, 200);
  assert.equal(login.body.user.role, "ACCOUNT_HOLDER");
  const currentUser = await request(app).get("/api/auth/me").set(auth(login.body.accessToken));
  assert.equal(currentUser.status, 200);
  assert.equal(currentUser.body.username, "new-holder");
});

test("demo seeder creates role-specific users and a balanced funded account", async () => {
  const directory = await mkdtemp(join(tmpdir(), "ledgerline-demo-"));
  const credentialPath = join(directory, "credentials.json");
  try {
    await execFileAsync(process.execPath, ["scripts/seed-demo.js"], {
      cwd: new URL("..", import.meta.url),
      env: {
        ...process.env,
        NODE_ENV: "test",
        ALLOW_DEMO_SEED: "true",
        MONGO_URI: replSet.getUri(),
        DEMO_CREDENTIALS_PATH: credentialPath,
      },
    });
    const credentials = JSON.parse(await readFile(credentialPath, "utf8"));
    assert.deepEqual(credentials.users.map((user) => user.role).sort(), [
      "ACCOUNT_HOLDER", "ADMIN", "AUDITOR",
    ]);
    assert.ok(credentials.users.every((user) => user.password.length >= 32));
    const demoHolder = await User.findOne({ username: "demo-holder" });
    const primary = await Account.findById(credentials.accounts.primary.id);
    assert.equal(String(primary.ownerId), String(demoHolder._id));
    assert.equal(primary.balance.toString(), "1000.0000");
    const openingEntries = await LedgerEntry.find({ accountId: primary._id });
    assert.equal(openingEntries.length, 1);
    assert.equal(openingEntries[0].entrySide, "CREDIT");
    const openingTransaction = await Transaction.findById(openingEntries[0].transactionId);
    assert.equal(openingTransaction.transactionType, "DEPOSIT");
    assert.equal(openingTransaction.amount.toString(), "1000.0000");
    const balancedOpeningEntries = await LedgerEntry.find({ transactionId: openingTransaction._id });
    assert.equal(balancedOpeningEntries.length, 2);
    assert.deepEqual(balancedOpeningEntries.map((entry) => entry.entrySide).sort(), ["CREDIT", "DEBIT"]);
    assert.equal(balancedOpeningEntries[0].amount.toString(), balancedOpeningEntries[1].amount.toString());

    const server = app.listen(0);
    try {
      await once(server, "listening");
      const { port } = server.address();
      const demoRun = await execFileAsync(process.execPath, ["scripts/demo-concurrency.js"], {
        cwd: new URL("..", import.meta.url),
        env: {
          ...process.env,
          DEMO_API_URL: `http://127.0.0.1:${port}`,
          DEMO_CREDENTIALS_PATH: credentialPath,
        },
      });
      assert.match(demoRun.stdout, /same transaction/);
      assert.match(demoRun.stdout, /one posted, one was refused/);
      const afterDemo = await Account.findById(primary._id);
      assert.equal(afterDemo.balance.toString(), "500.4999");
    } finally {
      await new Promise((resolve, reject) => {
        server.close((error) => error ? reject(error) : resolve());
      });
    }
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});
