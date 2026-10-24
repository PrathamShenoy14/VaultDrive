import { protectedRead } from "@/features/auth/server/current-user";
import { Explorer, type ExplorerMode } from "./explorer";
import { searchFilters } from "./contracts";

export type ExplorerParams = Promise<
  Record<string, string | string[] | undefined>
>;
export async function ExplorerPage({
  mode,
  searchParams,
}: {
  mode: ExplorerMode;
  searchParams: ExplorerParams;
}) {
  await protectedRead("/api/v1/users/me");
  const params = await searchParams;
  const folder =
    (mode === "drive" || mode === "shared") && typeof params.folder === "string"
      ? params.folder
      : undefined;
  const query = new URLSearchParams();
  for (const key of ["q", ...searchFilters])
    if (typeof params[key] === "string") query.set(key, params[key]);
  return (
    <Explorer
      mode={mode}
      folder={folder}
      query={mode === "search" ? query.toString() : undefined}
    />
  );
}
