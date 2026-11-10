"use client";

import { Suspense, useEffect, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { useHost } from "@/components/app";
import { ContainerForm, emptySpec, specFromTemplate } from "@/components/ContainerForm";
import { Notice, PageHeader, Spinner, useToast } from "@/components/ui";
import { api } from "@/lib/api";
import type { ContainerSpec, Template } from "@/lib/types";

export default function NewContainerPage() {
  return (
    <Suspense>
      <NewContainer />
    </Suspense>
  );
}

function NewContainer() {
  const { hostId } = useHost();
  const params = useSearchParams();
  const router = useRouter();
  const toast = useToast();
  const templateId = params.get("template");
  const [initial, setInitial] = useState<ContainerSpec | null>(templateId ? null : emptySpec());
  const [template, setTemplate] = useState<Template | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!templateId) return;
    api
      .get<Template>(`/api/templates/${templateId}`)
      .then((t) => {
        setTemplate(t);
        setInitial(specFromTemplate(t));
      })
      .catch((e) => setError((e as Error).message));
  }, [templateId]);

  if (!hostId) return null;

  const create = async (spec: ContainerSpec) => {
    setBusy(true);
    setError(null);
    try {
      const r = await api.post<{ id: string }>(`/api/docker/hosts/${hostId}/containers`, spec);
      toast(spec.start ? `${spec.name || "Container"} is starting` : "Container created");
      router.push(`/containers/view/?id=${r.id}`);
    } catch (e) {
      setError((e as Error).message);
      setBusy(false);
    }
  };

  return (
    <>
      <PageHeader
        title={template ? `New ${template.name} container` : "New container"}
        back={{ href: "/containers/", label: "Containers" }}
        intro={
          template ? (
            <>
              {template.description}{" "}
              {template.documentation && (
                <a href={template.documentation} target="_blank" rel="noreferrer" className="underline underline-offset-2">
                  Documentation
                </a>
              )}
            </>
          ) : (
            "The image is pulled if this host doesn't have it yet. Everything can be changed later."
          )
        }
      />
      {template && /change|your-|xxxx/i.test(template.defaultEnv ?? "") && (
        <div className="mb-6">
          <Notice tone="warn">This template has placeholder values (like passwords). Replace them before creating it.</Notice>
        </div>
      )}
      {!initial && !error && <Spinner />}
      {!initial && error && <Notice tone="error">{error}</Notice>}
      {initial && <ContainerForm initial={initial} hostId={hostId} submitLabel="Create container" busy={busy} error={error} onSubmit={create} />}
    </>
  );
}
