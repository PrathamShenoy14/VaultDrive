import { expect, test, type Page } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";
import { randomUUID } from "node:crypto";
const origin = process.env.E2E_APP_ORIGIN ?? "https://localhost:3000";
test.use({ actionTimeout: 15000 });
const admission = { Origin: origin, "X-VaultDrive-Client": "1" };
async function signIn(page: Page) {
  const email = `frontend-m6-${randomUUID()}@example.test`,
    password = `M6-${randomUUID()}!`;
  await page.goto("/register");
  await page.getByLabel("Your name").fill("M6 Browser Check");
  await page.getByLabel("Email address").fill(email);
  await page.getByLabel("Password", { exact: true }).fill(password);
  await page
    .getByRole("button", { name: "Create account", exact: true })
    .click();
  await page.getByRole("button", { name: "Continue to sign in" }).click();
  await page.getByLabel("Email address").fill(email);
  await page.getByLabel("Password", { exact: true }).fill(password);
  await page.getByRole("button", { name: "Sign in", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: "My Drive", exact: true }),
  ).toBeVisible();
  return email;
}
async function accessible(page: Page, dialog = false) {
  // Filter navigation streams metadata independently of an unchanged row count.
  // Scan only after the RSC transition and its title have settled.
  await page.waitForLoadState("networkidle", { timeout: 15000 });
  await expect(page).toHaveTitle(/VaultDrive/);
  await page.emulateMedia({ reducedMotion: "reduce" });
  await page.mouse.move(0, 0);
  const scan = new AxeBuilder({ page });
  if (dialog) scan.include('[role="dialog"]');
  expect((await scan.analyze()).violations).toEqual([]);
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true);
}
test("real HTTPS M6 account, owner histories, empty disabled-worker inbox, admission and multi-tab logout", async ({
  page,
  context,
}, info) => {
  test.setTimeout(180000);
  await page.emulateMedia({ colorScheme: "light" });
  const email = await signIn(page);
  let folderId = "";
  for (let i = 0; i < 21; i++) {
    const response = await context.request.post("/api/v1/folders", {
      headers: admission,
      data: { name: `M6 folder ${i.toString().padStart(2, "0")}` },
    });
    expect(response.status()).toBe(201);
    if (i == 0) folderId = (await response.json()).folderId;
  }
  await page.goto("/settings");
  await expect(page.getByText(email, { exact: true })).toBeVisible();
  await expect(
    page.getByRole("region", { name: "Quota details" }).getByRole("meter"),
  ).toHaveAttribute("aria-valuenow", "0");
  await page.getByLabel("Display name").fill("M6 Updated Name");
  await page.getByRole("button", { name: "Save display name" }).click();
  await expect(page.getByText("Display name saved.")).toBeVisible();
  await expect(
    page.getByRole("button", { name: /Account menu for M6 Updated Name/ }),
  ).toBeVisible();
  expect(
    (await context.request.get("/api/v1/users/me").then((r) => r.json()))
      .displayName,
  ).toBe("M6 Updated Name");
  expect(
    (
      await context.request.patch("/api/v1/users/me", {
        headers: {
          Origin: "http://localhost:3000",
          "X-VaultDrive-Client": "1",
        },
        data: { displayName: "rejected" },
      })
    ).status(),
  ).toBe(403);
  expect(
    (
      await context.request.patch(
        `/api/v1/notifications/${randomUUID()}/read`,
        { headers: { Origin: origin } },
      )
    ).status(),
  ).toBe(403);
  await accessible(page);
  await page.screenshot({
    path: info.outputPath("m6-live-account-light.png"),
    fullPage: true,
  });
  const recipientEmail = `m6-recipient-${randomUUID()}@example.test`;
  expect(
    (
      await context.request.post("/api/v1/auth/register", {
        headers: admission,
        data: {
          email: recipientEmail,
          password: `M6-${randomUUID()}!`,
          displayName: "M6 recipient",
        },
      })
    ).status(),
  ).toBe(201);
  expect(
    (
      await context.request.post(`/api/v1/folders/${folderId}/grants`, {
        headers: admission,
        data: { email: recipientEmail, role: "VIEWER" },
      })
    ).status(),
  ).toBe(201);
  await page.goto("/activity");
  await expect(
    page.getByRole("heading", { name: /Folder created · M6 folder/ }),
  ).toHaveCount(20);
  await page.getByRole("link", { name: "Next page" }).click();
  await expect(
    page.getByRole("heading", { name: /Folder created · M6 folder/ }),
  ).toHaveCount(1);
  await page.reload();
  await expect(
    page.getByRole("heading", { name: /Folder created · M6 folder/ }),
  ).toHaveCount(1);
  await page.getByRole("link", { name: "Previous page" }).click();
  await expect(
    page.getByRole("heading", { name: /Folder created · M6 folder/ }),
  ).toHaveCount(20);
  await page
    .getByRole("combobox", { name: "Action", exact: true })
    .selectOption("FOLDER_CREATED");
  await expect(page).toHaveURL(/eventType=FOLDER_CREATED/);
  await expect(
    page.getByRole("heading", { name: /Folder created · M6 folder/ }),
  ).toHaveCount(20);
  await accessible(page);
  await page
    .getByRole("combobox", { name: "History", exact: true })
    .selectOption("permissions");
  await expect(page.getByText("Access granted · M6 folder 00")).toBeVisible();
  await expect(
    page.getByText(`Recipient: ${recipientEmail} · New role: VIEWER`),
  ).toBeVisible();
  await page.goto("/notifications");
  await expect(page.getByText("No notifications here")).toBeVisible();
  const bell = page.getByRole("button", { name: "Notifications, 0 unread" });
  await bell.focus();
  await page.keyboard.press("Enter");
  await expect(page.getByRole("dialog")).toBeVisible();
  await accessible(page, true);
  await page.keyboard.press("Escape");
  await expect(bell).toBeFocused();
  await page
    .getByRole("button", { name: "Toggle light and dark theme" })
    .click();
  await accessible(page);
  await page.screenshot({
    path: info.outputPath("m6-live-notifications-dark.png"),
    fullPage: true,
  });
  const other = await context.newPage();
  await other.goto("/settings");
  await expect(other.getByLabel("Display name")).toHaveValue("M6 Updated Name");
  // A new login shares the browser cookie but must discard the other tab's
  // personalized Router Cache before it displays the replacement account.
  await signIn(other);
  await expect(
    page.getByRole("button", { name: /Account menu for M6 Browser Check/ }),
  ).toBeVisible();
  await page
    .getByRole("button", { name: /Account menu for M6 Browser Check/ })
    .click();
  await page.getByRole("menuitem", { name: "Sign out" }).click();
  await expect(page).toHaveURL(/\/login/);
  await expect(other).toHaveURL(/\/login\?reason=session/);
  expect((await context.request.get("/api/v1/activity")).status()).toBe(401);
  await other.close();
});
test("mocked M6 notification paging, read confirmation, quota snapshots, redaction and stale navigation", async ({
  page,
}, info) => {
  test.setTimeout(120000);
  await page.emulateMedia({ colorScheme: "light" });
  await signIn(page);
  const id = randomUUID(),
    hidden = randomUUID(),
    now = new Date().toISOString();
  let read = false;
  const item = {
    id,
    sourceEventId: randomUUID(),
    eventType: "GRANT_CREATED",
    createdAt: now,
    readAt: null,
    actionable: true,
    resourceType: "FOLDER",
    resourceId: id,
    resourceName: "Mock shared project",
    newRole: "EDITOR",
    quota: null,
  };
  await page.route("**/api/v1/**", async (route) => {
    const url = new URL(route.request().url());
    const path = url.pathname;
    if (path === "/api/v1/notifications/unread-count")
      return route.fulfill({ json: { unreadCount: read ? 1 : 2 } });
    if (path === `/api/v1/notifications/${id}/read`) {
      read = true;
      return route.fulfill({ status: 204 });
    }
    if (path === `/api/v1/folders/${id}`)
      return route.fulfill({
        status: 404,
        json: { status: 404, error: "NOT_FOUND", message: "Unavailable" },
      });
    if (path === "/api/v1/notifications") {
      const index = Number(url.searchParams.get("page") ?? 0);
      return route.fulfill({
        json: {
          content:
            index === 0 && url.searchParams.get("size") !== "10"
              ? [
                  { ...item, readAt: read ? now : null },
                  {
                    ...item,
                    id: hidden,
                    actionable: false,
                    resourceName: "REDACT_THIS",
                    resourceId: hidden,
                  },
                ]
              : [
                  {
                    ...item,
                    id: hidden,
                    eventType: "STORAGE_QUOTA_THRESHOLD_CROSSED",
                    actionable: false,
                    quota: {
                      threshold: 90,
                      logicalUsageBytes: 90,
                      limitBytes: 100,
                      occurredAt: now,
                    },
                  },
                ],
          page: index,
          size: 20,
          totalPages: 2,
          totalElements: 21,
        },
      });
    }
    if (path === "/api/v1/activity")
      return route.fulfill({
        json: {
          content: [
            {
              id,
              actorId: hidden,
              actorDisplayName: "Historical actor",
              ownerId: hidden,
              resourceType: "FOLDER",
              resourceId: id,
              resourceName: "Historical project",
              eventType: "MOVED",
              beforeParentId: "REDACT_PARENT",
              afterParentId: null,
              actorAttribution: "CALLER",
              occurredAt: now,
            },
          ],
          page: 0,
          size: 20,
          totalPages: 1,
          totalElements: 1,
        },
      });
    return route.continue();
  });
  await page.goto("/notifications");
  await expect(page.getByText("Mock shared project · Editor")).toBeVisible();
  await expect(page.getByText("REDACT_THIS")).toHaveCount(0);
  await page
    .getByRole("button", { name: "Mark as read", exact: true })
    .first()
    .click();
  await expect(
    page.getByRole("button", { name: "Notifications, 1 unread" }),
  ).toBeVisible();
  await page.getByRole("button", { name: "Open resource" }).click();
  await expect(
    page
      .getByRole("alert")
      .filter({ hasText: "unavailable or your access has changed" }),
  ).toContainText("unavailable or your access has changed");
  await expect(page.getByText("Mock shared project · Editor")).toHaveCount(0);
  await accessible(page);
  await page.screenshot({
    path: info.outputPath("m6-mocked-redaction-light.png"),
    fullPage: true,
  });
  await page.getByRole("link", { name: "Next page" }).click();
  await expect(page.getByText(/Event-time snapshot/)).toBeVisible();
  await page.getByLabel("Read state").selectOption("true");
  await expect(page).toHaveURL(/unread=true/);
  await page.goto("/activity");
  await expect(page.getByText("Moved · Historical project")).toBeVisible();
  await expect(page.getByText("REDACT_PARENT")).toHaveCount(0);
  await page
    .getByRole("button", { name: "Toggle light and dark theme" })
    .click();
  await accessible(page);
  await page.screenshot({
    path: info.outputPath("m6-mocked-activity-dark.png"),
    fullPage: true,
  });
  await page.getByRole("button", { name: "Notifications, 1 unread" }).click();
  await page
    .getByRole("dialog")
    .getByRole("link", { name: "View storage settings" })
    .click();
  await expect(page).toHaveURL(/\/settings$/);
  await expect(page.getByRole("dialog")).toHaveCount(0);
});
test("mocked M6 expired session clears personalized history", async ({
  page,
}) => {
  await signIn(page);
  await page.route("**/api/v1/notifications?*", (route) =>
    route.fulfill({
      status: 401,
      json: { status: 401, error: "UNAUTHORIZED", message: "Session ended" },
    }),
  );
  await page.goto("/notifications");
  await expect(page).toHaveURL(/\/login\?reason=session/);
  await expect(
    page.getByRole("heading", { name: "Notifications", exact: true }),
  ).toHaveCount(0);
});
