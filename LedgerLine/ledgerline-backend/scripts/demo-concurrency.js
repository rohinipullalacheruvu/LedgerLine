import "dotenv/config";
import { randomUUID } from "node:crypto";
import { readFile } from "node:fs/promises";
import { isAbsolute } from "node:path";
import { fileURLToPath } from "node:url";

const apiBaseUrl = (process.env.DEMO_API_URL || "http://127.0.0.1:8080").replace(/\/+$/, "");
const credentialPath = process.env.DEMO_CREDENTIALS_PATH
  ? isAbsolute(process.env.DEMO_CREDENTIALS_PATH)
    ? process.env.DEMO_CREDENTIALS_PATH
    : fileURLToPath(new URL(process.env.DEMO_CREDENTIALS_PATH, import.meta.url))
  : fileURLToPath(new URL("../.demo-credentials.json", import.meta.url));

async function request(path, { method = "GET", token, key, body } = {}) {
  const response = await fetch(`${apiBaseUrl}${path}`, {
    method,
    headers: {
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...(key ? { "Idempotency-Key": key } : {}),
      ...(body ? { "Content-Type": "application/json" } : {}),
    },
    ...(body ? { body: JSON.stringify(body) } : {}),
  });
  const data = response.status === 204 ? null : await response.json();
  return { status: response.status, data };
}

function assert(condition, message) {
  if (!condition) throw new Error(message);
}

const credentials = JSON.parse(await readFile(credentialPath, "utf8"));
const holder = credentials.users.find((user) => user.role === "ACCOUNT_HOLDER");
const account = credentials.accounts.primary;
assert(holder && account, "Demo holder credentials or primary account are missing");

const login = await request("/api/auth/login", {
  method: "POST",
  body: { usernameOrEmail: holder.username, password: holder.password },
});
assert(login.status === 200, `Demo login failed (${login.status})`);
const token = login.data.accessToken;

const duplicateKey = `demo-duplicate-${randomUUID()}`;
const duplicateBody = { amount: "1.0000", description: "Five-minute demo idempotency check" };
const duplicateResults = await Promise.all([
  request(`/api/accounts/${account.id}/deposits`, {
    method: "POST", token, key: duplicateKey, body: duplicateBody,
  }),
  request(`/api/accounts/${account.id}/deposits`, {
    method: "POST", token, key: duplicateKey, body: duplicateBody,
  }),
]);
assert(duplicateResults.every((result) => result.status === 201), "Duplicate request did not replay successfully");
assert(
  duplicateResults[0].data.transactionId === duplicateResults[1].data.transactionId,
  "Duplicate request created different transactions",
);
console.info("Duplicate request: both requests returned the same transaction.");

const balanceResponse = await request(`/api/accounts/${account.id}/balance`, { token });
assert(balanceResponse.status === 200, "Could not read the demo account balance");
const balanceUnits = Math.round(Number(balanceResponse.data.balance) * 10000);
assert(Number.isSafeInteger(balanceUnits) && balanceUnits > 0, "Demo account needs a positive balance");
const attemptedUnits = Math.floor(balanceUnits / 2) + 1;
const amount = `${Math.floor(attemptedUnits / 10000)}.${String(attemptedUnits % 10000).padStart(4, "0")}`;

const withdrawals = await Promise.all([
  request(`/api/accounts/${account.id}/withdrawals`, {
    method: "POST", token, key: `demo-withdraw-${randomUUID()}`, body: { amount },
  }),
  request(`/api/accounts/${account.id}/withdrawals`, {
    method: "POST", token, key: `demo-withdraw-${randomUUID()}`, body: { amount },
  }),
]);
assert(withdrawals.filter((result) => result.status === 201).length === 1, "Expected exactly one concurrent withdrawal to post");
assert(withdrawals.filter((result) => result.status === 409).length === 1, "Expected the competing withdrawal to be refused");

const finalBalance = await request(`/api/accounts/${account.id}/balance`, { token });
assert(finalBalance.status === 200, "Could not verify the balance after concurrent withdrawals");
assert(Number(finalBalance.data.balance) >= 0, "Concurrent withdrawals produced a negative balance");
console.info(`Concurrent withdrawals: one posted, one was refused; balance remains ${finalBalance.data.balance} ${finalBalance.data.currency}.`);
