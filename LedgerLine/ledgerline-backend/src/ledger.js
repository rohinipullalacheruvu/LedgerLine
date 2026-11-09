import { createHash, randomUUID } from "node:crypto";
import Decimal from "decimal.js";
import mongoose from "mongoose";
import {
  Account,
  AccountPermission,
  AuditLog,
  IdempotencyRecord,
  LedgerEntry,
  Transaction,
  User,
  decimal as mongoDecimal,
} from "./models.js";
import {
  ApiError,
  parseAmount,
  validateDescription,
  validateIdempotencyKey,
} from "./validation.js";

const CUSTOMER_TYPES = ["CHECKING", "SAVINGS", "CASH"];
const ACCOUNT_STATUSES = ["ACTIVE", "FROZEN", "CLOSED"];
const TRANSACTION_TYPES = ["DEPOSIT", "WITHDRAWAL", "TRANSFER", "ADJUSTMENT"];
const decimalValue = (value) => new Decimal(value?.toString() || "0");
const numberValue = (value) => Number(decimalValue(value).toFixed(4));
const idValue = (value) => value == null ? null : String(value);

function isObjectId(value) {
  return mongoose.isValidObjectId(value);
}

function assertObjectId(value, label) {
  if (!isObjectId(value)) throw new ApiError(400, `${label} is invalid`, "Invalid request");
}

function accountSummary(account) {
  return {
    id: idValue(account._id),
    accountNumber: account.accountNumber,
    name: account.name,
    accountType: account.accountType,
    currency: account.currency,
    balance: numberValue(account.balance),
    status: account.status,
  };
}

function adminAccountSummary(account) {
  const owner = account.ownerId && typeof account.ownerId === "object"
    ? account.ownerId
    : null;
  return {
    ...accountSummary(account),
    ownerId: owner ? idValue(owner._id) : idValue(account.ownerId),
    ownerUsername: owner?.username || null,
    createdAt: account.createdAt,
  };
}

function assertRole(user, ...roles) {
  if (!roles.includes(user.role)) {
    throw new ApiError(403, "You are not allowed to perform this operation", "Forbidden");
  }
}

async function assertCanView(user, account) {
  if (["ADMIN", "AUDITOR"].includes(user.role)) return;
  if (idValue(account.ownerId) === idValue(user._id)) return;
  const permission = await AccountPermission.findOne({
    accountId: account._id,
    userId: user._id,
    role: { $in: ["ACCOUNT_ADMIN", "TRANSACT", "VIEW"] },
  });
  if (!permission) throw new ApiError(403, "You do not have permission to view this account", "Forbidden");
}

async function assertCanTransact(user, account) {
  if (user.role !== "ACCOUNT_HOLDER") {
    throw new ApiError(403, "Only account holders can perform account transactions", "Forbidden");
  }
  if (idValue(account.ownerId) === idValue(user._id)) return;
  const permission = await AccountPermission.findOne({
    accountId: account._id,
    userId: user._id,
    role: { $in: ["ACCOUNT_ADMIN", "TRANSACT"] },
  });
  if (!permission) throw new ApiError(403, "You do not have permission to transact on this account", "Forbidden");
}

async function assertCanManage(user, account) {
  if (user.role === "ADMIN" || idValue(account.ownerId) === idValue(user._id)) return;
  if (user.role !== "ACCOUNT_HOLDER") {
    throw new ApiError(403, "You do not have permission to manage this account", "Forbidden");
  }
  const permission = await AccountPermission.findOne({
    accountId: account._id,
    userId: user._id,
    role: "ACCOUNT_ADMIN",
  });
  if (!permission) throw new ApiError(403, "You do not have permission to manage this account", "Forbidden");
}

async function findAccount(accountId, session) {
  assertObjectId(accountId, "accountId");
  const query = Account.findById(accountId);
  if (session) query.session(session);
  const account = await query;
  if (!account) throw new ApiError(404, "Account was not found", "Not found");
  return account;
}

function requestHash(...values) {
  return createHash("sha256").update(JSON.stringify(values)).digest("hex");
}

