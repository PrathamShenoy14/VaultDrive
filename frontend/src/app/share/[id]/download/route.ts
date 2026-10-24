import { publicDownload } from "@/features/sharing/server/public-link";
export const dynamic = "force-dynamic";
async function handle(
  request: Request,
  context: { params: Promise<{ id: string }> },
) {
  return publicDownload(request, (await context.params).id);
}
export { handle as POST, handle as GET };
