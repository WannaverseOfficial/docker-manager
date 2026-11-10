"use client";

import { Suspense, useEffect, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { useHost } from "@/components/app";
import { Button, CodeEditor, Field, Notice, PageHeader, Panel, Spinner, inputClass, useToast } from "@/components/ui";
import { api } from "@/lib/api";
import type { Template } from "@/lib/types";

const STARTER = `services:
  web:
    image: nginx:alpine
    ports:
      - "8080:80"
    restart: unless-stopped
`;

const NAME_RE = /^[a-z0-9][a-z0-9_-]{0,62}$/;

export default function NewStackPage() {
  return (
    <Suspense>
      <NewStack />
    </Suspense>
  );
}

function NewStack() {
  const { hostId } = useHost();
  const params = useSearchParams();
  const router = useRouter();
  const toast = useToast();
  const templateId = params.get("template");
  const [template, setTemplate] = useState<Template | null>(null);
  const [name, setName] = useState("");
  const [compose, setCompose] = useState(templateId ? "" : STARTER);
  const [env, setEnv] = useState("");
  const [busy, setBusy] = useState<"save" | "deploy" | "check" | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [checked, setChecked] = useState<string | null>(null);

  useEffect(() => {
    if (!templateId) return;
    api
      .get<Template>(`/api/templates/${templateId}`)
      .then((t) => {
        setTemplate(t);
        setCompose(t.composeContent ?? "");
        setName(t.name.toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-|-$/g, ""));
        // Variables the template asks for, so they can be filled in before deploying.
        const vars = [...(t.composeContent ?? "").matchAll(/\$\{([A-Z0-9_]+)(?::?[-?][^}]*)?\}/g)].map((m) => m[1]);
        const unique = [...new Set(vars)];
        if (unique.length) setEnv(unique.map((v) => `${v}=`).join("\n") + "\n");
      })
      .catch((e) => setError((e as Error).message));
  }, [templateId]);

  if (!hostId) return null;
  const nameError = name && !NAME_RE.test(name) ? "Lowercase letters, digits, '-' and '_', starting with a letter or digit." : null;

  const save = async (deploy: boolean) => {
    setBusy(deploy ? "deploy" : "save");
    setError(null);
    try {
      await api.post(`/api/docker/hosts/${hostId}/stacks`, {
        name,
        composeContent: compose,
        envContent: env,
        templateName: template?.name,
        deploy,
      });
      toast(deploy ? `Deploying ${name}` : `${name} saved`);
      router.push(`/stacks/view/?name=${encodeURIComponent(name)}`);
    } catch (e) {
      setError((e as Error).message);
      setBusy(null);
    }
  };

  const check = async () => {
    setBusy("check");
    setError(null);
    setChecked(null);
    try {
      const r = await api.post<{ services: string[]; images: string[] }>(`/api/docker/hosts/${hostId}/stacks/validate`, { composeContent: compose });
      setChecked(`Looks good: ${r.services.length} service${r.services.length === 1 ? "" : "s"} (${r.services.join(", ")}).`);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(null);
    }
  };

  if (templateId && !template && !error) return <Spinner />;

  return (
    <>
      <PageHeader
        title={template ? `New ${template.name} stack` : "New stack"}
        back={{ href: "/stacks/", label: "Stacks" }}
        intro={template?.description ?? "A compose file describes the containers, networks and volumes that run together."}
      />
      <div className="flex flex-col gap-6">
        <Panel>
          <Field label="Name" error={nameError} hint="Also the Compose project name. Containers are called name-service-1.">
            <input className={`${inputClass} sm:max-w-sm`} value={name} onChange={(e) => setName(e.target.value)} placeholder="my-app" autoCapitalize="off" spellCheck={false} required />
          </Field>
        </Panel>
        <Panel title="docker-compose.yml">
          <CodeEditor label="Compose file" value={compose} onChange={(v) => (setCompose(v), setChecked(null))} rows={20} />
        </Panel>
        <Panel title=".env" action={<span className="text-[12.5px] text-slate">Variables used as $&#123;NAME&#125; in the compose file</span>}>
          <CodeEditor label=".env file" value={env} onChange={setEnv} rows={5} placeholder="POSTGRES_PASSWORD=choose-a-strong-one" />
        </Panel>
        {checked && <Notice tone="ok">{checked}</Notice>}
        {error && <Notice tone="error">{error}</Notice>}
        <div className="flex flex-wrap gap-2">
          <Button onClick={() => save(true)} busy={busy === "deploy"} disabled={!name || !!nameError || !compose.trim() || !!busy}>
            Create and deploy
          </Button>
          <Button tone="quiet" onClick={() => save(false)} busy={busy === "save"} disabled={!name || !!nameError || !compose.trim() || !!busy}>
            Save without deploying
          </Button>
          <Button tone="ghost" onClick={check} busy={busy === "check"} disabled={!compose.trim() || !!busy}>
            Check file
          </Button>
        </div>
      </div>
    </>
  );
}
