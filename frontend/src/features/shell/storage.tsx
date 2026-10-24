import { Cloud } from "lucide-react";
import type { Usage } from "@/lib/api/transport";
export function formatBytes(bytes: bigint) {
  const units = ["B", "KB", "MB", "GB", "TB", "PB", "EB"];
  let factor = 1n,
    index = 0;
  while (bytes >= factor * 1024n && index < units.length - 1) {
    factor *= 1024n;
    index++;
  }
  const tenths = (bytes * 10n) / factor;
  return `${tenths / 10n}${index && tenths % 10n ? `.${tenths % 10n}` : ""} ${units[index]}`;
}
export function Storage({ usage }: { usage: Usage }) {
  const charged = usage.usedBytes + usage.reservedBytes;
  const percent =
    usage.limitBytes > 0n
      ? Math.min(100, Number((charged * 100n) / usage.limitBytes))
      : charged > 0n
        ? 100
        : 0;
  return (
    <section
      aria-label="Storage usage"
      className="space-y-3 border-t px-3 pt-5 text-sm"
    >
      <div className="flex items-center gap-3 font-medium">
        <Cloud className="size-5 text-muted-foreground" aria-hidden="true" />
        Storage
      </div>
      <div
        role="meter"
        aria-label="Logical storage used and reserved"
        aria-valuemin={0}
        aria-valuemax={100}
        aria-valuenow={percent}
        aria-valuetext={`${formatBytes(charged)} of ${formatBytes(usage.limitBytes)} used or reserved`}
        className="h-1.5 overflow-hidden rounded-full bg-muted"
      >
        <div
          className="h-full rounded-full bg-primary transition-all"
          style={{ width: `${percent}%` }}
        />
      </div>
      <p className="text-xs leading-relaxed text-muted-foreground">
        {formatBytes(charged)} of {formatBytes(usage.limitBytes)} used or
        reserved
      </p>
      <p
        className="text-xs text-muted-foreground"
        title={`${usage.usedBytes} bytes used`}
      >
        {formatBytes(usage.usedBytes)} used
      </p>
      <p
        className="text-xs text-muted-foreground"
        title={`${usage.reservedBytes} bytes reserved`}
      >
        {formatBytes(usage.reservedBytes)} reserved
      </p>
      <p
        className="text-xs text-muted-foreground"
        title={`${usage.limitBytes} byte limit`}
      >
        {formatBytes(usage.limitBytes)} limit
      </p>
      <p
        className="text-xs text-muted-foreground"
        title={`${usage.availableBytes} bytes available`}
      >
        {formatBytes(usage.availableBytes)} available
      </p>
      <p className="text-[11px] leading-relaxed text-muted-foreground">
        Logical usage. Physical storage may differ.
      </p>
    </section>
  );
}
