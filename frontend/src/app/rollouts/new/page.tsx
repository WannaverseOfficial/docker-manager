"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { useHost } from "@/components/app";
import { RolloutForm, rolloutBody, rolloutFormFrom } from "@/components/RolloutForm";
import { PageHeader, useToast } from "@/components/ui";
import { api } from "@/lib/api";
import type { Rollout } from "@/lib/types";

export default function NewRolloutPage() {
  const { hostId } = useHost();
  const router = useRouter();
  const toast = useToast();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  if (!hostId) return null;
  return (
    <>
      <PageHeader
        title="New rollout"
        back={{ href: "/rollouts/", label: "Rollouts" }}
        intro="Release new versions of an app next to the current one, test them, and move visitors over safely. Needs ingress turned on for the host."
      />
      <RolloutForm
        initial={rolloutFormFrom(null, hostId)}
        busy={busy}
        error={error}
        submitLabel="Create rollout"
        onSubmit={async (v) => {
          setBusy(true);
          setError(null);
          try {
            const r = await api.post<Rollout>("/api/rollouts", rolloutBody(v));
            toast(`${r.name} created`);
            router.push(`/rollouts/view/?id=${r.id}`);
          } catch (e) {
            setError((e as Error).message);
            setBusy(false);
          }
        }}
      />
    </>
  );
}
