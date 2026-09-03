import { Redis } from "ioredis";
import { buildApp } from "./app.js";

const PORT = Number(process.env.PORT ?? 18081);
const REDIS_URL = process.env.REDIS_URL ?? "redis://127.0.0.1:6379";

const redis = new Redis(REDIS_URL, { maxRetriesPerRequest: 3 });
const app = await buildApp(redis);

try {
  await app.listen({ port: PORT, host: "0.0.0.0" });
} catch (err) {
  app.log.error(err);
  process.exit(1);
}
