import { expect, test, type BrowserContext, type Page } from "@playwright/test";
import { randomUUID } from "node:crypto";
import AxeBuilder from "@axe-core/playwright";

const origin = process.env.E2E_APP_ORIGIN ?? "https://localhost:3000";
const admission = { Origin: origin, "X-VaultDrive-Client": "1" };
async function signIn(page: Page) {
  const email = `frontend-m5-${randomUUID()}@example.test`,
    password = `M5-${randomUUID()}!`;
  await page.goto("/register");
  await page.getByLabel("Your name").fill("M5 Browser Check");
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
async function createFolder(
  context: BrowserContext,
  name: string,
  parentFolderId: string | null = null,
) {
  const response = await context.request.post("/api/v1/folders", {
    headers: admission,
    data: { name, parentFolderId },
  });
  expect(response.status()).toBe(201);
  return (await response.json()).folderId as string;
}
async function emptyFile(
  context: BrowserContext,
  name: string,
  folderId: string | null = null,
) {
  const response = await context.request.post("/api/v1/files/empty", {
    headers: { ...admission, "Idempotency-Key": randomUUID() },
    data: { name, folderId, contentType: "text/plain" },
  });
  expect(response.status()).toBe(200);
  return (await response.json()).fileId as string;
}
async function share(page: Page, name: string) {
  await page
    .getByRole("button", { name: `Actions for ${name}`, exact: true })
    .click();
  await page.getByRole("menuitem", { name: "Share", exact: true }).click();
  await expect(page.getByLabel("Registered recipient email")).toBeVisible();
}
async function grant(page: Page, email: string, role: "VIEWER" | "EDITOR") {
  await page.getByLabel("Registered recipient email").fill(email);
  await page.getByLabel("New recipient role").selectOption(role);
  await page.getByRole("button", { name: "Grant access", exact: true }).click();
  await expect(
    page.getByRole("region", { name: "Direct access", exact: true }),
  ).toContainText(email);
  await expect(
    page.getByText("Direct access granted.", { exact: true }),
  ).toBeVisible();
}
async function close(page: Page) {
  await page.keyboard.press("Escape");
  await expect(page.getByRole("dialog")).toHaveCount(0);
}
async function confirm(page: Page) {
  await page
    .getByRole("button", { name: "Confirm change", exact: true })
    .click();
}
async function refresh(page: Page) {
  await page
    .getByRole("button", { name: "Refresh resources and storage" })
    .click();
}
async function accessible(page: Page) {
  await page.emulateMedia({ reducedMotion: "reduce" });
  await page.mouse.move(0, 0);
  const result = await new AxeBuilder({ page })
    .include('[role="dialog"]')
    .analyze();
  expect(result.violations).toEqual([]);
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true);
}

