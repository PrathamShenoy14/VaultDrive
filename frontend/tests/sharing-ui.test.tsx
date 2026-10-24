// @vitest-environment-options {"url":"https://localhost:3000"}
import { StrictMode } from "react";
import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, expect, it, vi } from "vitest";
import { Dialog, DialogContent } from "@/components/ui/dialog";
import {
  SharingAction,
  SharingDetails,
  SharingProvider,
} from "@/features/sharing/provider";
import { SharePage } from "@/features/sharing/share-page";
import { Explorer } from "@/features/explorer/explorer";
import type { Resource } from "@/features/explorer/contracts";
import type { Grant, Link } from "@/features/sharing/api";
const { router } = vi.hoisted(() => ({
  router: { push: vi.fn(), refresh: vi.fn() },
}));
vi.mock("next/navigation", () => ({
  useRouter: () => router,
  usePathname: () => "/shared",
  useSearchParams: () => new URLSearchParams(),
}));
const id = "11111111-1111-4111-8111-111111111111",
  parent = "22222222-2222-4222-8222-222222222222",
  grantId = "33333333-3333-4333-8333-333333333333";
const now = "2026-10-24T00:00:00Z",
  secret = "A".repeat(43);
const entry: Extract<Resource, { resourceType: "FILE" }> = {
  resourceType: "FILE",
  resource: {
    id,
    name: "Notes.txt",
    folderId: parent,
    effectiveRole: "OWNER",
    allowedCapabilities: ["READ_METADATA", "DOWNLOAD", "MANAGE_GRANTS"],
    sizeBytes: 0,
    contentType: "text/plain",
    createdAt: now,
    updatedAt: now,
  },
};
const grant: Grant = {
  id: grantId,
  fileId: id,
  folderId: null,
  granteeUserId: parent,
  email: "reader@example.test",
  role: "VIEWER",
  version: 9007199254740993n,
  createdAt: now,
  updatedAt: now,
};
const link: Link = {
  id: grantId,
  createdAt: now,
  expiresAt: "2099-10-25T00:00:00Z",
  revokedAt: null,
  version: 0,
};
let direct: Grant[],
  inherited: Grant[],
  links: Link[],
  metadata: Resource["resource"];
