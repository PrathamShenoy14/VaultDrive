import { expect, test } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";
import { randomUUID } from "node:crypto";
const origin = process.env.E2E_APP_ORIGIN ?? "https://localhost:3000";
const admission = { Origin: origin, "X-VaultDrive-Client": "1" };

test.describe("native form fallback", () => {
  test.use({ javaScriptEnabled: false });
  test("keeps credentials out of URLs when JavaScript is unavailable", async ({
    page,
  }) => {
    await page.goto("/login");
    await page.getByLabel("Email address").fill("no-js@example.test");
    await page
      .getByLabel("Password", { exact: true })
      .fill("NoJsPassword12345");
    const sent = page.waitForRequest(
      (request) => request.isNavigationRequest() && request.method() === "POST",
    );
    await page.getByRole("button", { name: "Sign in", exact: true }).click();
    const submission = await sent;
    expect(new URL(submission.url()).pathname).toBe("/login");
    expect(new URL(submission.url()).search).toBe("");
    expect(submission.postData()).toContain("password=NoJsPassword12345");
  });
});

test("public auth controls, validation, themes and protected redirects", async ({
  page,
}) => {
  await page.goto("/drive");
  await expect(page).toHaveURL(/\/login\?reason=session/);
  await page.getByRole("button", { name: "Sign in", exact: true }).click();
  await expect(page.getByText("Enter a valid email address.")).toBeVisible();
  await page.getByRole("button", { name: "Show password" }).click();
  await expect(page.getByLabel("Password", { exact: true })).toHaveAttribute(
    "type",
    "text",
  );
  await page
    .getByRole("button", { name: "Toggle light and dark theme" })
    .click();
  await page.screenshot({
    path: `test-results/login-${test.info().project.name}.png`,
    fullPage: true,
  });
  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([]);
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth,
    ),
  ).toBe(true);
  await page.getByRole("link", { name: "Create an account" }).click();
  await expect(page.getByLabel("Your name")).toBeVisible();
});

test("real backend registration/login/profile/quota, shell navigation and committed logout", async ({
  page,
  context,
  request,
}) => {
  const email = `frontend-m1-${randomUUID()}@example.test`;
  const password = `M1-${randomUUID()}!`;
  // HTTP API-client bootstrap deliberately supplies the configured HTTPS Origin.
  // This is real backend integration, not a browser login/cookie-origin proof.
  const registration = await request.post("/api/v1/auth/register", {
    headers: admission,
    data: { email, password, displayName: "M1 Browser Check" },
  });
  expect(registration.status()).toBe(201);
  const login = await request.post("/api/v1/auth/login", {
    headers: admission,
    data: { email, password },
  });
  expect(login.status()).toBe(204);
  expect(await login.text()).toBe("");
  const cookies = login
    .headersArray()
    .filter((h) => h.name.toLowerCase() === "set-cookie")
    .map((h) => h.value);
  expect(cookies).toHaveLength(2);
  const session = cookies
    .find((value) => value.startsWith("__Host-vaultdrive-session="))!
    .split(";")[0]!;
  const pair = session.split("=");
  // Chromium's localhost exception permits Secure cookies for this local HTTP UI
  // check. Production remains HTTPS-only; no cookie flags are weakened.
  await context.addCookies([
    {
      name: pair[0]!,
      value: pair[1]!,
      domain: "localhost",
      path: "/",
      secure: true,
      httpOnly: true,
      sameSite: "Strict",
    },
  ]);
  const profile = await request.get("/api/v1/users/me", {
    headers: { Cookie: session },
  });
  expect(profile.status()).toBe(200);
  expect(profile.headers()["cache-control"]).toContain("no-store");
  expect((await profile.json()).email).toBe(email);
  const quota = await request.get("/api/v1/users/me/storage", {
    headers: { Cookie: session },
  });
  expect(quota.status()).toBe(200);
  expect(quota.headers()["cache-control"]).toContain("no-store");
  expect(await quota.json()).toMatchObject({ usedBytes: 0, reservedBytes: 0 });
  const unknown = await request.get("/api/v1/files", {
    headers: { Cookie: session },
  });
  expect(unknown.status()).toBe(404);
  const csrfDenied = await request.post("/api/v1/auth/logout", {
    headers: {
      Cookie: session,
      Origin: "https://evil.test",
      "X-VaultDrive-Client": "1",
    },
  });
  expect(csrfDenied.status()).toBe(403);
  await page.goto("/drive");
  await expect(
    page.getByRole("heading", { name: "My Drive", exact: true }),
  ).toBeVisible();
  await expect(
    page.getByRole("textbox", { name: /Search in VaultDrive/ }),
  ).toBeDisabled();
  const mobile = page.getByRole("button", { name: "Open navigation" });
  if (await mobile.isVisible()) {
    await mobile.click();
    await expect(
      page.getByRole("dialog", { name: "Navigation" }),
    ).toBeVisible();
    await page.keyboard.press("Escape");
    await expect(mobile).toBeFocused();
  }
  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([]);
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth,
    ),
  ).toBe(true);
  await page.screenshot({
    path: `test-results/drive-${test.info().project.name}.png`,
    fullPage: true,
  });
  await page
    .getByRole("button", { name: "Toggle light and dark theme" })
    .click();
  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([]);
  await page.screenshot({
    path: `test-results/drive-dark-${test.info().project.name}.png`,
    fullPage: true,
  });
  if (await mobile.isVisible()) await mobile.click();
  await page
    .getByRole("link", { name: "Shared", exact: true })
    .filter({ visible: true })
    .click();
  await expect(
    page.getByRole("heading", { name: "Shared", exact: true }),
  ).toBeVisible();
  await page.getByRole("button", { name: /Account menu for/ }).click();
  await page.getByRole("menuitem", { name: "Your account" }).click();
  await expect(
    page.getByRole("dialog", { name: "Your account" }),
  ).toContainText(email);
  await page.keyboard.press("Escape");
  for (const item of [
    { name: "Recent / Activity", heading: "Recent / Activity" },
    { name: "Trash", heading: "Trash" },
  ]) {
    if (await mobile.isVisible()) await mobile.click();
    await page
      .getByRole("link", { name: item.name, exact: true })
      .filter({ visible: true })
      .click();
    await expect(
      page.getByRole("heading", { name: item.heading, exact: true }),
    ).toBeVisible();
  }
  await page.reload();
  await expect(
    page.getByRole("heading", { name: "Trash", exact: true }),
  ).toBeVisible();
  // Logout through the HTTP BFF contract, because browser mutation Origin is HTTP.
  const logout = await request.post("/api/v1/auth/logout", {
    headers: { ...admission, Cookie: session },
  });
  expect(logout.status()).toBe(204);
  expect(
    logout
      .headersArray()
      .filter((h) => h.name.toLowerCase() === "set-cookie")
      .every((h) => h.value.includes("Max-Age=0")),
  ).toBe(true);
  expect(
    (
      await request.get("/api/v1/users/me", { headers: { Cookie: session } })
    ).status(),
  ).toBe(401);
  await page.goto("/drive");
  await expect(page).toHaveURL(/\/login\?reason=session/);
});
