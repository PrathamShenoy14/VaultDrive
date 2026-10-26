import {
  expect,
  test,
  type APIRequestContext,
  type BrowserContext,
} from "@playwright/test";
import { createHash, randomUUID } from "node:crypto";
import { createReadStream } from "node:fs";
import { stat } from "node:fs/promises";
import AxeBuilder from "@axe-core/playwright";
import type {
  PartUrl,
  UploadSession,
  StoredPart,
} from "../../src/features/transfers/contracts";

const backend = process.env.BACKEND_URL ?? "http://127.0.0.1:8080";
const admission = {
  Origin: process.env.E2E_APP_ORIGIN ?? "https://localhost:3000",
  "X-VaultDrive-Client": "1",
};
async function bootstrap(request: APIRequestContext, context: BrowserContext) {
  const input = {
    email: `frontend-m3-${randomUUID()}@example.test`,
    password: `M3-${randomUUID()}!`,
    displayName: "M3 Browser Check",
  };
  expect(
    (
      await request.post("/api/v1/auth/register", {
        headers: admission,
        data: input,
      })
    ).status(),
  ).toBe(201);
  const login = await request.post("/api/v1/auth/login", {
    headers: admission,
    data: input,
  });
  expect(login.status()).toBe(204);
  const pair = login
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
      name: pair[0]!,
      value: pair[1]!,
      domain: "localhost",
      path: "/",
      secure: true,
      httpOnly: true,
      sameSite: "Strict",
    },
  ]);
  const direct = await request.post(`${backend}/api/v1/auth/login`, {
    headers: { "X-VaultDrive-Client": "1" },
    data: input,
  });
  expect(direct.status()).toBe(200);
  const { accessToken } = await direct.json();
  return { Authorization: `Bearer ${accessToken}` };
}
async function digestFile(path: string) {
  const hash = createHash("sha256");
  for await (const chunk of createReadStream(path)) hash.update(chunk);
  return hash.digest("hex");
}

test("live Garage-backed native empty and multipart-file downloads; origin-aware browser admission", async ({
  page,
  request,
  context,
}, info) => {
  test.setTimeout(120000);
  const bearer = await bootstrap(request, context);
  // Fixture PUTs below use the Node HTTP client, not Chrome. They do NOT prove
  // browser multipart/CORS interoperability. The downloaded bytes are real Garage.
  const empty = await request.post("/api/v1/files/empty", {
    headers: { ...admission, "Idempotency-Key": randomUUID() },
    data: { name: "Empty.txt", contentType: "text/plain" },
  });
  expect(empty.status()).toBe(200);
  const emptyId = (await empty.json()).fileId;
  const sizes = [8 * 1024 * 1024, 8 * 1024 * 1024, 37];
  const total = sizes.reduce((sum, size) => sum + size, 0),
    whole = createHash("sha256");
  const reserve = await request.post(`${backend}/api/v1/upload-sessions`, {
    headers: { ...bearer, "Idempotency-Key": randomUUID() },
    data: {
      name: "Stream.bin",
      size: total,
      contentType: "application/octet-stream",
    },
  });
  expect(reserve.status()).toBe(201);
  const session = await reserve.json();
  // Do not bypass expiry or manufacture storage proof when the isolated
  // PostgreSQL clock creates a deadline already in the JVM's past.
  const serverTime = Date.parse(reserve.headers()["date"] ?? "");
  test.skip(
    Number.isFinite(serverTime) && Date.parse(session.expiresAt) <= serverTime,
    "Infrastructure clock gate: PostgreSQL upload expiry is already past according to the backend HTTP Date; multipart fixture cannot be signed.",
  );
  const path = `${backend}/api/v1/upload-sessions/${session.id}`;
  expect(
    (await request.post(`${path}/initiate`, { headers: bearer })).status(),
  ).toBe(200);
  for (const [index, size] of sizes.entries()) {
    const bytes = Buffer.alloc(size, index + 1);
    whole.update(bytes);
    const sha256 = createHash("sha256").update(bytes).digest("base64");
    const sign = await request.post(`${path}/part-urls`, {
      headers: bearer,
      data: { parts: [{ partNumber: index + 1, length: size, sha256 }] },
    });
    expect(sign.status()).toBe(200);
    const [signed] = await sign.json();
    const headers = Object.fromEntries(
      Object.entries(
        signed.upload.requiredHeaders as Record<string, string[]>,
      ).map(([key, value]) => [key, value[0]!]),
    );
    const put = await request.put(signed.upload.url, { headers, data: bytes });
    expect(put.status()).toBe(200);
    await put.dispose();
  }
  const complete = await request.post(`${path}/complete`, { headers: bearer });
  expect(complete.status()).toBe(200);
  const ready = await complete.json();
  expect(ready.completion.disposition).toBe("READY");
  await page.goto("/drive");
  await expect(
    page.getByRole("button", { name: "Stream.bin", exact: true }),
  ).toBeVisible();
  for (const [name, length, expectedHash] of [
    ["Empty.txt", 0, createHash("sha256").digest("hex")],
    ["Stream.bin", total, whole.digest("hex")],
  ] as const) {
    await page.getByLabel(`Select ${name}`, { exact: true }).check();
    const downloaded = page.waitForEvent("download");
    await page.getByRole("link", { name: "Download", exact: true }).click();
    const download = await downloaded;
    expect(download.suggestedFilename()).toBe(name);
    const filePath = info.outputPath(name);
    await download.saveAs(filePath);
    expect(await download.failure()).toBeNull();
    expect((await stat(filePath)).size).toBe(length);
    expect(await digestFile(filePath)).toBe(expectedHash);
    await page.getByRole("button", { name: "Clear selection" }).click();
  }
  const downloadHeaders = await request.get(
    `/api/v1/files/${emptyId}/download`,
  );
  expect(downloadHeaders.status()).toBe(200);
  expect(downloadHeaders.headers()["content-length"]).toBe("0");
  expect(downloadHeaders.headers()["content-type"]).toContain("text/plain");
  // A real HTTP browser Origin must not impersonate the approved HTTPS origin.
  // On approved HTTPS, this exercises actual zero-byte browser admission.
  await expect(
    page.getByRole("button", { name: "Upload files", exact: true }),
  ).toBeEnabled();
  await page.getByLabel("Choose files to upload").setInputFiles({
    name: "Http-blocked.txt",
    mimeType: "text/plain",
    buffer: Buffer.alloc(0),
  });
  const panel = page.getByRole("region", { name: "Transfers" });
  if (new URL(page.url()).origin === admission.Origin) {
    await expect(panel.getByText("completed", { exact: true })).toBeVisible();
    await expect(panel.getByText(/Published READY/)).toBeVisible();
  } else {
    await expect(panel.getByText("blocked", { exact: true })).toBeVisible();
    await expect(panel.getByText(/not permitted/)).toBeVisible();
  }
  await panel.getByRole("button", { name: "Minimize" }).click();
  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([]);
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true);
  await page.screenshot({
    path: info.outputPath("m3-live.png"),
    fullPage: true,
  });
  await context.clearCookies();
  const denied = await context.newPage();
  const failure = await denied.goto(`/api/v1/files/${emptyId}/download`);
  expect(failure?.status()).toBe(401);
  await expect(
    denied.getByRole("heading", { name: "Download unavailable" }),
  ).toBeVisible();
  await denied.close();
});

