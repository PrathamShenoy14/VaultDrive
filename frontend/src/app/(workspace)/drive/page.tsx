import { protectedRead } from "@/features/auth/server/current-user";
import { MilestonePage } from "@/features/shell/milestone-page";
export const metadata = { title: "My Drive" };
export default async function Drive() {
  await protectedRead("/api/v1/users/me");
  return <MilestonePage section="drive" />;
}
