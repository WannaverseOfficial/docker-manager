"use client";

import { useState } from "react";
import { useSession } from "@/components/app";
import { HostedRegistry } from "@/components/HostedRegistry";
import { Badge, Button, Empty, Field, Menu, Modal, Notice, PageHeader, Select, Spinner, Tabs, Toggle, inputClass, useConfirm, useToast } from "@/components/ui";
import { api } from "@/lib/api";
import { usePoll } from "@/lib/hooks";
import type { Registry } from "@/lib/types";

const TYPES: Record<Registry["registryType"], { label: string; url: string; user: string; secret: string }> = {
  DOCKER_HUB: { label: "Docker Hub", url: "https://index.docker.io/v1/", user: "Username", secret: "Access token" },
  PRIVATE: { label: "Other registry (GitHub, GitLab, Harbor…)", url: "ghcr.io", user: "Username", secret: "Password or token" },
  AWS_ECR: { label: "Amazon ECR", url: "123456789012.dkr.ecr.eu-west-1.amazonaws.com", user: "", secret: "Token from aws ecr get-login-password" },
  GCR: { label: "Google Artifact Registry", url: "europe-docker.pkg.dev", user: "", secret: "Service account JSON key" },
  ACR: { label: "Azure Container Registry", url: "myregistry.azurecr.io", user: "Client ID", secret: "Client secret" },
};

export default function RegistriesPage() {
  const { session } = useSession();
  const [tab, setTab] = useState<"signins" | "own">("signins");
  return (
    <>
      <PageHeader
        title="Registries"
        intro={
          tab === "signins"
            ? "Sign-ins for private images. They are used automatically when an image comes from that registry, and only for it."
            : "Host a private registry for your own images on this server."
        }
      />
      {session?.admin && (
        <Tabs
          value={tab}
          onChange={setTab}
          tabs={[
            { value: "signins", label: "Sign-ins" },
            { value: "own", label: "Your own registry" },
          ]}
        />
      )}
      {tab === "signins" ? <SignIns /> : <HostedRegistry />}
    </>
  );
}

function SignIns() {
  const { data, error, refresh } = usePoll<Registry[]>("/api/registries");
  const [editing, setEditing] = useState<Registry | "new" | null>(null);
  const toast = useToast();
  const confirm = useConfirm();
  return (
    <>
      <div className="flex justify-end mb-4">
        <Button onClick={() => setEditing("new")}>Add registry</Button>
      </div>
      {error && <Notice tone="error">{error}</Notice>}
      {!data && !error && <Spinner />}
      {data && data.length === 0 && <Empty title="No registries">Public images need none. Add one to pull private images.</Empty>}
      {data && data.length > 0 && (
        <ul className="rounded-2xl border border-line bg-paper divide-y divide-line">
          {data.map((r) => (
            <li key={r.id} className={`flex items-center gap-3 px-4 py-3 ${r.enabled ? "" : "opacity-60"}`}>
              <div className="flex-1 min-w-0">
                <div className="flex flex-wrap items-center gap-2">
                  <span className="font-medium">{r.name}</span>
                  <Badge>{TYPES[r.registryType]?.label.split(" (")[0]}</Badge>
                  {!r.enabled && <Badge>off</Badge>}
                  {!r.hasCredentials && <Badge tone="warn">no sign-in</Badge>}
                </div>
                <div className="text-[13px] text-slate truncate font-mono">{r.url}</div>
              </div>
              <Button
                tone="quiet"
                size="sm"
                onClick={async () => {
                  try {
                    const res = await api.post<{ success: boolean; message: string }>(`/api/registries/${r.id}/test`);
                    toast(res.success ? `Signed in to ${r.name}` : `Sign-in failed: ${res.message}`, res.success ? "ok" : "error");
                  } catch (e) {
                    toast((e as Error).message, "error");
                  }
                }}
              >
                Test
              </Button>
              <Menu
                items={[
                  { label: "Edit", onClick: () => setEditing(r) },
                  {
                    label: "Delete",
                    danger: true,
                    onClick: async () => {
                      if (!(await confirm({ title: `Delete ${r.name}?`, confirm: "Delete", danger: true }))) return;
                      await api.del(`/api/registries/${r.id}`);
                      refresh();
                    },
                  },
                ]}
              />
            </li>
          ))}
        </ul>
      )}
      {editing && <RegistryDialog registry={editing === "new" ? null : editing} onClose={() => setEditing(null)} onSaved={() => (setEditing(null), refresh())} />}
    </>
  );
}

