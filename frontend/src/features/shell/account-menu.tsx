"use client";
/* eslint-disable @next/next/no-location-assign-relative-destination -- Logout must discard all personalized Router Cache entries. */
import { useState } from "react";
import { LogOut, UserRound } from "lucide-react";
import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuGroup,
  DropdownMenuTrigger,
  DropdownMenuContent,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuItem,
} from "@/components/ui/dropdown-menu";
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogDescription,
} from "@/components/ui/dialog";
import {
  authRequest,
  errorMessage,
  type Profile,
  ApiError,
} from "@/lib/api/transport";

export function AccountMenu({ profile }: { profile: Profile }) {
  const [account, setAccount] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string>();
  async function logout() {
    setBusy(true);
    setError(undefined);
    try {
      await authRequest("logout");
      window.location.assign("/login");
    } catch (e) {
      if (e instanceof ApiError && e.status === 401) {
        window.location.assign("/login?reason=session");
        return;
      }
      setError(errorMessage(e));
      setAccount(true);
    } finally {
      setBusy(false);
    }
  }
  const initials =
    profile.displayName
      .trim()
      .split(/\s+/)
      .slice(0, 2)
      .map((v) => Array.from(v)[0])
      .join("")
      .toUpperCase() || "V";
  return (
    <>
      <DropdownMenu>
        <DropdownMenuTrigger
          render={
            <Button
              variant="ghost"
              size="icon"
              className="rounded-full"
              aria-label={`Account menu for ${profile.displayName}, ${initials}`}
              disabled={busy}
            />
          }
        >
          <span
            aria-hidden="true"
            className="flex size-9 items-center justify-center rounded-full bg-primary/15 text-xs font-semibold text-primary"
          >
            {initials}
          </span>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end" className="w-64">
          <DropdownMenuGroup>
            <DropdownMenuLabel>
              <span className="block truncate">{profile.displayName}</span>
              <span className="block truncate text-xs font-normal text-muted-foreground">
                {profile.email}
              </span>
            </DropdownMenuLabel>
            <DropdownMenuSeparator />
            <DropdownMenuItem onClick={() => setAccount(true)}>
              <UserRound aria-hidden="true" />
              Your account
            </DropdownMenuItem>
            <DropdownMenuItem onClick={logout} disabled={busy}>
              <LogOut aria-hidden="true" />
              {busy ? "Signing out…" : "Sign out"}
            </DropdownMenuItem>
          </DropdownMenuGroup>
        </DropdownMenuContent>
      </DropdownMenu>
      <Dialog open={account} onOpenChange={setAccount}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Your account</DialogTitle>
            <DialogDescription>
              Your profile, connected to VaultDrive.
            </DialogDescription>
          </DialogHeader>
          <dl className="grid gap-4 py-4 text-sm">
            <div>
              <dt className="text-muted-foreground">Name</dt>
              <dd className="mt-1 break-words font-medium">
                {profile.displayName}
              </dd>
            </div>
            <div>
              <dt className="text-muted-foreground">Email</dt>
              <dd className="mt-1 break-all font-medium">{profile.email}</dd>
            </div>
          </dl>
          <p className="text-xs text-muted-foreground">
            Profile editing will be available in a later milestone.
          </p>
          {error && (
            <p role="alert" className="text-sm text-destructive">
              {error}
            </p>
          )}
          <Button variant="outline" onClick={logout} disabled={busy}>
            <LogOut aria-hidden="true" />
            {busy ? "Signing out…" : "Sign out"}
          </Button>
        </DialogContent>
      </Dialog>
    </>
  );
}
