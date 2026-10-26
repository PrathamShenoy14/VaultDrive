import { AccountPage } from "@/features/experience/account";
import { protectedRead } from "@/features/auth/server/current-user";
export const metadata = { title: "Account settings" };
export default async function Settings() {
  await protectedRead("/api/v1/users/me");
  return <AccountPage />;
}
