"use client";
import { createContext, useContext, useEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import {
  Dialog,
  DialogContent,
  DialogTitle,
  DialogDescription,
} from "@/components/ui/dialog";
import { getMetadata } from "@/features/explorer/api";
import {
  canRead,
  folderHref,
  type ExplorerEntry,
  type Resource,
} from "@/features/explorer/contracts";
import { ApiError, errorMessage } from "@/lib/api/transport";
import {
  expiryError,
  resourcePath,
  roleLabel,
  sharingError,
  sharingRequest,
  shareUrl,
  type CreatedLink,
  type Grant,
  type Grants,
  type Link,
  type Role,
} from "./api";

const Context = createContext<{ open: (entry: Resource) => void } | null>(null);
export const useSharing = () => useContext(Context);
export function SharingProvider({ children }: { children: React.ReactNode }) {
  const [entry, setEntry] = useState<Resource>();
  const [busy, setBusy] = useState(false);
  const returnFocus = useRef<HTMLElement | null>(null);
  return (
    <Context.Provider
      value={{
        open: (value) => {
          const active = document.activeElement as HTMLElement;
          returnFocus.current =
            active?.getAttribute("role") === "menuitem"
              ? ([
                  ...document.querySelectorAll<HTMLButtonElement>(
                    "button[aria-label]",
                  ),
                ].find(
                  (button) =>
                    button.getAttribute("aria-label") ===
                    `Actions for ${value.resource.name}`,
                ) ?? active)
              : active;
          setEntry(value);
        },
      }}
    >
      {children}
      <Dialog
        open={!!entry}
        onOpenChange={(open) => {
          if (!open && !busy) setEntry(undefined);
        }}
      >
        <DialogContent
          finalFocus={() => returnFocus.current}
          className="max-h-[85dvh] overflow-y-auto sm:max-w-2xl"
        >
          {entry && (
            <SharingDetails
              key={`${entry.resourceType}:${entry.resource.id}`}
              entry={entry}
              setBusy={setBusy}
            />
          )}
        </DialogContent>
      </Dialog>
    </Context.Provider>
  );
}
export function SharingAction({ entry }: { entry: ExplorerEntry }) {
  const sharing = useSharing();
  return (
    sharing &&
    entry.resourceType !== "TRASH" &&
    canRead(entry) && (
      <Button size="sm" variant="ghost" onClick={() => sharing.open(entry)}>
        {entry.resource.allowedCapabilities.includes("MANAGE_GRANTS")
          ? "Share"
          : "Access details"}
      </Button>
    )
  );
}
type Snapshot = {
  checkedAt: number;
  resource: Resource["resource"];
  grants?: Grants;
  links?: Link[];
};
type Confirmation =
  | { kind: "revoke-grant"; grant: Grant }
  | { kind: "role"; grant: Grant; role: Role }
  | { kind: "revoke-link"; link: Link };
export function SharingDetails({
  entry,
  setBusy = () => {},
}: {
  entry: Resource;
  setBusy?: (busy: boolean) => void;
}) {
  const router = useRouter();
  const path = resourcePath(entry);
  const [snapshot, setSnapshot] = useState<Snapshot>();
  const [loading, setLoading] = useState(true);
  const [readError, setReadError] = useState("");
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [blocked, setBlocked] = useState(false);
  const [working, setWorking] = useState(false);
  const [email, setEmail] = useState("");
  const [role, setRole] = useState<Role>("VIEWER");
  const [expiry, setExpiry] = useState("");
  const [created, setCreated] = useState<CreatedLink>();
  const [copied, setCopied] = useState("");
  const [confirmation, setConfirmation] = useState<Confirmation>();
  const active = useRef<AbortController | null>(null);
  const lock = useRef(false);
  const mounted = useRef(true);
  const changed = () => {
    window.dispatchEvent(new Event("vaultdrive:sharing-changed"));
    router.refresh();
  };
  async function load() {
    active.current?.abort();
    const controller = new AbortController();
    active.current = controller;
    try {
      const resource = await getMetadata(
        entry.resourceType,
        entry.resource.id,
        controller.signal,
      );
      const manage = resource.allowedCapabilities.includes("MANAGE_GRANTS");
      const [grants, links] = await Promise.all([
        manage
          ? sharingRequest<Grants>(
              `${path}/grants`,
              "GET",
              undefined,
              undefined,
              controller.signal,
            )
          : undefined,
        manage && entry.resourceType === "FILE"
          ? sharingRequest<Link[]>(
              `${path}/share-links`,
              "GET",
              undefined,
              undefined,
              controller.signal,
            )
          : undefined,
      ]);
      if (!controller.signal.aborted && mounted.current) {
        setSnapshot({ resource, grants, links, checkedAt: Date.now() });
        setCreated((previous) =>
          previous &&
          links?.some(
            (link) =>
              link.id === previous.link.id &&
              !link.revokedAt &&
              Date.parse(link.expiresAt) > Date.now(),
          )
            ? previous
            : undefined,
        );
        setLoading(false);
        setReadError("");
        setBlocked(false);
      }
    } catch (failure) {
      if (!controller.signal.aborted && mounted.current) {
        setSnapshot(undefined);
        setCreated(undefined);
        setReadError(errorMessage(failure));
        setLoading(false);
        if (
          failure instanceof ApiError &&
          [401, 403, 404].includes(failure.status)
        )
          changed();
      }
    }
  }
  useEffect(() => {
    mounted.current = true;
    // load setters follow network reads; initial loading is already true.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    void load();
    const revalidate = () => {
      if (!lock.current && document.visibilityState !== "hidden") {
        setLoading(true);
        setConfirmation(undefined);
        void load();
      }
    };
    window.addEventListener("focus", revalidate);
    document.addEventListener("visibilitychange", revalidate);
    return () => {
      mounted.current = false;
      active.current?.abort();
      window.removeEventListener("focus", revalidate);
      document.removeEventListener("visibilitychange", revalidate);
    };
    // Resource identity is keyed by the provider. No retained recipient cache.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);
  async function mutate(operation: () => Promise<unknown>, message: string) {
    if (lock.current || blocked || loading || !snapshot?.grants) return;
    lock.current = true;
    setBusy(true);
    setWorking(true);
    setError("");
    setNotice("");
    try {
      await operation();
      if (mounted.current) {
        setNotice(message);
        setEmail("");
        setConfirmation(undefined);
      }
      changed();
      await load();
    } catch (failure) {
      if (mounted.current) {
        setError(sharingError(failure));
        setConfirmation(undefined);
        if (
          !(failure instanceof ApiError) ||
          [408, 409, 412, 428].includes(failure.status) ||
          failure.status >= 500
        )
          setBlocked(true);
        if (
          failure instanceof ApiError &&
          [401, 403, 404].includes(failure.status)
        ) {
          setSnapshot(undefined);
          setCreated(undefined);
          setBlocked(true);
        }
      }
      changed();
    } finally {
      lock.current = false;
      setBusy(false);
      if (mounted.current) setWorking(false);
    }
  }
  const disabled = working || blocked || loading;
  const validation = expiryError(expiry);
  const manage = !!snapshot?.grants;
  return (
    <div className="min-w-0 space-y-5">
      <div className="space-y-2 pr-7">
        <DialogTitle>Share and access</DialogTitle>
        <DialogDescription className="break-words">
          {snapshot?.resource.name ?? entry.resource.name}
        </DialogDescription>
      </div>
      <p className="text-xs text-muted-foreground">
        Viewer can read and download. Editor can also rename and add content in
        editable folders; Trash requires an editable immediate parent. Only the
        owner manages access, moves, copies, restores, or permanently deletes.
      </p>
      {loading && <p role="status">Loading current access…</p>}
      {readError && <p role="alert">{readError}</p>}
      {error && <p role="alert">{error}</p>}
      {notice && <p role="status">{notice}</p>}
      <Button
        size="sm"
        variant="outline"
        disabled={working || loading}
        onClick={() => {
          setLoading(true);
          setError("");
          setConfirmation(undefined);
          void load();
          changed();
        }}
      >
        Refresh access
      </Button>
      {!loading && snapshot && (
        <>
          <section
            aria-label="Effective access"
            className="space-y-2 rounded-lg bg-muted/50 p-3"
          >
            <h2 className="font-medium">
              Your effective access:{" "}
              {roleLabel(snapshot.resource.effectiveRole)}
            </h2>
            <p className="text-xs text-muted-foreground">
              {manage
                ? "Direct and ancestor grants combine; the strongest current role applies for each recipient."
                : "Your role combines current direct and inherited access. The API does not disclose your grant sources or other recipients. Ask the owner to change access."}
            </p>
          </section>
          {manage && (
            <>
              <form
                className="space-y-3 rounded-lg border p-3"
                onSubmit={(event) => {
                  event.preventDefault();
                  if (!email.trim() || !["VIEWER", "EDITOR"].includes(role))
                    return;
                  void mutate(
                    () =>
                      sharingRequest(`${path}/grants`, "POST", {
                        email: email.trim(),
                        role,
                      }),
                    "Direct access granted.",
                  );
                }}
              >
                <h2 className="font-medium">Grant direct access</h2>
                <label className="block space-y-1 text-sm">
                  <span>Registered recipient email</span>
                  <Input
                    type="email"
                    required
                    maxLength={320}
                    autoComplete="off"
                    value={email}
                    onChange={(event) => setEmail(event.target.value)}
                    disabled={disabled}
                  />
                </label>
                <p className="text-xs text-muted-foreground">
                  Use an existing account’s email. There is no invitation or
                  user search.
                </p>
                <label className="block space-y-1 text-sm">
                  <span>New recipient role</span>
                  <RoleSelect
                    value={role}
                    setValue={setRole}
                    disabled={disabled}
                  />
                </label>
                <Button type="submit" disabled={disabled || !email.trim()}>
                  Grant access
                </Button>
              </form>
              <section aria-label="Direct access" className="space-y-3">
                <h2 className="font-medium">Direct access</h2>
                {!snapshot.grants!.direct.length && (
                  <p className="text-sm text-muted-foreground">
                    No direct grants.
                  </p>
                )}
                <ul className="space-y-3">
                  {snapshot.grants!.direct.map((grant) => (
                    <GrantRow
                      key={`${grant.id}:${grant.version}`}
                      grant={grant}
                      effective={effective(grant, snapshot.grants!)}
                      disabled={disabled}
                      change={(next) =>
                        setConfirmation({ kind: "role", grant, role: next })
                      }
                      revoke={() =>
                        setConfirmation({ kind: "revoke-grant", grant })
                      }
                    />
                  ))}
                </ul>
              </section>
              <section aria-label="Inherited access" className="space-y-3">
                <h2 className="font-medium">
                  Inherited access · read only here
                </h2>
                <p className="text-xs text-muted-foreground">
                  Change these grants on their source folder. Revoking a direct
                  grant here does not remove inherited access; descendants
                  follow current ancestor access.
                </p>
                {!snapshot.grants!.inherited.length && (
                  <p className="text-sm text-muted-foreground">
                    No inherited grants.
                  </p>
                )}
                <ul className="space-y-3">
                  {snapshot.grants!.inherited.map((grant) => (
                    <li
                      key={grant.id}
                      className="space-y-1 rounded-lg border p-3 text-sm"
                    >
                      <p className="break-all font-medium">{grant.email}</p>
                      <p>
                        {roleLabel(grant.role)} · Effective here:{" "}
                        {effective(grant, snapshot.grants!)}
                      </p>
                      <p className="break-all text-xs text-muted-foreground">
                        Recipient ID: {grant.granteeUserId}
                      </p>
                      {grant.folderId && (
                        <a
                          className="break-all text-xs underline"
                          href={folderHref(grant.folderId)}
                        >
                          Source folder: {grant.folderId}
                        </a>
                      )}
                    </li>
                  ))}
                </ul>
              </section>
              {entry.resourceType === "FILE" && (
                <section
                  aria-label="Viewer links"
                  className="space-y-3 border-t pt-4"
                >
                  <h2 className="font-medium">Viewer links</h2>
                  <p className="text-xs text-muted-foreground">
                    Separate bearer access to this file only. Anyone holding the
                    secret can read and download. Links expire and can be
                    revoked. Default expiry is 24 hours; maximum is seven days.
                  </p>
                  <form
                    className="space-y-3"
                    onSubmit={(event) => {
                      event.preventDefault();
                      if (expiryError(expiry)) return;
                      void mutate(async () => {
                        const result = await sharingRequest<CreatedLink>(
                          `${path}/share-links`,
                          "POST",
                          {
                            expiresAt: expiry
                              ? new Date(expiry).toISOString()
                              : null,
                          },
                        );
                        if (mounted.current) {
                          setCreated(result);
                          setCopied("");
                        }
                      }, "Viewer link created. Copy it now; its secret is shown only this time.");
                    }}
                  >
                    <label className="block space-y-1 text-sm">
                      <span>Link expiry (optional, local time)</span>
                      <Input
                        type="datetime-local"
                        value={expiry}
                        onChange={(event) => setExpiry(event.target.value)}
                        disabled={disabled}
                        aria-describedby="expiry-help"
                      />
                    </label>
                    <p
                      id="expiry-help"
                      className="text-xs text-muted-foreground"
                    >
                      {validation ??
                        "Leave blank for 24 hours. Expiry uses the server clock."}
                    </p>
                    <Button
                      type="submit"
                      disabled={disabled || !!validation || !!created}
                    >
                      Create Viewer link
                    </Button>
                  </form>
                  {created && (
                    <div className="space-y-3 rounded-lg bg-muted p-3">
                      <p className="text-sm">
                        New link secret · copy before closing. It cannot be
                        retrieved later.
                      </p>
                      <label className="block space-y-1 text-sm">
                        <span>New share URL</span>
                        <Input
                          readOnly
                          value={shareUrl(window.location.origin, created)}
                          onFocus={(event) => event.target.select()}
                        />
                      </label>
                      <div className="flex flex-wrap gap-2">
                        <Button
                          size="sm"
                          onClick={async () => {
                            try {
                              await navigator.clipboard.writeText(
                                shareUrl(window.location.origin, created),
                              );
                              setCopied(
                                "Link copied. Share it only with intended readers.",
                              );
                            } catch {
                              setCopied(
                                "Copy unavailable. Select and copy the URL above.",
                              );
                            }
                          }}
                        >
                          Copy link
                        </Button>
                        <Button
                          size="sm"
                          variant="outline"
                          onClick={() => {
                            setCreated(undefined);
                            setCopied("");
                          }}
                        >
                          Hide secret
                        </Button>
                      </div>
                      {copied && (
                        <p role="status" className="text-xs">
                          {copied}
                        </p>
                      )}
                    </div>
                  )}
                  <p className="text-xs text-muted-foreground">
                    Existing link secrets are never listed or recovered. To
                    replace a forgotten link, revoke it and create a new one.
                  </p>
                  {!snapshot.links?.length && (
                    <p className="text-sm text-muted-foreground">
                      No Viewer links.
                    </p>
                  )}
                  <ul className="space-y-3">
                    {snapshot.links?.map((link) => (
                      <li
                        key={link.id}
                        className="space-y-2 rounded-lg border p-3 text-sm"
                      >
                        <p className="break-all font-medium">Link {link.id}</p>
                        <p>
                          {link.revokedAt
                            ? "Revoked"
                            : Date.parse(link.expiresAt) <= snapshot.checkedAt
                              ? "Expired"
                              : "Active at last check"}{" "}
                          · Viewer · File only
                        </p>
                        <p className="text-xs text-muted-foreground">
                          Created: {time(link.createdAt)} · Expires:{" "}
                          {time(link.expiresAt)}
                          {link.revokedAt
                            ? ` · Revoked: ${time(link.revokedAt)}`
                            : ""}
                        </p>
                        {!link.revokedAt && (
                          <Button
                            size="sm"
                            variant="outline"
                            disabled={disabled}
                            aria-label={`Revoke link ${link.id}`}
                            onClick={() =>
                              setConfirmation({ kind: "revoke-link", link })
                            }
                          >
                            Revoke link
                          </Button>
                        )}
                      </li>
                    ))}
                  </ul>
                </section>
              )}
            </>
          )}
        </>
      )}
      <Dialog
        open={!!confirmation}
        onOpenChange={(open) => {
          if (!open && !working) setConfirmation(undefined);
        }}
      >
        <DialogContent className="max-h-[80dvh] overflow-y-auto">
          <DialogTitle>
            {confirmation?.kind === "role"
              ? "Change direct role?"
              : confirmation?.kind === "revoke-link"
                ? "Revoke Viewer link?"
                : "Revoke direct access?"}
          </DialogTitle>
          <DialogDescription>
            {confirmation?.kind === "revoke-link"
              ? "Future requests using this link will fail. Already admitted downloads may finish. This does not revoke direct user grants."
              : `Confirm the change for ${confirmation?.grant.email ?? "this recipient"}. Other direct or inherited grants may preserve access.`}
          </DialogDescription>
          {confirmation?.kind === "role" && (
            <p>New direct role: {roleLabel(confirmation.role)}</p>
          )}
          <div className="flex flex-wrap justify-end gap-2">
            <Button
              variant="outline"
              disabled={working}
              onClick={() => setConfirmation(undefined)}
            >
              Cancel
            </Button>
            <Button
              disabled={disabled}
              variant={
                confirmation?.kind === "role" ? "default" : "destructive"
              }
              onClick={() => {
                const target = confirmation;
                if (!target) return;
                void mutate(
                  async () => {
                    if (target.kind === "role")
                      await sharingRequest(
                        `${path}/grants/${target.grant.id}`,
                        "PATCH",
                        { role: target.role },
                        target.grant.version,
                      );
                    else if (target.kind === "revoke-grant")
                      await sharingRequest(
                        `${path}/grants/${target.grant.id}`,
                        "DELETE",
                      );
                    else {
                      await sharingRequest(
                        `${path}/share-links/${target.link.id}`,
                        "DELETE",
                      );
                      if (created?.link.id === target.link.id)
                        setCreated(undefined);
                    }
                  },
                  target.kind === "role"
                    ? "Direct role updated."
                    : target.kind === "revoke-link"
                      ? "Viewer link revoked."
                      : "Direct access revoked. Inherited access may remain.",
                );
              }}
            >
              {working ? "Saving…" : "Confirm change"}
            </Button>
          </div>
        </DialogContent>
      </Dialog>
    </div>
  );
}
function effective(grant: Grant, grants: Grants) {
  return [...grants.direct, ...grants.inherited].some(
    (value) =>
      value.granteeUserId === grant.granteeUserId && value.role === "EDITOR",
  )
    ? "Editor"
    : "Viewer";
}
function time(value: string) {
  return new Date(value).toLocaleString();
}
function RoleSelect({
  value,
  setValue,
  disabled,
}: {
  value: Role;
  setValue: (value: Role) => void;
  disabled: boolean;
}) {
  return (
    <select
      className="w-full rounded-lg border bg-background p-2 text-foreground"
      value={value}
      disabled={disabled}
      onChange={(event) => {
        if (event.target.value === "VIEWER" || event.target.value === "EDITOR")
          setValue(event.target.value);
      }}
    >
      <option value="VIEWER">Viewer</option>
      <option value="EDITOR">Editor</option>
    </select>
  );
}
function GrantRow({
  grant,
  effective,
  disabled,
  change,
  revoke,
}: {
  grant: Grant;
  effective: string;
  disabled: boolean;
  change: (role: Role) => void;
  revoke: () => void;
}) {
  const [role, setRole] = useState<Role>(
    grant.role === "EDITOR" ? "EDITOR" : "VIEWER",
  );
  return (
    <li className="space-y-2 rounded-lg border p-3 text-sm">
      <p className="break-all font-medium">{grant.email}</p>
      <p>
        Direct: {roleLabel(grant.role)} · Effective here: {effective}
      </p>
      <p className="break-all text-xs text-muted-foreground">
        Recipient ID: {grant.granteeUserId}
      </p>
      <p className="text-xs text-muted-foreground">
        Created: {time(grant.createdAt)} · Updated: {time(grant.updatedAt)}
      </p>
      <label className="block space-y-1 break-all">
        <span>Direct role for {grant.email}</span>
        <RoleSelect value={role} setValue={setRole} disabled={disabled} />
      </label>
      <div className="flex flex-wrap gap-2">
        <Button
          size="sm"
          variant="outline"
          aria-label={`Save role for ${grant.email}`}
          disabled={disabled || role === grant.role}
          onClick={() => change(role)}
        >
          Save role
        </Button>
        <Button
          size="sm"
          variant="outline"
          aria-label={`Revoke access for ${grant.email}`}
          disabled={disabled}
          onClick={revoke}
        >
          Revoke access
        </Button>
      </div>
    </li>
  );
}
