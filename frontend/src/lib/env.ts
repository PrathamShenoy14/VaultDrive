import { z } from "zod";
const schema = z.object({
  BACKEND_URL: z.url().refine((v) => {
    const u = new URL(v);
    return (
      ["http:", "https:"].includes(u.protocol) &&
      !u.username &&
      !u.password &&
      u.pathname === "/" &&
      !u.search &&
      !u.hash
    );
  }, "BACKEND_URL must be an HTTP(S) origin without credentials or a path"),
  APP_ORIGIN: z.url().refine((v) => {
    const u = new URL(v);
    return u.protocol === "https:" && u.origin === v;
  }, "APP_ORIGIN must be an exact HTTPS origin, without a trailing slash"),
  SESSION_MODE: z.literal("single-process"),
  SESSION_MAX_ENTRIES: z.coerce
    .number()
    .int()
    .min(1)
    .max(100000)
    .default(10000),
});
export function env() {
  const result = schema.safeParse(process.env);
  if (!result.success)
    throw new Error(
      `Invalid frontend environment: ${result.error.issues.map((i) => i.path.join(".") + ": " + i.message).join("; ")}`,
    );
  return result.data;
}
