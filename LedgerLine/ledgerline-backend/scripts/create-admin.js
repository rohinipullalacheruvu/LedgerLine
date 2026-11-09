import "dotenv/config";
import bcrypt from "bcryptjs";
import mongoose from "mongoose";
import { User } from "../src/models.js";

const required = ["MONGO_URI", "ADMIN_USERNAME", "ADMIN_EMAIL", "ADMIN_FULL_NAME", "ADMIN_PASSWORD"];
const missing = required.filter((name) => !process.env[name]);

if (missing.length) {
  throw new Error(`Missing required environment values: ${missing.join(", ")}`);
}
if (process.env.ADMIN_PASSWORD.length < 12
  || Buffer.byteLength(process.env.ADMIN_PASSWORD, "utf8") > 72) {
  throw new Error("ADMIN_PASSWORD must be 12 to 72 UTF-8 bytes");
}

try {
  await mongoose.connect(process.env.MONGO_URI);
  if (await User.exists({ role: "ADMIN" })) {
    throw new Error("An administrator already exists; refusing to create another bootstrap administrator");
  }
  await User.create({
    username: process.env.ADMIN_USERNAME.trim().toLowerCase(),
    email: process.env.ADMIN_EMAIL.trim().toLowerCase(),
    fullName: process.env.ADMIN_FULL_NAME.trim(),
    passwordHash: await bcrypt.hash(process.env.ADMIN_PASSWORD, 12),
    role: "ADMIN",
  });
  console.info("Initial LedgerLine administrator created.");
} finally {
  await mongoose.disconnect();
}
