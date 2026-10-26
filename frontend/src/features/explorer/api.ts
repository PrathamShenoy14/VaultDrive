import { sessionRequest } from "@/features/auth/session-request";
import type { components } from "@/lib/api/generated";
import {
  PAGE_SIZE,
  type Resource,
  type FolderResource,
  type FileResource,
  type Page,
  type ExplorerEntry,
} from "./contracts";

// Only M2 read endpoints are expressible here; backend DTOs remain generated.
type Feed =
  | { kind: "folders"; folder?: string }
  | { kind: "files"; folder?: string }
  | { kind: "shared" }
  | { kind: "search"; query: string }
  | { kind: "trashFiles" | "trashFolders" };
export async function getMetadata(
  type: "FILE",
  id: string,
  signal: AbortSignal,
): Promise<FileResource>;
export async function getMetadata(
  type: "FOLDER",
  id: string,
  signal: AbortSignal,
): Promise<FolderResource>;
export async function getMetadata(
  type: "FILE" | "FOLDER",
  id: string,
  signal: AbortSignal,
): Promise<FileResource | FolderResource>;
export async function getMetadata(
  type: "FILE" | "FOLDER",
  id: string,
  signal: AbortSignal,
) {
  return get<FileResource | FolderResource>(
    `/api/v1/${type === "FILE" ? "files" : "folders"}/${encodeURIComponent(id)}`,
    signal,
  );
}
async function get<T>(url: string, signal: AbortSignal): Promise<T> {
  return sessionRequest<T>(url, {
    signal: AbortSignal.any([signal, AbortSignal.timeout(15000)]),
    cache: "no-store",
    credentials: "same-origin",
  });
}
export async function getPage(
  feed: Feed,
  page: number,
  signal: AbortSignal,
): Promise<Page<ExplorerEntry>> {
  const params = new URLSearchParams(feed.kind === "search" ? feed.query : "");
  params.set("page", String(page));
  params.set("size", String(PAGE_SIZE));
  let path: string;
  switch (feed.kind) {
    case "folders":
      path = feed.folder
        ? `/folders/${encodeURIComponent(feed.folder)}/children`
        : "/folders";
      break;
    case "files":
      path = "/files";
      if (feed.folder) params.set("folderId", feed.folder);
      break;
    case "shared":
      path = "/shared-with-me";
      break;
    case "search":
      path = "/search";
      break;
    case "trashFiles":
      path = "/files/trash";
      break;
    case "trashFolders":
      path = "/trash/folders";
      break;
  }
  if (feed.kind === "folders") {
    const value = await get<
      components["schemas"]["folder_dto_FolderPageResponse"]
    >(`/api/v1${path}?${params}`, signal);
    return {
      ...value,
      content: value.content.map((resource) => ({
        resourceType: "FOLDER",
        resource,
      })),
    };
  }
  if (feed.kind === "files") {
    const value = await get<components["schemas"]["file_dto_FilePageResponse"]>(
      `/api/v1${path}?${params}`,
      signal,
    );
    return {
      ...value,
      content: value.content.map((resource) => ({
        resourceType: "FILE",
        resource,
      })),
    };
  }
  if (feed.kind === "trashFiles" || feed.kind === "trashFolders") {
    const value = await get<
      components["schemas"]["folder_dto_TrashPageResponse"]
    >(`/api/v1${path}?${params}`, signal);
    return {
      ...value,
      content: value.content.map((resource) => ({
        resourceType: "TRASH",
        resource,
      })),
    };
  }
  return get<Page<Resource>>(`/api/v1${path}?${params}`, signal);
}
export type { Feed };
