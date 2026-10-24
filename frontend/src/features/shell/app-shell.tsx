"use client";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { useState } from "react";
import { Menu, Plus, ShieldCheck } from "lucide-react";
import { Brand } from "@/components/brand";
import { ThemeToggle } from "@/components/theme-toggle";
import { Button } from "@/components/ui/button";
import { SearchBox } from "@/features/explorer/search-box";
import {
  Sheet,
  SheetContent,
  SheetTitle,
  SheetDescription,
  SheetTrigger,
} from "@/components/ui/sheet";
import { navigation } from "./navigation";
import { AccountMenu } from "./account-menu";
import type { Profile } from "@/lib/api/transport";
import { cn } from "@/lib/utils";

function Navigation({ close }: { close?: () => void }) {
  const pathname = usePathname();
  return (
    <nav aria-label="Main navigation" className="space-y-1">
      {navigation.map(({ href, label, icon: Icon }) => (
        <Link
          key={href}
          href={href}
          prefetch={false}
          onClick={close}
          aria-current={pathname === href ? "page" : undefined}
          className={cn(
            "flex min-h-11 items-center gap-3 rounded-full px-4 py-2.5 text-sm transition-colors hover:bg-muted",
            pathname === href
              ? "bg-accent font-semibold text-accent-foreground hover:bg-accent"
              : "text-muted-foreground",
          )}
        >
          <Icon className="size-5" strokeWidth={1.7} aria-hidden="true" />
          {label}
        </Link>
      ))}
    </nav>
  );
}
export function AppShell({
  profile,
  storage,
  children,
}: {
  profile: Profile;
  storage: React.ReactNode;
  children: React.ReactNode;
}) {
  const [mobile, setMobile] = useState(false);
  const side = (
    <>
      <div className="mb-7 px-3">
        <Brand />
      </div>
      <div className="mb-6 px-3">
        <Button
          variant="outline"
          disabled
          className="h-13 rounded-2xl bg-card px-5 shadow-sm"
        >
          <Plus className="size-5" aria-hidden="true" />
          New
          <span className="ml-1 text-[10px] text-muted-foreground">Later</span>
        </Button>
      </div>
      <Navigation close={() => setMobile(false)} />
      <div className="mt-auto pt-8">{storage}</div>
    </>
  );
  return (
    <div className="min-h-svh">
      <a
        href="#main"
        className="sr-only fixed top-3 left-3 z-50 rounded-lg bg-primary px-4 py-3 text-primary-foreground focus:not-sr-only"
      >
        Skip to content
      </a>
      <aside
        aria-label="Sidebar"
        className="fixed inset-y-0 left-0 hidden w-60 flex-col px-4 py-7 lg:flex"
      >
        {side}
      </aside>
      <div className="lg:ml-60">
        <header className="flex h-21 items-center gap-3 px-4 sm:px-7">
          <Sheet open={mobile} onOpenChange={setMobile}>
            <SheetTrigger
              render={
                <Button
                  variant="ghost"
                  size="icon"
                  className="lg:hidden"
                  aria-label="Open navigation"
                />
              }
            >
              <Menu aria-hidden="true" />
            </SheetTrigger>
            <SheetContent side="left" className="flex w-72 flex-col p-5">
              <SheetTitle className="sr-only">Navigation</SheetTitle>
              <SheetDescription className="sr-only">
                Navigate your VaultDrive account.
              </SheetDescription>
              {side}
            </SheetContent>
          </Sheet>
          <SearchBox />
          <div className="ml-auto flex shrink-0 items-center gap-1 sm:gap-3">
            <ThemeToggle />
            <AccountMenu profile={profile} />
          </div>
        </header>
        <div className="px-3 pb-4 sm:px-6">
          <main
            id="main"
            tabIndex={-1}
            className="min-h-[calc(100svh-6.25rem)] rounded-2xl border bg-card p-5 outline-none sm:p-8 lg:p-10"
          >
            {children}
            <footer className="mt-12 flex items-center gap-2 border-t pt-5 text-xs text-muted-foreground">
              <ShieldCheck className="size-3.5" aria-hidden="true" />
              Connected to your VaultDrive account
              <span className="ml-auto hidden sm:inline">
                Your personal workspace
              </span>
            </footer>
          </main>
        </div>
      </div>
    </div>
  );
}
