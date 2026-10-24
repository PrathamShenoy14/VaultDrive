import { beforeEach, expect, it, vi } from "vitest";
import { act, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { Dialog, DialogContent } from "@/components/ui/dialog";
import {
  ManagementProvider,
  MutationForm,
  useManagement,
} from "@/features/management/provider";
import { ManagementStore, actions } from "@/features/management/model";
import { DestinationPicker } from "@/features/management/destination-picker";
import { Explorer } from "@/features/explorer/explorer";
import type {
  FolderResource,
  Resource,
  TrashEntry,
} from "@/features/explorer/contracts";
const { router, location } = vi.hoisted(() => ({
  router: { push: vi.fn(), refresh: vi.fn() },
  location: { pathname: "/drive", search: "view=grid" },
}));
vi.mock("next/navigation", () => ({
  useRouter: () => router,
  usePathname: () => location.pathname,
  useSearchParams: () => new URLSearchParams(location.search),
}));
const sourceId = "11111111-1111-4111-8111-111111111111",
  destinationId = "22222222-2222-4222-8222-222222222222";
const folder: FolderResource = {
  id: sourceId,
  name: "Projects",
  parentFolderId: null,
  effectiveRole: "OWNER",
  allowedCapabilities: [
    "READ_METADATA",
    "LIST_CHILDREN",
    "RENAME",
    "MOVE",
    "COPY",
    "TRASH",
    "CREATE_FOLDER",
  ],
  createdAt: "2026-10-24T00:00:00Z",
  updatedAt: "2026-10-24T00:00:00Z",
};
const entry: Resource = { resourceType: "FOLDER", resource: folder };
const file: Extract<Resource, { resourceType: "FILE" }> = {
  resourceType: "FILE",
  resource: {
    id: sourceId,
    name: "Notes.txt",
    folderId: null,
    effectiveRole: "OWNER",
    allowedCapabilities: ["RENAME", "MOVE", "COPY", "TRASH"],
    sizeBytes: 1,
    contentType: "text/plain",
    createdAt: folder.createdAt,
    updatedAt: folder.updatedAt,
  },
};
const trash: TrashEntry = {
  id: sourceId,
  resourceType: "FOLDER",
  name: "Projects",
  originalParentId: destinationId,
  deletedAt: folder.createdAt,
  purgeRequestedAt: null,
  lifecycleState: "TRASHED",
  allowedCapabilities: ["RESTORE", "PURGE"],
};
const page = (content: unknown[], number = 0, totalPages = 1) => ({
  content,
  page: number,
  totalPages,
  size: 50,
  totalElements: content.length,
});
function form(intent: Parameters<typeof MutationForm>[0]["intent"]) {
  const done = vi.fn();
  const store = new ManagementStore({ changed: vi.fn(), persist: vi.fn() });
  const view = render(
    <Dialog open>
      <DialogContent>
        <MutationForm intent={intent} store={store} done={done} />
      </DialogContent>
    </Dialog>,
  );
  return { done, store, ...view };
}
beforeEach(() => {
  vi.clearAllMocks();
  sessionStorage.clear();
  location.pathname = "/drive";
  location.search = "view=grid";
});
it("opens management for lossless bigint metadata without serializing the resource DTO", async () => {
  function OpenLargeFile() {
    const management = useManagement();
    return (
      <button
        disabled={!management}
        onClick={() =>
          management?.open({
            action: "rename",
            entry: {
              resourceType: "FILE",
              resource: { ...file.resource, sizeBytes: 9223372036854775807n },
            },
          })
        }
      >
        Rename large metadata
      </button>
    );
  }
  render(
    <ManagementProvider userId="large-metadata">
      <OpenLargeFile />
    </ManagementProvider>,
  );
  const user = userEvent.setup();
  await user.click(
    screen.getByRole("button", { name: "Rename large metadata" }),
  );
  expect(await screen.findByRole("dialog", { name: "Rename" })).toBeVisible();
  expect(screen.getByLabelText("Name")).toHaveValue("Notes.txt");
});
it("validates folder creation and prevents repeated submission while preserving normalized request", async () => {
  let finish!: (response: Response) => void;
  const fetcher = vi.fn<
    (url: RequestInfo | URL, init?: RequestInit) => Promise<Response>
  >(
    () =>
      new Promise<Response>((resolve) => {
        finish = resolve;
      }),
  );
  vi.stubGlobal("fetch", fetcher);
  const { done } = form({ action: "create", parentId: destinationId });
  const user = userEvent.setup();
  expect(screen.getByRole("button", { name: "Create folder" })).toBeDisabled();
  await user.type(screen.getByLabelText("Name"), "bad/name");
  expect(screen.getByText(/path separators/)).toBeVisible();
  await user.clear(screen.getByLabelText("Name"));
  await user.type(screen.getByLabelText("Name"), "  New folder  ");
  await user.dblClick(screen.getByRole("button", { name: "Create folder" }));
  expect(fetcher).toHaveBeenCalledTimes(1);
  expect(done).not.toHaveBeenCalled();
  expect(JSON.parse(fetcher.mock.calls[0]![1]!.body as string)).toEqual({
    name: "New folder",
    parentFolderId: destinationId,
  });
  await act(async () =>
    finish(Response.json({ folderId: sourceId }, { status: 201 })),
  );
  await waitFor(() =>
    expect(done).toHaveBeenCalledWith("Create folder confirmed."),
  );
});
it.each([
  [409, /name collision/],
  [403, /current permissions/],
  [404, /no longer accessible/],
] as const)(
  "shows rename failure %i without claiming success",
  async (status, message) => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValue(
          Response.json(
            { status, error: "ERROR", message: "Backend" },
            { status },
          ),
        ),
    );
    const { done } = form({ action: "rename", entry });
    const user = userEvent.setup();
    expect(screen.getByLabelText("Name")).toHaveValue("Projects");
    await user.type(screen.getByLabelText("Name"), " New");
    await user.click(screen.getByRole("button", { name: "Rename" }));
    expect(await screen.findByRole("alert")).toHaveTextContent(message);
    expect(done).not.toHaveBeenCalled();
  },
);
it("preserves file extensions and sends rename only after valid input", async () => {
  const fetcher = vi
    .fn()
    .mockResolvedValue(
      Response.json({ ...file.resource, name: "Changed.txt" }),
    );
  vi.stubGlobal("fetch", fetcher);
  const { done } = form({ action: "rename", entry: file });
  const user = userEvent.setup();
  await user.clear(screen.getByLabelText("Name"));
  await user.type(screen.getByLabelText("Name"), "Changed.pdf");
  expect(screen.getByRole("button", { name: "Rename" })).toBeDisabled();
  await user.clear(screen.getByLabelText("Name"));
  await user.type(screen.getByLabelText("Name"), "Changed");
  await user.click(screen.getByRole("button", { name: "Rename" }));
  expect(done).toHaveBeenCalledWith("Rename confirmed.");
  expect(fetcher.mock.calls[0]![0]).toContain(`/files/${sourceId}/name`);
});
it("retains uncertain rename evidence and blocks resubmission", async () => {
  const fetcher = vi.fn().mockRejectedValue(new TypeError("lost"));
  vi.stubGlobal("fetch", fetcher);
  const { store, done } = form({ action: "rename", entry });
  const user = userEvent.setup();
  await user.click(screen.getByRole("button", { name: "Rename" }));
  expect(await screen.findByRole("alert")).toHaveTextContent(
    /outcome is uncertain/,
  );
  expect(screen.getByRole("button", { name: "Rename" })).toBeDisabled();
  expect(store.snapshot()[0]).toMatchObject({ kind: "uncertain", sourceId });
  expect(done).not.toHaveBeenCalled();
  expect(fetcher).toHaveBeenCalledTimes(1);
});
it("navigates destination pages with keyboard, excludes source/cycles and disables current move parent", async () => {
  const destination = { ...folder, id: destinationId, name: "Destination" };
  vi.stubGlobal(
    "fetch",
    vi.fn(async (url: string) =>
      Response.json(
        url.includes("children")
          ? page([])
          : page([
              folder,
              destination,
              {
                ...destination,
                id: "viewer",
                name: "Viewer",
                effectiveRole: "VIEWER",
              },
            ]),
      ),
    ),
  );
  const choose = vi.fn();
  render(
    <DestinationPicker
      moving
      currentParent={null}
      excludedId={sourceId}
      onChoose={choose}
    />,
  );
  const user = userEvent.setup();
  await screen.findByRole("button", { name: "Open Destination" });
  expect(screen.queryByRole("button", { name: "Open Projects" })).toBeNull();
  expect(screen.queryByRole("button", { name: "Open Viewer" })).toBeNull();
  expect(
    screen.getByRole("button", { name: "Use this folder" }),
  ).toBeDisabled();
  screen.getByRole("button", { name: "Open Destination" }).focus();
  await user.keyboard("{Enter}");
  await waitFor(() =>
    expect(
      screen.getByRole("button", { name: "Use this folder" }),
    ).toBeEnabled(),
  );
  await user.click(screen.getByRole("button", { name: "Use this folder" }));
  expect(choose).toHaveBeenCalledWith(destinationId, "Destination");
  await user.click(screen.getByRole("button", { name: "Up one folder" }));
  await waitFor(() =>
    expect(
      screen.getByRole("button", { name: "Use this folder" }),
    ).toBeDisabled(),
  );
});
it("submits file move using folderId and handles cycle/invalid destination conflicts", async () => {
  const fetcher = vi.fn(async (_url: string, init?: RequestInit) =>
    init?.method === "PATCH"
      ? Response.json(
          { status: 409, error: "CONFLICT", message: "cycle" },
          { status: 409 },
        )
      : Response.json(
          page([{ ...folder, id: destinationId, name: "Destination" }]),
        ),
  );
  vi.stubGlobal("fetch", fetcher);
  const { done } = form({ action: "move", entry: file });
  const user = userEvent.setup();
  await user.click(
    await screen.findByRole("button", { name: "Open Destination" }),
  );
  await user.click(
    await screen.findByRole("button", { name: "Use this folder" }),
  );
  await user.click(screen.getByRole("button", { name: "Move" }));
  expect(await screen.findByRole("alert")).toHaveTextContent(/folder cycle/);
  expect(done).not.toHaveBeenCalled();
  const call = fetcher.mock.calls.find(([, init]) => init?.method === "PATCH")!;
  expect(JSON.parse(call[1]!.body as string)).toEqual({
    folderId: destinationId,
  });
});
it.each(["trash", "restore", "purge"] as const)(
  "confirms %s and uses its distinct frozen route",
  async (action) => {
    const fetcher = vi.fn().mockResolvedValue(
      action === "trash"
        ? new Response(null, { status: 204 })
        : Response.json(
            action === "purge"
              ? { operationId: "retained-operation" }
              : {
                  folderId: sourceId,
                  restoredName: "Projects (restored)",
                  parentFolderId: null,
                },
            { status: action === "purge" ? 202 : 200 },
          ),
    );
    vi.stubGlobal("fetch", fetcher);
    const { store, done } = form({
      action,
      entry:
        action === "trash" ? entry : { resourceType: "TRASH", resource: trash },
    });
    const user = userEvent.setup();
    expect(fetcher).not.toHaveBeenCalled();
    await user.click(
      screen.getByRole("button", {
        name:
          action === "trash"
            ? "Move to Trash"
            : action === "restore"
              ? "Restore"
              : "Delete permanently",
      }),
    );
    expect(done).toHaveBeenCalledWith(
      expect.stringMatching(
        action === "purge"
          ? /physical cleanup is not confirmed/
          : action === "restore"
            ? /Projects \(restored\).*My Drive/
            : /confirmed/,
      ),
    );
    expect(fetcher.mock.calls[0]![0]).toBe(
      `/api/v1/${action === "trash" ? `folders/${sourceId}` : `trash/folders/${sourceId}/${action === "purge" ? "permanent" : "restore"}`}`,
    );
    store.stop();
  },
);
it("uses capability actions for Owner, Viewer, Editor and pending Trash", () => {
  expect(actions(entry)).toEqual(["rename", "move", "copy", "trash"]);
  expect(
    actions({
      ...entry,
      resource: {
        ...folder,
        effectiveRole: "VIEWER",
        allowedCapabilities: ["READ_METADATA"],
      },
    }),
  ).toEqual([]);
  expect(
    actions({
      ...entry,
      resource: {
        ...folder,
        effectiveRole: "EDITOR",
        allowedCapabilities: ["RENAME"],
      },
    }),
  ).toEqual(["rename"]);
  expect(
    actions({
      resourceType: "TRASH",
      resource: {
        ...trash,
        lifecycleState: "PENDING_PURGE",
        allowedCapabilities: [],
      },
    }),
  ).toEqual([]);
});
it("refreshes loaded pages and selected renamed metadata without changing URL/navigation", async () => {
  let renamed = false;
  const fetcher = vi.fn(async (url: string, init?: RequestInit) => {
    if (init?.method === "PATCH") {
      renamed = true;
      return Response.json({ ...folder, name: "Renamed" });
    }
    if (url.includes("/folders?")) {
      const number = Number(
        new URL(url, "https://localhost:3000").searchParams.get("page"),
      );
      return Response.json(
        page(
          number === 0
            ? [{ ...folder, name: renamed ? "Renamed" : "Projects" }]
            : [{ ...folder, id: destinationId, name: "Page two" }],
          number,
          2,
        ),
      );
    }
    return Response.json(page([]));
  });
  vi.stubGlobal("fetch", fetcher);
  render(
    <ManagementProvider userId="test-user">
      <Explorer mode="drive" />
    </ManagementProvider>,
  );
  const user = userEvent.setup();
  await user.click(
    await screen.findByRole("button", { name: "Load more folders" }),
  );
  await screen.findByRole("checkbox", { name: "Select Page two" });
  await user.click(screen.getByRole("checkbox", { name: "Select Projects" }));
  await user.click(
    within(screen.getByRole("toolbar")).getByRole("button", { name: "Rename" }),
  );
  await user.clear(screen.getByLabelText("Name"));
  await user.type(screen.getByLabelText("Name"), "Renamed");
  await user.click(
    within(screen.getByRole("dialog")).getByRole("button", { name: "Rename" }),
  );
  await waitFor(() =>
    expect(
      screen.getByRole("checkbox", { name: "Select Renamed" }),
    ).toBeChecked(),
  );
  expect(
    screen.getByRole("checkbox", { name: "Select Page two" }),
  ).toBeVisible();
  expect(router.push).not.toHaveBeenCalled();
  expect(router.refresh).toHaveBeenCalled();
  expect(location.search).toBe("view=grid");
});
it("returns focus after Escape dismisses a keyboard-opened management dialog", async () => {
  vi.stubGlobal("fetch", vi.fn().mockResolvedValue(Response.json(page([]))));
  render(
    <ManagementProvider userId="keyboard-user">
      <Explorer mode="drive" />
    </ManagementProvider>,
  );
  const user = userEvent.setup();
  const create = await screen.findByRole("button", { name: "Create folder" });
  create.focus();
  await user.keyboard("{Enter}");
  await screen.findByRole("dialog");
  await user.keyboard("{Escape}");
  await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
  expect(create).toHaveFocus();
});
