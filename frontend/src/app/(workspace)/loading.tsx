import { Skeleton } from "@/components/ui/skeleton";
export default function Loading() {
  return (
    <div
      role="status"
      aria-label="Loading your workspace"
      className="space-y-6 p-8"
    >
      <Skeleton className="h-9 w-44" />
      <Skeleton className="h-4 w-64 max-w-full" />
      <Skeleton className="h-72 w-full rounded-2xl" />
    </div>
  );
}
