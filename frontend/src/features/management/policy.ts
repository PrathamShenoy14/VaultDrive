const id =
  "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
export function managementPolicy(path: string) {
  if (path === "folders") return { methods: ["POST"], json: true };
  if (new RegExp(`^folders/${id}$`).test(path))
    return { methods: ["PATCH", "DELETE"], json: true };
  if (new RegExp(`^files/${id}$`).test(path)) return { methods: ["DELETE"] };
  if (
    new RegExp(`^(?:files/${id}/(?:name|move)|folders/${id}/move)$`).test(path)
  )
    return { methods: ["PATCH"], json: true };
  if (new RegExp(`^(?:files|folders)/${id}/copy$`).test(path))
    return { methods: ["POST"], json: true, key: true };
  if (new RegExp(`^(?:files|trash/folders)/${id}/restore$`).test(path))
    return { methods: ["POST"] };
  if (new RegExp(`^(?:files|trash/folders)/${id}/permanent$`).test(path))
    return { methods: ["DELETE"] };
  if (
    new RegExp(
      `^(?:copy-operations|folder-copy-operations|deletion-operations)/${id}$`,
    ).test(path)
  )
    return { methods: ["GET"] };
  if (
    new RegExp(
      `^(?:copy-operations|folder-copy-operations)/${id}/(?:execute|recover|cancel)$`,
    ).test(path) ||
    new RegExp(`^folder-copy-operations/${id}/discard$`).test(path)
  )
    return { methods: ["POST"] };
}
