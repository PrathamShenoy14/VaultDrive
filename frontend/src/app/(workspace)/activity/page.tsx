import { protectedRead } from "@/features/auth/server/current-user";
import { ActivityPage } from "@/features/experience/activity";
export const metadata = { title: "Activity" };
export default async function Activity() {
  await protectedRead("/api/v1/users/me");
  return <ActivityPage />;
}
