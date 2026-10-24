import { AuthForm } from "@/features/auth/auth-form";
export const metadata = { title: "Sign in" };
export default async function Login({
  searchParams,
}: {
  searchParams: Promise<{ reason?: string }>;
}) {
  const { reason } = await searchParams;
  return (
    <>
      <div className="mb-8">
        <p className="mb-2 text-xs font-medium tracking-widest text-primary uppercase">
          Welcome back
        </p>
        <h1 className="text-3xl font-semibold tracking-tight">
          Sign in to your space
        </h1>
        <p className="mt-3 text-sm text-muted-foreground">
          Pick up where you left off.
        </p>
      </div>
      {reason === "session" && (
        <p role="status" className="mb-5 rounded-lg bg-muted p-3 text-sm">
          Please sign in to continue.
        </p>
      )}
      <AuthForm mode="login" />
    </>
  );
}
