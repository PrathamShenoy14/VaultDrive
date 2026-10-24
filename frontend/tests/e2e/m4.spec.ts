import { expect, test, type Page } from "@playwright/test";
import { randomUUID } from "node:crypto";
import AxeBuilder from "@axe-core/playwright";

// Requires an isolated backend and a trusted HTTPS frontend. No TLS bypass,
// browser bearer, storage interception or Garage multipart claim in the live case.
async function signIn(page: Page) {
  const email = `frontend-m4-${randomUUID()}@example.test`,
    password = `M4-${randomUUID()}!`;
  await page.goto("/register");
  await page.getByLabel("Your name").fill("M4 Browser Check");
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
}
async function createFolder(page: Page, name: string) {
  await page
    .getByRole("button", { name: "Create folder", exact: true })
    .click();
  await page.getByLabel("Name", { exact: true }).fill(name);
  await page
    .getByRole("dialog")
    .getByRole("button", { name: "Create folder", exact: true })
    .click();
  await expect(page.getByRole("dialog")).toHaveCount(0);
  await expect(page.getByRole("button", { name, exact: true })).toBeVisible();
}
async function action(page: Page, name: string, label: string) {
  await page
    .getByRole("button", { name: `Actions for ${name}`, exact: true })
    .click();
  await page.getByRole("menuitem", { name: label, exact: true }).click();
  await expect(page.getByRole("dialog")).toBeVisible();
}
async function submit(page: Page, label: string) {
  await page
    .getByRole("dialog")
    .getByRole("button", { name: label, exact: true })
    .click();
  await expect(page.getByRole("dialog")).toHaveCount(0);
}
async function operations(page: Page) {
  const summary = page
    .locator("summary")
    .filter({ hasText: /^Management operations/ });
  if (
    !(await summary.evaluate((element) =>
      element.parentElement!.hasAttribute("open"),
    ))
  )
    await summary.click();
  return summary.locator("..");
}
test("real HTTPS browser and backend: folders, rename, move, Trash/restore, copies and accepted deletion", async ({
  page,
  context,
}, info) => {
  test.setTimeout(180000);
  await page.emulateMedia({ colorScheme: "light" });
  await signIn(page);
  await createFolder(page, "Destination");
  await createFolder(page, "Projects");
  await action(page, "Projects", "Rename");
  await page.getByLabel("Name", { exact: true }).fill("Renamed projects");
  await submit(page, "Rename");
  await expect(
    page.getByRole("button", { name: "Renamed projects", exact: true }),
  ).toBeVisible();
  await action(page, "Renamed projects", "Move");
  await page
    .getByRole("button", { name: "Open Destination", exact: true })
    .click();
  await page
    .getByRole("button", { name: "Use this folder", exact: true })
    .click();
  await submit(page, "Move");
  await expect(
    page.getByRole("button", { name: "Renamed projects", exact: true }),
  ).toHaveCount(0);
  await page.getByRole("button", { name: "Destination", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: "Destination", exact: true }),
  ).toBeVisible();
  await action(page, "Renamed projects", "Move to Trash");
  await submit(page, "Move to Trash");
  await page.goto("/trash");
  await action(page, "Renamed projects", "Restore");
  await submit(page, "Restore");
  await expect(
    page.getByRole("button", { name: "Renamed projects", exact: true }),
  ).toHaveCount(0);
  await page.goto("/drive");
  await page.getByRole("button", { name: "Destination", exact: true }).click();
  await expect(
    page.getByRole("button", { name: "Renamed projects", exact: true }),
  ).toBeVisible();
  await action(page, "Renamed projects", "Copy");
  await page.getByLabel("Name", { exact: true }).fill("Independent tree");
  await page
    .getByRole("button", { name: "Use this folder", exact: true })
    .click();
  await submit(page, "Copy");
  let panel = await operations(page);
  await panel
    .getByRole("button", { name: "Continue copy (bounded step)" })
    .click();
  await expect(
    panel.getByRole("status").filter({ hasText: /^COMPLETED/ }),
  ).toBeVisible();
  // Real BFF fixture creation of a zero-byte Garage object. No multipart bytes.
  const empty = await context.request.post("/api/v1/files/empty", {
    headers: {
      Origin: "https://localhost:3000",
      "X-VaultDrive-Client": "1",
      "Idempotency-Key": randomUUID(),
    },
    data: { name: "Original.txt", contentType: "text/plain" },
  });
  expect(empty.status()).toBe(200);
  const original = await empty.json();
  await page.goto("/drive?view=grid");
  await expect(
    page.getByRole("button", { name: "Independent tree", exact: true }),
  ).toBeVisible();
  await action(page, "Original.txt", "Rename");
  await page.getByLabel("Name", { exact: true }).fill("Renamed.txt");
  await submit(page, "Rename");
  await action(page, "Renamed.txt", "Copy");
  await page.getByLabel("Name", { exact: true }).fill("Independent file.txt");
  await page
    .getByRole("button", { name: "Use this folder", exact: true })
    .click();
  await submit(page, "Copy");
  panel = await operations(page);
  await expect(
    panel.getByRole("status").filter({ hasText: /^READY/ }),
  ).toBeVisible();
  await expect(
    page.getByRole("button", { name: "Independent file.txt", exact: true }),
  ).toBeVisible();
  const files = await (await context.request.get("/api/v1/files")).json();
  expect(
    files.content.find(
      (file: { name: string }) => file.name === "Independent file.txt",
    ).id,
  ).not.toBe(original.fileId);
  await action(page, "Independent tree", "Move to Trash");
  await submit(page, "Move to Trash");
  await page.goto("/trash");
  await action(page, "Independent tree", "Delete permanently");
  await expect(page.getByRole("dialog")).toContainText(
    "does not prove physical storage cleanup",
  );
  const axe = await new AxeBuilder({ page })
    .include('[role="dialog"]')
    .analyze();
  expect(axe.violations).toEqual([]);
  await page.screenshot({
    path: info.outputPath("m4-live-confirmation.png"),
    fullPage: false,
  });
  await submit(page, "Delete permanently");
  panel = await operations(page);
  const deletion = panel
    .locator("li")
    .filter({ hasText: "Delete permanently: Independent tree" });
  await deletion.getByRole("button", { name: "Check status" }).click();
  await expect(deletion.getByRole("status")).toHaveText(
    /ACCEPTED|PROCESSING|LOGICALLY_COMPLETED|BLOCKED|FAILED/,
  );
  await expect(deletion).toContainText("Operation:");
  await page.reload();
  panel = await operations(page);
  await expect(panel).toContainText("Restored evidence");
  expect(
    (await context.cookies()).find(
      (cookie) => cookie.name === "__Host-vaultdrive-session",
    ),
  ).toMatchObject({ httpOnly: true, secure: true, sameSite: "Strict" });
});

