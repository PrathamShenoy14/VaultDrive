import type { components } from "@/lib/api/generated";
export type Notification =
  components["schemas"]["sharing_SharingNotificationService_Notification"];
export type ResourceActivity =
  components["schemas"]["activity_ResourceActivityService_Activity"];
export type PermissionActivity =
  components["schemas"]["sharing_PermissionActivityService_Activity"];
export const resourceEvents = {
  UPLOAD_COMPLETED: "Upload completed",
  COPIED: "Copied",
  FOLDER_CREATED: "Folder created",
  RENAMED: "Renamed",
  MOVED: "Moved",
  TRASHED: "Moved to Trash",
  RESTORED: "Restored",
  PERMANENTLY_DELETED: "Permanently deleted",
} satisfies Record<ResourceActivity["eventType"], string>;
export const permissionEvents = {
  GRANT_CREATED: "Access granted",
  GRANT_ROLE_CHANGED: "Access role changed",
  GRANT_REVOKED: "Access revoked",
  LINK_CREATED: "Viewer link created",
  LINK_REVOKED: "Viewer link revoked",
} satisfies Record<PermissionActivity["eventType"], string>;
export const notificationEvents = {
  GRANT_CREATED: "Access granted",
  GRANT_ROLE_CHANGED: "Access role changed",
  GRANT_REVOKED: "Access revoked",
  STORAGE_QUOTA_THRESHOLD_CROSSED: "Storage threshold reached",
} satisfies Record<Notification["eventType"], string>;
export function timestamp(value: string) {
  const date = new Date(value);
  return Number.isNaN(date.valueOf())
    ? "Time unavailable"
    : new Intl.DateTimeFormat("en", {
        dateStyle: "medium",
        timeStyle: "short",
        timeZone: "UTC",
      }).format(date) + " UTC";
}
