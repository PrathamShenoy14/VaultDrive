import { AuthForm } from "@/features/auth/auth-form";
export const metadata = { title: "Create account" };
export default function Register() {
  return (
    <>
      <div className="mb-8">
        <p className="mb-2 text-xs font-medium tracking-widest text-primary uppercase">
          Make room
        </p>
        <h1 className="text-3xl font-semibold tracking-tight">
          Create your space
        </h1>
        <p className="mt-3 text-sm text-muted-foreground">
          A fresh start for everything you keep.
        </p>
      </div>
      <AuthForm mode="register" />
    </>
  );
}
