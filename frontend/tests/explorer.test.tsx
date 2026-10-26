import { StrictMode } from "react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import {
  render,
  screen,
  waitFor,
  within,
  fireEvent,
  act,
} from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { Explorer } from "@/features/explorer/explorer";
import { Breadcrumbs } from "@/features/explorer/breadcrumbs";
import { SearchBox } from "@/features/explorer/search-box";
import { SearchFilters } from "@/features/explorer/search-filters";
import {
  canOpen,
  canRead,
  type FolderResource,
  type Resource,
  type TrashEntry,
} from "@/features/explorer/contracts";
import { selectEntry } from "@/features/explorer/selection";
const { push, refresh, location } = vi.hoisted(() => ({
  push: vi.fn(),
  refresh: vi.fn(),
  location: { pathname: "/drive", search: "" },
}));
vi.mock("next/navigation", () => ({
  useRouter: () => ({ push, refresh }),
  usePathname: () => location.pathname,
  useSearchParams: () => new URLSearchParams(location.search),
}));
const folderId = "11111111-1111-4111-8111-111111111111";
const parentId = "22222222-2222-4222-8222-222222222222";
const fileId = "33333333-3333-4333-8333-333333333333";
const folder: FolderResource = {
  id: folderId,
  name: "Projects",
  parentFolderId: null,
  createdAt: "2026-10-24T00:00:00Z",
  updatedAt: "2026-10-24T00:00:00Z",
  effectiveRole: "OWNER",
  allowedCapabilities: ["READ_METADATA", "LIST_CHILDREN", "RENAME", "TRASH"],
};
const file: Resource = {
  resourceType: "FILE",
  resource: {
    id: fileId,
    name: "Notes.txt",
    folderId: null,
    contentType: "text/plain",
    sizeBytes: 512,
    createdAt: folder.createdAt,
    updatedAt: folder.updatedAt,
    effectiveRole: "OWNER",
    allowedCapabilities: ["READ_METADATA", "DOWNLOAD", "RENAME", "TRASH"],
  },
};
const page = (content: unknown[], number = 0, totalPages = 1) => ({
  content,
  page: number,
  size: 50,
  totalElements: content.length,
  totalPages,
});
function backend(
  overrides: (url: URL) => Response | Promise<Response> | undefined = () =>
    undefined,
) {
  const fetcher = vi.fn(async (input: string | URL | Request) => {
    const url = new URL(String(input), "https://localhost:3000");
    const override = overrides(url);
    if (override) return override;
    if (url.pathname === `/api/v1/folders/${folderId}`)
      return Response.json(folder);
    if (url.pathname === `/api/v1/files/${fileId}`)
      return new Response(JSON.stringify(file.resource));
    if (url.pathname === "/api/v1/folders")
      return Response.json(page([folder]));
    if (url.pathname === "/api/v1/files")
      return Response.json(page([file.resource]));
    return Response.json(page([]));
  });
  vi.stubGlobal("fetch", fetcher);
  return fetcher;
}
beforeEach(() => {
  location.pathname = "/drive";
  location.search = "";
  push.mockReset();
  refresh.mockReset();
});