test("mocked management failure/progress states with real Chrome keyboard and mobile dialogs", async ({
  page,
}, info) => {
  test.setTimeout(120000);
  await page.emulateMedia({ colorScheme: "dark" });
  await signIn(page);
  await expect(page.locator("html")).toHaveClass(/dark/);
  const id = randomUUID(),
    job = randomUUID(),
    now = new Date().toISOString();
  const folder = {
    id,
    name: "Mock tree",
    parentFolderId: null,
    createdAt: now,
    updatedAt: now,
    effectiveRole: "OWNER",
    allowedCapabilities: [
      "READ_METADATA",
      "LIST_CHILDREN",
      "RENAME",
      "MOVE",
      "COPY",
      "TRASH",
    ],
  };
  let state = "ACTIVE",
    checkpoint = 0,
    execute = 0,
    admission = 0;
  const snapshot = () => ({
    id: job,
    sourceFolderId: id,
    folderId: randomUUID(),
    destinationFolderId: null,
    name: "Mock copy",
    state,
    checkpoint,
    totalMembers: 8,
    completedFiles: checkpoint,
    totalFiles: 7,
    revision: checkpoint,
    createdAt: now,
    disposalOperationId: null,
  });
  await page.route("**/api/v1/**", async (route) => {
    const request = route.request(),
      path = new URL(request.url()).pathname;
    if (path === "/api/v1/folders")
      return route.fulfill({
        json: {
          content: [folder],
          page: 0,
          size: 50,
          totalPages: 1,
          totalElements: 1,
        },
      });
    if (path === `/api/v1/folders/${id}` && request.method() === "PATCH")
      return route.fulfill({
        status: 409,
        json: { status: 409, error: "CONFLICT", message: "Collision" },
      });
    if (path === `/api/v1/folders/${id}/copy`) {
      admission++;
      if (admission === 1) return route.abort("failed");
      return route.fulfill({ status: 202, json: snapshot() });
    }
    if (path.startsWith(`/api/v1/folder-copy-operations/${job}`)) {
      if (path.endsWith("/execute")) {
        execute++;
        checkpoint = 4;
        state = "UNKNOWN";
      }
      if (path.endsWith("/recover")) {
        state = "COMPLETED";
        checkpoint = 8;
      }
      return route.fulfill({ status: 200, json: snapshot() });
    }
    if (path === "/api/v1/files")
      return route.fulfill({
        json: {
          content: [],
          page: 0,
          size: 50,
          totalPages: 0,
          totalElements: 0,
        },
      });
    return route.continue();
  });
  await page
    .getByRole("button", { name: "Refresh resources and storage" })
    .click();
  await action(page, "Mock tree", "Rename");
  await page.getByLabel("Name", { exact: true }).fill("Collision");
  await page
    .getByRole("dialog")
    .getByRole("button", { name: "Rename", exact: true })
    .click();
  await expect(page.getByRole("alert")).toContainText("name collision");
  await page.keyboard.press("Escape");
  await expect(
    page.getByRole("button", { name: "Actions for Mock tree" }),
  ).toBeFocused();
  await action(page, "Mock tree", "Copy");
  await page.getByLabel("Name", { exact: true }).fill("Mock copy");
  await expect(
    page.getByRole("button", { name: "Open Mock tree" }),
  ).toHaveCount(0);
  await page
    .getByRole("button", { name: "Use this folder", exact: true })
    .click();
  await submit(page, "Copy");
  const panel = await operations(page);
  await expect(panel).toContainText("outcome is uncertain");
  expect(admission).toBe(1);
  await panel
    .getByRole("button", { name: "Replay original admission" })
    .click();
  await expect(panel.getByRole("status")).toHaveText("ACTIVE");
  await panel
    .getByRole("button", { name: "Continue copy (bounded step)" })
    .click();
  await expect(panel.getByRole("status")).toHaveText("UNKNOWN");
  expect(execute).toBe(1);
  await expect(
    panel.getByRole("button", { name: "Continue copy (bounded step)" }),
  ).toHaveCount(0);
  await page.screenshot({
    path: info.outputPath("m4-mocked-unknown.png"),
    fullPage: false,
  });
  await panel
    .getByRole("button", { name: "Recover existing evidence" })
    .click();
  await expect(panel.getByRole("status")).toHaveText("COMPLETED");
});