function transferResponse(transaction) {
  return {
    transactionReference: `LLTX-${transaction._id}`,
    senderAccountNumber: transaction.sourceAccountNumber,
    receiverAccountNumber: transaction.destinationAccountNumber,
    amount: numberValue(transaction.amount),
    currency: transaction.currency,
    status: transaction.status,
    createdAt: transaction.createdAt,
    postedAt: transaction.postedAt,
  };
}

function moneyResponse(transaction, account) {
  return {
    transactionId: idValue(transaction._id),
    accountId: idValue(account._id),
    accountNumber: account.accountNumber,
    transactionType: transaction.transactionType,
    status: transaction.status,
    amount: numberValue(transaction.amount),
    currency: transaction.currency,
    createdAt: transaction.createdAt,
    postedAt: transaction.postedAt,
  };
}

function operationFailure(code, message) {
  return { failureCode: code, failureMessage: message };
}

async function executeIdempotent(user, rawKey, hash, action) {
  const key = validateIdempotencyKey(rawKey);
  const session = await mongoose.startSession();
  let outcome;
  try {
    await session.withTransaction(async () => {
      const existing = await IdempotencyRecord.findOne({
        userId: user._id,
        idempotencyKey: key,
      }).session(session);
      if (existing) {
        if (existing.requestHash !== hash) {
          throw new ApiError(409, "Idempotency-Key has already been used with different request details", "Conflict");
        }
        if (existing.status === "FAILED") {
          outcome = operationFailure(existing.failureCode, existing.failureMessage);
          return;
        }
        if (existing.status !== "COMPLETED" || !existing.response) {
          throw new ApiError(409, "The request associated with this Idempotency-Key is not complete", "Conflict");
        }
        outcome = existing.response;
        return;
      }

      const record = new IdempotencyRecord({
        userId: user._id,
        idempotencyKey: key,
        requestHash: hash,
        status: "PROCESSING",
      });
      await record.save({ session });

      const result = await action(session);
      if (result.failureCode) {
        record.status = "FAILED";
        record.failureCode = result.failureCode;
        record.failureMessage = result.failureMessage;
        await record.save({ session });
        outcome = result;
        return;
      }

      record.status = "COMPLETED";
      record.transactionId = result.transactionId;
      record.response = result.response;
      await record.save({ session });
      outcome = result.response;
    }, {
      readConcern: { level: "snapshot" },
      writeConcern: { w: "majority" },
      readPreference: "primary",
    });
  } catch (error) {
    if (error.code === 11000) {
      const existing = await IdempotencyRecord.findOne({ userId: user._id, idempotencyKey: key });
      if (existing) {
        if (existing.requestHash !== hash) {
          throw new ApiError(409, "Idempotency-Key has already been used with different request details", "Conflict");
        }
        if (existing.status === "COMPLETED" && existing.response) return existing.response;
        if (existing.status === "FAILED") {
          throw new ApiError(409, existing.failureMessage, existing.failureCode);
        }
      }
    }
    if (error.code === 20 || error.codeName === "IllegalOperation"
      || /Transaction numbers are only allowed|replica set|mongos/i.test(error.message || "")) {
      throw new ApiError(
        503,
        "Financial operations require MongoDB running as a replica set. Configure MONGO_URI with a replicaSet option.",
        "Database transactions unavailable",
      );
    }
    throw error;
  } finally {
    await session.endSession();
  }

  if (outcome?.failureCode) {
    throw new ApiError(409, outcome.failureMessage, outcome.failureCode);
  }
  return outcome;
}

async function addAudit(session, userId, action, entityType, entityId, details = {}) {
  const record = {
    actorUserId: userId,
    action,
    entityType,
    entityId,
    details: Object.fromEntries(Object.entries(details).map(([key, value]) => [key, String(value)])),
  };
  const auditRecord = new AuditLog(record);
  await auditRecord.save(session ? { session } : undefined);
}

function ensureBalanceRange(value, allowNegative = false) {
  if (!allowNegative && value.lt(0)) return false;
  return value.abs().lte("999999999999999.9999");
}

async function saveAccountsInOrder(accounts, session) {
  const ordered = [...accounts].sort((first, second) =>
    String(first._id).localeCompare(String(second._id)));
  for (const account of ordered) {
    account.version += 1;
    await account.save({ session });
  }
}

