"use client";
import { useEffect, useRef, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import Link from "next/link";
import { Bell } from "lucide-react";
import { Button } from "@/components/ui/button";
import {
  Sheet,
  SheetContent,
  SheetTitle,
  SheetDescription,
  SheetTrigger,
} from "@/components/ui/sheet";
import { formatBytes } from "@/features/shell/storage";
import { errorMessage } from "@/lib/api/transport";
import { experienceRequest } from "./api";
import { boundedPoll } from "./poll";
import { notificationEvents, timestamp, type Notification } from "./contracts";
import {
  historyQuery,
  Pagination,
  ReadState,
  useExperienceRead,
  type HistoryPage,
} from "./feed";
import { ResourceAction } from "./resource-action";

export function NotificationBell() {
  const [open, setOpen] = useState(false);
  const [count, setCount] = useState<string>();
  const [error, setError] = useState(false);
  const [paused, setPaused] = useState(false);
  const [revision, setRevision] = useState(0);
  useEffect(() => {
    const changed = () => {
      setCount(undefined);
      setError(false);
      setPaused(false);
      setRevision((n) => n + 1);
    };
    window.addEventListener("vaultdrive:notifications-changed", changed);
    const stop = boundedPoll(
      async (signal) => {
        try {
          const value = await experienceRequest<{
            unreadCount: number | bigint;
          }>("notifications/unread-count", signal);
          if (!signal.aborted) {
            setCount(value.unreadCount.toString());
            setError(false);
          }
        } catch (error) {
          if (!signal.aborted) {
            setCount(undefined);
            setError(true);
          }
          throw error;
        }
      },
      () => setPaused(true),
    );
    return () => {
      stop();
      window.removeEventListener("vaultdrive:notifications-changed", changed);
    };
  }, [revision]);
  const refresh = () =>
    window.dispatchEvent(new Event("vaultdrive:notifications-changed"));
  return (
    <Sheet open={open} onOpenChange={setOpen}>
      <SheetTrigger
        render={
          <Button
            variant="ghost"
            size="icon"
            className="relative"
            aria-label={`Notifications${count === undefined ? ", count unavailable" : `, ${count} unread`}`}
          />
        }
      >
        <Bell aria-hidden="true" />
        {count !== undefined && BigInt(count) > 0n && (
          <span
            aria-hidden="true"
            className="absolute -top-1 -right-1 rounded-full bg-primary px-1.5 text-[10px] font-semibold text-primary-foreground"
          >
            {BigInt(count) > 99n ? "99+" : count}
          </span>
        )}
      </SheetTrigger>
      <SheetContent className="w-full overflow-y-auto p-5 sm:max-w-lg">
        <SheetTitle>Notifications</SheetTitle>
        <SheetDescription>
          Sharing updates and storage alerts. Delivery may be delayed.
        </SheetDescription>
        <p className="my-3 text-sm" role="status">
          {error
            ? "Unread count unavailable."
            : count === undefined
              ? "Checking unread count…"
              : `${count} unread`}
          {paused && " Automatic checks paused."}
        </p>
        <Button variant="outline" size="sm" onClick={refresh}>
          Refresh notifications
        </Button>
        <Link
          href="/notifications"
          onClick={() => setOpen(false)}
          className="ml-3 text-sm underline"
        >
          View all notifications
        </Link>
        {open && (
          <NotificationList
            key={revision}
            query="page=0&size=10"
            compact
            navigated={() => setOpen(false)}
          />
        )}
      </SheetContent>
    </Sheet>
  );
}
function NotificationRow({
  item,
  refresh,
  navigated,
}: {
  item: Notification;
  refresh: () => void;
  navigated?: () => void;
}) {
  const [busy, setBusy] = useState(false),
    [error, setError] = useState<unknown>();
  const [unavailable, setUnavailable] = useState(false);
  const deniedNotice = useRef<HTMLParagraphElement>(null);
  useEffect(() => {
    if (unavailable) deniedNotice.current?.focus();
  }, [unavailable]);
  const active = useRef<AbortController | null>(null);
  useEffect(() => () => active.current?.abort(), []);
  async function read() {
    if (active.current) return;
    const controller = new AbortController();
    active.current = controller;
    setBusy(true);
    setError(undefined);
    try {
      await experienceRequest(
        `notifications/${item.id}/read`,
        controller.signal,
        undefined,
        "PATCH",
      );
      if (!controller.signal.aborted) {
        refresh();
        window.dispatchEvent(new Event("vaultdrive:notifications-changed"));
      }
    } catch (error) {
      if (!controller.signal.aborted) setError(error);
    } finally {
      if (!controller.signal.aborted) setBusy(false);
      active.current = null;
    }
  }
  const quota =
    item.eventType === "STORAGE_QUOTA_THRESHOLD_CROSSED" ? item.quota : null;
  return (
    <li className="space-y-2 border-b py-5 break-words">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h2 className="font-medium">
          {notificationEvents[item.eventType] ?? "Notification"}
        </h2>
        <span className="text-xs text-muted-foreground">
          {item.readAt ? "Read" : "Unread"}
        </span>
      </div>
      {quota ? (
        <p className="text-sm">
          Your logical storage reached {quota.threshold}%:{" "}
          {formatBytes(BigInt(quota.logicalUsageBytes))} of{" "}
          {formatBytes(BigInt(quota.limitBytes))}. Event-time snapshot,{" "}
          {timestamp(quota.occurredAt)}. Check Settings for current usage.
        </p>
      ) : item.actionable && !unavailable && item.resourceName ? (
        <p className="text-sm">
          {item.resourceName}
          {item.newRole
            ? ` · ${item.newRole === "EDITOR" ? "Editor" : "Viewer"}`
            : ""}
        </p>
      ) : (
        <p className="text-sm text-muted-foreground">
          Historical update. Resource details are unavailable.
        </p>
      )}
      <time
        className="block text-xs text-muted-foreground"
        dateTime={item.createdAt}
      >
        {timestamp(item.createdAt)}
      </time>
      {unavailable && (
        <p
          ref={deniedNotice}
          tabIndex={-1}
          role="alert"
          className="text-sm outline-none"
        >
          This resource is unavailable or your access has changed. Refresh
          history for current details.
        </p>
      )}
      {item.actionable &&
        !unavailable &&
        item.resourceId &&
        item.resourceType && (
          <ResourceAction
            type={item.resourceType}
            id={item.resourceId}
            denied={() => setUnavailable(true)}
            navigated={navigated}
          />
        )}
      {quota && (
        <Link
          href="/settings"
          onClick={navigated}
          className="block text-sm underline"
        >
          View storage settings
        </Link>
      )}
      {!item.readAt && (
        <Button variant="outline" size="sm" onClick={read} disabled={busy}>
          {busy ? "Marking as read…" : "Mark as read"}
        </Button>
      )}
      {error !== undefined && (
        <p role="alert" className="text-sm">
          {errorMessage(error)} Refresh to check read state before trying again.
        </p>
      )}
    </li>
  );
}
function NotificationList({
  query,
  compact = false,
  navigated,
}: {
  query: string;
  compact?: boolean;
  navigated?: () => void;
}) {
  const { value, error, loading, reload } = useExperienceRead<
    HistoryPage<Notification>
  >(`notifications?${query}`);
  return (
    <section aria-label="Notification history" aria-busy={loading}>
      <ReadState loading={loading} error={error} retry={reload} />
      {value && (
        <>
          <ul>
            {value.content.map((item) => (
              <NotificationRow
                key={item.id}
                item={item}
                refresh={reload}
                navigated={navigated}
              />
            ))}
          </ul>
          {value.content.length === 0 && (
            <div className="my-5 rounded-xl border border-dashed p-5">
              <p className="font-medium">No notifications here</p>
              <p className="mt-2 text-sm text-muted-foreground">
                Notifications appear after supported events are delivered. Local
                notification workers may be disabled; an empty inbox does not
                establish a delivery failure.
              </p>
            </div>
          )}
          {!compact && (
            <Pagination
              page={value}
              href={(page) => {
                const params = new URLSearchParams(query);
                params.set("page", String(page));
                return `/notifications?${params}`;
              }}
            />
          )}
        </>
      )}
    </section>
  );
}
export function NotificationsPage() {
  const search = useSearchParams(),
    router = useRouter();
  const query = historyQuery(new URLSearchParams(search), [
    "unread",
    "eventType",
  ]);
  const [revision, setRevision] = useState(0);
  function filter(key: string, value: string) {
    const next = new URLSearchParams(query);
    next.delete("page");
    if (value) next.set(key, value);
    else next.delete(key);
    router.push(`/notifications?${next}`);
  }
  return (
    <>
      <h1 className="text-2xl font-semibold">Notifications</h1>
      <p className="mt-2 text-sm text-muted-foreground">
        Recipient-only history, newest first. Delivery may be delayed or
        reordered. Times are UTC.
      </p>
      <div className="my-5 flex flex-wrap items-end gap-4">
        <label className="text-sm">
          Read state
          <select
            className="mt-1 block rounded-md border bg-background p-2"
            value={query.get("unread") ?? ""}
            onChange={(e) => filter("unread", e.target.value)}
          >
            <option value="">All notifications</option>
            <option value="true">Unread</option>
            <option value="false">Read</option>
          </select>
        </label>
        <label className="text-sm">
          Notification type
          <select
            className="mt-1 block max-w-full rounded-md border bg-background p-2"
            value={query.get("eventType") ?? ""}
            onChange={(e) => filter("eventType", e.target.value)}
          >
            <option value="">All types</option>
            {Object.entries(notificationEvents).map(([key, value]) => (
              <option key={key} value={key}>
                {value}
              </option>
            ))}
          </select>
        </label>
        <Button
          variant="outline"
          onClick={() => {
            setRevision((n) => n + 1);
            window.dispatchEvent(new Event("vaultdrive:notifications-changed"));
          }}
        >
          Refresh history
        </Button>
      </div>
      <NotificationList key={`${query}:${revision}`} query={query.toString()} />
    </>
  );
}
