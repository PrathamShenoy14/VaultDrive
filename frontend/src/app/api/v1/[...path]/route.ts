import { bff } from "@/features/auth/server/bff";
export const runtime = "nodejs";
export const dynamic = "force-dynamic";
async function handle(
  request: Request,
  context: { params: Promise<{ path: string[] }> },
) {
  const { path } = await context.params;
  return bff(request, path.join("/"));
}
export {
  handle as GET,
  handle as POST,
  handle as PATCH,
  handle as DELETE,
  handle as PUT,
  handle as OPTIONS,
};
