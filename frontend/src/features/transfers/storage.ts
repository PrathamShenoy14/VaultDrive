import type {
  PartExpectation,
  PartUrl,
  StoredPart,
  UploadSession,
} from "./contracts";
import { PART_SIZE, MAX_FILE_SIZE } from "./contracts";

export class TransferProblem extends Error {
  constructor(
    message: string,
    public readonly state: "blocked" | "paused" = "blocked",
  ) {
    super(message);
  }
}
export async function checksum(bytes: ArrayBuffer): Promise<string> {
  const digest = new Uint8Array(await crypto.subtle.digest("SHA-256", bytes));
  return btoa(String.fromCharCode(...digest));
}
export function validateSession(session: UploadSession, size: number) {
  if (
    size <= 0 ||
    size > MAX_FILE_SIZE ||
    Number(session.declaredSize) !== size ||
    session.partSize !== PART_SIZE ||
    session.partCount !== Math.ceil(size / PART_SIZE)
  )
    throw new TransferProblem(
      "The session geometry does not match this file. Keep the session ID for investigation.",
    );
}
export function signedHeaders(
  signed: PartUrl,
  part: PartExpectation,
  now = Date.now(),
): Record<string, string> {
  const url = new URL(signed.upload.url);
  if (
    url.protocol !== "https:" ||
    url.username ||
    url.password ||
    url.hash ||
    (typeof location !== "undefined" && url.origin === location.origin)
  )
    throw new TransferProblem(
      "Direct upload requires a trusted HTTPS Garage endpoint distinct from the app. Ask the operator to check the storage binding and CORS.",
    );
  const headers: Record<string, string> = {};
  for (const [key, values] of Object.entries(signed.upload.requiredHeaders)) {
    if (values.length !== 1 || headers[key.toLowerCase()] !== undefined)
      throw new TransferProblem(
        "The signed headers are unsupported. Keep the session ID and contact the operator.",
      );
    headers[key.toLowerCase()] = values[0]!;
  }
  if (
    Object.keys(headers).sort().join(",") !==
      "content-length,host,x-amz-checksum-sha256" ||
    headers.host !== url.host ||
    headers["content-length"] !== String(part.length) ||
    headers["x-amz-checksum-sha256"] !== part.sha256 ||
    signed.part.partNumber !== part.partNumber ||
    Number(signed.part.length) !== Number(part.length) ||
    signed.part.sha256 !== part.sha256 ||
    !signed.part.registrationId
  )
    throw new TransferProblem(
      "The signed capability does not match the immutable part evidence.",
    );
  if (
    !Number.isFinite(Date.parse(signed.upload.expiresAt)) ||
    Date.parse(signed.upload.expiresAt) <= now + 5000
  )
    throw new TransferProblem(
      "The signed URL has expired or is too close to expiry. Resume to request a fresh URL for the same bytes.",
      "paused",
    );
  // Host and Content-Length are forbidden browser headers; ArrayBuffer framing
  // supplies the exact length. Do not add a Content-Type or API credentials.
  return { "x-amz-checksum-sha256": part.sha256 };
}
export type WireEvidence = { etag: string; checksum: string };
export async function putPart(
  signed: PartUrl,
  part: PartExpectation,
  bytes: ArrayBuffer,
  signal: AbortSignal,
  progress: (bytes: number) => void,
): Promise<WireEvidence> {
  const headers = signedHeaders(signed, part);
  if (bytes.byteLength !== Number(part.length))
    throw new TransferProblem("The local part length changed.");
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    const abort = () => xhr.abort();
    const finish = () => signal.removeEventListener("abort", abort);
    xhr.open("PUT", signed.upload.url);
    xhr.withCredentials = false;
    xhr.timeout = 120000;
    for (const [name, value] of Object.entries(headers))
      xhr.setRequestHeader(name, value);
    xhr.upload.onprogress = (event) =>
      progress(Math.min(bytes.byteLength, event.loaded));
    xhr.onerror = xhr.ontimeout = () => {
      finish();
      reject(
        new TransferProblem(
          "The storage response was lost. Check your network, trusted HTTPS and Garage CORS; resume to inspect parts before sending identical bytes.",
          "paused",
        ),
      );
    };
    xhr.onabort = () => {
      finish();
      reject(
        new TransferProblem(
          "The local part request stopped. Its storage outcome may be unknown.",
          "paused",
        ),
      );
    };
    xhr.onload = () => {
      finish();
      if (xhr.status !== 200) {
        reject(
          new TransferProblem(
            `Storage rejected the part (HTTP ${xhr.status}). The URL may have expired; resume to inspect evidence and refresh its capability.`,
            "paused",
          ),
        );
        return;
      }
      const etag = xhr.getResponseHeader("ETag"),
        digest = xhr.getResponseHeader("x-amz-checksum-sha256");
      if (
        !validEtag(etag) ||
        digest !== part.sha256 ||
        xhr.responseURL !== signed.upload.url
      ) {
        reject(
          new TransferProblem(
            "Storage evidence is missing or mismatched. Check exposed ETag/checksum headers and routing. Resume will inspect the backend's retained evidence.",
          ),
        );
        return;
      }
      resolve({ etag: etag!, checksum: digest! });
    };
    signal.addEventListener("abort", abort, { once: true });
    if (signal.aborted) {
      finish();
      reject(new TransferProblem("Transfer stopped.", "paused"));
    } else xhr.send(bytes);
  });
}
export function validEtag(etag: string | null): etag is string {
  return (
    !!etag?.trim() && etag.length <= 1024 && !/[\u0000-\u001f\u007f]/.test(etag)
  );
}
export function matchEvidence(
  observed: StoredPart,
  expected: PartExpectation,
  wire?: WireEvidence,
) {
  if (
    observed.partNumber !== expected.partNumber ||
    Number(observed.size) !== Number(expected.length) ||
    observed.sha256 !== expected.sha256 ||
    !validEtag(observed.etag) ||
    (wire && (observed.etag !== wire.etag || observed.sha256 !== wire.checksum))
  )
    throw new TransferProblem(
      "The local bytes, storage response and backend part evidence do not match. Keep the session ID; completion is blocked.",
    );
}
