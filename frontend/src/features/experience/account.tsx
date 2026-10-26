"use client";
import { useEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Storage, formatBytes } from "@/features/shell/storage";
import {
  errorMessage,
  normalizeUsage,
  type Profile,
  type Usage,
} from "@/lib/api/transport";
import { experienceRequest } from "./api";
import { ReadState, useExperienceRead } from "./feed";
import { timestamp } from "./contracts";
function ProfileForm({
  profile,
  refreshed,
}: {
  profile: Profile;
  refreshed: () => void;
}) {
  const router = useRouter();
  const [name, setName] = useState(profile.displayName),
    [busy, setBusy] = useState(false),
    [notice, setNotice] = useState("");
  const [error, setError] = useState<unknown>();
  const active = useRef<AbortController | null>(null);
  useEffect(() => () => active.current?.abort(), []);
  async function save(event: React.FormEvent) {
    event.preventDefault();
    if (active.current) return;
    const value = name.trim();
    if (
      !value ||
      value.length > 100 ||
      /[\u0000-\u001f\u007f-\u009f]/.test(value)
    ) {
      setNotice("Enter a name of 1–100 characters without control characters.");
      return;
    }
    const controller = new AbortController();
    active.current = controller;
    setBusy(true);
    setNotice("");
    setError(undefined);
    try {
      await experienceRequest<Profile>(
        "users/me",
        controller.signal,
        { displayName: value },
        "PATCH",
      );
      if (!controller.signal.aborted) {
        router.refresh();
        refreshed();
      }
    } catch (error) {
      if (!controller.signal.aborted) setError(error);
    } finally {
      if (!controller.signal.aborted) setBusy(false);
      active.current = null;
    }
  }
  return (
    <form onSubmit={save} className="space-y-4">
      <dl className="space-y-3 text-sm">
        <div>
          <dt className="text-muted-foreground">Email</dt>
          <dd className="break-all">{profile.email}</dd>
        </div>
        <div>
          <dt className="text-muted-foreground">Account ID</dt>
          <dd className="break-all">{profile.userId}</dd>
        </div>
        <div>
          <dt className="text-muted-foreground">Registered</dt>
          <dd>{timestamp(profile.createdAt)}</dd>
        </div>
        <div>
          <dt className="text-muted-foreground">Profile updated</dt>
          <dd>{timestamp(profile.updatedAt)}</dd>
        </div>
      </dl>
      <div className="space-y-2">
        <Label htmlFor="display-name">Display name</Label>
        <Input
          id="display-name"
          value={name}
          maxLength={100}
          required
          onChange={(event) => setName(event.target.value)}
          autoComplete="name"
        />
      </div>
      <Button type="submit" disabled={busy || error !== undefined}>
        {busy ? "Saving…" : "Save display name"}
      </Button>
      {notice && (
        <p role="status" className="text-sm">
          {notice}
        </p>
      )}
      {error !== undefined && (
        <p role="alert" className="text-sm">
          {errorMessage(error)} The change may be unconfirmed. Refresh your
          account before submitting again.
        </p>
      )}
    </form>
  );
}
export function AccountPage() {
  const profile = useExperienceRead<Profile>("users/me");
  const storage = useExperienceRead<Usage>("users/me/storage");
  const [saved, setSaved] = useState(false);
  useEffect(() => {
    let timer: ReturnType<typeof setTimeout> | undefined;
    const changed = () => {
      clearTimeout(timer);
      timer = setTimeout(storage.reload, 500);
    };
    const events = [
      "vaultdrive:transfers-changed",
      "vaultdrive:management-changed",
    ];
    events.forEach((event) => window.addEventListener(event, changed));
    return () => {
      clearTimeout(timer);
      events.forEach((event) => window.removeEventListener(event, changed));
    };
    // Refresh after existing workflow invalidations, coalescing progress bursts.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);
  const usage = storage.value ? normalizeUsage(storage.value) : undefined;
  return (
    <>
      <h1 className="text-2xl font-semibold">Account settings</h1>
      <p className="mt-2 text-sm text-muted-foreground">
        Manage your display name and review your logical storage. Times are UTC.
      </p>
      <Button
        className="my-5"
        variant="outline"
        onClick={() => {
          setSaved(false);
          profile.reload();
          storage.reload();
        }}
      >
        Refresh account and storage
      </Button>
      {saved && (
        <p role="status" className="mb-4 text-sm">
          Display name saved.
        </p>
      )}
      <div className="grid min-w-0 gap-8 xl:grid-cols-2">
        <section
          aria-label="Account information"
          className="min-w-0 rounded-xl border p-5"
        >
          <h2 className="mb-4 text-lg font-medium">Profile</h2>
          <ReadState
            loading={profile.loading}
            error={profile.error}
            retry={profile.reload}
          />
          {profile.value && (
            <ProfileForm
              key={profile.value.updatedAt}
              profile={profile.value}
              refreshed={() => {
                setSaved(true);
                profile.reload();
              }}
            />
          )}
          <p className="mt-5 text-xs text-muted-foreground">
            Email, password and account deletion cannot be changed here. Use the
            account menu to sign out of this browser session.
          </p>
        </section>
        <section
          aria-label="Quota details"
          className="min-w-0 rounded-xl border p-5"
        >
          <h2 className="mb-4 text-lg font-medium">Storage and reservations</h2>
          <ReadState
            loading={storage.loading}
            error={storage.error}
            retry={storage.reload}
          />
          {usage && (
            <>
              <Storage usage={usage} label="Account logical storage" />
              <p className="mt-4 text-xs text-muted-foreground">
                Used storage covers completed files, including Trash.
                Reservations cover pending uploads. Trash and restoration do not
                release the charge. Metadata removal can release logical quota;
                it does not prove physical reclamation.
              </p>
              <dl className="mt-4 space-y-3 text-sm">
                <div>
                  <dt>Uncertain storage estimate</dt>
                  <dd title={`${usage.uncertainStorageBytes} bytes`}>
                    {formatBytes(usage.uncertainStorageBytes)}
                  </dd>
                </div>
                <div>
                  <dt>Uncertain allocations</dt>
                  <dd>{usage.uncertainStorageAllocations.toString()}</dd>
                </div>
                <div>
                  <dt>Allocations with unknown size</dt>
                  <dd>{usage.unknownSizeAllocations.toString()}</dd>
                </div>
              </dl>
              <p className="mt-3 text-xs text-muted-foreground">
                Uncertain estimates may overlap reservations. They are not added
                to your quota charge.
              </p>
            </>
          )}
        </section>
      </div>
    </>
  );
}
