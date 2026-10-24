import { Brand } from "@/components/brand";
import { ThemeToggle } from "@/components/theme-toggle";
import { FolderClosed, ShieldCheck, Sparkles } from "lucide-react";
export default function AuthLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <div className="flex min-h-svh flex-col">
      <header className="flex items-center justify-between px-6 py-6 sm:px-10">
        <Brand />
        <ThemeToggle />
      </header>
      <main className="mx-auto grid w-full max-w-6xl flex-1 items-center gap-16 px-6 py-12 lg:grid-cols-2 lg:px-10">
        <section className="hidden max-w-lg lg:block">
          <div className="mb-8 inline-flex items-center gap-2 rounded-full border bg-card px-3 py-1.5 text-xs text-muted-foreground">
            <ShieldCheck className="size-3.5 text-primary" aria-hidden="true" />
            A little more peace of mind
          </div>
          <p className="text-5xl leading-[1.12] font-semibold tracking-tight">
            A clear space.
            <br />
            <span className="text-primary">
              For everything
              <br />
              that matters.
            </span>
          </p>
          <p className="mt-6 max-w-sm text-lg leading-relaxed text-muted-foreground">
            Your files deserve a thoughtful home. Keep your world together with
            VaultDrive.
          </p>
          <div
            aria-hidden="true"
            className="relative mt-12 flex h-36 items-center gap-5"
          >
            <div className="-rotate-6 rounded-2xl border bg-card p-7 shadow-sm">
              <FolderClosed
                className="size-14 text-primary/70"
                strokeWidth={1.2}
              />
            </div>
            <div className="rotate-6 rounded-2xl border bg-card p-7 shadow-sm">
              <Sparkles className="size-14 text-primary/50" strokeWidth={1.2} />
            </div>
          </div>
        </section>
        <section className="mx-auto w-full max-w-md rounded-2xl border bg-card p-7 shadow-sm sm:p-10">
          {children}
        </section>
      </main>
      <footer className="px-6 py-6 text-center text-xs text-muted-foreground">
        Your space, thoughtfully connected.
      </footer>
    </div>
  );
}
