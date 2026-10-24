import { expect, test } from "@playwright/test";
import { randomUUID } from "node:crypto";

test("mocked lost link creation is never automatically resubmitted", async ({
  page,
}) => {
  test.setTimeout(60000);
  const email = `m5-loss-${randomUUID()}@example.test`,
    password = `M5-${randomUUID()}!`;
  await page.goto("/register");
  await page.getByLabel("Your name").fill("M5 Mock Check");
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
  const id = randomUUID(),
    now = new Date().toISOString();
  const file = {
    id,
    name: "Mock link.txt",
    folderId: null,
    sizeBytes: 0,
    contentType: "text/plain",
    effectiveRole: "OWNER",
    allowedCapabilities: ["READ_METADATA", "DOWNLOAD", "MANAGE_GRANTS"],
    createdAt: now,
    updatedAt: now,
  };
  let submits = 0;
  await page.route("**/api/v1/**", async (route) => {
    const path = new URL(route.request().url()).pathname;
    if (path === `/api/v1/files/${id}/share-links`) {
      if (route.request().method() === "POST") {
        submits++;
        return route.abort("failed");
      }
      return route.fulfill({ json: [] });
    }
    if (path.endsWith("/grants"))
      return route.fulfill({ json: { direct: [], inherited: [] } });
    if (path === `/api/v1/files/${id}`) return route.fulfill({ json: file });
    if (path === "/api/v1/files" || path === "/api/v1/folders")
      return route.fulfill({
        json: {
          content: path.endsWith("/files") ? [file] : [],
          page: 0,
          size: 50,
          totalPages: 1,
          totalElements: path.endsWith("/files") ? 1 : 0,
        },
      });
    return route.continue();
  });
  await page
    .getByRole("button", { name: "Refresh resources and storage" })
    .click();
  await page.getByRole("button", { name: "Actions for Mock link.txt" }).click();
  await page.getByRole("menuitem", { name: "Share", exact: true }).click();
  await page
    .getByRole("button", { name: "Create Viewer link", exact: true })
    .click();
  await expect(page.getByRole("dialog").getByRole("alert")).toContainText(
    "outcome is uncertain",
  );
  await expect(
    page.getByRole("button", { name: "Create Viewer link", exact: true }),
  ).toBeDisabled();
  await expect(
    page.getByRole("button", { name: "Copy link", exact: true }),
  ).toHaveCount(0);
  expect(submits).toBe(1);
  await page.getByRole("button", { name: "Refresh access" }).click();
  await expect(
    page.getByRole("button", { name: "Create Viewer link", exact: true }),
  ).toBeEnabled();
  expect(submits).toBe(1);
});
