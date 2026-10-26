// No credentials or user metadata cross tabs. A hard navigation discards Router Cache.
let lifetime = new AbortController();
export function sessionSignal() {
  return lifetime.signal;
}
export function pauseSession() {
  lifetime.abort();
  if (typeof window !== "undefined")
    window.dispatchEvent(new Event("vaultdrive:session-paused"));
}
export function resumeSession() {
  lifetime = new AbortController();
  if (typeof window !== "undefined")
    window.dispatchEvent(new Event("vaultdrive:session-resumed"));
}
export function announceSession(kind: "changed" | "ended") {
  if (typeof window === "undefined") return;
  if (kind === "ended") pauseSession();
  try {
    const channel = new BroadcastChannel("vaultdrive-session");
    channel.postMessage(kind);
    channel.close();
  } catch {
    /* Requests still fail closed when cross-tab messaging is unavailable. */
  }
}
