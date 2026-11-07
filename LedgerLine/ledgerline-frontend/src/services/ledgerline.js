import { api, postIdempotent } from "./api";

export function listAccounts(role) {
  if (role === "ADMIN") return api.get("/api/admin/accounts").then(({ data }) => data);
  if (role === "AUDITOR") return api.get("/api/auditor/accounts").then(({ data }) => data);
  return api.get("/api/accounts").then(({ data }) => data);
}

export function getPortfolioBalanceSummary() {
  return api.get("/api/accounts/summary").then(({ data }) => data);
}

export function getAccount(accountId, role) {
  const path = role === "ADMIN"
    ? `/api/admin/accounts/${accountId}`
    : `/api/accounts/${accountId}`;
  return api.get(path).then(({ data }) => data);
}

export function getStatement(accountId, filters = {}) {
  const params = {};
  if (filters.fromDate) params.fromDate = filters.fromDate;
  if (filters.toDate) params.toDate = filters.toDate;
  if (filters.transactionType) params.transactionType = filters.transactionType;
  return api.get(`/api/accounts/${accountId}/statement`, { params }).then(({ data }) => data);
}

export function getAccountBalance(accountId) {
  return api.get(`/api/accounts/${accountId}/balance`).then(({ data }) => data);
}

export function createAccount(values) {
  return api.post("/api/accounts", values).then(({ data }) => data);
}

export function updateAdminAccount(accountId, values) {
  return api.patch(`/api/admin/accounts/${accountId}`, values).then(({ data }) => data);
}

export function moneyOperation(accountId, operation, values) {
  const path = `/api/accounts/${accountId}/${operation === "DEPOSIT" ? "deposits" : "withdrawals"}`;
  return postIdempotent(path, values);
}

export function transferMoney(values) {
  return postIdempotent("/api/transfers", values);
}

export async function loadStatements(accounts, filters = {}) {
  const results = await Promise.allSettled(
    accounts.map(async (account) => ({
      ...await getStatement(account.id, filters),
      accountNumber: account.accountNumber,
      accountName: account.name,
      accountType: account.accountType,
    })),
  );
  const statements = [];
  const errors = [];
  results.forEach((result, index) => {
    if (result.status === "fulfilled") statements.push(result.value);
    else errors.push({ account: accounts[index], error: result.reason });
  });
  return { statements, errors };
}
