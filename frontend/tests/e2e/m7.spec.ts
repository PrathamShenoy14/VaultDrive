import { expect, test, type Page } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";
import { createHash, randomUUID } from "node:crypto";
import { readFile } from "node:fs/promises";

const admission = {
  Origin: process.env.E2E_APP_ORIGIN ?? "https://localhost:3000",
  "X-VaultDrive-Client": "1",
};
test.use({ actionTimeout: 15000 });
async function signIn(page: Page, name = "M7 Browser Review") {
  const email = `m7-${randomUUID()}@example.test`;
  const password = `M7-${randomUUID()}!`;
  await page.goto("/register");
  await page.getByLabel("Your name").fill(name);
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
  await expect(
    page.getByRole("button", { name: "Upload files", exact: true }),
  ).toBeEnabled();
}
async function scan(page: Page, dialog = false) {
  await page.waitForLoadState("networkidle");
  await page.mouse.move(0, 0);
  await page.emulateMedia({ reducedMotion: "reduce" });
  const axe = new AxeBuilder({ page });
  if (dialog) axe.include('[role="dialog"]');
  expect((await axe.analyze()).violations).toEqual([]);
  const overflow = await page.evaluate(() =>
    [...document.querySelectorAll<HTMLElement>("body *")]
      .filter(
        (el) =>
          el.getBoundingClientRect().right > innerWidth + 1 &&
          el.getBoundingClientRect().width > 0,
      )
      .map((el) => ({
        tag: el.tagName,
        class: el.className,
        right: el.getBoundingClientRect().right,
      }))
      .slice(-12),
  );
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
    JSON.stringify(overflow),
  ).toBe(true);
}
async function contained(page: Page, selector: string) {
  const box = await page.locator(selector).boundingBox();
  expect(box).not.toBeNull();
  expect(box!.y).toBeGreaterThanOrEqual(0);
  expect(box!.y + box!.height).toBeLessThanOrEqual(page.viewportSize()!.height);
}

test("real HTTPS narrow search and enlarged-text account dialog remain usable", async ({
  page,
}, info) => {
  test.setTimeout(120000);
  await signIn(page, "M".repeat(100));
  await page.setViewportSize({ width: 320, height: 568 });
  await page.screenshot({
    path: info.outputPath("m7-narrow-search.png"),
    fullPage: true,
  });
  const textWidth = await page
    .getByRole("textbox", { name: "Search in VaultDrive" })
    .evaluate((el) => {
      const style = getComputedStyle(el);
      return (
        el.clientWidth -
        parseFloat(style.paddingLeft) -
        parseFloat(style.paddingRight)
      );
    });
  expect.soft(textWidth).toBeGreaterThan(80);
  await scan(page);
  await page.setViewportSize({ width: 320, height: 360 });
  await page.addStyleTag({ content: "html { font-size: 200%; }" });
  await page.getByRole("button", { name: /Account menu for/ }).click();
  await page.getByRole("menuitem", { name: "Your account" }).click();
  const dialog = page.getByRole("dialog", {
    name: "Your account",
    exact: true,
  });
  await expect(dialog).toBeVisible();
  await expect(
    dialog.getByRole("heading", { name: "Your account" }),
  ).toBeInViewport();
  const titleRight = await dialog
    .getByRole("heading", { name: "Your account" })
    .evaluate((el) => {
      const text = document.createRange();
      text.selectNodeContents(el);
      return text.getBoundingClientRect().right;
    });
  const closeButton = await dialog
    .getByRole("button", { name: "Close", exact: true })
    .boundingBox();
  expect(titleRight).toBeLessThanOrEqual(closeButton!.x);
  await page.screenshot({
    path: info.outputPath("m7-account-enlarged.png"),
    fullPage: true,
  });
  await contained(page, '[role="dialog"]');
  await scan(page, true);
  for (const key of ["Tab", "Tab", "Shift+Tab", "Shift+Tab"]) {
    await page.keyboard.press(key);
    expect(
      await dialog.evaluate((el) => el.contains(document.activeElement)),
    ).toBe(true);
  }
  await page.keyboard.press("Escape");
  await expect(dialog).toHaveCount(0);
  await expect(
    page.getByRole("button", { name: /Account menu for/ }),
  ).toBeFocused();
  const navigation = page.getByRole("button", { name: "Open navigation" });
  await navigation.click();
  const drawer = page.getByRole("dialog", { name: "Navigation", exact: true });
  await expect(drawer).toBeVisible();
  await contained(page, '[role="dialog"]');
  await drawer.getByRole("link", { name: "Trash", exact: true }).focus();
  await expect(
    drawer.getByRole("link", { name: "Trash", exact: true }),
  ).toBeInViewport();
  await scan(page, true);
  await page.keyboard.press("Escape");
  await expect(navigation).toBeFocused();
});

