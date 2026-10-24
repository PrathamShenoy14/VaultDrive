const id =
  "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
export const validId = (value: string) => new RegExp(`^${id}$`).test(value);
export function sharingPolicy(path: string) {
  if (new RegExp(`^(files|folders)/${id}/grants$`).test(path))
    return { methods: ["GET", "POST"], json: true };
  if (new RegExp(`^(files|folders)/${id}/grants/${id}$`).test(path))
    return { methods: ["PATCH", "DELETE"], json: true, precondition: true };
  if (new RegExp(`^files/${id}/share-links$`).test(path))
    return { methods: ["GET", "POST"], json: true };
  if (new RegExp(`^files/${id}/share-links/${id}$`).test(path))
    return { methods: ["DELETE"], json: false };
}
export const publicLinkPath = (path: string) =>
  new RegExp(`^public/share-links/${id}(?:/download)?$`).test(path);
// Canonical unpadded base64url of exactly 32 bytes (last sextet has two zero bits).
export const validSecret = (secret: string) =>
  /^[A-Za-z0-9_-]{42}[AEIMQUYcgkosw048]$/.test(secret);
