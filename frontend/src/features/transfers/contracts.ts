import type { components } from "@/lib/api/generated";

export type UploadSession =
  components["schemas"]["file_MultipartSessionService_Session"];
export type Cancellation =
  components["schemas"]["file_MultipartCancellationTransactions_Cancellation"];
export type EmptyCreation =
  components["schemas"]["file_EmptyFileTransactions_Status"];
export type PartExpectation =
  components["schemas"]["file_MultipartSessionService_PartExpectation"];
export type PartUrl =
  components["schemas"]["file_MultipartTransferService_PartUrl"];
export type StoredPart = components["schemas"]["storage_MultipartStorage_Part"];
export type PartPage = components["schemas"]["storage_MultipartStorage_Page"];
export type UploadInput =
  components["schemas"]["file_MultipartSessionService_Request"];

export function canRecoverCompletion(session?: UploadSession) {
  return (
    session?.state === "COMPLETION_DECIDED" &&
    !!session.completion &&
    ["PENDING", "UNKNOWN", "STORAGE_COMPLETE", "BLOCKED"].includes(
      session.completion.disposition,
    )
  );
}

export const TRANSFER_LIMIT = 50;
export const FILE_CONCURRENCY = 2;
export const PART_SIZE = 8 * 1024 * 1024;
export const MAX_FILE_SIZE = 5 * 1024 ** 3;
export const UUID =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export type TransferState =
  | "queued"
  | "preparing"
  | "uploading"
  | "pending"
  | "unknown"
  | "blocked"
  | "failed"
  | "cancelling"
  | "cancelled"
  | "completed"
  | "paused";
export type Dispatch =
  "admission" | "initiate" | "complete" | "cancel" | "empty";
// No File, signed URL, provider upload ID or credential is stored in this journal.
export type TransferRecord = {
  id: string;
  key: string;
  name: string;
  size: number;
  contentType: string;
  lastModified: number;
  folderId: string | null;
  sessionId?: string;
  emptyId?: string;
  dispatch?: Dispatch;
};
export type Transfer = TransferRecord & {
  state: TransferState;
  bytes: number;
  message: string;
  busy: boolean;
  hasFile: boolean;
  session?: UploadSession;
  empty?: EmptyCreation;
  cancellation?: Cancellation;
  evidence: StoredPart[];
  retryAt?: number;
  authRequired?: boolean;
};

export interface TransferApi {
  reserve(input: UploadInput, key: string): Promise<UploadSession>;
  session(id: string): Promise<UploadSession>;
  initiate(id: string): Promise<UploadSession>;
  sign(id: string, part: PartExpectation): Promise<PartUrl[]>;
  parts(id: string, marker: number, limit: number): Promise<PartPage>;
  complete(id: string): Promise<UploadSession>;
  recover(id: string): Promise<UploadSession>;
  cancel(id: string): Promise<Cancellation>;
  cancellation(id: string): Promise<Cancellation>;
  recoverCancellation(id: string): Promise<Cancellation>;
  empty(input: Omit<UploadInput, "size">, key: string): Promise<EmptyCreation>;
  emptyStatus(id: string): Promise<EmptyCreation>;
  recoverEmpty(id: string): Promise<EmptyCreation>;
}
