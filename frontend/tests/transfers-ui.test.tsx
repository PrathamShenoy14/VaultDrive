import { StrictMode } from "react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { Explorer } from "@/features/explorer/explorer";
import { TransferProvider } from "@/features/transfers/provider";
import { putPart } from "@/features/transfers/storage";
import type { PartUrl } from "@/features/transfers/contracts";

const { router } = vi.hoisted(() => ({
  router: { push: vi.fn(), refresh: vi.fn() },
}));
vi.mock("next/navigation", () => ({
  useRouter: () => router,
  usePathname: () => "/drive",
  useSearchParams: () => new URLSearchParams(),
}));
const uuid = "11111111-1111-4111-8111-111111111111";
const page = (content: unknown[]) => ({
  content,
  page: 0,
  size: 50,
  totalElements: content.length,
  totalPages: 1,
});
const file = {
  id: uuid,
  name: "Ready.txt",
  contentType: "text/plain",
  sizeBytes: 0,
  folderId: null,
  createdAt: "2026-10-24T00:00:00Z",
  updatedAt: "2026-10-24T00:00:00Z",
  effectiveRole: "OWNER",
  allowedCapabilities: ["READ_METADATA", "DOWNLOAD"],
};
beforeEach(() => {
  sessionStorage.clear();
  router.refresh.mockClear();
});
function backend(unknown = false) {
  let completed = false;
  const fetcher = vi.fn(async (input: string, init?: RequestInit) => {
    if (input === "/api/v1/files/empty") {
      completed = !unknown;
      expect(init?.headers).toMatchObject({
        "X-VaultDrive-Client": "1",
        "Idempotency-Key": expect.any(String),
      });
      return Response.json(
        {
          id: uuid,
          fileId: uuid,
          state: unknown ? "UNKNOWN" : "READY",
          storageVerified: !unknown,
        },
        { status: unknown ? 202 : 200 },
      );
    }
    if (input.endsWith("/recover")) {
      completed = true;
      return Response.json({
        id: uuid,
        fileId: uuid,
        state: "READY",
        storageVerified: true,
      });
    }
    if (input.startsWith("/api/v1/empty-file-creations/"))
      return Response.json({
        id: uuid,
        fileId: uuid,
        state: completed ? "READY" : "UNKNOWN",
        storageVerified: completed,
      });
    if (input.startsWith("/api/v1/files?"))
      return Response.json(page(completed ? [file] : []));
    if (input.startsWith("/api/v1/folders?")) return Response.json(page([]));
    return Response.json(file);
  });
  vi.stubGlobal("fetch", fetcher);
  return fetcher;
}
function workspace() {
  return render(
    <StrictMode>
      <TransferProvider userId={uuid}>
        <Explorer mode="drive" />
      </TransferProvider>
    </StrictMode>,
  );
}
describe("M3 explorer integration (mocked backend)", () => {
  it("queues files from the picker, refreshes resources/quota and enables accessible download actions", async () => {
    const fetcher = backend();
    const user = userEvent.setup();
    workspace();
    await waitFor(() =>
      expect(
        screen.getByRole("button", { name: "Upload files" }),
      ).toBeEnabled(),
    );
    await user.upload(
      screen.getByLabelText("Choose files to upload"),
      new File([], "Ready.txt", { type: "text/plain" }),
    );
    const panel = await screen.findByRole("region", { name: "Transfers" });
    await within(panel).findByText("completed");
    expect(
      within(panel).getByRole("progressbar", {
        name: "Overall upload progress",
      }),
    ).toBeVisible();
    await screen.findByRole("button", { name: "Ready.txt" });
    expect(router.refresh).toHaveBeenCalled();
    await user.click(screen.getByLabelText("Select Ready.txt"));
    const download = screen.getByRole("link", { name: "Download" });
    expect(download).toHaveAttribute("href", `/api/v1/files/${uuid}/download`);
    expect(download).toHaveAttribute("target", "_blank");
    await user.click(
      screen.getByRole("button", { name: "Actions for Ready.txt" }),
    );
    expect(
      await screen.findByRole("menuitem", { name: "Download" }),
    ).toHaveAttribute("href", `/api/v1/files/${uuid}/download`);
    await user.keyboard("{Escape}");
    await user.click(within(panel).getByRole("button", { name: "Minimize" }));
    expect(within(panel).queryByText(/Two files/)).not.toBeInTheDocument();
    await user.click(within(panel).getByRole("button", { name: "Expand" }));
    expect(
      fetcher.mock.calls.filter(([path]) => path === "/api/v1/files/empty"),
    ).toHaveLength(1);
  });
  it("accepts multi-file drop with the destination fixed at selection time", async () => {
    const fetcher = backend();
    workspace();
    const upload = screen.getByRole("button", { name: "Upload files" });
    await waitFor(() => expect(upload).toBeEnabled());
    fireEvent.drop(upload.parentElement!.parentElement!, {
      dataTransfer: {
        files: [new File([], "one.txt"), new File([], "two.txt")],
        items: [],
      },
    });
    await waitFor(() =>
      expect(
        fetcher.mock.calls.filter(([path]) => path === "/api/v1/files/empty"),
      ).toHaveLength(2),
    );
    expect(
      await screen.findByText("2 of 2 published · 0 B / 0 B sent"),
    ).toBeVisible();
  });
  it("shows UNKNOWN empty recovery separately and never offers an unsupported cancellation", async () => {
    const fetcher = backend(true);
    const user = userEvent.setup();
    workspace();
    await user.upload(
      screen.getByLabelText("Choose files to upload"),
      new File([], "empty.txt"),
    );
    const panel = await screen.findByRole("region", { name: "Transfers" });
    await within(panel).findByText("unknown");
    expect(
      within(panel).queryByRole("button", { name: "Cancel" }),
    ).not.toBeInTheDocument();
    expect(within(panel).getByText(/Empty creation:/)).toHaveTextContent(uuid);
    await user.click(within(panel).getByRole("button", { name: "Recover" }));
    await within(panel).findByText("completed");
    expect(
      fetcher.mock.calls.filter(([path]) => path === "/api/v1/files/empty"),
    ).toHaveLength(1);
  });
  it("restores metadata-only handles without dispatch and asks for the original file", async () => {
    const fetcher = backend();
    sessionStorage.setItem(
      `vaultdrive-transfers-v1:${uuid}`,
      JSON.stringify([
        {
          id: uuid,
          key: uuid,
          name: "large.bin",
          size: 100,
          contentType: "application/octet-stream",
          lastModified: 123,
          folderId: null,
          sessionId: uuid,
        },
      ]),
    );
    workspace();
    const panel = await screen.findByRole("region", { name: "Transfers" });
    expect(
      within(panel).getByRole("button", { name: "Resume" }),
    ).toBeDisabled();
    expect(
      within(panel).getByLabelText("Reselect original file for large.bin"),
    ).toBeVisible();
    expect(
      fetcher.mock.calls.every(([, init]) => init?.method !== "POST"),
    ).toBe(true);
    expect(
      sessionStorage.getItem(`vaultdrive-transfers-v1:${uuid}`),
    ).not.toMatch(/url|accessToken|uploadId/);
  });
  it.each(["VIEWER", "EDITOR"])(
    "uses fresh folder UPLOAD capability for %s",
    async (role) => {
      vi.stubGlobal(
        "fetch",
        vi.fn(async (input: string) =>
          input === `/api/v1/folders/${uuid}`
            ? Response.json({
                ...file,
                parentFolderId: null,
                effectiveRole: role,
                allowedCapabilities: [
                  "READ_METADATA",
                  "LIST_CHILDREN",
                  ...(role === "EDITOR" ? ["UPLOAD"] : []),
                ],
              })
            : Response.json(page([])),
        ),
      );
      render(
        <TransferProvider userId={uuid}>
          <Explorer mode="shared" folder={uuid} />
        </TransferProvider>,
      );
      await screen.findByRole("heading", { name: "Ready.txt" });
      expect(!!screen.queryByRole("button", { name: "Upload files" })).toBe(
        role === "EDITOR",
      );
    },
  );
});