function RegistryDialog({ registry, onClose, onSaved }: { registry: Registry | null; onClose: () => void; onSaved: () => void }) {
  const [type, setType] = useState<Registry["registryType"]>(registry?.registryType ?? "DOCKER_HUB");
  const [name, setName] = useState(registry?.name ?? "");
  const [url, setUrl] = useState(registry?.url ?? TYPES.DOCKER_HUB.url);
  const [user, setUser] = useState("");
  const [secret, setSecret] = useState("");
  const [tenant, setTenant] = useState(registry?.azureTenantId ?? "");
  const [enabled, setEnabled] = useState(registry?.enabled ?? true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const t = TYPES[type];

  const body = () => {
    const b: Record<string, unknown> = { name, url, registryType: type, enabled };
    if (type === "DOCKER_HUB" || type === "PRIVATE") Object.assign(b, { username: user || undefined, password: secret || undefined });
    if (type === "AWS_ECR") Object.assign(b, { password: secret || undefined });
    if (type === "GCR") Object.assign(b, { gcpServiceAccountJson: secret || undefined });
    if (type === "ACR") Object.assign(b, { azureClientId: user || undefined, azureClientSecret: secret || undefined, azureTenantId: tenant || undefined });
    return b;
  };

  return (
    <Modal
      open
      onClose={onClose}
      title={registry ? `Edit ${registry.name}` : "Add a registry"}
      footer={
        <>
          <Button tone="quiet" onClick={onClose}>
            Cancel
          </Button>
          <Button
            busy={busy}
            disabled={!name || !url}
            onClick={async () => {
              setBusy(true);
              setError(null);
              try {
                if (registry) await api.put(`/api/registries/${registry.id}`, body());
                else await api.post("/api/registries", body());
                onSaved();
              } catch (e) {
                setError((e as Error).message);
                setBusy(false);
              }
            }}
          >
            Save
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-4">
        {!registry && (
          <Field label="Type">
            <Select
              value={type}
              onChange={(v) => {
                setType(v as Registry["registryType"]);
                setUrl(TYPES[v as Registry["registryType"]].url);
              }}
            >
              {Object.entries(TYPES).map(([k, v]) => (
                <option key={k} value={k}>
                  {v.label}
                </option>
              ))}
            </Select>
          </Field>
        )}
        <Field label="Name">
          <input className={inputClass} value={name} onChange={(e) => setName(e.target.value)} placeholder="GitHub packages" />
        </Field>
        <Field label="Registry address" hint="Credentials are only sent to this exact host.">
          <input className={`${inputClass} font-mono text-[13px]`} value={url} onChange={(e) => setUrl(e.target.value)} disabled={type === "DOCKER_HUB"} />
        </Field>
        {t.user && (
          <Field label={t.user}>
            <input className={inputClass} value={user} onChange={(e) => setUser(e.target.value)} autoComplete="off" placeholder={registry?.hasCredentials ? "Unchanged" : ""} />
          </Field>
        )}
        <Field label={t.secret} hint={registry?.hasCredentials ? "Saved. Leave empty to keep it." : "Stored encrypted."}>
          {type === "GCR" ? (
            <textarea className={`${inputClass} code`} rows={5} value={secret} onChange={(e) => setSecret(e.target.value)} />
          ) : (
            <input className={inputClass} type="password" value={secret} onChange={(e) => setSecret(e.target.value)} autoComplete="new-password" />
          )}
        </Field>
        {type === "ACR" && (
          <Field label="Tenant ID" optional>
            <input className={inputClass} value={tenant} onChange={(e) => setTenant(e.target.value)} />
          </Field>
        )}
        {type === "AWS_ECR" && <Notice tone="warn">ECR tokens expire after 12 hours. Paste a fresh one when pulls start failing.</Notice>}
        {registry && <Toggle checked={enabled} onChange={setEnabled} label="Use this registry" />}
        {error && <Notice tone="error">{error}</Notice>}
      </div>
    </Modal>
  );
}
