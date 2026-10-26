import { NotificationsPage } from "@/features/experience/notifications";
import { protectedRead } from "@/features/auth/server/current-user";
export const metadata = { title: "Notifications" };
export default async function Notifications() {
  await protectedRead("/api/v1/users/me");
  return <NotificationsPage />;
}
