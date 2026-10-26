"use client";
/* eslint-disable @next/next/no-location-assign-relative-destination -- Clear all personalized Router Cache on session changes. */
import { useEffect, useState } from "react";
import { pauseSession } from "@/features/auth/client-session";
export function SessionBoundary({ children }: { children: React.ReactNode }) {
  const [paused, setPaused] = useState(false);
  useEffect(() => {
    const pause = () => setPaused(true);
    const resume = () => setPaused(false);
    const expired = () => {
      pauseSession();
      window.location.assign("/login?reason=session");
    };
    window.addEventListener("vaultdrive:session-paused", pause);
    window.addEventListener("vaultdrive:session-resumed", resume);
    window.addEventListener("vaultdrive:session-expired", expired);
    let channel: BroadcastChannel | undefined;
    try {
      channel = new BroadcastChannel("vaultdrive-session");
      channel.onmessage = ({ data }) => {
        if (data === "ended") expired();
        else if (data === "changed") {
          pauseSession();
          window.location.reload();
        }
      };
    } catch {
      /* The next authenticated request remains authoritative. */
    }
    return () => {
      channel?.close();
      window.removeEventListener("vaultdrive:session-paused", pause);
      window.removeEventListener("vaultdrive:session-resumed", resume);
      window.removeEventListener("vaultdrive:session-expired", expired);
    };
  }, []);
  return (
    <>
      {paused && (
        <div className="p-8" role="status">
          Updating your session…
        </div>
      )}
      <div hidden={paused}>{children}</div>
    </>
  );
}
