// Narrow BFF surface. Values/domain authorization are validated by Spring;
// only documented query names are forwarded, once each, without reinterpretation.
import { searchFilters } from "./contracts";
const uuid =
  "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
export function readQueryKeys(path: string): readonly string[] | undefined {
  if (
    ["folders", "files/trash", "trash/folders", "shared-with-me"].includes(
      path,
    ) ||
    new RegExp(`^folders/${uuid}/children$`).test(path)
  )
    return ["page", "size"];
  if (path === "files") return ["folderId", "page", "size"];
  if (path === "search") return ["q", ...searchFilters, "page", "size"];
  if (new RegExp(`^(files|folders)/${uuid}$`).test(path)) return [];
}
export function validReadQuery(
  query: URLSearchParams,
  keys: readonly string[],
) {
  return (
    query.toString().length <= 4096 &&
    [...query.keys()].every(
      (key) => keys.includes(key) && query.getAll(key).length === 1,
    )
  );
}