test("mocked Chrome multipart queue, exact evidence, unknown completion, recovery and navigation persistence", async ({
  page,
  request,
  context,
}, info) => {
  test.setTimeout(90000);
  await bootstrap(request, context);
  type MockTransfer = {
    session: UploadSession;
    parts: StoredPart[];
    name: string;
    recovered: boolean;
  };
  const transfers = new Map<string, MockTransfer>();
  let completes = 0,
    puts = 0,
    recovered = 0;
  const readyFiles: unknown[] = [];
  await page.route("**/api/v1/files?*", (route) =>
    route.fulfill({
      json: {
        content: readyFiles,
        page: 0,
        size: 50,
        totalElements: readyFiles.length,
        totalPages: 1,
      },
    }),
  );
  await page.route("**/api/v1/upload-sessions{,/**}", async (route) => {
    const req = route.request(),
      segments = new URL(req.url()).pathname.split("/").slice(4);
    if (!segments.length) {
      const input = req.postDataJSON(),
        id = randomUUID();
      const session: UploadSession = {
        id,
        fileId: randomUUID(),
        state: "RESERVED",
        revision: 0,
        initiationId: null,
        uploadId: null,
        decisionId: null,
        createdAt: new Date().toISOString(),
        expiresAt: new Date(Date.now() + 86400000).toISOString(),
        declaredSize: input.size,
        partSize: 8 * 1024 * 1024,
        partCount: Math.ceil(input.size / (8 * 1024 * 1024)),
        completion: null,
        ownerId: randomUUID(),
        initiatorUserId: randomUUID(),
      };
      transfers.set(id, {
        session,
        parts: [],
        name: input.name,
        recovered: false,
      });
      await route.fulfill({ status: 201, json: session });
      return;
    }
    const value = transfers.get(segments[0]!)!,
      session = value.session,
      action = segments[1];
    if (!action) {
      await route.fulfill({ json: session });
      return;
    }
    if (action === "initiate") {
      session.state = "OPEN";
      session.initiationId = randomUUID();
      session.uploadId = "mock-storage-id";
      await route.fulfill({ json: session });
      return;
    }
    if (action === "parts") {
      const params = new URL(req.url()).searchParams;
      const marker = Number(params.get("marker")),
        limit = Number(params.get("limit"));
      const parts = value.parts
        .filter((p) => p.partNumber > marker)
        .slice(0, limit);
      await route.fulfill({ json: { parts, nextMarker: null } });
      return;
    }
    if (action === "part-urls") {
      const { parts } = req.postDataJSON();
      const part = parts[0];
      const signed: PartUrl = {
        part: { ...part, registrationId: randomUUID() },
        upload: {
          url: `https://mock-garage.example.test/${session.id}/${part.partNumber}`,
          requiredHeaders: {
            host: ["mock-garage.example.test"],
            "content-length": [String(part.length)],
            "x-amz-checksum-sha256": [part.sha256],
          },
          expiresAt: new Date(Date.now() + 300000).toISOString(),
        },
      };
      await route.fulfill({ json: [signed] });
      return;
    }
    if (action === "complete") {
      completes++;
      session.state = "COMPLETION_DECIDED";
      session.completion = {
        disposition: "UNKNOWN",
        revision: 1,
        dispatchId: randomUUID(),
        receiptRecorded: false,
        reason: null,
      };
      await route.fulfill({ status: 202, json: session });
      return;
    }
    if (action === "recover") {
      recovered++;
      session.completion!.disposition = "READY";
      session.completion!.receiptRecorded = true;
      if (!value.recovered) {
        readyFiles.push({
          id: session.fileId,
          name: value.name,
          folderId: null,
          contentType: "application/octet-stream",
          sizeBytes: session.declaredSize,
          createdAt: session.createdAt,
          updatedAt: session.createdAt,
          effectiveRole: "OWNER",
          allowedCapabilities: ["READ_METADATA", "DOWNLOAD"],
        });
        value.recovered = true;
      }
      await route.fulfill({ json: session });
      return;
    }
    await route.abort();
  });
  await page.route("https://mock-garage.example.test/**", async (route) => {
    const req = route.request(),
      segments = new URL(req.url()).pathname.split("/");
    const bytes = req.postDataBuffer()!;
    const sha256 = createHash("sha256").update(bytes).digest("base64");
    expect(req.headers()["x-amz-checksum-sha256"]).toBe(sha256);
    expect(req.headers().authorization).toBeUndefined();
    expect(req.headers().cookie).toBeUndefined();
    expect(bytes.length).toBeLessThanOrEqual(8 * 1024 * 1024);
    const number = Number(segments[2]),
      etag = `"mock-exact-${number}"`;
    transfers
      .get(segments[1]!)!
      .parts.push({ partNumber: number, size: bytes.length, sha256, etag });
    puts++;
    await route.fulfill({
      status: 200,
      body: "",
      headers: {
        ETag: etag,
        "x-amz-checksum-sha256": sha256,
        "Access-Control-Allow-Origin": new URL(page.url()).origin,
        "Access-Control-Expose-Headers": "ETag,x-amz-checksum-sha256",
      },
    });
  });
  await page.goto("/drive");
  await page.getByLabel("Choose files to upload").setInputFiles([
    {
      name: "Multipart.bin",
      mimeType: "application/octet-stream",
      buffer: Buffer.alloc(8 * 1024 * 1024 + 37, 7),
    },
    {
      name: "Small.bin",
      mimeType: "application/octet-stream",
      buffer: Buffer.from("single"),
    },
  ]);
  const panel = page.getByRole("region", { name: "Transfers" });
  await expect(panel.getByText("unknown", { exact: true })).toHaveCount(2);
  expect(puts).toBe(3);
  expect(completes).toBe(2);
  await panel
    .getByRole("button", { name: "Recover", exact: true })
    .first()
    .click();
  await expect(panel.getByText("completed", { exact: true })).toHaveCount(1);
  await panel.getByRole("button", { name: "Recover", exact: true }).click();
  await expect(panel.getByText("completed", { exact: true })).toHaveCount(2);
  expect(recovered).toBe(2);
  expect(completes).toBe(2);
  await panel.getByRole("button", { name: "Minimize" }).click();
  if (await page.getByRole("button", { name: "Open navigation" }).isVisible())
    await page.getByRole("button", { name: "Open navigation" }).click();
  await page.getByRole("link", { name: "Shared", exact: true }).click();
  await expect(page).toHaveURL(/\/shared/);
  await expect(panel).toBeVisible();
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true);
  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([]);
  await page.screenshot({
    path: info.outputPath("m3-mocked.png"),
    fullPage: true,
  });
});