test("real HTTPS sharing: nested inheritance, mixed roles, private-parent redaction and ancestor revocation", async ({
  page,
  context,
  browser,
}, info) => {
  test.setTimeout(180000);
  await page.emulateMedia({ colorScheme: "light" });
  await signIn(page);
  const recipientContext = await browser.newContext({
    baseURL: origin,
    viewport: page.viewportSize(),
  });
  try {
    const recipient = await recipientContext.newPage();
    const email = await signIn(recipient);
    const privateId = await createFolder(context, "Private parent");
    const sharedId = await createFolder(context, "Shared projects", privateId);
    const childId = await createFolder(context, "Nested", sharedId);
    const fileId = await emptyFile(context, "Nested notes.txt", childId);
    await page.goto(`/drive?folder=${privateId}`);
    await share(page, "Shared projects");
    await grant(page, email, "EDITOR");
    await accessible(page);
    await page.screenshot({
      path: info.outputPath("m5-live-grants-light.png"),
    });
    await close(page);
    await page.goto(`/drive?folder=${childId}`);
    await share(page, "Nested notes.txt");
    await grant(page, email, "VIEWER");
    await expect(
      page.getByRole("region", { name: "Direct access", exact: true }),
    ).toContainText("Direct: Viewer · Effective here: Editor");
    const inherited = page.getByRole("region", {
      name: "Inherited access",
      exact: true,
    });
    await expect(inherited).toContainText("Editor · Effective here: Editor");
    await expect(inherited.getByRole("button")).toHaveCount(0);
    await close(page);
    await recipient.goto("/shared");
    await expect(
      recipient.getByRole("button", { name: "Shared projects", exact: true }),
    ).toBeVisible();
    const sharedMeta = await (
      await recipientContext.request.get(`/api/v1/folders/${sharedId}`)
    ).json();
    expect(sharedMeta.parentFolderId).toBeNull();
    expect(
      (
        await recipientContext.request.get(`/api/v1/folders/${privateId}`)
      ).status(),
    ).toBe(404);
    await recipient
      .getByRole("button", { name: "Shared projects", exact: true })
      .click();
    await recipient
      .getByRole("button", { name: "Nested", exact: true })
      .click();
    await recipient
      .getByRole("button", { name: "Actions for Nested notes.txt" })
      .click();
    await expect(
      recipient.getByRole("menuitem", { name: "Rename", exact: true }),
    ).toBeVisible();
    await expect(
      recipient.getByRole("menuitem", { name: "Move to Trash", exact: true }),
    ).toBeVisible();
    await expect(
      recipient.getByRole("menuitem", { name: "Share", exact: true }),
    ).toHaveCount(0);
    await recipient.keyboard.press("Escape");
    // Revoke the ancestor source. The independently direct file grant survives.
    await page.goto(`/drive?folder=${privateId}`);
    await share(page, "Shared projects");
    await page
      .getByRole("button", { name: `Revoke access for ${email}`, exact: true })
      .click();
    await confirm(page);
    await expect(page.getByText("No direct grants.")).toBeVisible();
    await close(page);
    await refresh(recipient);
    await expect(
      recipient.getByRole("alert").filter({ hasText: "unavailable" }).first(),
    ).toBeVisible();
    await expect(
      recipient.getByRole("button", { name: "Create folder", exact: true }),
    ).toHaveCount(0);
    await recipient.goto("/shared");
    await expect(
      recipient.getByRole("button", { name: "Shared projects", exact: true }),
    ).toHaveCount(0);
    await expect(
      recipient.getByRole("button", { name: "Nested notes.txt", exact: true }),
    ).toBeVisible();
    const fileMeta = await (
      await recipientContext.request.get(`/api/v1/files/${fileId}`)
    ).json();
    expect(fileMeta.folderId).toBeNull();
    expect(fileMeta.effectiveRole).toBe("VIEWER");
    await recipient
      .getByRole("button", { name: "Actions for Nested notes.txt" })
      .click();
    await expect(
      recipient.getByRole("menuitem", { name: "Rename", exact: true }),
    ).toHaveCount(0);
    await recipient
      .getByRole("menuitem", { name: "Access details", exact: true })
      .click();
    await expect(
      recipient.getByRole("heading", { name: "Your effective access: Viewer" }),
    ).toBeVisible();
    await expect(
      recipient.getByLabel("Registered recipient email"),
    ).toHaveCount(0);
    await close(recipient);
    // Direct Editor under a private immediate parent permits rename, never Trash.
    await page.goto(`/drive?folder=${childId}`);
    await share(page, "Nested notes.txt");
    await page.getByLabel(`Direct role for ${email}`).selectOption("EDITOR");
    await page
      .getByRole("button", { name: `Save role for ${email}`, exact: true })
      .click();
    await confirm(page);
    await expect(page.getByText("Direct role updated.")).toBeVisible();
    await refresh(recipient);
    await recipient
      .getByRole("button", { name: "Actions for Nested notes.txt" })
      .click();
    await expect(
      recipient.getByRole("menuitem", { name: "Rename", exact: true }),
    ).toBeVisible();
    for (const name of ["Move to Trash", "Move", "Copy", "Share"])
      await expect(
        recipient.getByRole("menuitem", { name, exact: true }),
      ).toHaveCount(0);
    await recipient
      .getByRole("menuitem", { name: "Access details", exact: true })
      .click();
    await expect(
      recipient.getByRole("heading", { name: "Your effective access: Editor" }),
    ).toBeVisible();
    await page
      .getByRole("button", { name: `Revoke access for ${email}`, exact: true })
      .click();
    await confirm(page);
    await expect(page.getByText("No direct grants.")).toBeVisible();
    await recipient.getByRole("button", { name: "Refresh access" }).click();
    await expect(
      recipient.getByRole("dialog").getByRole("alert"),
    ).toContainText("unavailable");
    await close(recipient);
    await recipient.goto("/search?q=Nested");
    await expect(recipient.getByText("No matching resources")).toBeVisible();
  } finally {
    await recipientContext.close();
  }
});

