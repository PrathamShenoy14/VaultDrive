import { describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { AuthForm } from "@/features/auth/auth-form";
import { AppShell } from "@/features/shell/app-shell";
import { Storage } from "@/features/shell/storage";
import { AccountMenu } from "@/features/shell/account-menu";
vi.mock("next/navigation", () => ({
  usePathname: () => "/drive",
  useRouter: () => ({ push: vi.fn(), refresh: vi.fn() }),
}));
vi.mock("next-themes", () => ({
  useTheme: () => ({ resolvedTheme: "light", setTheme: vi.fn() }),
}));
const profile = {
  userId: "user",
  displayName: "Alice Example",
  email: "alice@example.test",
  createdAt: "2026-10-24T00:00:00Z",
  updatedAt: "2026-10-24T00:00:00Z",
};
describe("primary accessible controls", () => {
  it("sends logout from the account menu and displays a backend refusal", async () => {
    const user = userEvent.setup();
    const fetcher = vi
      .fn()
      .mockResolvedValue(
        Response.json(
          { status: 403, error: "FORBIDDEN", message: "Invalid CSRF token" },
          { status: 403 },
        ),
      );
    vi.stubGlobal("fetch", fetcher);
    render(<AccountMenu profile={profile} />);
    await user.click(
      screen.getByRole("button", { name: /Account menu for Alice/ }),
    );
    await user.click(await screen.findByRole("menuitem", { name: "Sign out" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("not permitted");
    expect(fetcher).toHaveBeenCalledTimes(1);
    expect(fetcher.mock.calls[0]![0]).toBe("/api/v1/auth/logout");
  });
  it("registers against the BFF and requires separate login", async () => {
    const user = userEvent.setup();
    const fetcher = vi
      .fn()
      .mockResolvedValue(
        Response.json(
          { userId: "new-user", message: "User registered successfully" },
          { status: 201 },
        ),
      );
    vi.stubGlobal("fetch", fetcher);
    render(<AuthForm mode="register" />);
    await user.type(screen.getByLabelText("Your name"), "Alice");
    await user.type(
      screen.getByLabelText("Email address"),
      "alice@example.test",
    );
    await user.type(
      screen.getByLabelText("Password", { exact: true }),
      "Password12345!",
    );
    await user.click(screen.getByRole("button", { name: "Create account" }));
    expect(await screen.findByText("Your account is ready")).toBeVisible();
    expect(fetcher).toHaveBeenCalledTimes(1);
    expect(fetcher.mock.calls[0]![0]).toBe("/api/v1/auth/register");
  });
  it("shows login errors and supports an accessible password visibility control", async () => {
    const user = userEvent.setup();
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        Response.json(
          {
            status: 401,
            error: "UNAUTHORIZED",
            message: "Invalid credentials",
          },
          { status: 401 },
        ),
      ),
    );
    render(<AuthForm mode="login" />);
    await user.type(
      screen.getByLabelText("Email address"),
      "alice@example.test",
    );
    await user.type(
      screen.getByLabelText("Password", { exact: true }),
      "wrong",
    );
    await user.click(screen.getByRole("button", { name: "Show password" }));
    expect(screen.getByLabelText("Password", { exact: true })).toHaveAttribute(
      "type",
      "text",
    );
    await user.click(screen.getByRole("button", { name: "Sign in" }));
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Email or password is incorrect.",
    );
  });
  it("renders real quota including reservations and clamps a zero limit", () => {
    render(
      <Storage
        usage={{
          limitBytes: 0n,
          usedBytes: 1n,
          reservedBytes: 1n,
          availableBytes: 0n,
          uncertainStorageBytes: 9000n,
          uncertainStorageAllocations: 1n,
          unknownSizeAllocations: 0n,
        }}
      />,
    );
    expect(screen.getByRole("meter")).toHaveAttribute("aria-valuenow", "100");
    expect(screen.getByText("2 B of 0 B used or reserved")).toBeVisible();
  });
  it("renders navigation, unavailable search and account controls", () => {
    render(
      <AppShell profile={profile} storage={<p>Storage</p>}>
        <h1>My Drive</h1>
      </AppShell>,
    );
    expect(screen.getByRole("link", { name: "My Drive" })).toHaveAttribute(
      "aria-current",
      "page",
    );
    expect(
      screen.getByRole("textbox", { name: /Search in VaultDrive/ }),
    ).toBeDisabled();
    expect(
      screen.getByRole("button", { name: /Account menu for Alice/ }),
    ).toBeVisible();
    expect(
      screen.getByRole("button", { name: "Open navigation" }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: "Toggle light and dark theme" }),
    ).toBeVisible();
    expect(screen.getByRole("button", { name: /New/ })).toBeDisabled();
  });
});
