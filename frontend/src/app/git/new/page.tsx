"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { useHost } from "@/components/app";
import { GitForm, gitBody, gitFormFrom } from "@/components/GitForm";
import { PageHeader, useToast } from "@/components/ui";
import { api } from "@/lib/api";
import type { GitRepo } from "@/lib/types";

export default function NewRepoPage() {
  const { hostId } = useHost();
  const router = useRouter();
  const toast = useToast();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  if (!hostId) return null;
  return (
    <>
      <PageHeader title="Add a repository" back={{ href: "/git/", label: "Git" }} intro="Docker Manager checks out the branch and deploys it on the host you choose." />
      <GitForm
        initial={gitFormFrom(null, hostId)}
        busy={busy}
        error={error}
        submitLabel="Add repository"
        onSubmit={async (v) => {
          setBusy(true);
          setError(null);
          try {
            const r = await api.post<GitRepo>("/api/git/repositories", gitBody(v));
            toast(`${r.name} added`);
            router.push(`/git/view/?id=${r.id}`);
          } catch (e) {
            setError((e as Error).message);
            setBusy(false);
          }
        }}
      />
    </>
  );
}
