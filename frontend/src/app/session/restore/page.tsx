"use client";
import { useEffect, useState } from "react";
import Link from "next/link";
import { LoaderCircle } from "lucide-react";
import { authRequest, errorMessage } from "@/lib/api/transport";
// Across tabs/StrictMode, duplicate POSTs join the same server session lock.
export default function Restore() {
  const [error, setError] = useState<string>();
  useEffect(() => {
    let active = true;
    authRequest("refresh")
      .then(() => {
        if (active) window.location.replace("/drive");
      })
      .catch((e) => {
        if (active) setError(errorMessage(e));
      });
    return () => {
      active = false;
    };
  }, []);
  return (
    <main className="grid min-h-svh place-content-center gap-4 p-8 text-center">
      {error ? (
        <>
          <h1 className="text-xl font-semibold">Sign in to continue</h1>
          <p role="alert" className="max-w-md text-muted-foreground">
            {error}
          </p>
          <Link
            href="/login"
            className="text-primary underline underline-offset-4"
          >
            Return to sign in
          </Link>
        </>
      ) : (
        <>
          <LoaderCircle
            aria-hidden="true"
            className="mx-auto animate-spin text-primary"
          />
          <h1 role="status">Restoring your session…</h1>
        </>
      )}
    </main>
  );
}