test("real HTTPS Viewer links: one-time secret, anonymous header access, streamed download, expiry and revoke", async ({
  page,
  context,
  browser,
}, info) => {
  test.setTimeout(150000);
  await page.emulateMedia({ colorScheme: "dark" });
  await signIn(page);
  const fileId = await emptyFile(context, "Public notes.txt");
  await page.goto("/drive?view=grid");
  await share(page, "Public notes.txt");
  await page
    .getByRole("button", { name: "Create Viewer link", exact: true })
    .click();
  const copiedUrl = await page.getByLabel("New share URL").inputValue();
  const url = new URL(copiedUrl),
    secret = url.hash.slice(1),
    linkId = url.pathname.split("/").at(-1)!;
  expect(secret.length).toBe(43);
  expect(url.search).toBe("");
  const listed = await (
    await context.request.get(`/api/v1/files/${fileId}/share-links`)
  ).text();
  expect(listed.includes(secret)).toBe(false);
  await page.getByRole("button", { name: "Hide secret" }).click();
  await expect(
    page.getByRole("button", { name: "Create Viewer link", exact: true }),
  ).toBeEnabled();
  await accessible(page);
  await page.screenshot({ path: info.outputPath("m5-live-links-dark.png") });
  const anonymous = await browser.newContext({
    baseURL: origin,
    viewport: page.viewportSize(),
    acceptDownloads: true,
  });
  try {
    const reader = await anonymous.newPage();
    const requested: string[] = [];
    let formStatus = 0,
      formOrigin = "",
      blockedByPolicy = false;
    anonymous.on("request", (request) => {
      if (
        request.method() === "POST" &&
        new URL(request.url()).pathname.endsWith("/download")
      )
        formOrigin = request.headers()["origin"] ?? "missing";
    });
    anonymous.on("response", (response) => {
      if (new URL(response.url()).pathname.endsWith("/download"))
        formStatus = response.status();
    });
    reader.on("console", (message) => {
      if (message.text().includes("form-action")) blockedByPolicy = true;
    });
    reader.on("request", (request) => requested.push(request.url()));
    await reader.goto(copiedUrl);
    await expect(
      reader.getByRole("heading", { name: "Public notes.txt", exact: true }),
    ).toBeVisible();
    expect(new URL(reader.url()).hash).toBe("");
    expect(requested.some((value) => value.includes(secret))).toBe(false);
    expect(
      (await anonymous.cookies()).some(
        (cookie) => cookie.name === "__Host-vaultdrive-session",
      ),
    ).toBe(false);
    const downloadPromise = reader
      .waitForEvent("download", { timeout: 15000 })
      .catch(() => {
        throw new Error(
          `Download not started: HTTP ${formStatus}, Origin ${formOrigin}, policy block ${blockedByPolicy}`,
        );
      });
    await reader.getByRole("button", { name: "Download shared file" }).click();
    const download = await downloadPromise;
    expect(download.suggestedFilename()).toBe("Public notes.txt");
    expect(await download.failure()).toBeNull();
    expect(
      (await anonymous.cookies()).some(
        (cookie) => cookie.name === "__Host-vaultdrive-share-download",
      ),
    ).toBe(false);
    expect(requested.some((value) => value.includes(secret))).toBe(false);
    await reader.screenshot({
      path: info.outputPath("m5-live-public-mobile.png"),
    });
    const result = await new AxeBuilder({ page: reader }).analyze();
    expect(result.violations).toEqual([]);
    await page
      .getByRole("button", { name: `Revoke link ${linkId}`, exact: true })
      .click();
    await confirm(page);
    await expect(page.getByText("Viewer link revoked.")).toBeVisible();
    await reader.getByRole("button", { name: "Check link access" }).click();
    await expect(reader.getByRole("main").getByRole("alert")).toContainText(
      "expired, revoked",
    );
    await expect(
      reader.getByRole("button", { name: "Download shared file" }),
    ).toHaveCount(0);
    const denied = await anonymous.request.get(
      `/api/v1/public/share-links/${linkId}`,
      { headers: { "X-VaultDrive-Share-Token": secret } },
    );
    expect(denied.status()).toBe(404);
    expect((await denied.text()).includes(secret)).toBe(false);
    // Real backend clock expiry, no browser clock or infrastructure changes.
    const expiring = await context.request.post(
      `/api/v1/files/${fileId}/share-links`,
      {
        headers: admission,
        data: { expiresAt: new Date(Date.now() + 2500).toISOString() },
      },
    );
    expect(expiring.status()).toBe(201);
    const short = await expiring.json();
    await expect
      .poll(
        async () =>
          (
            await anonymous.request.get(
              `/api/v1/public/share-links/${short.link.id}`,
              { headers: { "X-VaultDrive-Share-Token": short.secret } },
            )
          ).status(),
        { timeout: 10000 },
      )
      .toBe(404);
    const expiredReader = await anonymous.newPage();
    await expiredReader.goto(`/share/${short.link.id}#${short.secret}`);
    await expect(
      expiredReader.getByRole("alert").filter({ hasText: "unavailable" }),
    ).toBeVisible();
    expect(new URL(expiredReader.url()).hash).toBe("");
    await expiredReader.close();
    await reader.goto(`/share/${linkId}#malformed`);
    await expect(reader.getByRole("main").getByRole("alert")).toContainText(
      "unavailable",
    );
  } finally {
    await anonymous.close();
  }
  await close(page);
  await share(page, "Public notes.txt");
  await expect(page.getByLabel("New share URL")).toHaveCount(0);
  expect(
    (await context.cookies()).find(
      (cookie) => cookie.name === "__Host-vaultdrive-session",
    ),
  ).toMatchObject({ secure: true, httpOnly: true, sameSite: "Strict" });
});

