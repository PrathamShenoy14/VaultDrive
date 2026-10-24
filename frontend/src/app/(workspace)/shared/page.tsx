import { protectedRead } from "@/features/auth/server/current-user";
import { MilestonePage } from "@/features/shell/milestone-page";
export const metadata = { title: "Shared" };
export default async function Shared() {
  await protectedRead("/api/v1/users/me");
  return <MilestonePage section="shared" />;
}