describe("M3 browser transport (mocked XMLHttpRequest)", () => {
  it("sends exact ArrayBuffer/checksum without API credentials or forbidden headers, and captures progress/evidence", async () => {
    const digest = "A".repeat(43) + "=",
      progress = vi.fn();
    let instance!: FakeXhr;
    class FakeXhr {
      withCredentials = true;
      timeout = 0;
      status = 200;
      responseURL = "";
      headers: Record<string, string> = {};
      body?: ArrayBuffer;
      upload: { onprogress: ((event: { loaded: number }) => void) | null } = {
        onprogress: null,
      };
      onload: (() => void) | null = null;
      onerror = null;
      ontimeout = null;
      onabort = null;
      // The test needs the constructed transport to inspect its wire settings.
      constructor() {
        // eslint-disable-next-line @typescript-eslint/no-this-alias
        instance = this;
      }
      open(method: string, url: string) {
        expect(method).toBe("PUT");
        this.responseURL = url;
      }
      setRequestHeader(key: string, value: string) {
        this.headers[key] = value;
      }
      getResponseHeader(name: string) {
        return name === "ETag" ? '"exact-opaque"' : digest;
      }
      abort() {}
      send(body: ArrayBuffer) {
        this.body = body;
        this.upload.onprogress?.({ loaded: 1 });
        this.onload?.();
      }
    }
    vi.stubGlobal("XMLHttpRequest", FakeXhr);
    const part = { partNumber: 1, length: 3, sha256: digest };
    const signed: PartUrl = {
      part: { ...part, registrationId: uuid },
      upload: {
        url: "https://garage.example.test/key?secret=capability",
        requiredHeaders: {
          host: ["garage.example.test"],
          "content-length": ["3"],
          "x-amz-checksum-sha256": [digest],
        },
        expiresAt: new Date(Date.now() + 300000).toISOString(),
      },
    };
    expect(
      await putPart(
        signed,
        part,
        new ArrayBuffer(3),
        new AbortController().signal,
        progress,
      ),
    ).toEqual({ etag: '"exact-opaque"', checksum: digest });
    expect(instance.withCredentials).toBe(false);
    expect(instance.headers).toEqual({ "x-amz-checksum-sha256": digest });
    expect(instance.body?.byteLength).toBe(3);
    expect(progress).toHaveBeenCalledWith(1);
  });
});
