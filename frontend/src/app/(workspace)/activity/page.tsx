import { protectedRead } from "@/features/auth/server/current-user";
import { MilestonePage } from "@/features/shell/milestone-page";
export const metadata = { title: "Activity" };
export default async function Activity() {
  await protectedRead("/api/v1/users/me");
  return <MilestonePage section="activity" />;
}
