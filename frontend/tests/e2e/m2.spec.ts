import {
  expect,
  test,
  type APIRequestContext,
  type BrowserContext,
} from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";
import { randomUUID } from "node:crypto";

// Run only against the fresh, disposable backend schema described in README.
// Setup calls existing backend APIs directly; no mutation is exposed by M2's BFF.
const backend = process.env.BACKEND_URL ?? "http://127.0.0.1:8080";
const admission = {
  Origin: process.env.E2E_APP_ORIGIN ?? "https://localhost:3000",
  "X-VaultDrive-Client": "1",
};
async function account(request: APIRequestContext, label: string) {
  const input = {
    email: `frontend-m2-${randomUUID()}@example.test`,
    password: `M2-${randomUUID()}!`,
    displayName: label,
  };
  expect(
    (
      await request.post(`${backend}/api/v1/auth/register`, { data: input })
    ).status(),
  ).toBe(201);
  const login = await request.post(`${backend}/api/v1/auth/login`, {
    data: { email: input.email, password: input.password },
    headers: { "X-VaultDrive-Client": "1" },
  });
  expect(login.status()).toBe(200);
  const { accessToken } = await login.json();
  return { input, headers: { Authorization: `Bearer ${accessToken}` } };
}
async function bootstrap(
  request: APIRequestContext,
  context: BrowserContext,
  input: { email: string; password: string },
) {
  const login = await request.post("/api/v1/auth/login", {
    headers: admission,
    data: input,
  });
  expect(login.status()).toBe(204);
  const cookie = login
    .headersArray()
    .find(
      (header) =>
        header.name.toLowerCase() === "set-cookie" &&
        header.value.startsWith("__Host-vaultdrive-session="),
    )!
    .value.split(";")[0]!
    .split("=");
  await context.addCookies([
    {
      name: cookie[0]!,
      value: cookie[1]!,
      domain: "localhost",
      path: "/",
      secure: true,
      httpOnly: true,
      sameSite: "Strict",
    },
  ]);
}

