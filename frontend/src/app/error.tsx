"use client";
import Link from "next/link";
import { Button } from "@/components/ui/button";
export default function ErrorPage({
  reset,
}: {
  error: Error & { digest?: string };
  reset: () => void;
}) {
  return (
    <main className="grid min-h-svh place-content-center gap-5 p-8 text-center">
      <h1 className="text-2xl font-semibold">
        We couldn’t open your workspace
      </h1>
      <p
        role="alert"
        className="max-w-md text-sm leading-6 text-muted-foreground"
      >
        VaultDrive may be temporarily unavailable. Please try again in a moment.
      </p>
      <div className="flex justify-center gap-4">
        <Button onClick={reset}>Try again</Button>
        <Button variant="outline" render={<Link href="/login" />}>
          Sign in
        </Button>
      </div>
    </main>
  );
}
