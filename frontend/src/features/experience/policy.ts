const uuid =
  "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
export function experiencePolicy(path: string) {
  if (path === "users/me")
    return { methods: ["GET", "PATCH"], json: true, key: false, query: [] };
  if (new RegExp(`^notifications/${uuid}/read$`).test(path))
    return { methods: ["PATCH"], json: false, key: false, query: [] };
  const query =
    path === "notifications"
      ? ["unread", "eventType", "page", "size"]
      : path === "notifications/unread-count"
        ? []
        : path === "activity"
          ? [
              "resourceType",
              "resourceId",
              "eventType",
              "actorId",
              "from",
              "to",
              "page",
              "size",
            ]
          : path === "permission-activity"
            ? ["resourceType", "resourceId", "eventType", "page", "size"]
            : undefined;
  if (query) return { methods: ["GET"], json: false, key: false, query };
}
