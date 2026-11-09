import mongoose from "mongoose";
import Decimal from "decimal.js";

const { Schema } = mongoose;
const decimal = (value) => mongoose.Types.Decimal128.fromString(new Decimal(value || 0).toFixed(4));

const userSchema = new Schema({
  username: { type: String, required: true, unique: true, lowercase: true, trim: true, minlength: 3, maxlength: 50 },
  email: { type: String, required: true, unique: true, lowercase: true, trim: true, maxlength: 254 },
  passwordHash: { type: String, required: true, select: false },
  fullName: { type: String, required: true, trim: true, maxlength: 120 },
  role: { type: String, enum: ["ADMIN", "ACCOUNT_HOLDER", "AUDITOR"], default: "ACCOUNT_HOLDER", required: true },
  enabled: { type: Boolean, default: true },
  tokenVersion: { type: Number, default: 0 },
}, { timestamps: true });

const accountSchema = new Schema({
  ownerId: { type: Schema.Types.ObjectId, ref: "User", default: null, index: true },
  accountNumber: { type: String, required: true, unique: true, maxlength: 34 },
  name: { type: String, required: true, trim: true, maxlength: 100 },
  accountType: { type: String, enum: ["CHECKING", "SAVINGS", "CASH", "EXTERNAL_CLEARING"], required: true },
  currency: { type: String, required: true, uppercase: true, match: /^[A-Z]{3}$/ },
  balance: { type: Schema.Types.Decimal128, default: () => decimal(0), required: true },
  status: { type: String, enum: ["ACTIVE", "FROZEN", "CLOSED"], default: "ACTIVE", required: true },
  version: { type: Number, default: 0 },
}, { timestamps: true });

accountSchema.path("balance").validate(function (value) {
  return this.accountType === "EXTERNAL_CLEARING" || new Decimal(value.toString()).gte(0);
}, "Customer account balances cannot be negative");

accountSchema.index({ ownerId: 1, createdAt: -1 });

const accountPermissionSchema = new Schema({
  accountId: { type: Schema.Types.ObjectId, ref: "Account", required: true },
  userId: { type: Schema.Types.ObjectId, ref: "User", required: true },
  role: { type: String, enum: ["ACCOUNT_ADMIN", "TRANSACT", "VIEW"], required: true },
  grantedByUserId: { type: Schema.Types.ObjectId, ref: "User", default: null },
}, { timestamps: true });
accountPermissionSchema.index({ accountId: 1, userId: 1 }, { unique: true });

const transactionSchema = new Schema({
  initiatedByUserId: { type: Schema.Types.ObjectId, ref: "User", required: true },
  sourceAccountId: { type: Schema.Types.ObjectId, ref: "Account", default: null },
  destinationAccountId: { type: Schema.Types.ObjectId, ref: "Account", default: null },
  transactionType: { type: String, enum: ["DEPOSIT", "WITHDRAWAL", "TRANSFER", "ADJUSTMENT"], required: true },
  status: { type: String, enum: ["POSTED"], default: "POSTED", required: true },
  amount: { type: Schema.Types.Decimal128, required: true },
  currency: { type: String, required: true, uppercase: true },
  description: { type: String, default: null, maxlength: 500 },
  postedAt: { type: Date, required: true },
}, { timestamps: true });
transactionSchema.index({ initiatedByUserId: 1, createdAt: -1 });
transactionSchema.index({ sourceAccountId: 1, createdAt: -1 });
transactionSchema.index({ destinationAccountId: 1, createdAt: -1 });

const ledgerEntrySchema = new Schema({
  transactionId: { type: Schema.Types.ObjectId, ref: "Transaction", required: true, immutable: true },
  accountId: { type: Schema.Types.ObjectId, ref: "Account", required: true, immutable: true },
  entrySide: { type: String, enum: ["DEBIT", "CREDIT"], required: true, immutable: true },
  amount: { type: Schema.Types.Decimal128, required: true, immutable: true },
}, { timestamps: true });
ledgerEntrySchema.index({ transactionId: 1 });
ledgerEntrySchema.index({ accountId: 1, createdAt: -1 });
ledgerEntrySchema.pre("save", function rejectPostedEntryUpdate() {
  if (!this.isNew) throw new Error("Ledger entries are append-only; post a reversing entry instead.");
});
ledgerEntrySchema.pre("deleteOne", { document: true, query: false }, function rejectPostedEntryDelete() {
  throw new Error("Ledger entries are append-only; post a reversing entry instead.");
});
for (const method of ["updateOne", "updateMany", "findOneAndUpdate", "replaceOne", "deleteOne", "deleteMany", "findOneAndDelete"]) {
  ledgerEntrySchema.pre(method, function rejectPostedEntryMutation() {
    throw new Error("Ledger entries are append-only; post a reversing entry instead.");
  });
}
ledgerEntrySchema.pre("bulkWrite", function rejectPostedEntryBulkWrite() {
  throw new Error("Ledger entries are append-only; post a reversing entry instead.");
});

const auditLogSchema = new Schema({
  actorUserId: { type: Schema.Types.ObjectId, ref: "User", default: null },
  action: { type: String, required: true, maxlength: 64 },
  entityType: { type: String, required: true, maxlength: 64 },
  entityId: { type: Schema.Types.ObjectId, default: null },
  details: { type: Map, of: String, default: undefined },
}, { timestamps: { createdAt: true, updatedAt: false } });
auditLogSchema.index({ entityType: 1, entityId: 1, createdAt: -1 });
auditLogSchema.index({ actorUserId: 1, createdAt: -1 });

const idempotencySchema = new Schema({
  userId: { type: Schema.Types.ObjectId, ref: "User", required: true },
  idempotencyKey: { type: String, required: true, maxlength: 128 },
  requestHash: { type: String, required: true, match: /^[a-f0-9]{64}$/ },
  status: { type: String, enum: ["PROCESSING", "COMPLETED", "FAILED"], default: "PROCESSING", required: true },
  transactionId: { type: Schema.Types.ObjectId, ref: "Transaction", default: null },
  response: { type: Schema.Types.Mixed, default: null },
  failureCode: { type: String, default: null },
  failureMessage: { type: String, default: null },
}, { timestamps: true });
idempotencySchema.index({ userId: 1, idempotencyKey: 1 }, { unique: true });
idempotencySchema.index({ transactionId: 1 }, { unique: true, sparse: true });

export const User = mongoose.model("User", userSchema);
export const Account = mongoose.model("Account", accountSchema);
export const AccountPermission = mongoose.model("AccountPermission", accountPermissionSchema);
export const Transaction = mongoose.model("Transaction", transactionSchema);
export const LedgerEntry = mongoose.model("LedgerEntry", ledgerEntrySchema);
export const AuditLog = mongoose.model("AuditLog", auditLogSchema);
export const IdempotencyRecord = mongoose.model("IdempotencyRecord", idempotencySchema);

export { decimal };
