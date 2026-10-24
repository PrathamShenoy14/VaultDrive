import { Layers2 } from "lucide-react";
import { cn } from "@/lib/utils";
export function Brand({ className }: { className?: string }) {
  return (
    <span
      className={cn(
        "inline-flex items-center gap-2.5 text-xl font-semibold tracking-tight",
        className,
      )}
    >
      <span className="flex size-9 items-center justify-center rounded-xl bg-primary text-primary-foreground">
        <Layers2 className="size-5" aria-hidden="true" />
      </span>
      VaultDrive
    </span>
  );
}
