"use client";
import { useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { Button } from "@/components/ui/button";
import {
  resourceEvents,
  permissionEvents,
  timestamp,
  type ResourceActivity,
  type PermissionActivity,
} from "./contracts";
import {
  historyQuery,
  Pagination,
  ReadState,
  useExperienceRead,
  type HistoryPage,
} from "./feed";
import { ResourceAction } from "./resource-action";
export function ActivityRow({
  item,
  permission,
}: {
  item: ResourceActivity | PermissionActivity;
  permission: boolean;
}) {
  const resource = item as ResourceActivity,
    grant = item as PermissionActivity;
  const label = permission
    ? permissionEvents[grant.eventType]
    : resourceEvents[resource.eventType];
  return (
    <li className="space-y-2 border-b py-5 break-words">
      <h2 className="font-medium">
        {label ?? "Resource update"} ·{" "}
        {item.resourceName || "Resource unavailable"}
      </h2>
      <p className="text-sm">
        {item.actorDisplayName || "Account unavailable"} ·{" "}
        {item.resourceType === "FOLDER" ? "Folder" : "File"}
      </p>
      {!permission && resource.actorAttribution !== "CALLER" && (
        <p className="text-xs text-muted-foreground">
          {resource.actorAttribution === "UPLOAD_INITIATOR"
            ? "Attributed to the original upload initiator (legacy event)."
            : "Attributed to the deletion requester."}
        </p>
      )}
      {!permission && resource.eventType === "RENAMED" && (
        <p className="text-sm">
          {resource.beforeName ?? "Name unavailable"} →{" "}
          {resource.afterName ?? "Name unavailable"}
        </p>
      )}
      {permission && grant.recipientEmail && (
        <p className="text-sm">
          Recipient: {grant.recipientEmail}
          {grant.oldRole ? ` · Previous role: ${grant.oldRole}` : ""}
          {grant.newRole ? ` · New role: ${grant.newRole}` : ""}
        </p>
      )}
      {permission && grant.eventType === "LINK_CREATED" && (
        <p className="text-sm">
          Viewer link
          {grant.linkExpiresAt
            ? ` · Expires ${timestamp(grant.linkExpiresAt)}`
            : ""}
        </p>
      )}
      <time
        className="block text-xs text-muted-foreground"
        dateTime={item.occurredAt}
      >
        {timestamp(item.occurredAt)}
      </time>
      {item.resourceId &&
        item.resourceType &&
        item.eventType !== "PERMANENTLY_DELETED" && (
          <ResourceAction type={item.resourceType} id={item.resourceId} />
        )}
      {item.eventType === "PERMANENTLY_DELETED" && (
        <p className="text-xs text-muted-foreground">
          Historical metadata removal. Physical storage retention may differ.
        </p>
      )}
    </li>
  );
}
function ActivityFeed({
  query,
  permission,
}: {
  query: string;
  permission: boolean;
}) {
  const { value, error, loading, reload } = useExperienceRead<
    HistoryPage<ResourceActivity | PermissionActivity>
  >(`${permission ? "permission-activity" : "activity"}?${query}`);
  return (
    <section
      aria-label={permission ? "Permission history" : "Resource history"}
      aria-busy={loading}
    >
      <ReadState loading={loading} error={error} retry={reload} />
      {value && (
        <>
          <ul>
            {value.content.map((item) => (
              <ActivityRow key={item.id} item={item} permission={permission} />
            ))}
          </ul>
          {value.content.length === 0 && (
            <p className="my-5 rounded-xl border border-dashed p-5 text-sm">
              No activity here. Only supported events for resources you own
              appear in this history.
            </p>
          )}
          <Pagination
            page={value}
            href={(page) => {
              const next = new URLSearchParams(query);
              next.set("page", String(page));
              if (permission) next.set("history", "permissions");
              return `/activity?${next}`;
            }}
          />
        </>
      )}
    </section>
  );
}
export function ActivityPage() {
  const params = useSearchParams(),
    router = useRouter();
  const permission = params.get("history") === "permissions";
  const query = historyQuery(new URLSearchParams(params), [
    "resourceType",
    "resourceId",
    "eventType",
    ...(!permission ? ["actorId", "from", "to"] : []),
  ]);
  const [revision, setRevision] = useState(0);
  function filter(key: string, value: string) {
    const next = new URLSearchParams(query);
    next.delete("page");
    if (key === "history") {
      next.delete("eventType");
      next.delete("actorId");
      next.delete("from");
      next.delete("to");
    } else if (permission) next.set("history", "permissions");
    if (key === "resourceType") next.delete("resourceId");
    if (value) next.set(key, value);
    else next.delete(key);
    router.push(`/activity?${next}`);
  }
  return (
    <>
      <h1 className="text-2xl font-semibold">Activity</h1>
      <p className="mt-2 text-sm text-muted-foreground">
        Owner-only resource and permission history, newest first. Historical
        snapshots may survive deletion. This is not a complete audit log. Times
        are UTC.
      </p>
      <div className="my-5 flex flex-wrap items-end gap-4">
        <label className="text-sm">
          History
          <select
            className="mt-1 block rounded-md border bg-background p-2"
            value={permission ? "permissions" : ""}
            onChange={(e) => filter("history", e.target.value)}
          >
            <option value="">Resource activity</option>
            <option value="permissions">Permission activity</option>
          </select>
        </label>
        <label className="text-sm">
          Resource type
          <select
            className="mt-1 block rounded-md border bg-background p-2"
            value={query.get("resourceType") ?? ""}
            onChange={(e) => filter("resourceType", e.target.value)}
          >
            <option value="">All resources</option>
            <option value="FILE">Files</option>
            <option value="FOLDER">Folders</option>
          </select>
        </label>
        <label className="text-sm">
          Action
          <select
            className="mt-1 block max-w-full rounded-md border bg-background p-2"
            value={query.get("eventType") ?? ""}
            onChange={(e) => filter("eventType", e.target.value)}
          >
            <option value="">All actions</option>
            {Object.entries(permission ? permissionEvents : resourceEvents).map(
              ([key, value]) => (
                <option key={key} value={key}>
                  {value}
                </option>
              ),
            )}
          </select>
        </label>
        <Button variant="outline" onClick={() => setRevision((n) => n + 1)}>
          Refresh activity
        </Button>
      </div>
      <ActivityFeed
        key={`${permission}:${query}:${revision}`}
        query={query.toString()}
        permission={permission}
      />
    </>
  );
}
