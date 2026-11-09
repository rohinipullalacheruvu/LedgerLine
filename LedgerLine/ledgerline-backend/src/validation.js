import Decimal from "decimal.js";

export class ApiError extends Error {
  constructor(status, message, error = "Request failed") {
    super(message);
    this.status = status;
    this.error = error;
  }
}

export function requireText(value, field, { min = 1, max = 500, pattern } = {}) {
  if (typeof value !== "string") throw new ApiError(400, `${field} is required`, "Invalid request");
  const text = value.trim();
  if (text.length < min || text.length > max || (pattern && !pattern.test(text))) {
    throw new ApiError(400, `${field} is invalid`, "Invalid request");
  }
  return text;
}

export function requirePassword(value, { min = 1, max = 72 } = {}) {
  if (typeof value !== "string" || value.length < min || value.length > max
    || Buffer.byteLength(value, "utf8") > 72) {
    throw new ApiError(400, "password is invalid", "Invalid request");
  }
  return value;
}

export function parseAmount(value) {
  if ((typeof value !== "string" && typeof value !== "number")
    || !/^\d{1,15}(?:\.\d{1,4})?$/.test(String(value))) {
    throw new ApiError(400, "amount must be positive and fit DECIMAL(19,4)", "Invalid request");
  }
  const amount = new Decimal(String(value));
  if (!amount.isFinite() || amount.lte(0)) {
    throw new ApiError(400, "amount must be positive and fit DECIMAL(19,4)", "Invalid request");
  }
  return amount.toFixed(4);
}

export function validateDescription(value) {
  if (value == null || value === "") return null;
  if (typeof value !== "string" || value.length > 500) {
    throw new ApiError(400, "description must be at most 500 characters", "Invalid request");
  }
  return value.trim() || null;
}

export function validateIdempotencyKey(value) {
  if (typeof value !== "string" || !/^[\x21-\x7e]{1,128}$/.test(value)) {
    throw new ApiError(400, "Idempotency-Key must contain 1 to 128 printable ASCII characters", "Invalid request");
  }
  return value;
}