test("real HTTPS zero-byte browser upload and native Garage-backed download preserve integrity", async ({
  page,
  context,
}, info) => {
  test.setTimeout(120000);
  await signIn(page);
  await page.getByLabel("Choose files to upload").setInputFiles({
    name: "M7 empty.txt",
    mimeType: "text/plain",
    buffer: Buffer.alloc(0),
  });
  const panel = page.getByRole("region", { name: "Transfers", exact: true });
  await expect(panel.getByText("completed", { exact: true })).toBeVisible();
  await expect(panel.getByText(/Published READY/)).toBeVisible();
  await panel.getByRole("button", { name: "Dismiss", exact: true }).click();
  await page.getByLabel("Select M7 empty.txt", { exact: true }).check();
  const downloadEvent = page.waitForEvent("download");
  await page.getByRole("link", { name: "Download", exact: true }).click();
  const download = await downloadEvent;
  const path = info.outputPath("m7-empty.txt");
  await download.saveAs(path);
  expect(await download.failure()).toBeNull();
  expect(download.suggestedFilename()).toBe("M7 empty.txt");
  expect(
    createHash("sha256")
      .update(await readFile(path))
      .digest("hex"),
  ).toBe(createHash("sha256").digest("hex"));
  const cookies = await context.cookies();
  expect(
    cookies.find((c) => c.name === "__Host-vaultdrive-session"),
  ).toMatchObject({
    httpOnly: true,
    secure: true,
    sameSite: "Strict",
    path: "/",
  });
  expect(await page.evaluate(() => document.cookie)).not.toContain(
    "vaultdrive",
  );
  expect(await page.evaluate(() => Object.keys(localStorage))).toEqual([]);
  expect(
    (
      await context.request.post("/api/v1/folders", {
        headers: { ...admission, Origin: "http://localhost:3000" },
        data: { name: "Rejected" },
      })
    ).status(),
  ).toBe(403);
  for (const route of [
    "/drive?view=grid",
    "/shared",
    "/trash",
    "/search?q=M7",
    "/notifications",
    "/activity",
    "/settings",
  ]) {
    await page.goto(route);
    await scan(page);
  }
  await page
    .getByRole("button", { name: "Toggle light and dark theme" })
    .click();
  await scan(page);
  await page.screenshot({
    path: info.outputPath("m7-settings-dark.png"),
    fullPage: true,
  });
});

test("mocked unknown transfers fit short screens and stop on session pause without replay", async ({
  page,
}, info) => {
  test.setTimeout(120000);
  await signIn(page);
  let admissions = 0;
  await page.route("**/api/v1/files/empty", async (route) => {
    admissions++;
    await route.fulfill({
      status: 202,
      json: {
        id: randomUUID(),
        fileId: randomUUID(),
        state: "UNKNOWN",
        storageVerified: false,
      },
    });
  });
  await page.route("**/api/v1/empty-file-creations/**", async (route) => {
    await route.fulfill({
      status: 503,
      json: {
        status: 503,
        error: "UNAVAILABLE",
        message: "Storage unavailable",
      },
    });
  });
  await page.getByLabel("Choose files to upload").setInputFiles(
    [1, 2, 3].map((i) => ({
      name: `Uncertain ${i}.txt`,
      mimeType: "text/plain",
      buffer: Buffer.alloc(0),
    })),
  );
  const panel = page.getByRole("region", { name: "Transfers", exact: true });
  await expect(panel.getByText("unknown", { exact: true })).toHaveCount(3);
  await page.setViewportSize({ width: 320, height: 320 });
  await page.screenshot({
    path: info.outputPath("m7-transfers-short.png"),
    fullPage: true,
  });
  await contained(page, 'section[aria-label="Transfers"]');
  await scan(page);
  await panel.getByRole("button", { name: "Minimize", exact: true }).click();
  await page.evaluate(() =>
    window.dispatchEvent(new Event("vaultdrive:session-paused")),
  );
  await expect(panel).toBeHidden();
  await page.evaluate(() =>
    window.dispatchEvent(new Event("vaultdrive:session-resumed")),
  );
  await expect(panel).toBeVisible();
  expect(admissions).toBe(3);
});

