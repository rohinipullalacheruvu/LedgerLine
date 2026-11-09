import "dotenv/config";
import mongoose from "mongoose";
import app from "./app.js";
import { ensureClearingAccount } from "./ledger.js";
import "./models.js";

const port = Number(process.env.PORT || 8080);
const mongoUri = process.env.MONGO_URI;

if (!mongoUri) throw new Error("MONGO_URI is required");

try {
  await mongoose.connect(mongoUri, {
    autoIndex: false,
    serverSelectionTimeoutMS: 10000,
  });
  await Promise.all(Object.values(mongoose.models).map((model) => model.createIndexes()));
  await ensureClearingAccount();
  const server = app.listen(port, () => {
    console.info(`LedgerLine API listening on port ${port}`);
  });

  async function shutdown(signal) {
    console.info(`${signal} received; shutting down LedgerLine API`);
    server.close(async (error) => {
      if (error) {
        console.error("Failed to close HTTP server cleanly", error);
        process.exitCode = 1;
      }
      await mongoose.disconnect();
    });
  }
  process.once("SIGINT", () => shutdown("SIGINT"));
  process.once("SIGTERM", () => shutdown("SIGTERM"));
} catch (error) {
  console.error("LedgerLine API failed to start", error);
  await mongoose.disconnect();
  process.exitCode = 1;
}
