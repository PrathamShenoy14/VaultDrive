// @vitest-environment node
import { it, expect, vi } from "vitest";
import { env } from "@/lib/env";
it("rejects insecure origins, credential-bearing upstreams and unsupported session modes", () => {
  vi.stubEnv("APP_ORIGIN", "http://localhost:3000");
  expect(() => env()).toThrow("APP_ORIGIN");
  vi.stubEnv("APP_ORIGIN", "https://localhost:3000");
  vi.stubEnv("BACKEND_URL", "https://user:secret@backend.test");
  expect(() => env()).toThrow("BACKEND_URL");
  vi.stubEnv("BACKEND_URL", "http://127.0.0.1:8080");
  vi.stubEnv("SESSION_MODE", "serverless");
  expect(() => env()).toThrow("SESSION_MODE");
  vi.unstubAllEnvs();
});