async function createPostedTransaction({
  user,
  source,
  destination,
  type,
  amount,
  description,
  session,
}) {
  const postedAt = new Date();
  const transaction = new Transaction({
    initiatedByUserId: user._id,
    sourceAccountId: source?._id || null,
    destinationAccountId: destination?._id || null,
    transactionType: type,
    status: "POSTED",
    amount: mongoose.Types.Decimal128.fromString(amount),
    currency: (source || destination).currency,
    description,
    postedAt,
  });
  await transaction.save({ session });
  const entries = [
    new LedgerEntry({
      transactionId: transaction._id,
      accountId: source._id,
      entrySide: "DEBIT",
      amount: mongoose.Types.Decimal128.fromString(amount),
    }),
    new LedgerEntry({
      transactionId: transaction._id,
      accountId: destination._id,
      entrySide: "CREDIT",
      amount: mongoose.Types.Decimal128.fromString(amount),
    }),
  ];
  for (const entry of entries) await entry.save({ session });
  return transaction;
}

export async function createCustomerAccount(user, body) {
  assertRole(user, "ADMIN", "ACCOUNT_HOLDER");
  const name = typeof body.name === "string" ? body.name.trim() : "";
  const accountType = body.accountType;
  const currency = typeof body.currency === "string" ? body.currency.trim().toUpperCase() : "";
  if (!name || name.length > 100 || !CUSTOMER_TYPES.includes(accountType) || !/^[A-Z]{3}$/.test(currency)) {
    throw new ApiError(400, "Provide a valid name, account type, and three-letter currency", "Invalid request");
  }
  const accountNumber = `LL${randomUUID().replaceAll("-", "").toUpperCase()}`;
  let account;
  if (user.role === "ADMIN") {
    const session = await mongoose.startSession();
    try {
      await session.withTransaction(async () => {
        account = new Account({
          ownerId: user._id,
          accountNumber,
          name,
          accountType,
          currency,
        });
        await account.save({ session });
        await addAudit(session, user._id, "ADMIN_ACCOUNT_CREATED", "ACCOUNT", account._id, {
          accountNumber: account.accountNumber,
          accountType: account.accountType,
        });
      });
    } finally {
      await session.endSession();
    }
  } else {
    account = new Account({
      ownerId: user._id,
      accountNumber,
      name,
      accountType,
      currency,
    });
    await account.save();
  }
  return accountSummary(account);
}

export async function listAccounts(user) {
  const accounts = user.role === "ADMIN"
    ? await Account.find().sort({ createdAt: -1 }).populate("ownerId", "username")
    : await Account.find({ ownerId: user._id }).sort({ createdAt: -1 });
  return accounts.map(user.role === "ADMIN" ? adminAccountSummary : accountSummary);
}

export async function listAllAccountsForOversight(user) {
  assertRole(user, "ADMIN", "AUDITOR");
  const accounts = await Account.find().sort({ createdAt: -1 }).populate("ownerId", "username");
  return accounts.map(adminAccountSummary);
}

export async function getAccount(user, accountId) {
  const account = await findAccount(accountId);
  await assertCanView(user, account);
  return user.role === "ADMIN"
    ? adminAccountSummary(await account.populate("ownerId", "username"))
    : accountSummary(account);
}

export async function getAccountBalance(user, accountId) {
  const account = await findAccount(accountId);
  await assertCanView(user, account);
  return {
    accountId: idValue(account._id),
    accountNumber: account.accountNumber,
    currency: account.currency,
    balance: numberValue(account.balance),
  };
}

export async function renameAccount(user, accountId, body) {
  const account = await findAccount(accountId);
  await assertCanManage(user, account);
  const name = typeof body.name === "string" ? body.name.trim() : "";
  if (!name || name.length > 100 || Object.keys(body).some((field) => field !== "name")) {
    throw new ApiError(400, "Only a valid account name can be changed", "Invalid request");
  }
  account.name = name;
  await account.save();
  return accountSummary(account);
}

