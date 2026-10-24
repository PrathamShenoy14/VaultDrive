import { protectedRead } from "@/features/auth/server/current-user";
import { MilestonePage } from "@/features/shell/milestone-page";
export const metadata = { title: "Trash" };
export default async function Trash() {
  await protectedRead("/api/v1/users/me");
  return <MilestonePage section="trash" />;
}