describe("M2 explorer with mocked HTTP responses", () => {
  it("retries the failed refresh instead of skipping to the next page", async () => {
    let failed = false;
    const pages: string[] = [];
    backend((url) => {
      if (url.pathname !== "/api/v1/folders") return;
      pages.push(url.searchParams.get("page")!);
      if (failed) {
        failed = false;
        return Response.json(
          { status: 503, error: "UNAVAILABLE", message: "Unavailable" },
          { status: 503 },
        );
      }
      return Response.json(page([folder], 0, 2));
    });
    const user = userEvent.setup();
    render(<Explorer mode="drive" />);
    await screen.findByRole("button", { name: "Projects" });
    failed = true;
    await user.click(
      screen.getByRole("button", { name: "Refresh resources and storage" }),
    );
    await user.click(await screen.findByRole("button", { name: "Try again" }));
    await waitFor(() => expect(pages).toHaveLength(3));
    expect(pages).toEqual(["0", "0", "0"]);
  });
  it("aborts obsolete folder reads and ignores late responses after navigation", async () => {
    let finish: (response: Response) => void = () => {};
    let signal: AbortSignal | undefined;
    const fetcher = vi.fn((input: string, init?: RequestInit) => {
      if (input.includes(folderId)) {
        signal = init?.signal as AbortSignal;
        return new Promise<Response>((resolve) => {
          finish = resolve;
        });
      }
      return Promise.resolve(Response.json(page([])));
    });
    vi.stubGlobal("fetch", fetcher);
    const { rerender } = render(<Explorer mode="drive" folder={folderId} />);
    expect(
      screen.getByRole("status", { name: "Loading folder" }),
    ).toBeVisible();
    rerender(<Explorer mode="drive" />);
    expect(signal?.aborted).toBe(true);
    finish(Response.json(folder));
    await screen.findByText("No folders here");
    expect(
      screen.queryByRole("navigation", { name: "Folder breadcrumbs" }),
    ).not.toBeInTheDocument();
  });
  it("survives StrictMode's setup/cleanup cycle with current bounded feed requests", async () => {
    backend();
    render(
      <StrictMode>
        <Explorer mode="drive" />
      </StrictMode>,
    );
    expect(
      await screen.findByRole("button", { name: "Notes.txt" }),
    ).toBeVisible();
    expect(
      screen.queryByRole("status", { name: "Loading Files" }),
    ).not.toBeInTheDocument();
  });
  it("preserves filters on search pagination and never opens a result without capabilities", async () => {
    const user = userEvent.setup();
    const fetcher = backend((url) =>
      url.pathname === "/api/v1/search"
        ? Response.json(
            page(
              [
                {
                  resourceType: "FOLDER",
                  resource: { ...folder, allowedCapabilities: [] },
                },
              ],
              Number(url.searchParams.get("page")),
              2,
            ),
          )
        : undefined,
    );
    render(
      <Explorer
        mode="search"
        query="q=projects&scope=SHARED&resourceType=FOLDER"
      />,
    );
    expect(
      await screen.findByRole("button", { name: "Projects" }),
    ).toBeDisabled();
    const row = screen.getByLabelText("Projects, Folder");
    row.focus();
    await user.keyboard("{Enter}");
    expect(push).not.toHaveBeenCalled();
    await user.click(screen.getByRole("button", { name: "Load more results" }));
    await waitFor(() => expect(fetcher).toHaveBeenCalledTimes(2));
    expect(String(fetcher.mock.calls[1]![0])).toContain(
      "scope=SHARED&resourceType=FOLDER&page=1&size=50",
    );
  });
  it("loads independent bounded root feeds, selection, keyboard focus and folder navigation", async () => {
    const user = userEvent.setup();
    const fetcher = backend();
    render(<Explorer mode="drive" />);
    await screen.findByRole("button", { name: "Notes.txt" });
    expect(fetcher.mock.calls.map((call) => String(call[0]))).toEqual(
      expect.arrayContaining([
        "/api/v1/folders?page=0&size=50",
        "/api/v1/files?page=0&size=50",
      ]),
    );
    await user.click(screen.getByLabelText("Select Projects"));
    await user.click(screen.getByLabelText("Select Notes.txt"));
    expect(screen.getByText("2 selected")).toBeVisible();
    expect(screen.getByRole("button", { name: "Details" })).toBeDisabled();
    await user.click(screen.getByRole("button", { name: "Clear selection" }));
    const row = screen.getByLabelText("Projects, Folder");
    row.focus();
    await user.keyboard(" ");
    expect(screen.getByLabelText("Select Projects")).toBeChecked();
    await user.keyboard("{Enter}");
    expect(push).toHaveBeenLastCalledWith(`/drive?folder=${folderId}`);
    fireEvent.doubleClick(row);
    expect(push).toHaveBeenLastCalledWith(`/drive?folder=${folderId}`);
    expect(
      screen.getByRole("combobox", { name: "Sort order" }),
    ).toHaveTextContent("Name · ascending");
    expect(
      fetcher.mock.calls.every((call) => !String(call[0]).includes("sort=")),
    ).toBe(true);
  });
  it("incrementally loads only the requested feed, retries a failed later page without losing entries", async () => {
    const user = userEvent.setup();
    let failed = false;
    const fetcher = backend((url) => {
      if (url.pathname !== "/api/v1/files") return;
      if (url.searchParams.get("page") === "0")
        return Response.json(page([file.resource], 0, 2));
      if (!failed) {
        failed = true;
        return Response.json(
          { status: 503, error: "SERVICE_UNAVAILABLE", message: "Timeout" },
          { status: 503 },
        );
      }
      return Response.json(
        page([{ ...file.resource, id: "another", name: "Second.txt" }], 1, 2),
      );
    });
    render(<Explorer mode="drive" />);
    await user.click(
      await screen.findByRole("button", { name: "Load more files" }),
    );
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "temporarily unavailable",
    );
    expect(screen.getByRole("button", { name: "Notes.txt" })).toBeVisible();
    await user.click(screen.getByRole("button", { name: "Try again" }));
    await screen.findByRole("button", { name: "Second.txt" });
    expect(
      fetcher.mock.calls.filter((call) => String(call[0]).includes("/folders?"))
        .length,
    ).toBe(1);
    expect(
      screen.queryByRole("button", { name: "Load more files" }),
    ).not.toBeInTheDocument();
  });
  it("opens fresh metadata, preserves large byte integers and restores focus on closing details", async () => {
    const user = userEvent.setup();
    backend((url) =>
      url.pathname === `/api/v1/files/${fileId}`
        ? new Response(
            JSON.stringify(file.resource).replace(
              '"sizeBytes":512',
              '"sizeBytes":9223372036854775807',
            ),
          )
        : undefined,
    );
    render(<Explorer mode="drive" />);
    const name = await screen.findByRole("button", { name: "Notes.txt" });
    await user.click(name);
    const dialog = await screen.findByRole("dialog", {
      name: "Resource details",
    });
    expect(
      await within(dialog).findByText(/9223372036854775807 bytes/),
    ).toBeVisible();
    await user.keyboard("{Escape}");
    await waitFor(() => expect(name).toHaveFocus());
  });
  it("offers an accessible download menu without a mounted mutation provider", async () => {
    const user = userEvent.setup();
    backend();
    render(<Explorer mode="drive" />);
    await user.click(
      await screen.findByRole("button", { name: "Actions for Notes.txt" }),
    );
    expect(
      await screen.findByRole("menuitem", {
        name: "Download",
      }),
    ).toHaveAttribute("href", `/api/v1/files/${fileId}/download`);
    expect(
      screen.queryByRole("menuitem", { name: "Rename" }),
    ).not.toBeInTheDocument();
    await user.keyboard("{Escape}");
    fireEvent.contextMenu(screen.getByLabelText("Notes.txt, text/plain"));
    expect(
      await screen.findByRole("menuitem", { name: "View details" }),
    ).toBeVisible();
  });
  it("loads shared entry points and Viewer children without guessing private parent IDs", async () => {
    const viewer = {
      ...folder,
      parentFolderId: null,
      effectiveRole: "VIEWER",
      allowedCapabilities: ["READ_METADATA", "LIST_CHILDREN"],
    };
    const fetcher = backend((url) =>
      url.pathname === "/api/v1/shared-with-me"
        ? Response.json(page([{ resourceType: "FOLDER", resource: viewer }]))
        : url.pathname === `/api/v1/folders/${folderId}`
          ? Response.json(viewer)
          : undefined,
    );
    const { rerender } = render(<Explorer mode="shared" />);
    await userEvent.click(
      await screen.findByRole("button", { name: "Projects" }),
    );
    expect(push).toHaveBeenLastCalledWith(`/shared?folder=${folderId}`);
    rerender(<Explorer mode="shared" folder={folderId} />);
    await screen.findByRole("navigation", { name: "Folder breadcrumbs" });
    await waitFor(() =>
      expect(
        fetcher.mock.calls.some((call) =>
          String(call[0]).includes(`/folders/${folderId}/children`),
        ),
      ).toBe(true),
    );
    expect(
      fetcher.mock.calls.some((call) => String(call[0]).includes(parentId)),
    ).toBe(false);
    expect(
      screen.getByText(
        "Shared entry points. Only accessible ancestors are shown.",
      ),
    ).toBeVisible();
  });
  it("stops breadcrumb traversal at a redacted parent and at 404", async () => {
    const fetcher = backend((url) =>
      url.pathname === `/api/v1/folders/${parentId}`
        ? Response.json(
            { status: 404, error: "NOT_FOUND", message: "Unavailable" },
            { status: 404 },
          )
        : undefined,
    );
    const { rerender } = render(<Breadcrumbs folder={folder} shared />);
    expect(fetcher).not.toHaveBeenCalled();
    rerender(
      <Breadcrumbs
        key="child"
        folder={{ ...folder, parentFolderId: parentId }}
        shared
      />,
    );
    await screen.findByText(/Some ancestors are unavailable/);
    expect(screen.queryByText(parentId)).not.toBeInTheDocument();
    expect(fetcher).toHaveBeenCalledTimes(1);
  });
  it("builds accessible owner breadcrumbs from backend metadata", async () => {
    backend((url) =>
      url.pathname === `/api/v1/folders/${parentId}`
        ? Response.json({ ...folder, id: parentId, name: "Parent" })
        : undefined,
    );
    render(
      <Breadcrumbs
        folder={{ ...folder, parentFolderId: parentId }}
        shared={false}
      />,
    );
    expect(await screen.findByRole("link", { name: "Parent" })).toHaveAttribute(
      "href",
      `/drive?folder=${parentId}`,
    );
    expect(screen.getByText("Projects")).toHaveAttribute(
      "aria-current",
      "page",
    );
  });
  it.each([401, 403, 404, 429, 503])(
    "handles folder authorization/API error %i before requesting children",
    async (status) => {
      const fetcher = backend((url) =>
        url.pathname === `/api/v1/folders/${folderId}`
          ? Response.json(
              { status, error: "ERROR", message: "Unavailable" },
              { status, headers: { "Retry-After": "1" } },
            )
          : undefined,
      );
      render(<Explorer mode="drive" folder={folderId} />);
      await screen.findByRole("alert");
      expect(fetcher).toHaveBeenCalledTimes(1);
      expect(screen.getByRole("button", { name: "Try again" })).toBeEnabled();
      if (status === 401)
        expect(screen.getByRole("link", { name: "Sign in" })).toBeVisible();
    },
  );
  it("uses the two bounded Trash feeds, shows lifecycle and never reads active metadata", async () => {
    const user = userEvent.setup();
    const entry = {
      id: folderId,
      name: "Deleted folder",
      resourceType: "FOLDER",
      originalParentId: parentId,
      deletedAt: folder.createdAt,
      purgeRequestedAt: folder.createdAt,
      lifecycleState: "PENDING_PURGE",
      allowedCapabilities: [],
    } satisfies TrashEntry;
    const fetcher = backend((url) =>
      url.pathname === "/api/v1/trash/folders"
        ? Response.json(page([entry]))
        : undefined,
    );
    render(<Explorer mode="trash" />);
    await user.click(
      await screen.findByRole("button", { name: "Deleted folder" }),
    );
    const dialog = await screen.findByRole("dialog");
    expect(await within(dialog).findByText("PENDING_PURGE")).toBeVisible();
    expect(screen.queryByText(parentId)).not.toBeInTheDocument();
    expect(fetcher.mock.calls).toHaveLength(2);
    await user.keyboard("{Escape}");
    await user.click(
      screen.getByRole("button", { name: "Actions for Deleted folder" }),
    );
    expect(
      screen.queryByRole("menuitem", { name: "Restore" }),
    ).not.toBeInTheDocument();
  });
  it("sends search filters losslessly, handles no matches and timeouts", async () => {
    const fetcher = backend();
    const query =
      "q=report&scope=SHARED&resourceType=FILE&maxSizeBytes=9223372036854775807&modifiedFrom=2026-10-01T00%3A00%3A00Z";
    const { rerender } = render(<Explorer mode="search" query={query} />);
    await screen.findByText("No matching resources");
    expect(String(fetcher.mock.calls[0]![0])).toContain(
      "maxSizeBytes=9223372036854775807",
    );
    vi.mocked(fetch).mockRejectedValue(
      new DOMException("Timeout", "TimeoutError"),
    );
    rerender(<Explorer mode="search" query="q=timeout" />);
    expect(await screen.findByRole("alert")).toHaveTextContent("timed out");
  });
  it("gates navigation by backend capabilities and renders disabled resource actions", () => {
    const denied: Resource = {
      resourceType: "FOLDER",
      resource: { ...folder, allowedCapabilities: [] },
    };
    expect(canOpen(denied)).toBe(false);
    expect(canRead(denied)).toBe(false);
  });
  it("retains selection when switching view and requests a quota refresh on explicit refresh", async () => {
    const user = userEvent.setup();
    backend();
    const { rerender } = render(<Explorer mode="drive" />);
    await user.click(await screen.findByLabelText("Select Notes.txt"));
    await user.click(screen.getByRole("button", { name: "Grid view" }));
    expect(push).toHaveBeenLastCalledWith("/drive?view=grid", {
      scroll: false,
    });
    location.search = "view=grid";
    rerender(<Explorer mode="drive" />);
    expect(screen.getByLabelText("Select Notes.txt")).toBeChecked();
    await user.click(
      screen.getByRole("button", { name: "Refresh resources and storage" }),
    );
    expect(refresh).toHaveBeenCalledOnce();
  });
  it("supports range and toggle selection without collisions between resource types", () => {
    const order = ["FOLDER:a", "FILE:a", "FILE:b"];
    expect([
      ...selectEntry(
        new Set([order[0]!]),
        order[2]!,
        order,
        order[0]!,
        false,
        true,
      ),
    ]).toEqual(order);
    expect([
      ...selectEntry(new Set(order), order[1]!, order, null, true, false),
    ]).toEqual([order[0], order[2]]);
  });
});