export async function updateAdminAccount(user, accountId, body) {
  assertRole(user, "ADMIN");
  const fields = Object.keys(body);
  if (!fields.length || fields.some((field) => !["name", "status"].includes(field))) {
    throw new ApiError(400, "Only name and status can be changed", "Invalid request");
  }
  const session = await mongoose.startSession();
  let account;
  try {
    await session.withTransaction(async () => {
      account = await findAccount(accountId, session);
      if (body.name !== undefined) {
        const name = typeof body.name === "string" ? body.name.trim() : "";
        if (!name || name.length > 100) throw new ApiError(400, "name is invalid", "Invalid request");
        account.name = name;
      }
      if (body.status !== undefined) {
        if (!ACCOUNT_STATUSES.includes(body.status)) throw new ApiError(400, "status is invalid", "Invalid request");
        account.status = body.status;
      }
      await account.save({ session });
      await addAudit(session, user._id, "ADMIN_ACCOUNT_UPDATED", "ACCOUNT", account._id, {
        changedFields: fields.join(","),
        status: account.status,
      });
    });
  } finally {
    await session.endSession();
  }
  return adminAccountSummary(await account.populate("ownerId", "username"));
}

export async function portfolioSummary(user) {
  const isHolder = user.role === "ACCOUNT_HOLDER";
  if (!["ADMIN", "AUDITOR", "ACCOUNT_HOLDER"].includes(user.role)) {
    throw new ApiError(403, "Account portfolio access is required", "Forbidden");
  }
  const accountFilter = isHolder
    ? { ownerId: user._id, accountType: { $ne: "EXTERNAL_CLEARING" } }
    : { ownerId: { $ne: null }, accountType: { $ne: "EXTERNAL_CLEARING" } };
  const balances = await Account.aggregate([
    { $match: accountFilter },
    { $group: { _id: "$currency", totalBalance: { $sum: "$balance" }, accountCount: { $sum: 1 } } },
    { $sort: { _id: 1 } },
  ]);
  const result = new Map(balances.map((row) => [row._id, {
    currency: row._id,
    totalBalance: numberValue(row.totalBalance),
    accountCount: row.accountCount,
    monthlyDeposits: 0,
    monthlyDepositCount: 0,
    monthlyWithdrawals: 0,
    monthlyWithdrawalCount: 0,
  }]));
  const now = new Date();
  const from = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), 1));
  const to = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth() + 1, 1));
  const movements = await Transaction.find({
    status: "POSTED",
    transactionType: { $in: ["DEPOSIT", "WITHDRAWAL"] },
    postedAt: { $gte: from, $lt: to },
  }).lean();
  const accountIds = [...new Set(movements.flatMap((item) =>
    [item.sourceAccountId, item.destinationAccountId].filter(Boolean).map(String)))];
  const relatedAccounts = await Account.find({ _id: { $in: accountIds } }).select("ownerId");
  const accountOwners = new Map(relatedAccounts.map((item) => [String(item._id), idValue(item.ownerId)]));
  for (const movement of movements) {
    const customerAccountId = movement.transactionType === "DEPOSIT"
      ? idValue(movement.destinationAccountId)
      : idValue(movement.sourceAccountId);
    if (!customerAccountId || accountOwners.get(customerAccountId) == null) continue;
    if (isHolder && accountOwners.get(customerAccountId) !== idValue(user._id)) continue;
    const row = result.get(movement.currency) || {
      currency: movement.currency,
      totalBalance: 0,
      accountCount: 0,
      monthlyDeposits: 0,
      monthlyDepositCount: 0,
      monthlyWithdrawals: 0,
      monthlyWithdrawalCount: 0,
    };
    const amount = numberValue(movement.amount);
    if (movement.transactionType === "DEPOSIT") {
      row.monthlyDeposits = Number(new Decimal(row.monthlyDeposits).plus(amount).toFixed(4));
      row.monthlyDepositCount += 1;
    } else {
      row.monthlyWithdrawals = Number(new Decimal(row.monthlyWithdrawals).plus(amount).toFixed(4));
      row.monthlyWithdrawalCount += 1;
    }
    result.set(movement.currency, row);
  }
  return [...result.values()].sort((first, second) => first.currency.localeCompare(second.currency));
}

