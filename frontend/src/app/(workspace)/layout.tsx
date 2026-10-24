import { Suspense } from "react";
import { AppShell } from "@/features/shell/app-shell";
import { Storage } from "@/features/shell/storage";
import { protectedRead } from "@/features/auth/server/current-user";
import { Skeleton } from "@/components/ui/skeleton";
import { StorageRetry } from "@/features/shell/storage-retry";
import { unstable_rethrow } from "next/navigation";
import { TransferProvider } from "@/features/transfers/provider";
import { ManagementProvider } from "@/features/management/provider";
import { SharingProvider } from "@/features/sharing/provider";
export const dynamic = "force-dynamic";
async function StorageUsage() {
  let usage;
  try {
    usage = await protectedRead("/api/v1/users/me/storage");
  } catch (error) {
    // Next redirects are framework control flow and must propagate.
    unstable_rethrow(error);
    return <StorageRetry />;
  }
  return <Storage usage={usage} />;
}
export default async function WorkspaceLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  const profile = await protectedRead("/api/v1/users/me");
  return (
    <TransferProvider key={profile.userId} userId={profile.userId}>
      <ManagementProvider key={profile.userId} userId={profile.userId}>
        <SharingProvider>
          <AppShell
            profile={profile}
            storage={
              <Suspense
                fallback={
                  <div
                    role="status"
                    aria-label="Loading storage usage"
                    className="space-y-3"
                  >
                    <Skeleton className="h-4 w-24" />
                    <Skeleton className="h-2 w-full" />
                  </div>
                }
              >
                <StorageUsage />
              </Suspense>
            }
          >
            {children}
          </AppShell>
        </SharingProvider>
      </ManagementProvider>
    </TransferProvider>
  );
}
