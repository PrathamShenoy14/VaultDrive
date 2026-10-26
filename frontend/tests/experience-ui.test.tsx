import { beforeEach, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import {
  NotificationsPage,
  NotificationBell,
} from "@/features/experience/notifications";
import { ActivityPage, ActivityRow } from "@/features/experience/activity";
import { AccountPage } from "@/features/experience/account";
import { experienceRequest } from "@/features/experience/api";
import type {
  Notification,
  ResourceActivity,
  PermissionActivity,
} from "@/features/experience/contracts";
const { push, refresh, search } = vi.hoisted(() => ({
  push: vi.fn(),
  refresh: vi.fn(),
  search: { value: "" },
}));
vi.mock("next/navigation", () => ({
  useRouter: () => ({ push, refresh }),
  useSearchParams: () => new URLSearchParams(search.value),
}));
vi.mock("@/features/experience/api", () => ({ experienceRequest: vi.fn() }));
const id = "11111111-1111-4111-8111-111111111111";
const at = "2026-10-26T12:00:00Z";
const notification: Notification = {
  id,
  sourceEventId: id,
  eventType: "GRANT_CREATED",
  createdAt: at,
  readAt: null,
  actionable: true,
  resourceType: "FOLDER",
  resourceId: id,
  resourceName: "Shared report",
  newRole: "VIEWER",
  quota: null,
};
const activity = {
  id,
  actorId: "private-actor",
  actorDisplayName: "Alice",
  ownerId: "private-owner",
  resourceType: "FILE",
  resourceId: id,
  resourceName: "Notes.txt",
  eventType: "RENAMED",
  beforeName: "Before.txt",
  afterName: "Notes.txt",
  beforeParentId: "private-before-parent",
  afterParentId: "private-after-parent",
  actorAttribution: "CALLER",
  beforeTrashed: false,
  afterTrashed: false,
  beforeStatus: "READY",
  afterStatus: "READY",
  occurredAt: at,
} satisfies ResourceActivity;
function page<T>(content: T[], index = 0, total = 2) {
  return {
    content,
    page: index,
    size: 20,
    totalPages: total,
    totalElements: 21,
  };
}
beforeEach(() => {
  vi.mocked(experienceRequest).mockReset();
  search.value = "";
  push.mockReset();
  refresh.mockReset();
});
it("lists notifications with fixed paging and URL filters", async () => {
  const user = userEvent.setup();
  vi.mocked(experienceRequest).mockResolvedValue(page([notification]));
  render(<NotificationsPage />);
  expect(await screen.findByText("Shared report · Viewer")).toBeVisible();
  expect(screen.getByRole("link", { name: "Next page" })).toHaveAttribute(
    "href",
    "/notifications?page=1&size=20",
  );
  expect(experienceRequest).toHaveBeenCalledWith(
    "notifications?page=0&size=20",
    expect.any(AbortSignal),
  );
  await user.selectOptions(screen.getByLabelText("Read state"), "true");
  expect(push).toHaveBeenCalledWith("/notifications?size=20&unread=true");
});
it("suppresses all obsolete resource metadata even if a malformed snapshot includes it", async () => {
  vi.mocked(experienceRequest).mockResolvedValue(
    page([
      { ...notification, actionable: false, resourceName: "must never render" },
    ]),
  );
  render(<NotificationsPage />);
  expect(await screen.findByText(/Historical update/)).toBeVisible();
  expect(screen.queryByText(/must never render/)).not.toBeInTheDocument();
  expect(
    screen.queryByRole("button", { name: "Open resource" }),
  ).not.toBeInTheDocument();
});
it("renders untrusted text without interpreting HTML", async () => {
  vi.mocked(experienceRequest).mockResolvedValue(
    page([{ ...notification, resourceName: '<img src=x onerror="alert(1)">' }]),
  );
  const { container } = render(<NotificationsPage />);
  expect(await screen.findByText(/<img/)).toBeVisible();
  expect(container.querySelector("img")).toBeNull();
});
it("checks current folder capabilities before navigation", async () => {
  const user = userEvent.setup();
  vi.mocked(experienceRequest).mockImplementation(async (path) =>
    path.startsWith("notifications?")
      ? page([notification])
      : ({
          id,
          effectiveRole: "VIEWER",
          allowedCapabilities: ["READ_METADATA", "LIST_CHILDREN"],
        } as never),
  );
  render(<NotificationsPage />);
  await user.click(
    await screen.findByRole("button", { name: "Open resource" }),
  );
  expect(push).toHaveBeenCalledWith(`/shared?folder=${id}`);
});
it("removes stale notification metadata after access is revoked", async () => {
  const user = userEvent.setup();
  vi.mocked(experienceRequest).mockImplementation(async (path) => {
    if (path.startsWith("notifications?")) return page([notification]) as never;
    throw Object.assign(new Error("hidden"), { status: 404 });
  });
  render(<NotificationsPage />);
  await user.click(
    await screen.findByRole("button", { name: "Open resource" }),
  );
  expect(await screen.findByRole("alert")).toHaveTextContent(
    "unavailable or your access has changed",
  );
  expect(screen.getByRole("alert")).toHaveFocus();
  expect(screen.queryByText("Shared report · Viewer")).not.toBeInTheDocument();
  expect(push).not.toHaveBeenCalled();
});
it("updates unread count and the list only after confirmed mark-as-read", async () => {
  const user = userEvent.setup();
  let read = false;
  vi.mocked(experienceRequest).mockImplementation(
    async (path, _signal, _body, method) => {
      if (method === "PATCH") {
        read = true;
        return undefined as never;
      }
      if (path.endsWith("unread-count"))
        return { unreadCount: read ? 0 : 1 } as never;
      return page([{ ...notification, readAt: read ? at : null }]) as never;
    },
  );
  render(
    <>
      <NotificationBell />
      <NotificationsPage />
    </>,
  );
  expect(
    await screen.findByRole("button", { name: "Notifications, 1 unread" }),
  ).toBeVisible();
  await user.click(await screen.findByRole("button", { name: "Mark as read" }));
  expect(await screen.findByText("Read", { selector: "span" })).toBeVisible();
  expect(
    await screen.findByRole("button", { name: "Notifications, 0 unread" }),
  ).toBeVisible();
  expect(experienceRequest).toHaveBeenCalledWith(
    `notifications/${id}/read`,
    expect.any(AbortSignal),
    undefined,
    "PATCH",
  );
});
it("keeps unread state on an unconfirmed mutation", async () => {
  const user = userEvent.setup();
  vi.mocked(experienceRequest).mockImplementation(
    async (_path, _signal, _body, method) => {
      if (method === "PATCH") throw new Error("lost response");
      return page([notification]) as never;
    },
  );
  render(<NotificationsPage />);
  await user.click(await screen.findByRole("button", { name: "Mark as read" }));
  expect(await screen.findByRole("alert")).toHaveTextContent(
    "Refresh to check read state",
  );
  expect(screen.getByText("Unread", { selector: "span" })).toBeVisible();
});
it("provides keyboard panel focus and Escape return", async () => {
  const user = userEvent.setup();
  vi.mocked(experienceRequest).mockImplementation(async (path) =>
    path.endsWith("unread-count")
      ? ({ unreadCount: 0 } as never)
      : (page([]) as never),
  );
  render(<NotificationBell />);
  const bell = await screen.findByRole("button", {
    name: "Notifications, 0 unread",
  });
  bell.focus();
  await user.keyboard("{Enter}");
  expect(await screen.findByRole("dialog")).toBeVisible();
  await user.keyboard("{Escape}");
  await waitFor(() =>
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument(),
  );
  expect(bell).toHaveFocus();
});
it("closes the bell panel when current folder access permits navigation", async () => {
  const user = userEvent.setup();
  vi.mocked(experienceRequest).mockImplementation(async (path) => {
    if (path.endsWith("unread-count")) return { unreadCount: 1 } as never;
    if (path.startsWith("notifications?")) return page([notification]) as never;
    return {
      id,
      effectiveRole: "VIEWER",
      allowedCapabilities: ["READ_METADATA", "LIST_CHILDREN"],
    } as never;
  });
  render(<NotificationBell />);
  await user.click(
    await screen.findByRole("button", { name: "Notifications, 1 unread" }),
  );
  await user.click(
    await screen.findByRole("button", { name: "Open resource" }),
  );
  expect(push).toHaveBeenCalledWith(`/shared?folder=${id}`);
  await waitFor(() =>
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument(),
  );
});
it("shows empty worker-disabled guidance and retries failed notification history", async () => {
  const user = userEvent.setup();
  vi.mocked(experienceRequest)
    .mockRejectedValueOnce(new Error("offline"))
    .mockResolvedValue(page([]));
  render(<NotificationsPage />);
  expect(await screen.findByRole("alert")).toBeVisible();
  await user.click(screen.getByRole("button", { name: "Retry" }));
  expect(
    await screen.findByText(/Local notification workers may be disabled/),
  ).toBeVisible();
});
it("renders quota event snapshots without inferring action authority", async () => {
  vi.mocked(experienceRequest).mockResolvedValue(
    page([
      {
        ...notification,
        actionable: false,
        eventType: "STORAGE_QUOTA_THRESHOLD_CROSSED",
        quota: {
          threshold: 90,
          logicalUsageBytes: 90,
          limitBytes: 100,
          occurredAt: at,
        },
      },
    ]),
  );
  render(<NotificationsPage />);
  expect(
    await screen.findByText(/logical storage reached 90%/),
  ).toHaveTextContent("Event-time snapshot");
  expect(
    screen.getByRole("link", { name: "View storage settings" }),
  ).toHaveAttribute("href", "/settings");
  expect(
    screen.queryByRole("button", { name: "Open resource" }),
  ).not.toBeInTheDocument();
});
it("renders authorized activity without disclosing parent/owner/internal IDs", async () => {
  vi.mocked(experienceRequest).mockResolvedValue(page([activity], 1, 3));
  search.value = "page=1&resourceType=FILE";
  const { container } = render(<ActivityPage />);
  expect(await screen.findByText("Renamed · Notes.txt")).toBeVisible();
  expect(screen.getByText("Before.txt → Notes.txt")).toBeVisible();
  expect(container.textContent).not.toMatch(
    /private-before-parent|private-after-parent|private-owner|private-actor/,
  );
  expect(screen.getByRole("link", { name: "Next page" })).toHaveAttribute(
    "href",
    "/activity?resourceType=FILE&page=2&size=20",
  );
});
it("separates permission history and uses only backend recipient snapshots", async () => {
  search.value = "history=permissions";
  const grant = {
    ...activity,
    eventType: "GRANT_ROLE_CHANGED",
    recipientEmail: "reader@example.test",
    oldRole: "VIEWER",
    newRole: "EDITOR",
    subjectId: "private-subject",
    subjectVersion: 9007199254740993n,
  } as unknown as PermissionActivity;
  vi.mocked(experienceRequest).mockResolvedValue(page([grant]));
  const { container } = render(<ActivityPage />);
  expect(
    await screen.findByText(/Recipient: reader@example.test/),
  ).toHaveTextContent("New role: EDITOR");
  expect(container.textContent).not.toContain("private-subject");
  expect(experienceRequest).toHaveBeenCalledWith(
    "permission-activity?page=0&size=20",
    expect.any(AbortSignal),
  );
});
it("clears owner history after a fresh authorization denial", async () => {
  const user = userEvent.setup();
  vi.mocked(experienceRequest)
    .mockResolvedValueOnce(page([activity]))
    .mockRejectedValue(new Error("Access denied"));
  render(<ActivityPage />);
  expect(await screen.findByText("Renamed · Notes.txt")).toBeVisible();
  await user.click(screen.getByRole("button", { name: "Refresh activity" }));
  expect(await screen.findByRole("alert")).toBeVisible();
  expect(screen.queryByText("Renamed · Notes.txt")).not.toBeInTheDocument();
});
it("handles removed resources and legacy actor attribution", () => {
  render(
    <ActivityRow
      item={{
        ...activity,
        eventType: "PERMANENTLY_DELETED",
        actorAttribution: "DELETION_REQUESTER",
      }}
      permission={false}
    />,
  );
  expect(
    screen.getByText(/Attributed to the deletion requester/),
  ).toBeVisible();
  expect(
    screen.queryByRole("button", { name: "Open resource" }),
  ).not.toBeInTheDocument();
});
it("reads account and exact used/reserved/uncertain quota, saves only displayName", async () => {
  const user = userEvent.setup();
  const profile = {
    userId: id,
    email: "alice@example.test",
    displayName: "Alice",
    createdAt: at,
    updatedAt: at,
  };
  vi.mocked(experienceRequest).mockImplementation(
    async (path, _signal, body) =>
      path.endsWith("/storage")
        ? ({
            limitBytes: 1000,
            usedBytes: 800,
            reservedBytes: 100,
            availableBytes: 100,
            uncertainStorageBytes: 900,
            uncertainStorageAllocations: 2,
            unknownSizeAllocations: 1,
          } as never)
        : ({ ...profile, ...(body ?? {}) } as never),
  );
  render(<AccountPage />);
  const name = await screen.findByLabelText("Display name");
  expect(await screen.findByRole("meter")).toHaveAttribute(
    "aria-valuenow",
    "90",
  );
  expect(screen.getByText("900 B")).toBeVisible();
  await user.clear(name);
  await user.type(name, "  Alice Updated  ");
  await user.click(screen.getByRole("button", { name: "Save display name" }));
  expect(await screen.findByText("Display name saved.")).toBeVisible();
  expect(experienceRequest).toHaveBeenCalledWith(
    "users/me",
    expect.any(AbortSignal),
    { displayName: "Alice Updated" },
    "PATCH",
  );
  expect(refresh).toHaveBeenCalled();
});
it("rejects control characters in a display name without dispatch", async () => {
  vi.mocked(experienceRequest).mockImplementation(async (path) =>
    path.endsWith("/storage")
      ? ({
          limitBytes: 0,
          usedBytes: 0,
          reservedBytes: 0,
          availableBytes: 0,
          uncertainStorageBytes: 0,
          uncertainStorageAllocations: 0,
          unknownSizeAllocations: 0,
        } as never)
      : ({
          userId: id,
          displayName: "Alice",
          email: "alice@example.test",
          createdAt: at,
          updatedAt: at,
        } as never),
  );
  render(<AccountPage />);
  const input = await screen.findByLabelText("Display name");
  fireEvent.change(input, { target: { value: "bad\u0001name" } });
  fireEvent.submit(input.closest("form")!);
  expect(
    await screen.findByText(/Enter a name of 1–100 characters/),
  ).toBeVisible();
  expect(
    vi.mocked(experienceRequest).mock.calls.some((call) => call[3] === "PATCH"),
  ).toBe(false);
});
it("aborts reads on unmount and discards late results", async () => {
  let resolve!: (value: unknown) => void;
  let signal!: AbortSignal;
  vi.mocked(experienceRequest).mockImplementation((_path, scope) => {
    signal = scope;
    return new Promise((done) => {
      resolve = done;
    });
  });
  const { unmount } = render(<NotificationsPage />);
  unmount();
  expect(signal.aborted).toBe(true);
  resolve(page([notification]));
});