export async function statement(user, accountId, filters) {
  const account = await findAccount(accountId);
  await assertCanView(user, account);
  const fromDate = filters.fromDate ? parseUtcDate(filters.fromDate, "fromDate") : null;
  const toDate = filters.toDate ? parseUtcDate(filters.toDate, "toDate") : null;
  if (fromDate && toDate && fromDate > toDate) {
    throw new ApiError(400, "fromDate must be on or before toDate", "Invalid request");
  }
  const transactionType = filters.transactionType || null;
  if (transactionType && !TRANSACTION_TYPES.includes(transactionType)) {
    throw new ApiError(400, "transactionType is invalid", "Invalid request");
  }
  const entries = await LedgerEntry.find({ accountId: account._id })
    .sort({ createdAt: 1, _id: 1 })
    .populate({
      path: "transactionId",
      populate: { path: "initiatedByUserId", select: "username fullName" },
    })
    .lean();
  let runningBalance = new Decimal(0);
  const result = [];
  for (const entry of entries) {
    const transaction = entry.transactionId;
    if (!transaction) throw new Error("Ledger entry has no associated transaction");
    const sourceId = idValue(transaction.sourceAccountId);
    const destinationId = idValue(transaction.destinationAccountId);
    const amount = decimalValue(entry.amount);
    if (destinationId === idValue(account._id)) runningBalance = runningBalance.plus(amount);
    else if (sourceId === idValue(account._id)) runningBalance = runningBalance.minus(amount);
    else runningBalance = entry.entrySide === "DEBIT"
      ? runningBalance.plus(amount)
      : runningBalance.minus(amount);

    const transactionDate = transaction.postedAt || transaction.createdAt;
    if ((fromDate && transactionDate < fromDate)
      || (toDate && transactionDate >= new Date(toDate.getTime() + 86_400_000))
      || (transactionType && transaction.transactionType !== transactionType)) continue;
    result.push({
      transactionId: idValue(transaction._id),
      transactionDate,
      transactionType: transaction.transactionType,
      status: transaction.status,
      entrySide: entry.entrySide,
      amount: numberValue(entry.amount),
      currency: transaction.currency,
      description: transaction.description,
      initiatedByUsername: transaction.initiatedByUserId?.username || "",
      initiatedByFullName: transaction.initiatedByUserId?.fullName || "",
      runningBalance: Number(runningBalance.toFixed(4)),
    });
  }
  return {
    accountId: idValue(account._id),
    accountNumber: account.accountNumber,
    currency: account.currency,
    currentBalance: numberValue(account.balance),
    fromDate: filters.fromDate || null,
    toDate: filters.toDate || null,
    entries: result,
  };
}

function parseUtcDate(value, field) {
  if (typeof value !== "string" || !/^\d{4}-\d{2}-\d{2}$/.test(value)) {
    throw new ApiError(400, `${field} must be an ISO date (YYYY-MM-DD)`, "Invalid request");
  }
  const parsed = new Date(`${value}T00:00:00.000Z`);
  if (Number.isNaN(parsed.getTime()) || parsed.toISOString().slice(0, 10) !== value) {
    throw new ApiError(400, `${field} must be an ISO date (YYYY-MM-DD)`, "Invalid request");
  }
  return parsed;
}