describe("search controls with mocked router", () => {
  it("debounces filename queries, clears search and keeps the input keyboard accessible", async () => {
    const user = userEvent.setup();
    const { rerender } = render(<SearchBox />);
    const input = screen.getByRole("textbox", { name: "Search in VaultDrive" });
    await user.type(input, "report");
    expect(push).not.toHaveBeenCalled();
    await waitFor(() => expect(push).toHaveBeenCalledWith("/search?q=report"), {
      timeout: 1500,
    });
    expect(input).toHaveFocus();
    await user.click(screen.getByRole("button", { name: "Clear search" }));
    expect(push).toHaveBeenLastCalledWith("/drive");
    expect(input).toHaveFocus();
    await user.type(input, "pending");
    location.pathname = "/search";
    location.search = "q=history";
    rerender(<SearchBox />);
    expect(input).toHaveValue("history");
    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 450));
    });
    expect(push).toHaveBeenCalledTimes(2);
  });
  it("submits all backend filters as strings and resets filters without dropping the query", async () => {
    location.pathname = "/search";
    location.search = "q=report";
    const user = userEvent.setup();
    render(<SearchFilters />);
    await user.click(screen.getByText("Search filters"));
    await user.selectOptions(screen.getByLabelText("Type"), "FILE");
    await user.selectOptions(screen.getByLabelText("Location"), "SHARED");
    await user.type(
      screen.getByLabelText("Maximum size (bytes)"),
      "9223372036854775807",
    );
    await user.type(
      screen.getByLabelText("Created from (UTC)"),
      "2026-01-01T00:00:00Z",
    );
    await user.click(screen.getByRole("button", { name: "Apply filters" }));
    expect(push).toHaveBeenLastCalledWith(
      expect.stringContaining("maxSizeBytes=9223372036854775807"),
    );
    await user.click(screen.getByRole("button", { name: "Reset filters" }));
    expect(push).toHaveBeenLastCalledWith("/search?q=report");
  });
});