test("mocked sharing failures: missing recipient, duplicate and stale version", async ({
  page,
}, info) => {
  test.setTimeout(90000);
  await page.emulateMedia({ colorScheme: "dark" });
  await signIn(page);
  const id = randomUUID(),
    grantId = randomUUID(),
    now = new Date().toISOString();
  const folder = {
    id,
    name: "Mock sharing",
    parentFolderId: null,
    effectiveRole: "OWNER",
    allowedCapabilities: ["READ_METADATA", "LIST_CHILDREN", "MANAGE_GRANTS"],
    createdAt: now,
    updatedAt: now,
  };
  let failure = 400,
    submits = 0;
  await page.route("**/api/v1/**", async (route) => {
    const path = new URL(route.request().url()).pathname,
      method = route.request().method();
    if (path.endsWith("/grants") && method === "POST") {
      submits++;
      return route.fulfill({
        status: failure,
        json: { status: failure, error: "ERROR", message: "controlled test" },
      });
    }
    if (path.endsWith(`/grants/${grantId}`) && method === "PATCH")
      return route.fulfill({
        status: 412,
        json: { status: 412, error: "PRECONDITION_FAILED", message: "stale" },
      });
    if (path.endsWith("/grants"))
      return route.fulfill({
        json: {
          direct: [
            {
              id: grantId,
              folderId: id,
              fileId: null,
              granteeUserId: randomUUID(),
              email: "reader@example.test",
              role: "VIEWER",
              version: 0,
              createdAt: now,
              updatedAt: now,
            },
          ],
          inherited: [],
        },
      });
    if (path === `/api/v1/folders/${id}`)
      return route.fulfill({ json: folder });
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
  await refresh(page);
  await share(page, "Mock sharing");
  await page
    .getByLabel("Registered recipient email")
    .fill("missing@example.test");
  await page.getByRole("button", { name: "Grant access", exact: true }).click();
  await expect(page.getByRole("alert")).toContainText("recipient must already");
  failure = 409;
  await page.getByRole("button", { name: "Grant access", exact: true }).click();
  await expect(page.getByRole("alert")).toContainText("already exists");
  await expect(
    page.getByRole("button", { name: "Grant access", exact: true }),
  ).toBeDisabled();
  expect(submits).toBe(2);
  await page.getByRole("button", { name: "Refresh access" }).click();
  await page
    .getByLabel("Direct role for reader@example.test")
    .selectOption("EDITOR");
  await page
    .getByRole("button", { name: "Save role for reader@example.test" })
    .click();
  await confirm(page);
  await expect(page.getByRole("alert")).toContainText("changed since");
  await accessible(page);
  await page.screenshot({ path: info.outputPath("m5-mocked-stale.png") });
  await page.keyboard.press("Escape");
  await expect(
    page.getByRole("button", { name: "Actions for Mock sharing" }),
  ).toBeFocused();
});