export async function postMoneyOperation(user, accountId, type, body, rawKey) {
  assertRole(user, "ACCOUNT_HOLDER");
  const amount = parseAmount(body.amount);
  const description = validateDescription(body.description);
  const account = await findAccount(accountId);
  await assertCanTransact(user, account);
  if (account.accountType === "EXTERNAL_CLEARING") {
    throw new ApiError(404, "Account was not found", "Not found");
  }
  await ensureClearingAccount(account.currency);
  const clearingAccountNumber = clearingAccountNumberFor(account.currency);
  const hash = requestHash(type, String(account._id), amount, description);
  return executeIdempotent(user, rawKey, hash, async (session) => {
    const ids = [account._id];
    const clearingLookup = await Account.findOne({
      accountNumber: clearingAccountNumber,
    }).session(session);
    if (!clearingLookup
      || clearingLookup.accountType !== "EXTERNAL_CLEARING"
      || clearingLookup.ownerId != null
      || clearingLookup.status !== "ACTIVE") {
      throw new ApiError(503, "The designated clearing account is missing or misconfigured", "Clearing account unavailable");
    }
    ids.push(clearingLookup._id);
    const lockedAccounts = await Account.find({ _id: { $in: ids } }).session(session);
    const customer = lockedAccounts.find((item) => idValue(item._id) === idValue(account._id));
    const clearing = lockedAccounts.find((item) => idValue(item._id) === idValue(clearingLookup._id));
    if (!customer || !clearing) throw new ApiError(404, "Account was not found", "Not found");
    if (customer.currency !== clearing.currency) {
      throw new ApiError(503, "The account currency does not match the configured clearing account currency", "Clearing account unavailable");
    }
    if (customer.status !== "ACTIVE") {
      await addAudit(session, user._id, `${type}_REFUSED`, "ACCOUNT", customer._id, {
        operation: type, amount, currency: customer.currency, reason: "Account is not active",
      });
      return operationFailure("ACCOUNT_NOT_ACTIVE", "Account is not active");
    }
    const amountDecimal = new Decimal(amount);
    if (type === "WITHDRAWAL" && decimalValue(customer.balance).lt(amountDecimal)) {
      await addAudit(session, user._id, `${type}_REFUSED`, "ACCOUNT", customer._id, {
        operation: type, amount, currency: customer.currency, reason: "Insufficient balance",
      });
      return operationFailure("INSUFFICIENT_BALANCE", "Insufficient balance");
    }

    const source = type === "DEPOSIT" ? clearing : customer;
    const destination = type === "DEPOSIT" ? customer : clearing;
    const transaction = await createPostedTransaction({
      user, source, destination, type, amount, description, session,
    });
    const signedAmount = type === "DEPOSIT" ? amountDecimal : amountDecimal.negated();
    const customerBalance = decimalValue(customer.balance).plus(signedAmount);
    const clearingBalance = decimalValue(clearing.balance).minus(signedAmount);
    if (!ensureBalanceRange(customerBalance) || !ensureBalanceRange(clearingBalance, true)) {
      throw new ApiError(409, "The operation exceeds the supported account balance range", "Balance limit exceeded");
    }
    customer.balance = mongoDecimal(customerBalance);
    clearing.balance = mongoDecimal(clearingBalance);
    await saveAccountsInOrder([customer, clearing], session);

    return {
      transactionId: transaction._id,
      response: moneyResponse(transaction, customer),
    };
  });
}

export async function transfer(user, body, rawKey) {
  assertRole(user, "ACCOUNT_HOLDER");
  const senderNumber = typeof body.senderAccountNumber === "string" ? body.senderAccountNumber.trim() : "";
  const receiverNumber = typeof body.receiverAccountNumber === "string" ? body.receiverAccountNumber.trim() : "";
  if (!senderNumber || senderNumber.length > 34 || !receiverNumber || receiverNumber.length > 34) {
    throw new ApiError(400, "Sender and receiver account numbers are required", "Invalid request");
  }
  if (senderNumber === receiverNumber) {
    throw new ApiError(400, "Sender and receiver accounts must be different", "Invalid transfer");
  }
  const amount = parseAmount(body.amount);
  const description = validateDescription(body.description);
  const hash = requestHash("TRANSFER", senderNumber, receiverNumber, amount, description);
  return executeIdempotent(user, rawKey, hash, async (session) => {
    const senderCandidate = await Account.findOne({ accountNumber: senderNumber }).session(session);
    if (!senderCandidate || senderCandidate.accountType === "EXTERNAL_CLEARING") {
      throw new ApiError(404, "Account was not found", "Not found");
    }
    await assertCanTransact(user, senderCandidate);
    const accounts = await Account.find({
      accountNumber: { $in: [senderNumber, receiverNumber] },
    }).session(session);
    const sender = accounts.find((item) => item.accountNumber === senderNumber);
    const receiver = accounts.find((item) => item.accountNumber === receiverNumber);
    if (!sender || !receiver
      || sender.accountType === "EXTERNAL_CLEARING"
      || receiver.accountType === "EXTERNAL_CLEARING") {
      throw new ApiError(404, "Account was not found", "Not found");
    }
    if (sender.currency !== receiver.currency) {
      throw new ApiError(400, "Sender and receiver accounts must use the same currency", "Invalid transfer");
    }
    if (sender.status !== "ACTIVE" || receiver.status !== "ACTIVE") {
      await addAudit(session, user._id, "TRANSFER_REFUSED", "ACCOUNT", sender._id, {
        operation: "TRANSFER", amount, currency: sender.currency, reason: "Account is not active",
      });
      return operationFailure("ACCOUNT_NOT_ACTIVE", "Account is not active");
    }
    const amountDecimal = new Decimal(amount);
    if (decimalValue(sender.balance).lt(amountDecimal)) {
      await addAudit(session, user._id, "TRANSFER_REFUSED", "ACCOUNT", sender._id, {
        operation: "TRANSFER", amount, currency: sender.currency, reason: "Insufficient balance",
      });
      return operationFailure("INSUFFICIENT_BALANCE", "Insufficient balance");
    }
    const senderBalance = decimalValue(sender.balance).minus(amountDecimal);
    const receiverBalance = decimalValue(receiver.balance).plus(amountDecimal);
    if (!ensureBalanceRange(senderBalance) || !ensureBalanceRange(receiverBalance)) {
      throw new ApiError(409, "The operation exceeds the supported account balance range", "Balance limit exceeded");
    }
    const transaction = await createPostedTransaction({
      user,
      source: sender,
      destination: receiver,
      type: "TRANSFER",
      amount,
      description,
      session,
    });
    sender.balance = mongoDecimal(senderBalance);
    receiver.balance = mongoDecimal(receiverBalance);
    await saveAccountsInOrder([sender, receiver], session);
    transaction.sourceAccountNumber = sender.accountNumber;
    transaction.destinationAccountNumber = receiver.accountNumber;
    const response = {
      transactionReference: `LLTX-${transaction._id}`,
      senderAccountNumber: sender.accountNumber,
      receiverAccountNumber: receiver.accountNumber,
      amount: numberValue(transaction.amount),
      currency: transaction.currency,
      status: transaction.status,
      createdAt: transaction.createdAt,
      postedAt: transaction.postedAt,
    };
    return { transactionId: transaction._id, response };
  });
}

