import bcrypt from "bcryptjs";
import jwt from "jsonwebtoken";
import { User } from "./models.js";
import { ApiError, requirePassword, requireText } from "./validation.js";

const JWT_EXPIRATION = process.env.JWT_EXPIRATION || "15m";
const JWT_SECRET = process.env.JWT_SECRET
  || (process.env.NODE_ENV === "production" ? "" : "ledgerline-local-development-secret-change-me");

if (process.env.NODE_ENV === "production" && JWT_SECRET.length < 32) {
  throw new Error("JWT_SECRET must be at least 32 characters in production");
}

export function issueToken(user) {
  return jwt.sign(
    { tokenVersion: user.tokenVersion },
    JWT_SECRET,
    { subject: String(user._id), expiresIn: JWT_EXPIRATION, algorithm: "HS256" },
  );
}

export async function authenticate(req, _res, next) {
  try {
    const header = req.get("authorization") || "";
    if (!header.startsWith("Bearer ")) throw new ApiError(401, "Authentication is required", "Unauthorized");
    const claims = jwt.verify(header.slice(7), JWT_SECRET, { algorithms: ["HS256"] });
    const user = await User.findById(claims.sub);
    if (!user || !user.enabled || claims.tokenVersion !== user.tokenVersion) {
      throw new ApiError(401, "The session is invalid or has expired", "Unauthorized");
    }
    req.user = user;
    next();
  } catch (error) {
    if (error instanceof ApiError) return next(error);
    if (error.name === "TokenExpiredError" || error.name === "JsonWebTokenError") {
      return next(new ApiError(401, "The session is invalid or has expired", "Unauthorized"));
    }
    next(error);
  }
}

export function requireRole(...roles) {
  return (req, _res, next) => {
    if (!req.user || !roles.includes(req.user.role)) {
      return next(new ApiError(403, "You are not allowed to perform this operation", "Forbidden"));
    }
    next();
  };
}

export async function register(body) {
  const username = requireText(body.username, "username", {
    min: 3, max: 50, pattern: /^[A-Za-z0-9._-]+$/,
  }).toLowerCase();
  const email = requireText(body.email, "email", { max: 254 }).toLowerCase();
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) throw new ApiError(400, "email is invalid", "Invalid request");
  const password = requirePassword(body.password, { min: 12 });
  const fullName = requireText(body.fullName, "fullName", { max: 120 });
  const user = new User({
    username,
    email,
    fullName,
    passwordHash: await bcrypt.hash(password, 12),
    role: "ACCOUNT_HOLDER",
  });
  try {
    await user.save();
  } catch (error) {
    if (error.code === 11000) throw new ApiError(409, "Username or email is already in use", "Conflict");
    throw error;
  }
  return authResponse(user);
}

export async function login(body) {
  const usernameOrEmail = requireText(body.usernameOrEmail, "usernameOrEmail", { max: 254 }).toLowerCase();
  const password = requirePassword(body.password);
  const user = await User.findOne({
    $or: [{ username: usernameOrEmail }, { email: usernameOrEmail }],
  }).select("+passwordHash");
  if (!user || !user.enabled || !(await bcrypt.compare(password, user.passwordHash))) {
    throw new ApiError(401, "Invalid username/email or password", "Unauthorized");
  }
  return authResponse(user);
}

export function userSummary(user) {
  return {
    username: user.username,
    email: user.email,
    fullName: user.fullName,
    role: user.role,
  };
}

function authResponse(user) {
  const accessToken = issueToken(user);
  const claims = jwt.decode(accessToken);
  return {
    accessToken,
    tokenType: "Bearer",
    expiresIn: Math.max(0, claims.exp - Math.floor(Date.now() / 1000)),
    user: userSummary(user),
  };
}
