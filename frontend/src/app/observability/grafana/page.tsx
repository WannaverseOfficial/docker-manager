"use client";

import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { Suspense, useEffect, useRef, useState } from "react";
import { useSession } from "@/components/app";
import { Notice, PageHeader, Spinner } from "@/components/ui";
import { api, enc } from "@/lib/api";

/**
 * Signs an administrator in to Grafana: Docker Manager signs a short-lived token, and the browser
 * takes it to Grafana's address, where the ingress proxy keeps it in a cookie. Grafana sends people
 * here when they open it without a valid token.
 */
export default function GrafanaSignInPage() {
  return (
    <Suspense>
      <GrafanaSignIn />
    </Suspense>
  );
}

function GrafanaSignIn() {
  const params = useSearchParams();
  const { session } = useSession();
  const host = params.get("host");
  const next = params.get("next");
  const [target, setTarget] = useState<{ action: string; token: string } | null>(null);
  const [failure, setError] = useState<string | null>(null);
  const started = useRef(false);
  const error = session && !session.admin ? "Only administrators can open Grafana: it shows every container's logs and metrics." : failure;

  useEffect(() => {
    if (!session?.admin || !host || started.current) return;
    started.current = true;
    api
      .post<{ action: string; token: string }>(`/api/observability/hosts/${enc(host)}/sign-in`)
      .then(setTarget)
      .catch((e) => setError((e as Error).message));
  }, [session, host]);

  useEffect(() => {
    if (!target) return;
    // Only a path on Grafana's own address; the proxy checks again.
    const path = next && next.startsWith("/") && !next.startsWith("//") && !/[\x00-\x1f\\]/.test(next) ? next : "/";
    // The token travels in the fragment, which browsers don't send to servers; the proxy's page
    // reads it and posts it to itself. replace() keeps this page out of the back button.
    window.location.replace(`${target.action}#${new URLSearchParams({ token: target.token, next: path })}`);
  }, [target, next]);

  return (
    <>
      <PageHeader title="Opening Grafana" />
      {!host && <Notice tone="error">This link is missing the host. Open Grafana from the Observability page.</Notice>}
      {error && (
        <Notice tone="error" title="Grafana can't be opened">
          {error}{" "}
          <Link href="/observability/" className="underline underline-offset-2">
            Back to Observability
          </Link>
        </Notice>
      )}
      {host && !error && <Spinner label="Signing you in to Grafana" />}
    </>
  );
}