export async function adminOverview(user) {
  assertRole(user, "ADMIN");
  const [totalRegisteredUsers, totalAccounts, totalTransactions, postedTransactions] = await Promise.all([
    User.countDocuments(),
    Account.countDocuments(),
    Transaction.countDocuments(),
    Transaction.countDocuments({ status: "POSTED" }),
  ]);
  return { totalRegisteredUsers, totalAccounts, totalTransactions, postedTransactions };
}

export async function recentAuditRecords(user) {
  assertRole(user, "ADMIN", "AUDITOR");
  const records = await AuditLog.find()
    .sort({ createdAt: -1 })
    .limit(100)
    .populate("actorUserId", "username")
    .lean();
  return records.map((record) => ({
    id: idValue(record._id),
    action: record.action,
    entityType: record.entityType,
    entityId: idValue(record.entityId),
    actorUsername: record.actorUserId?.username || null,
    createdAt: record.createdAt,
    details: record.details instanceof Map
      ? Object.fromEntries(record.details.entries())
      : record.details || {},
  }));
}

export async function ledgerIntegrity(user) {
  assertRole(user, "AUDITOR");
  const zero = mongoose.Types.Decimal128.fromString("0.0000");
  const postings = await LedgerEntry.aggregate([
    {
      $group: {
        _id: "$transactionId",
        debitTotal: {
          $sum: { $cond: [{ $eq: ["$entrySide", "DEBIT"] }, "$amount", zero] },
        },
        creditTotal: {
          $sum: { $cond: [{ $eq: ["$entrySide", "CREDIT"] }, "$amount", zero] },
        },
        debitEntries: { $sum: { $cond: [{ $eq: ["$entrySide", "DEBIT"] }, 1, 0] } },
        creditEntries: { $sum: { $cond: [{ $eq: ["$entrySide", "CREDIT"] }, 1, 0] } },
        entryCount: { $sum: 1 },
      },
    },
    { $sort: { _id: 1 } },
  ]);
  const results = postings.map((posting) => {
    const debit = decimalValue(posting.debitTotal);
    const credit = decimalValue(posting.creditTotal);
    return {
      transactionId: idValue(posting._id),
      debitTotal: numberValue(debit),
      creditTotal: numberValue(credit),
      entryCount: posting.entryCount,
      balanced: debit.gt(0) && debit.eq(credit)
        && posting.debitEntries > 0 && posting.creditEntries > 0,
    };
  });
  return {
    totalTransactions: results.length,
    balancedTransactions: results.filter((item) => item.balanced).length,
    unbalancedTransactions: results.filter((item) => !item.balanced),
  };
}

