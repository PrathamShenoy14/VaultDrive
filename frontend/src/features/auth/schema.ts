import { z } from "zod";
export const loginSchema = z.object({
  email: z.email("Enter a valid email address."),
  password: z.string().min(1, "Enter your password."),
});
export const registerSchema = loginSchema.extend({
  email: z.email("Enter a valid email address.").max(255),
  displayName: z
    .string()
    .trim()
    .min(1, "Enter your name.")
    .max(100, "Use at most 100 characters."),
  password: z
    .string()
    .min(12, "Use at least 12 characters.")
    .max(72, "Use at most 72 characters.")
    .refine(
      (v) => new TextEncoder().encode(v).length <= 72,
      "Password must be at most 72 UTF-8 bytes.",
    ),
});
