import type { components } from "@/lib/api/generated";

export type FileResource = components["schemas"]["file_dto_FileResponse"];
export type FolderResource = components["schemas"]["folder_dto_FolderResponse"];
export type TrashEntry =
  components["schemas"]["folder_dto_TrashPageResponse"]["content"][number];
export type Resource =
  | { resourceType: "FILE"; resource: FileResource }
  | { resourceType: "FOLDER"; resource: FolderResource };
export type ExplorerEntry =
  Resource | { resourceType: "TRASH"; resource: TrashEntry };
export type Page<T> = {
  content: T[];
  page: number;
  size: number;
  totalElements: number | bigint;
  totalPages: number;
};
export const PAGE_SIZE = 50;
export const searchFilters = [
  "resourceType",
  "scope",
  "mimeType",
  "minSizeBytes",
  "maxSizeBytes",
  "createdFrom",
  "createdTo",
  "modifiedFrom",
  "modifiedTo",
] as const;
export type SearchFilter = (typeof searchFilters)[number];
export function entryKey(entry: ExplorerEntry) {
  return `${entry.resourceType === "TRASH" ? entry.resource.resourceType : entry.resourceType}:${entry.resource.id}`;
}
export function canOpen(entry: ExplorerEntry) {
  return (
    entry.resourceType === "FOLDER" &&
    entry.resource.allowedCapabilities.includes("LIST_CHILDREN")
  );
}
export function canRead(entry: ExplorerEntry) {
  return (
    entry.resourceType !== "TRASH" &&
    entry.resource.allowedCapabilities.includes("READ_METADATA")
  );
}
export function folderHref(id: string, shared = false) {
  return `${shared ? "/shared" : "/drive"}?folder=${encodeURIComponent(id)}`;
}
