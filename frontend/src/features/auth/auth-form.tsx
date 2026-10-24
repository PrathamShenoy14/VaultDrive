"use client";
/* eslint-disable @next/next/no-location-assign-relative-destination -- Authentication transitions must discard the browser Router Cache. */
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { useForm } from "react-hook-form";
import { ArrowRight, Eye, EyeOff, LoaderCircle } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  authRequest,
  errorMessage,
  ApiError,
  type RegisterInput,
} from "@/lib/api/transport";
import { loginSchema, registerSchema } from "./schema";

export function AuthForm({ mode }: { mode: "login" | "register" }) {
  const router = useRouter();
  const [visible, setVisible] = useState(false);
  const [success, setSuccess] = useState(false);
  const {
    register,
    handleSubmit,
    setError,
    formState: { errors, isSubmitting },
  } = useForm<RegisterInput>();
  const signup = mode === "register";
  const submit = handleSubmit(async (values) => {
    const parsed = (signup ? registerSchema : loginSchema).safeParse(values);
    if (!parsed.success) {
      for (const issue of parsed.error.issues)
        setError(issue.path[0] as keyof RegisterInput, {
          message: issue.message,
        });
      return;
    }
    try {
      if (signup) {
        await authRequest("register", parsed.data as RegisterInput);
        setSuccess(true);
      } else {
        await authRequest("login", parsed.data);
        window.location.assign("/drive");
      }
    } catch (error) {
      const message =
        error instanceof ApiError && !signup && error.status === 401
          ? "Email or password is incorrect."
          : error instanceof ApiError && signup && error.status === 409
            ? "An account with this email already exists. Try signing in."
            : errorMessage(error);
      setError("root", { message });
    }
  });
  if (success)
    return (
      <div className="space-y-5" role="status">
        <h2 className="text-2xl font-semibold">Your account is ready</h2>
        <p className="text-muted-foreground">
          Sign in with your email and password to open VaultDrive.
        </p>
        <Button className="w-full" onClick={() => router.push("/login")}>
          Continue to sign in
          <ArrowRight aria-hidden="true" />
        </Button>
      </div>
    );
  return (
    <form method="post" onSubmit={submit} noValidate className="space-y-5">
      {signup && (
        <div className="space-y-2">
          <Label htmlFor="displayName">Your name</Label>
          <Input
            id="displayName"
            autoComplete="name"
            maxLength={100}
            aria-invalid={!!errors.displayName}
            aria-describedby={errors.displayName ? "name-error" : undefined}
            {...register("displayName")}
          />
          {errors.displayName && (
            <p id="name-error" className="text-sm text-destructive">
              {errors.displayName.message}
            </p>
          )}
        </div>
      )}
      <div className="space-y-2">
        <Label htmlFor="email">Email address</Label>
        <Input
          id="email"
          type="email"
          autoComplete="email"
          autoCapitalize="none"
          spellCheck={false}
          aria-invalid={!!errors.email}
          aria-describedby={errors.email ? "email-error" : undefined}
          {...register("email")}
        />
        {errors.email && (
          <p id="email-error" className="text-sm text-destructive">
            {errors.email.message}
          </p>
        )}
      </div>
      <div className="space-y-2">
        <Label htmlFor="password">Password</Label>
        <div className="relative">
          <Input
            id="password"
            type={visible ? "text" : "password"}
            className="pr-12"
            autoComplete={signup ? "new-password" : "current-password"}
            aria-invalid={!!errors.password}
            aria-describedby={
              errors.password
                ? "password-error"
                : signup
                  ? "password-hint"
                  : undefined
            }
            {...register("password")}
          />
          <Button
            type="button"
            variant="ghost"
            size="icon"
            className="absolute top-0 right-0"
            aria-label={visible ? "Hide password" : "Show password"}
            aria-pressed={visible}
            onClick={() => setVisible(!visible)}
          >
            {visible ? (
              <EyeOff aria-hidden="true" />
            ) : (
              <Eye aria-hidden="true" />
            )}
          </Button>
        </div>
        {signup && (
          <p id="password-hint" className="text-xs text-muted-foreground">
            At least 12 characters. Maximum 72 UTF-8 bytes.
          </p>
        )}
        {errors.password && (
          <p id="password-error" className="text-sm text-destructive">
            {errors.password.message}
          </p>
        )}
      </div>
      {errors.root && (
        <p
          role="alert"
          className="rounded-lg border border-destructive/25 bg-destructive/5 p-3 text-sm text-destructive"
        >
          {errors.root.message}
        </p>
      )}
      <Button type="submit" className="h-11 w-full" disabled={isSubmitting}>
        {isSubmitting ? (
          <LoaderCircle className="animate-spin" aria-hidden="true" />
        ) : null}
        {isSubmitting ? "Please wait…" : signup ? "Create account" : "Sign in"}
        {!isSubmitting && <ArrowRight aria-hidden="true" />}
      </Button>
      <p className="text-center text-sm text-muted-foreground">
        {signup ? "Already have an account?" : "New to VaultDrive?"}{" "}
        <Link
          className="font-medium text-primary underline-offset-4 hover:underline"
          href={signup ? "/login" : "/register"}
        >
          {signup ? "Sign in" : "Create an account"}
        </Link>
      </p>
    </form>
  );
}
