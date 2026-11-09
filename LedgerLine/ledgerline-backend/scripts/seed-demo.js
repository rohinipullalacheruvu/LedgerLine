import "dotenv/config";
import { randomBytes, randomUUID } from "node:crypto";
import { existsSync, unlinkSync, writeFileSync } from "node:fs";
import { isAbsolute } from "node:path";
import { fileURLToPath } from "node:url";
import bcrypt from "bcryptjs";
import Decimal from "decimal.js";
import mongoose from "mongoose";
import { Account, LedgerEntry, Transaction, User, decimal } from "../src/models.js";
import { ensureClearingAccount } from "../src/ledger.js";

const usernames = ["demo-admin", "demo-holder", "demo-auditor"];
const credentialPath = process.env.DEMO_CREDENTIALS_PATH
  ? isAbsolute(process.env.DEMO_CREDENTIALS_PATH)
    ? process.env.DEMO_CREDENTIALS_PATH
    : fileURLToPath(new URL(process.env.DEMO_CREDENTIALS_PATH, import.meta.url))
  : fileURLToPath(new URL("../.demo-credentials.json", import.meta.url));

if (process.env.NODE_ENV === "production") {
  throw new Error("Demo seeding is disabled when NODE_ENV=production");
}
if (process.env.ALLOW_DEMO_SEED !== "true") {
  throw new Error("Set ALLOW_DEMO_SEED=true to explicitly enable demo account seeding");
}
if (!process.env.MONGO_URI) throw new Error("MONGO_URI is required");
if (existsSync(credentialPath)) {
  throw new Error(`Demo credentials already exist at ${credentialPath}; preserve them and do not reseed`);
}

const password = () => randomBytes(32).toString("base64url");
const demoUsers = [
  { username: usernames[0], email: "demo-admin@ledgerline.invalid", fullName: "Demo Administrator", role: "ADMIN", password: password() },
  { username: usernames[1], email: "demo-holder@ledgerline.invalid", fullName: "Demo Account Holder", role: "ACCOUNT_HOLDER", password: password() },
  { username: usernames[2], email: "demo-auditor@ledgerline.invalid", fullName: "Demo Auditor", role: "AUDITOR", password: password() },
];
const accountNumbers = [
  `LLDEMO${randomUUID().replaceAll("-", "").slice(0, 24).toUpperCase()}`,
  `LLDEMO${randomUUID().replaceAll("-", "").slice(0, 24).toUpperCase()}`,
];
const hashes = await Promise.all(demoUsers.map((user) => bcrypt.hash(user.password, 12)));
const users = demoUsers.map((user, index) => new User({
  username: user.username,
  email: user.email,
  fullName: user.fullName,
  role: user.role,
  passwordHash: hashes[index],
}));
const accounts = [
  new Account({
    ownerId: users[1]._id,
    accountNumber: accountNumbers[0],
    name: "Demo Primary Checking",
    accountType: "CHECKING",
    currency: "USD",
  }),
  new Account({
    ownerId: users[1]._id,
    accountNumber: accountNumbers[1],
    name: "Demo Savings",
    accountType: "SAVINGS",
    currency: "USD",
  }),
];
let credentialFileCreated = false;

try {
  writeFileSync(credentialPath, `${JSON.stringify({
    warning: "Local demo credentials. Never commit, share, or use these accounts in production.",
    users: demoUsers.map(({ username, email, role, password: userPassword }) => ({
      username, email, role, password: userPassword,
    })),
    accounts: {
      primary: { id: String(accounts[0]._id), accountNumber: accounts[0].accountNumber, openingBalance: "1000.0000 USD" },
      secondary: { id: String(accounts[1]._id), accountNumber: accounts[1].accountNumber, openingBalance: "0.0000 USD" },
    },
  }, null, 2)}\n`, { flag: "wx", mode: 0o600 });
  credentialFileCreated = true;

  await mongoose.connect(process.env.MONGO_URI, { serverSelectionTimeoutMS: 10000 });
  await Promise.all(Object.values(mongoose.models).map((model) => model.createIndexes()));
  await ensureClearingAccount("USD");

  if (await User.exists({ username: { $in: usernames } })) {
    throw new Error("Demo usernames already exist in this database; use a fresh evaluation database");
  }

  const session = await mongoose.startSession();
  try {
    await session.withTransaction(async () => {
      for (const user of users) await user.save({ session });
      for (const account of accounts) await account.save({ session });

      const clearing = await Account.findOne({
        accountNumber: process.env.CLEARING_ACCOUNT_NUMBER || "LL-CLEARING-USD",
      }).session(session);
      if (!clearing || clearing.currency !== "USD" || clearing.status !== "ACTIVE") {
        throw new Error("The USD clearing account is missing or misconfigured");
      }

      const fundingAmount = decimal("1000");
      const transaction = new Transaction({
        initiatedByUserId: users[1]._id,
        sourceAccountId: clearing._id,
        destinationAccountId: accounts[0]._id,
        transactionType: "DEPOSIT",
        status: "POSTED",
        amount: fundingAmount,
        currency: "USD",
        description: "Evaluation demo opening balance",
        postedAt: new Date(),
      });
      await transaction.save({ session });
      clearing.balance = decimal(new Decimal(clearing.balance.toString()).minus("1000").toFixed(4));
      clearing.version += 1;
      accounts[0].balance = fundingAmount;
      accounts[0].version += 1;
      await clearing.save({ session });
      await accounts[0].save({ session });

      const debit = new LedgerEntry({
        transactionId: transaction._id,
        accountId: clearing._id,
        entrySide: "DEBIT",
        amount: fundingAmount,
      });
      const credit = new LedgerEntry({
        transactionId: transaction._id,
        accountId: accounts[0]._id,
        entrySide: "CREDIT",
        amount: fundingAmount,
      });
      await debit.save({ session });
      await credit.save({ session });
    });
  } finally {
    await session.endSession();
  }

  console.info(`Created demo users and accounts. Credentials are stored locally at ${credentialPath}.`);
  console.info("Usernames: demo-admin, demo-holder, demo-auditor. Read the ignored credential file for their generated passwords.");
} catch (error) {
  if (credentialFileCreated && existsSync(credentialPath)) unlinkSync(credentialPath);
  console.error("Demo seeding failed", error);
  process.exitCode = 1;
} finally {
  await mongoose.disconnect();
}
