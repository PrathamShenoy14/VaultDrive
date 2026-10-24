import "server-only";
import { cookies } from "next/headers";
import { redirect } from "next/navigation";
import {
  ApiError,
  normalizeUsage,
  readResponse,
  type Profile,
  type Usage,
} from "@/lib/api/transport";
import { backend, expired } from "./backend";
import { SESSION_COOKIE, sessions } from "./session-store";
export async function protectedRead(path: "/api/v1/users/me"): Promise<Profile>;
export async function protectedRead(
  path: "/api/v1/users/me/storage",
): Promise<Usage>;
export async function protectedRead(path: string): Promise<Profile | Usage> {
  const id = (await cookies()).get(SESSION_COOKIE)?.value;
  if (!id || !sessions.get(id)) redirect("/login?reason=session");
  return sessions.locked(id, async () => {
    const session = sessions.get(id);
    if (!session) redirect("/login?reason=session");
    // RSC cannot mutate cookies. The restore page posts through the sole refresh
    // coordinator and then returns here; prefetch/render never rotates a token.
    if (expired(session)) redirect("/session/restore");
    const response = await backend(path, {
      headers: { Authorization: `Bearer ${session.access}` },
    });
    if (response.status === 401) {
      sessions.delete(id);
      redirect("/login?reason=session");
    }
    try {
      const result = await readResponse<Profile | Usage>(response);
      return path.endsWith("/storage")
        ? normalizeUsage(result as Usage)
        : result;
    } catch (error) {
      if (error instanceof ApiError) throw error;
      throw new Error("Unable to read account");
    }
  });
}