export async function listTransactions(user) {
  let query = {};
  if (user.role === "ACCOUNT_HOLDER") {
    const accountIds = await Account.find({
      $or: [
        { ownerId: user._id },
        { _id: { $in: await AccountPermission.distinct("accountId", {
          userId: user._id,
          role: { $in: ["ACCOUNT_ADMIN", "TRANSACT", "VIEW"] },
        }) } },
      ],
    }).distinct("_id");
    query = {
      $or: [
        { sourceAccountId: { $in: accountIds } },
        { destinationAccountId: { $in: accountIds } },
      ],
    };
  } else if (!["ADMIN", "AUDITOR"].includes(user.role)) {
    throw new ApiError(403, "Transaction history access is required", "Forbidden");
  }
  const transactions = await Transaction.find(query)
    .sort({ postedAt: -1, _id: -1 })
    .limit(500)
    .lean();
  return transactions.map((item) => ({
    transactionId: idValue(item._id),
    accountId: idValue(item.sourceAccountId || item.destinationAccountId),
    transactionType: item.transactionType,
    status: item.status,
    amount: numberValue(item.amount),
    currency: item.currency,
    createdAt: item.createdAt,
    postedAt: item.postedAt,
  }));
}

export async function listLedgerEntries(user) {
  if (!["ADMIN", "AUDITOR", "ACCOUNT_HOLDER"].includes(user.role)) {
    throw new ApiError(403, "Ledger history access is required", "Forbidden");
  }
  let accountIds = null;
  if (user.role === "ACCOUNT_HOLDER") {
    accountIds = await Account.find({
      $or: [
        { ownerId: user._id },
        { _id: { $in: await AccountPermission.distinct("accountId", {
          userId: user._id,
          role: { $in: ["ACCOUNT_ADMIN", "TRANSACT", "VIEW"] },
        }) } },
      ],
    }).distinct("_id");
  }
  const query = accountIds ? { accountId: { $in: accountIds } } : {};
  const entries = await LedgerEntry.find(query)
    .sort({ createdAt: -1, _id: -1 })
    .limit(1000)
    .lean();
  return entries.map((item) => ({
    id: idValue(item._id),
    transactionId: idValue(item.transactionId),
    accountId: idValue(item.accountId),
    entrySide: item.entrySide,
    amount: numberValue(item.amount),
    createdAt: item.createdAt,
  }));
}

function clearingAccountNumberFor(currency) {
  const configuredCurrency = (process.env.CLEARING_CURRENCY || "USD").toUpperCase();
  return currency === configuredCurrency
    ? process.env.CLEARING_ACCOUNT_NUMBER || `LL-CLEARING-${configuredCurrency}`
    : `LL-CLEARING-${currency}`;
}

export async function ensureClearingAccount(currency = process.env.CLEARING_CURRENCY || "USD") {
  const normalizedCurrency = currency.toUpperCase();
  const accountNumber = clearingAccountNumberFor(normalizedCurrency);
  const existing = await Account.findOne({ accountNumber });
  if (existing) {
    if (existing.accountType !== "EXTERNAL_CLEARING"
      || existing.ownerId != null
      || existing.currency !== normalizedCurrency) {
      throw new Error(`Clearing account ${accountNumber} is misconfigured`);
    }
    return existing;
  }
  try {
    return await Account.create({
      ownerId: null,
      accountNumber,
      name: "External clearing account",
      accountType: "EXTERNAL_CLEARING",
      currency: normalizedCurrency,
      status: "ACTIVE",
      balance: mongoDecimal(0),
    });
  } catch (error) {
    if (error.code !== 11000) throw error;
    const raced = await Account.findOne({ accountNumber });
    if (raced?.accountType === "EXTERNAL_CLEARING"
      && raced.ownerId == null
      && raced.currency === normalizedCurrency) return raced;
    throw error;
  }
}