test("live M2 navigation, pagination, selection, metadata, search, sharing, Trash and responsive access", async ({
  page,
  request,
  context,
}) => {
  test.setTimeout(120000);
  page.setDefaultTimeout(10000);
  const owner = await account(request, "M2 Owner");
  const viewer = await account(request, "M2 Recipient");
  async function folder(name: string, parentFolderId: string | null = null) {
    const response = await request.post(`${backend}/api/v1/folders`, {
      headers: owner.headers,
      data: { name, parentFolderId },
    });
    expect(response.status()).toBe(201);
    return (await response.json()).folderId as string;
  }
  async function empty(name: string, folderId: string | null = null) {
    const response = await request.post(`${backend}/api/v1/files/empty`, {
      headers: { ...owner.headers, "Idempotency-Key": randomUUID() },
      data: { name, folderId, contentType: "text/plain" },
    });
    expect(response.status()).toBe(200);
    const result = await response.json();
    expect(result.state).toBe("READY");
    return result.fileId as string;
  }
  const projects = await folder("A Projects");
  const nested = await folder("Design", projects);
  const privateParent = await folder("Private cabinet");
  const team = await folder("Team room", privateParent);
  await folder("Shared child", team);
  for (let index = 0; index < 51; index++)
    await folder(`Folder ${String(index).padStart(2, "0")}`);
  await empty("Notes.txt", nested);
  const brief = await empty("Brief.txt", privateParent);
  const old = await empty("Old.txt");
  const deleted = await folder("Old folder");
  expect(
    (
      await request.delete(`${backend}/api/v1/files/${old}`, {
        headers: owner.headers,
      })
    ).status(),
  ).toBe(204);
  expect(
    (
      await request.delete(`${backend}/api/v1/folders/${deleted}`, {
        headers: owner.headers,
      })
    ).status(),
  ).toBe(204);
  for (const [path, role] of [
    [`folders/${team}`, "VIEWER"],
    [`files/${brief}`, "EDITOR"],
  ]) {
    expect(
      (
        await request.post(`${backend}/api/v1/${path}/grants`, {
          headers: owner.headers,
          data: { email: viewer.input.email, role },
        })
      ).status(),
    ).toBe(201);
  }
  await bootstrap(request, context, owner.input);
  await page.goto("/drive");
  await expect(
    page.getByRole("button", { name: "A Projects", exact: true }),
  ).toBeVisible();
  await page.getByRole("button", { name: "Load more folders" }).click();
  await expect(
    page.getByRole("button", { name: "Folder 50", exact: true }),
  ).toBeVisible();
  await page.evaluate(() => window.scrollTo(0, 0));
  await page.screenshot({
    path: `test-results/m2-drive-list-${test.info().project.name}.png`,
  });
  await page.getByLabel("Select A Projects").check();
  await page.getByLabel("Select Folder 00").check();
  await expect(page.getByText("2 selected", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "Grid view" }).click();
  await expect(page.getByLabel("Select A Projects")).toBeChecked();
  await page.getByRole("button", { name: "A Projects", exact: true }).click();
  await expect(page).toHaveURL(new RegExp(`folder=${projects}`));
  await page.getByRole("button", { name: "Design", exact: true }).click();
  await expect(page).toHaveURL(new RegExp(`folder=${nested}`));
  const breadcrumbs = page.getByRole("navigation", {
    name: "Folder breadcrumbs",
  });
  await expect(
    breadcrumbs.getByRole("link", { name: "A Projects" }),
  ).toBeVisible();
  await page.getByRole("button", { name: "Notes.txt", exact: true }).click();
  const detail = page.getByRole("dialog", { name: "Resource details" });
  await expect(detail.getByText("0 B (0 bytes)")).toBeVisible();
  await page.keyboard.press("Escape");
  await expect(
    page.getByRole("button", { name: "Notes.txt", exact: true }),
  ).toBeFocused();
  await page.goBack();
  await expect(page).toHaveURL(new RegExp(`folder=${projects}`));
  await page.goForward();
  await expect(page).toHaveURL(new RegExp(`folder=${nested}`));
  await page.reload();
  await expect(
    page.getByRole("button", { name: "Notes.txt", exact: true }),
  ).toBeVisible();
  const search = page.getByRole("textbox", { name: "Search in VaultDrive" });
  await search.fill("Notes");
  await expect(page).toHaveURL(/\/search\?q=Notes/);
  await expect(
    page.getByRole("button", { name: "Notes.txt", exact: true }),
  ).toBeVisible();
  await expect(search).toBeFocused();
  await page.getByText("Search filters", { exact: true }).click();
  await page
    .getByRole("combobox", { name: "Type", exact: true })
    .selectOption("FILE");
  await page
    .getByRole("combobox", { name: "Location", exact: true })
    .selectOption("OWNED");
  await page.getByLabel("MIME type", { exact: true }).fill("text/plain");
  await page.getByLabel("Maximum size (bytes)").fill("9223372036854775807");
  await page.getByRole("button", { name: "Apply filters" }).click();
  await expect(page).toHaveURL(/maxSizeBytes=9223372036854775807/);
  await expect(
    page.getByRole("button", { name: "Notes.txt", exact: true }),
  ).toBeVisible();
  await page.getByRole("button", { name: "Clear search" }).click();
  await expect(page).toHaveURL(/\/drive$/);
  await page.goto("/trash");
  await expect(
    page.getByRole("button", { name: "Old.txt", exact: true }),
  ).toBeVisible();
  await expect(
    page.getByRole("button", { name: "Old folder", exact: true }),
  ).toBeVisible();
  await page.getByRole("button", { name: "Actions for Old.txt" }).click();
  await expect(
    page.getByRole("menuitem", { name: "Restore", exact: true }),
  ).toBeEnabled();
  await page.keyboard.press("Escape");
  await bootstrap(request, context, viewer.input);
  await page.goto("/shared");
  await expect(
    page.getByRole("button", { name: "Team room", exact: true }),
  ).toBeVisible();
  await expect(
    page.getByRole("button", { name: "Brief.txt", exact: true }),
  ).toBeVisible();
  await expect(page.getByText("Viewer", { exact: true })).toBeVisible();
  await expect(page.getByText("Editor", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "Brief.txt", exact: true }).click();
  await expect(detail.getByText("EDITOR", { exact: true })).toBeVisible();
  await expect(detail).not.toContainText("Private cabinet");
  await page.keyboard.press("Escape");
  await page.getByRole("button", { name: "Team room", exact: true }).click();
  await expect(page).toHaveURL(new RegExp(`/shared\\?folder=${team}`));
  await expect(
    page.getByRole("button", { name: "Shared child", exact: true }),
  ).toBeVisible();
  await expect(
    page.getByRole("navigation", { name: "Folder breadcrumbs" }),
  ).not.toContainText("Private cabinet");
  await page.getByRole("button", { name: "Actions for Shared child" }).click();
  await expect(page.getByRole("menuitem", { name: /Rename/ })).toHaveCount(0);
  await page.keyboard.press("Escape");
  await page.goto(`/drive?folder=${privateParent}`);
  await expect(page.getByRole("main").getByRole("alert")).toContainText(
    "unavailable",
  );
  await expect(
    page.getByRole("button", { name: "Team room", exact: true }),
  ).toHaveCount(0);
  await page.goto("/search?q=Private&scope=SHARED");
  await expect(page.getByText("No matching resources")).toBeVisible();
  await page.goto("/shared?view=grid");
  await expect(
    page.getByRole("button", { name: "Brief.txt", exact: true }),
  ).toBeVisible();
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true);
  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([]);
  await page.screenshot({
    path: `test-results/m2-shared-${test.info().project.name}.png`,
    fullPage: true,
  });
  await page
    .getByRole("button", { name: "Toggle light and dark theme" })
    .click();
  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([]);
  await page.screenshot({
    path: `test-results/m2-shared-dark-${test.info().project.name}.png`,
    fullPage: true,
  });
});
