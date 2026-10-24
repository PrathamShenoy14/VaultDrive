const id =
  "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
export function transferPolicy(path: string):
  | {
      methods: string[];
      query: string[];
      json?: boolean;
      key?: boolean;
      download?: boolean;
    }
  | undefined {
  if (path === "upload-sessions" || path === "files/empty")
    return { methods: ["POST"], query: [], json: true, key: true };
  if (
    new RegExp(`^upload-sessions/${id}$`).test(path) ||
    new RegExp(`^upload-sessions/${id}/cancellation$`).test(path) ||
    new RegExp(`^empty-file-creations/${id}$`).test(path)
  )
    return { methods: ["GET"], query: [] };
  if (new RegExp(`^upload-sessions/${id}/parts$`).test(path))
    return { methods: ["GET"], query: ["marker", "limit"] };
  if (new RegExp(`^upload-sessions/${id}/part-urls$`).test(path))
    return { methods: ["POST"], query: [], json: true };
  if (
    new RegExp(
      `^upload-sessions/${id}/(?:initiate|complete|recover|cancel|cancel/recover)$`,
    ).test(path) ||
    new RegExp(`^empty-file-creations/${id}/recover$`).test(path)
  )
    return { methods: ["POST"], query: [] };
  if (new RegExp(`^files/${id}/download$`).test(path))
    return { methods: ["GET"], query: [], download: true };
}
