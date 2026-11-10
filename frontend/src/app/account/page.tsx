"use client";

import { Suspense, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { api, changePassword, logout } from "@/lib/api";
import { useSession } from "@/components/app";
import { Button, Field, Notice, PageHeader, Panel, inputClass, useToast } from "@/components/ui";

export default function AccountPage() {
  return (
    <Suspense>
      <Account />
    </Suspense>
  );
}

function strength(p: string): { ok: boolean; hint: string } {
  if (p.length < 8) return { ok: false, hint: "At least 8 characters." };
  if (p.length < 12 && !(/[A-Z]/.test(p) && /[a-z]/.test(p) && /\d/.test(p))) {
    return { ok: true, hint: "Allowed. A longer passphrase is stronger." };
  }
  return { ok: true, hint: "Good." };
}

function Account() {
  const { session } = useSession();
  const router = useRouter();
  const first = useSearchParams().get("first") === "1" || !!session?.mustChangePassword;
  const toast = useToast();
  const [current, setCurrent] = useState("");
  const [next, setNext] = useState("");
  const [again, setAgain] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const s = strength(next);
  const mismatch = again.length > 0 && again !== next;

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!s.ok || next !== again) return;
    setBusy(true);
    setError(null);
    try {
      await changePassword(current, next);
      toast("Password changed. Other sessions were signed out.");
      setCurrent("");
      setNext("");
      setAgain("");
      if (first) router.replace("/");
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setBusy(false);
    }
  };

  const form = (
    <form onSubmit={submit} className="flex flex-col gap-4 max-w-md">
      <input type="text" autoComplete="username" value={session?.username ?? ""} readOnly hidden />
      <Field label={first ? "Temporary password" : "Current password"}>
        <input className={inputClass} type="password" value={current} onChange={(e) => setCurrent(e.target.value)} autoComplete="current-password" required />
      </Field>
      <Field label="New password" hint={next ? s.hint : "At least 8 characters. A passphrase of a few words is easy to remember and hard to guess."}>
        <input className={inputClass} type="password" value={next} onChange={(e) => setNext(e.target.value)} autoComplete="new-password" required minLength={8} />
      </Field>
      <Field label="New password again" error={mismatch ? "The passwords don't match." : null}>
        <input className={inputClass} type="password" value={again} onChange={(e) => setAgain(e.target.value)} autoComplete="new-password" required />
      </Field>
      {error && <Notice tone="error">{error}</Notice>}
      <div>
        <Button type="submit" busy={busy} disabled={!current || !s.ok || next !== again}>
          {first ? "Set password and continue" : "Change password"}
        </Button>
      </div>
    </form>
  );

  if (first) {
    return (
      <div className="min-h-dvh flex items-center justify-center px-6 py-12">
        <div className="w-full max-w-md">
          <div className="font-display text-[1.6rem] leading-none mb-10">
            docker<span className="text-slate">.manager</span>
          </div>
          <h1 className="font-display text-[1.6rem] leading-tight">Choose your password</h1>
          <p className="text-slate mt-1.5 mb-8 text-[15px]">
            You signed in with a temporary password. Pick your own to continue, {session?.username}.
          </p>
          {form}
        </div>
      </div>
    );
  }

  return (
    <>
      <PageHeader title="Your account" intro={`Signed in as ${session?.username}${session?.admin ? ", administrator" : ""}.`} />
      <div className="grid gap-6 lg:grid-cols-2">
        <Panel title="Password">{form}</Panel>
        <Panel title="Sessions">
          <p className="text-[14px] text-slate mb-4">
            Signs you out on every browser and device, including this one. Use it if you signed in somewhere you don&apos;t trust.
          </p>
          <Button
            tone="quiet"
            onClick={async () => {
              await api.post("/api/auth/logout-all");
              await logout();
              router.replace("/login/");
            }}
          >
            Sign out everywhere
          </Button>
        </Panel>
      </div>
    </>
  );
}