test("mocked explorer network, availability, conflict, rate and access failures retain accurate recovery", async ({
  page,
}, info) => {
  test.setTimeout(120000);
  await signIn(page);
  const id = randomUUID();
  const fixture = {
    id,
    name: "Failure fixture.txt",
    folderId: null,
    contentType: "text/plain",
    sizeBytes: 0,
    createdAt: "2026-10-24T00:00:00Z",
    updatedAt: "2026-10-24T00:00:00Z",
    effectiveRole: "OWNER",
    allowedCapabilities: ["READ_METADATA", "DOWNLOAD", "RENAME", "TRASH"],
  };
  const second = { ...fixture, id: randomUUID(), name: "Second fixture.txt" };
  let status: number | "offline" = 200;
  let reads = 0;
  await page.route("**/api/v1/files?**", async (route) => {
    reads++;
    if (status === "offline") {
      await route.abort("internetdisconnected");
      return;
    }
    await route.fulfill({
      status,
      json:
        status === 200
          ? {
              content: [fixture, second],
              page: 0,
              size: 50,
              totalElements: 2,
              totalPages: 1,
            }
          : { status, error: "REJECTED", message: "Unavailable" },
      ...(status === 429 ? { headers: { "Retry-After": "1" } } : {}),
    });
  });
  await page.reload();
  const resource = page.getByRole("button", {
    name: "Failure fixture.txt",
    exact: true,
  });
  await expect(resource).toBeVisible();
  await page.keyboard.press("Tab");
  await expect(
    page.getByRole("link", { name: "Skip to content" }),
  ).toBeFocused();
  await page.keyboard.press("Enter");
  await expect(page.getByRole("main")).toBeFocused();
  const rows = page.locator("[data-resource-row]");
  await rows.first().focus();
  await page.keyboard.press("Space");
  await expect(
    page.getByLabel("Select Failure fixture.txt", { exact: true }),
  ).toBeChecked();
  await page.keyboard.press("ArrowDown");
  await expect(rows.nth(1)).toBeFocused();
  await page.keyboard.press("ArrowUp");
  await expect(rows.first()).toBeFocused();
  await page.keyboard.press("Shift+ArrowDown");
  await expect(page.getByText("2 selected", { exact: true })).toBeVisible();
  await page.keyboard.press("Escape");
  await expect(
    page.getByLabel("Select Failure fixture.txt", { exact: true }),
  ).not.toBeChecked();
  await page.keyboard.press("End");
  await expect(rows.nth(1)).toBeFocused();
  await page.keyboard.press("Home");
  await expect(rows.first()).toBeFocused();
  await page.keyboard.press("Control+a");
  await expect(page.getByText("2 selected", { exact: true })).toBeVisible();
  await page.keyboard.press("Escape");
  await page.keyboard.press("Shift+F10");
  await expect(
    page.getByRole("menuitem", { name: "View details", exact: true }),
  ).toBeVisible();
  await page.keyboard.press("Escape");
  for (const failure of ["offline", 503, 409, 429, 403, 404] as const) {
    status = failure;
    await page
      .getByRole("button", { name: "Refresh resources and storage" })
      .click();
    const alert = page.getByRole("main").getByRole("alert");
    await expect(alert).toBeVisible();
    if (failure === 403 || failure === 404)
      await expect(resource).toHaveCount(0);
    else {
      await expect(resource).toBeVisible();
      await expect(
        page.getByText(/Previously loaded items are shown/),
      ).toBeVisible();
    }
    if (failure === 429) {
      const previous = reads;
      // Observe beyond the advertised delay: a failure never schedules a retry.
      await page.waitForTimeout(2200);
      expect(reads).toBe(previous);
      await page.screenshot({
        path: info.outputPath("m7-rate-limit.png"),
        fullPage: true,
      });
      await scan(page);
    }
    status = 200;
    await page.getByRole("button", { name: "Try again", exact: true }).click();
    await expect(alert).toHaveCount(0);
    await expect(resource).toBeVisible();
  }
});
