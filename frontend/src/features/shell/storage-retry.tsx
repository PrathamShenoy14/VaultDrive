"use client";
import { useRouter } from "next/navigation";
import { Button } from "@/components/ui/button";
export function StorageRetry() {
  const router = useRouter();
  return (
    <div role="status" className="border-t pt-5 text-xs text-muted-foreground">
      <p>Storage usage is unavailable.</p>
      <Button variant="link" size="sm" onClick={() => router.refresh()}>
        Retry storage
      </Button>
    </div>
  );
}
