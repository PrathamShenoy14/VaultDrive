import { parse, isInteger } from "lossless-json";
import type { components, paths } from "./generated";
export type Profile = components["schemas"]["user_ProfileService_Profile"];
type RawUsage = components["schemas"]["storage_StorageUsageService_Usage"];
export type Usage = { [K in keyof RawUsage]: bigint };
export type ApiErrorBody = components["schemas"]["common_exception_ApiError"];
export type LoginInput = components["schemas"]["auth_dto_LoginRequest"];
export type RegisterInput = components["schemas"]["auth_dto_RegisterRequest"];
export type LoginResult = components["schemas"]["auth_dto_LoginResponse"];
export type RegisterResult = components["schemas"]["auth_dto_RegisterResponse"];
export type M1Get = Extract<
  keyof paths,
  "/api/v1/users/me" | "/api/v1/users/me/storage"
>;
type GetResult<P extends M1Get> = P extends "/api/v1/users/me"
  ? Profile
  : Usage;

export class ApiError extends Error {
  constructor(
    public readonly body: ApiErrorBody,
    public readonly retryAfter: string | null = null,
  ) {
    super(body.message);
    this.name = "ApiError";
  }
  get status() {
    return this.body.status;
  }
}
export function parseJson<T>(text: string): T {
  // Only integers outside JS's exact range become bigint. Small int64 fields
  // are normalized by the typed quota reader below.
  return parse(text, undefined, (value) =>
    isInteger(value) && !Number.isSafeInteger(Number(value))
      ? BigInt(value)
      : Number(value),
  ) as T;
}
export async function readResponse<T>(response: Response): Promise<T> {
  if (!response.ok) {
    let body: ApiErrorBody;
    try {
      body = parseJson<ApiErrorBody>(await response.text());
      if (
        body.status !== response.status ||
        typeof body.error !== "string" ||
        typeof body.message !== "string"
      )
        throw new Error();
    } catch {
      body = {
        status: response.status,
        error: "UPSTREAM_ERROR",
        message: "The service could not complete this request.",
      };
    }
    throw new ApiError(body, response.headers.get("Retry-After"));
  }
  return response.status === 204
    ? (undefined as T)
    : parseJson<T>(await response.text());
}
export function normalizeUsage(value: RawUsage): Usage {
  return Object.fromEntries(
    Object.entries(value).map(([key, val]) => [key, BigInt(val)]),
  ) as Usage;
}
export async function apiGet<P extends M1Get>(path: P): Promise<GetResult<P>> {
  const result = await readResponse<GetResult<P>>(
    await fetch(path, { cache: "no-store", credentials: "same-origin" }),
  );
  return (
    path.endsWith("/storage") ? normalizeUsage(result as Usage) : result
  ) as GetResult<P>;
}
// Each mutation is sent exactly once. Authentication envelopes never contain JWTs.
export async function authRequest(
  action: "login",
  body: LoginInput,
): Promise<void>;
export async function authRequest(
  action: "register",
  body: RegisterInput,
): Promise<RegisterResult>;
export async function authRequest(action: "logout" | "refresh"): Promise<void>;
export async function authRequest(
  action: string,
  body?: LoginInput | RegisterInput,
): Promise<RegisterResult | void> {
  return readResponse(
    await fetch(`/api/v1/auth/${action}`, {
      method: "POST",
      cache: "no-store",
      credentials: "same-origin",
      headers: {
        "Content-Type": "application/json",
        "X-VaultDrive-Client": "1",
      },
      body: body ? JSON.stringify(body) : undefined,
    }),
  );
}
export function errorMessage(error: unknown): string {
  if (!(error instanceof ApiError))
    return "Unable to connect. Check your connection and try again.";
  const messages: Record<number, string> = {
    400: "Check the details and try again.",
    401: "Your session has ended. Please sign in again.",
    403: "This request was not permitted. Please sign in again if the problem continues.",
    404: "This resource is unavailable.",
    409: "These details conflict with an existing record.",
    429: "Too many requests. Please wait before trying again.",
  };
  const message =
    error.status >= 500
      ? "VaultDrive is temporarily unavailable. Please try again later."
      : (messages[error.status] ?? error.message);
  return (
    message + (error.retryAfter ? ` Retry after: ${error.retryAfter}.` : "")
  );
}
