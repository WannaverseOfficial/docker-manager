"use client";

import { Suspense, useEffect, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { getSession, login } from "@/lib/api";
import { useSession } from "@/components/app";
import { Button, Field, Notice, inputClass } from "@/components/ui";

export default function LoginPage() {
  return (
    <Suspense>
      <Login />
    </Suspense>
  );
}

/** Only follow same-site paths after signing in (never an absolute URL from the query string). */
function safeNext(next: string | null) {
  if (!next || !next.startsWith("/") || next.startsWith("//") || next.startsWith("/login")) return "/";
  return next;
}

function Login() {
  const router = useRouter();
  const params = useSearchParams();
  const { session, ready } = useSession();
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const next = safeNext(params.get("next"));

  useEffect(() => {
    if (ready && session) router.replace(session.mustChangePassword ? "/account/?first=1" : next);
  }, [ready, session, router, next]);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await login(username.trim(), password);
      const s = getSession();
      router.replace(s?.mustChangePassword ? "/account/?first=1" : next);
    } catch (err) {
      setError((err as Error).message === "Invalid credentials" ? "That username and password don't match." : (err as Error).message);
      setBusy(false);
    }
  };

  return (
    <div className="min-h-dvh grid lg:grid-cols-[1.15fr_1fr]">
      <div className="relative h-44 sm:h-60 lg:h-auto overflow-hidden bg-well">
        {/* eslint-disable-next-line @next/next/no-img-element */}
        <img src="/art/harbour.webp" alt="" className="absolute inset-0 size-full object-cover" />
        <div className="absolute inset-0 bg-gradient-to-t lg:bg-gradient-to-r from-black/40 via-black/10 to-transparent" aria-hidden />
        <p className="hidden lg:block absolute left-10 bottom-10 max-w-sm text-white/90 text-[15px] leading-relaxed drop-shadow">
          Run, deploy and look after the containers on your servers, from one place.
        </p>
      </div>
      <div className="flex items-center justify-center px-6 py-12">
        <form onSubmit={submit} className="w-full max-w-sm" aria-labelledby="signin-title">
          <div className="font-display text-[1.6rem] leading-none mb-10">
            docker<span className="text-slate">.manager</span>
          </div>
          <h1 id="signin-title" className="font-display text-[1.6rem] leading-tight">
            Sign in
          </h1>
          <p className="text-slate mt-1.5 text-[15px]">
            First time? Use <span className="text-ink">admin</span> and the temporary password printed in the server log.
          </p>
          <div className="flex flex-col gap-4 mt-8">
            <Field label="Username">
              <input
                className={inputClass}
                value={username}
                onChange={(e) => setUsername(e.target.value)}
                autoComplete="username"
                autoCapitalize="off"
                spellCheck={false}
                required
                autoFocus
              />
            </Field>
            <Field label="Password">
              <input
                className={inputClass}
                type="password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                autoComplete="current-password"
                required
              />
            </Field>
            {error && <Notice tone="error">{error}</Notice>}
            <Button type="submit" busy={busy} disabled={!username || !password} className="mt-2 h-10">
              Sign in
            </Button>
          </div>
        </form>
      </div>
    </div>
  );
}
