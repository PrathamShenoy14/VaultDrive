import Link from "next/link";
export default function NotFound() {
  return (
    <main className="grid min-h-svh place-content-center gap-4 p-8 text-center">
      <h1 className="text-2xl font-semibold">Page unavailable</h1>
      <p className="text-muted-foreground">This page could not be found.</p>
      <Link className="text-primary underline underline-offset-4" href="/drive">
        Return to My Drive
      </Link>
    </main>
  );
}