function json(value: unknown, status = 200) {
  return new Response(
    JSON.stringify(value, (_, value) =>
      typeof value === "bigint" ? Number(value) : value,
    ),
    { status, headers: { "Content-Type": "application/json" } },
  );
}
function mockApi(
  mutation?: (path: string, init: RequestInit) => Response | Promise<Response>,
) {
  const fetcher = vi.fn(
    async (url: RequestInfo | URL, init: RequestInit = {}) => {
      const path = String(url);
      if (init.method && init.method !== "GET")
        return mutation?.(path, init) ?? new Response(null, { status: 204 });
      if (path.endsWith("/grants"))
        return new Response(
          JSON.stringify({ direct, inherited }, (_, value) =>
            typeof value === "bigint" ? value.toString() : value,
          ).replace('"9007199254740993"', "9007199254740993"),
        );
      if (path.endsWith("/share-links")) return json(links);
      return json(metadata);
    },
  );
  vi.stubGlobal("fetch", fetcher);
  return fetcher;
}
function details(value: Resource = entry) {
  return render(
    <Dialog open>
      <DialogContent>
        <SharingDetails entry={value} />
      </DialogContent>
    </Dialog>,
  );
}
beforeEach(() => {
  vi.clearAllMocks();
  direct = [grant];
  inherited = [];
  links = [];
  metadata = entry.resource;
  window.history.replaceState(null, "", "/");
  sessionStorage.clear();
  localStorage.clear();
});
it("separates direct and inherited grants and shows strongest role with no descendant revoke controls", async () => {
  inherited = [
    { ...grant, id: parent, fileId: null, folderId: parent, role: "EDITOR" },
  ];
  mockApi();
  details();
  const source = await screen.findByRole("region", {
    name: "Inherited access",
  });
  expect(source).toHaveTextContent("Editor · Effective here: Editor");
  expect(within(source).queryByRole("button")).not.toBeInTheDocument();
  expect(within(source).getByRole("link")).toHaveAttribute(
    "href",
    `/drive?folder=${parent}`,
  );
  expect(
    screen.getByRole("region", { name: "Direct access" }),
  ).toHaveTextContent("Direct: Viewer · Effective here: Editor");
});
it("creates a grant once despite double submission and refreshes sharing and explorer", async () => {
  direct = [];
  let finish!: (value: Response) => void;
  const fetcher = mockApi(
    () =>
      new Promise((resolve) => {
        finish = resolve;
      }),
  );
  details();
  const user = userEvent.setup();
  await user.type(
    await screen.findByLabelText("Registered recipient email"),
    "person@example.test",
  );
  await user.selectOptions(
    screen.getByLabelText("New recipient role"),
    "EDITOR",
  );
  await user.dblClick(screen.getByRole("button", { name: "Grant access" }));
  const writes = () =>
    fetcher.mock.calls.filter(([, init]) => init?.method === "POST");
  expect(writes()).toHaveLength(1);
  expect(JSON.parse(writes()[0]![1]!.body as string)).toEqual({
    email: "person@example.test",
    role: "EDITOR",
  });
  direct = [{ ...grant, email: "person@example.test", role: "EDITOR" }];
  await act(async () => finish(json(direct[0], 201)));
  expect(await screen.findByText("Direct access granted.")).toBeVisible();
  expect(
    await screen.findByText("person@example.test", {
      selector: "p.font-medium",
    }),
  ).toBeVisible();
  expect(router.refresh).toHaveBeenCalled();
});
it("confirms role updates and preserves the exact quoted int64 version", async () => {
  const fetcher = mockApi(() => {
    direct = [{ ...grant, role: "EDITOR", version: 2 }];
    return json({ ...grant, version: 2 });
  });
  details();
  const user = userEvent.setup();
  await user.selectOptions(
    await screen.findByLabelText(`Direct role for ${grant.email}`),
    "EDITOR",
  );
  await user.click(
    screen.getByRole("button", { name: `Save role for ${grant.email}` }),
  );
  expect(
    screen.getByRole("dialog", { name: "Change direct role?" }),
  ).toBeVisible();
  expect(
    fetcher.mock.calls.filter(([, init]) => init?.method === "PATCH"),
  ).toHaveLength(0);
  await user.click(screen.getByRole("button", { name: "Confirm change" }));
  await screen.findByText("Direct role updated.");
  const init = fetcher.mock.calls.find(
    ([, init]) => init?.method === "PATCH",
  )![1]!;
  expect(new Headers(init.headers).get("If-Match")).toBe('"9007199254740993"');
  expect(JSON.parse(init.body as string)).toEqual({ role: "EDITOR" });
});
it("requires confirmation to revoke and retains inherited access", async () => {
  inherited = [
    { ...grant, id: parent, fileId: null, folderId: parent, role: "EDITOR" },
  ];
  const fetcher = mockApi(() => {
    direct = [];
    return new Response(null, { status: 204 });
  });
  details();
  const user = userEvent.setup();
  await user.click(
    await screen.findByRole("button", {
      name: `Revoke access for ${grant.email}`,
    }),
  );
  await user.click(screen.getByRole("button", { name: "Cancel" }));
  expect(
    fetcher.mock.calls.filter(([, init]) => init?.method === "DELETE"),
  ).toHaveLength(0);
  await user.click(
    screen.getByRole("button", { name: `Revoke access for ${grant.email}` }),
  );
  await user.click(screen.getByRole("button", { name: "Confirm change" }));
  await screen.findByText(
    "Direct access revoked. Inherited access may remain.",
  );
  expect(await screen.findByText("No direct grants.")).toBeVisible();
  expect(
    screen.getByRole("region", { name: "Inherited access" }),
  ).toHaveTextContent(grant.email);
});
it.each(["VIEWER", "EDITOR"] as const)(
  "shows only effective %s access without fetching private grants or links",
  async (role) => {
    metadata = {
      ...entry.resource,
      effectiveRole: role,
      folderId: null,
      allowedCapabilities: ["READ_METADATA", "DOWNLOAD"],
    };
    const fetcher = mockApi();
    details();
    await screen.findByText(
      `Your effective access: ${role === "VIEWER" ? "Viewer" : "Editor"}`,
    );
    expect(
      screen.queryByLabelText("Registered recipient email"),
    ).not.toBeInTheDocument();
    expect(screen.queryByText(grant.email)).not.toBeInTheDocument();
    expect(fetcher).toHaveBeenCalledTimes(1);
  },
);
it.each([
  [400, /recipient must already/],
  [403, /Only the owner/],
  [404, /no longer accessible/],
  [409, /already exists/],
  [412, /changed since/],
] as const)(
  "handles grant error %i with controlled feedback",
  async (status, message) => {
    mockApi(() => json({ status, error: "ERROR", message: secret }, status));
    details();
    const user = userEvent.setup();
    await user.type(
      await screen.findByLabelText("Registered recipient email"),
      "reader@example.test",
    );
    await user.click(screen.getByRole("button", { name: "Grant access" }));
    expect(await screen.findByRole("alert")).toHaveTextContent(message);
    expect(screen.queryByText(secret)).not.toBeInTheDocument();
    if ([409, 412].includes(status))
      expect(
        screen.getByRole("button", { name: "Grant access" }),
      ).toBeDisabled();
    if ([403, 404].includes(status))
      expect(
        screen.queryByLabelText("Registered recipient email"),
      ).not.toBeInTheDocument();
  },
);
it("blocks lost non-idempotent creation until an explicit successful access refresh", async () => {
  const fetcher = mockApi(() => Promise.reject(new TypeError(secret)));
  details();
  const user = userEvent.setup();
  await user.type(
    await screen.findByLabelText("Registered recipient email"),
    "reader@example.test",
  );
  await user.click(screen.getByRole("button", { name: "Grant access" }));
  expect(await screen.findByRole("alert")).toHaveTextContent(
    "outcome is uncertain",
  );
  expect(screen.getByRole("button", { name: "Grant access" })).toBeDisabled();
  expect(
    fetcher.mock.calls.filter(([, init]) => init?.method === "POST"),
  ).toHaveLength(1);
  await user.click(screen.getByRole("button", { name: "Refresh access" }));
  await waitFor(() =>
    expect(screen.getByRole("button", { name: "Grant access" })).toBeEnabled(),
  );
});
it("never reconstructs listed secrets and confirms link revocation", async () => {
  links = [link];
  const fetcher = mockApi(() => {
    links = [{ ...link, revokedAt: now }];
    return new Response(null, { status: 204 });
  });
  details();
  const user = userEvent.setup();
  await screen.findByRole("region", { name: "Viewer links" });
  expect(
    screen.queryByRole("button", { name: "Copy link" }),
  ).not.toBeInTheDocument();
  expect(screen.queryByLabelText("New share URL")).not.toBeInTheDocument();
  await user.click(
    screen.getByRole("button", { name: `Revoke link ${link.id}` }),
  );
  await user.click(screen.getByRole("button", { name: "Confirm change" }));
  await screen.findByText("Viewer link revoked.");
  expect(screen.getByText(/Revoked · Viewer/)).toBeVisible();
  expect(
    fetcher.mock.calls.filter(([, init]) => init?.method === "DELETE"),
  ).toHaveLength(1);
});
it("creates a Viewer link once, supports copy, and forgets the secret on close", async () => {
  const fetcher = mockApi(() => {
    links = [link];
    return json({ link, secret }, 201);
  });
  render(
    <SharingProvider>
      <SharingAction entry={entry} />
    </SharingProvider>,
  );
  const user = userEvent.setup();
  await user.click(screen.getByRole("button", { name: "Share" }));
  await user.click(
    await screen.findByRole("button", { name: "Create Viewer link" }),
  );
  const input = await screen.findByLabelText("New share URL");
  expect(input).toHaveValue(
    `https://localhost:3000/share/${link.id}#${secret}`,
  );
  await user.click(screen.getByRole("button", { name: "Copy link" }));
  expect(await screen.findByText(/Link copied/)).toBeVisible();
  expect(
    fetcher.mock.calls.filter(([, init]) => init?.method === "POST"),
  ).toHaveLength(1);
  expect(
    JSON.parse(
      fetcher.mock.calls.find(([, init]) => init?.method === "POST")![1]!
        .body as string,
    ),
  ).toEqual({ expiresAt: null });
  expect(sessionStorage.length).toBe(0);
  await user.keyboard("{Escape}");
  await user.click(screen.getByRole("button", { name: "Share" }));
  await screen.findByRole("region", { name: "Viewer links" });
  expect(screen.queryByLabelText("New share URL")).not.toBeInTheDocument();
  expect(
    screen.queryByRole("button", { name: "Copy link" }),
  ).not.toBeInTheDocument();
});
it("clears stale recipient details when the resource becomes inaccessible on focus", async () => {
  const fetcher = mockApi();
  details();
  await screen.findByLabelText("Registered recipient email");
  fetcher.mockImplementation(async () =>
    json({ status: 404, error: "NOT_FOUND", message: "unavailable" }, 404),
  );
  fireEvent(window, new Event("focus"));
  expect(await screen.findByRole("alert")).toHaveTextContent("unavailable");
  expect(screen.queryByText(grant.email)).not.toBeInTheDocument();
  expect(
    screen.queryByLabelText("Registered recipient email"),
  ).not.toBeInTheDocument();
});
it("supports keyboard escape and returns focus to its sharing trigger", async () => {
  mockApi();
  render(
    <SharingProvider>
      <SharingAction entry={entry} />
    </SharingProvider>,
  );
  const user = userEvent.setup();
  await user.tab();
  expect(screen.getByRole("button", { name: "Share" })).toHaveFocus();
  await user.keyboard("{Enter}");
  await screen.findByLabelText("Registered recipient email");
  await user.keyboard("{Escape}");
  await waitFor(() =>
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument(),
  );
  expect(screen.getByRole("button", { name: "Share" })).toHaveFocus();
});
it("refreshes Shared selection capabilities on sharing changes and removes revoked resources", async () => {
  let role = "EDITOR",
    present = true;
  vi.stubGlobal(
    "fetch",
    vi.fn(async () =>
      json({
        content: present
          ? [
              {
                ...entry,
                resource: {
                  ...entry.resource,
                  effectiveRole: role,
                  allowedCapabilities:
                    role === "EDITOR"
                      ? ["READ_METADATA", "DOWNLOAD", "RENAME"]
                      : ["READ_METADATA", "DOWNLOAD"],
                },
              },
            ]
          : [],
        page: 0,
        size: 50,
        totalPages: 1,
        totalElements: present ? 1 : 0,
      }),
    ),
  );
  render(
    <SharingProvider>
      <Explorer mode="shared" />
    </SharingProvider>,
  );
  const user = userEvent.setup();
  await user.click(
    await screen.findByRole("checkbox", { name: "Select Notes.txt" }),
  );
  await waitFor(() =>
    expect(screen.getByRole("toolbar")).toHaveTextContent("1 selected"),
  );
  role = "VIEWER";
  act(() => window.dispatchEvent(new Event("vaultdrive:sharing-changed")));
  await waitFor(() =>
    expect(screen.getByText("Viewer", { exact: true })).toBeVisible(),
  );
  expect(screen.getByRole("toolbar")).toHaveTextContent("1 selected");
  present = false;
  act(() => window.dispatchEvent(new Event("vaultdrive:sharing-changed")));
  await waitFor(() =>
    expect(screen.getByRole("toolbar")).toHaveTextContent("Select items"),
  );
  expect(
    screen.queryByRole("button", { name: "Notes.txt" }),
  ).not.toBeInTheDocument();
});
it("strips fragment secrets before header-only reads, survives StrictMode, and clears revoked metadata", async () => {
  window.history.replaceState(null, "", `/share/${id}#${secret}`);
  const fetcher = vi
    .fn()
    .mockImplementation(async () =>
      json({ name: "Public.txt", contentType: "text/plain", sizeBytes: 0 }),
    );
  vi.stubGlobal("fetch", fetcher);
  render(
    <StrictMode>
      <SharePage id={id} />
    </StrictMode>,
  );
  expect(await screen.findByText("Public.txt")).toBeVisible();
  expect(window.location.hash).toBe("");
  for (const [url, init] of fetcher.mock.calls) {
    expect(url).not.toContain(secret);
    expect(init.credentials).toBe("omit");
    expect(init.headers["X-VaultDrive-Share-Token"]).toBe(secret);
  }
  expect(localStorage.length).toBe(0);
  expect(sessionStorage.length).toBe(0);
  expect(
    screen.getByRole("button", { name: "Download shared file" }),
  ).toBeEnabled();
  fetcher.mockResolvedValue(
    json({ status: 404, error: "NOT_FOUND", message: secret }, 404),
  );
  await userEvent.click(
    screen.getByRole("button", { name: "Check link access" }),
  );
  expect(await screen.findByRole("alert")).toHaveTextContent(
    "expired, revoked",
  );
  expect(screen.queryByText("Public.txt")).not.toBeInTheDocument();
  expect(
    screen.queryByRole("button", { name: "Download shared file" }),
  ).not.toBeInTheDocument();
});
it("never sends a malformed/missing fragment to the API", async () => {
  const fetcher = vi.fn();
  vi.stubGlobal("fetch", fetcher);
  render(<SharePage id="malformed" />);
  expect(await screen.findByRole("alert")).toHaveTextContent("unavailable");
  expect(fetcher).not.toHaveBeenCalled();
});
it("clears the old capability when a new fragment targets the same public page", async () => {
  window.history.replaceState(null, "", `/share/${id}#${secret}`);
  const fetcher = vi
    .fn()
    .mockResolvedValue(
      json({ name: "Public.txt", contentType: "text/plain", sizeBytes: 0 }),
    );
  vi.stubGlobal("fetch", fetcher);
  render(<SharePage id={id} />);
  await screen.findByText("Public.txt");
  act(() => {
    window.history.pushState(null, "", `/share/${id}#malformed`);
    window.dispatchEvent(new HashChangeEvent("hashchange"));
  });
  expect(await screen.findByRole("alert")).toHaveTextContent("unavailable");
  expect(screen.queryByText("Public.txt")).not.toBeInTheDocument();
  expect(fetcher).toHaveBeenCalledTimes(1);
  expect(window.location.hash).toBe("");
});
it("prepares a public download once and does not echo capability input on failure", async () => {
  window.history.replaceState(null, "", `/share/${id}#${secret}`);
  const fetcher = vi
    .fn()
    .mockResolvedValueOnce(
      json({ name: "Public.txt", contentType: "text/plain", sizeBytes: 0 }),
    )
    .mockResolvedValue(
      json({ status: 502, error: "ERROR", message: secret }, 502),
    );
  vi.stubGlobal("fetch", fetcher);
  render(<SharePage id={id} />);
  const user = userEvent.setup();
  await user.click(
    await screen.findByRole("button", { name: "Download shared file" }),
  );
  expect(await screen.findByRole("alert")).toHaveTextContent(
    "No download was automatically retried",
  );
  expect(screen.queryByText(secret)).not.toBeInTheDocument();
  expect(fetcher).toHaveBeenCalledTimes(2);
  const [url, init] = fetcher.mock.calls[1]!;
  expect(url).toBe(`/share/${id}/download`);
  expect(init.method).toBe("POST");
  expect(JSON.parse(init.body)).toEqual({ secret });
  expect(init.headers["X-VaultDrive-Client"]).toBe("1");
});
