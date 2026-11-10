"use client";

import { Suspense, useMemo, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { Badge, Button, CodeEditor, Empty, Field, Menu, Modal, Notice, PageHeader, Search, Segmented, Select, Spinner, inputClass, useConfirm, useToast } from "@/components/ui";
import { useSession } from "@/components/app";
import { api } from "@/lib/api";
import { usePoll } from "@/lib/hooks";
import type { Template } from "@/lib/types";

export default function TemplatesPage() {
  return (
    <Suspense>
      <Templates />
    </Suspense>
  );
}

type Kind = "all" | "CONTAINER" | "COMPOSE" | "mine";

function Templates() {
  const { session } = useSession();
  const params = useSearchParams();
  const router = useRouter();
  const toast = useToast();
  const confirm = useConfirm();
  const { data, error, refresh } = usePoll<Template[]>("/api/templates");
  const [q, setQ] = useState("");
  const [kind, setKind] = useState<Kind>((params.get("type") as Kind) || "all");
  const [category, setCategory] = useState("");
  const [editing, setEditing] = useState<Partial<Template> | null>(null);

  const categories = useMemo(() => [...new Set((data ?? []).map((t) => t.category).filter(Boolean))].sort(), [data]);
  const list = useMemo(() => {
    const needle = q.trim().toLowerCase();
    return (data ?? [])
      .filter((t) => (kind === "mine" ? !t.system : kind === "all" ? true : t.type === kind))
      .filter((t) => !category || t.category === category)
      .filter((t) => !needle || `${t.name} ${t.description} ${t.category} ${t.imageName ?? ""}`.toLowerCase().includes(needle))
      .sort((a, b) => a.name.localeCompare(b.name));
  }, [data, q, kind, category]);

  const use = (t: Template) => router.push(t.type === "COMPOSE" ? `/stacks/new/?template=${t.id}` : `/containers/new/?template=${t.id}`);

  return (
    <>
      <PageHeader title="Templates" intro="Ready-made setups for popular software. Pick one, check the settings, and it runs." art="images">
        <Button tone="quiet" onClick={() => setEditing({ type: "CONTAINER", category: "Custom" })}>
          New template
        </Button>
      </PageHeader>
      <div className="flex flex-wrap items-center justify-between gap-3 mb-5">
        <div className="flex flex-wrap items-center gap-3">
          <Segmented<Kind>
            label="Type"
            value={kind}
            onChange={setKind}
            options={[
              { value: "all", label: "All" },
              { value: "CONTAINER", label: "Containers" },
              { value: "COMPOSE", label: "Stacks" },
              { value: "mine", label: "Yours" },
            ]}
          />
          <Select value={category} onChange={setCategory} aria-label="Category" className="w-48">
            <option value="">All categories</option>
            {categories.map((c) => (
              <option key={c} value={c}>
                {c}
              </option>
            ))}
          </Select>
        </div>
        <Search value={q} onChange={setQ} placeholder="Search templates" />
      </div>
      {error && <Notice tone="error">{error}</Notice>}
      {!data && !error && <Spinner />}
      {data && list.length === 0 && <Empty title="Nothing matches">Try another search or category.</Empty>}
      <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-3">
        {list.map((t) => (
          <div key={t.id} className="rounded-2xl bg-paper border border-line p-5 flex flex-col gap-3 min-w-0">
            <div className="flex items-start gap-3">
              <Logo src={t.logo} name={t.name} />
              <div className="min-w-0 flex-1">
                <div className="font-medium truncate">{t.name}</div>
                <div className="flex flex-wrap gap-1.5 mt-0.5">
                  <Badge>{t.category}</Badge>
                  <Badge>{t.type === "COMPOSE" ? "stack" : "container"}</Badge>
                  {!t.system && <Badge tone="live">yours</Badge>}
                </div>
              </div>
              <Menu
                items={[
                  { label: "Duplicate", onClick: async () => {
                      try {
                        await api.post(`/api/templates/${t.id}/duplicate?name=${encodeURIComponent(t.name + " copy")}`);
                        toast("Template duplicated");
                        refresh();
                      } catch (e) {
                        toast((e as Error).message, "error");
                      }
                    } },
                  !t.system && { label: "Edit", onClick: () => setEditing(t) },
                  !t.system && {
                    label: "Delete",
                    danger: true,
                    onClick: async () => {
                      if (!(await confirm({ title: `Delete ${t.name}?`, confirm: "Delete", danger: true }))) return;
                      try {
                        await api.del(`/api/templates/${t.id}`);
                        refresh();
                      } catch (e) {
                        toast((e as Error).message, "error");
                      }
                    },
                  },
                  !!t.documentation && { label: "Documentation", onClick: () => void window.open(t.documentation!, "_blank", "noopener") },
                ]}
              />
            </div>
            <p className="text-[14px] text-slate line-clamp-3 flex-1">{t.description}</p>
            <div className="text-[12.5px] text-slate font-mono truncate">{t.type === "COMPOSE" ? "docker-compose" : t.imageName}</div>
            <div>
              {t.type === "COMPOSE" && !session?.admin ? (
                <span className="text-[12.5px] text-slate">Stacks are created by administrators.</span>
              ) : (
                <Button size="sm" onClick={() => use(t)}>
                  Use template
                </Button>
              )}
            </div>
          </div>
        ))}
      </div>
      {editing && <TemplateEditor template={editing} onClose={() => setEditing(null)} onSaved={() => (setEditing(null), refresh())} />}
    </>
  );
}

/** The template's logo, or its first letter when there is none or it fails to load. */
function Logo({ src, name }: { src: string | null; name: string }) {
  const [failed, setFailed] = useState(false);
  return (
    <div className="size-10 rounded-xl bg-well grid place-items-center overflow-hidden shrink-0" aria-hidden>
      {src && !failed ? (
        // eslint-disable-next-line @next/next/no-img-element
        <img src={src} alt="" className="size-7 object-contain" loading="lazy" referrerPolicy="no-referrer" onError={() => setFailed(true)} />
      ) : (
        <span className="font-display text-slate text-[1.1rem]">{name[0]}</span>
      )}
    </div>
  );
}

function TemplateEditor({ template, onClose, onSaved }: { template: Partial<Template>; onClose: () => void; onSaved: () => void }) {
  const [t, setT] = useState<Partial<Template>>(template);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const set = (k: keyof Template, v: string) => setT((x) => ({ ...x, [k]: v }));
  const save = async () => {
    setBusy(true);
    setError(null);
    try {
      if (t.id) await api.put(`/api/templates/${t.id}`, t);
      else await api.post("/api/templates", t);
      onSaved();
    } catch (e) {
      setError((e as Error).message);
      setBusy(false);
    }
  };
  return (
    <Modal
      open
      wide
      onClose={onClose}
      title={t.id ? `Edit ${template.name}` : "New template"}
      footer={
        <>
          <Button tone="quiet" onClick={onClose}>
            Cancel
          </Button>
          <Button onClick={save} busy={busy} disabled={!t.name || (t.type === "COMPOSE" ? !t.composeContent : !t.imageName)}>
            Save
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-4">
        <div className="grid gap-4 sm:grid-cols-2">
          <Field label="Name">
            <input className={inputClass} value={t.name ?? ""} onChange={(e) => set("name", e.target.value)} />
          </Field>
          <Field label="Category">
            <input className={inputClass} value={t.category ?? ""} onChange={(e) => set("category", e.target.value)} />
          </Field>
        </div>
        <Field label="Description">
          <input className={inputClass} value={t.description ?? ""} onChange={(e) => set("description", e.target.value)} />
        </Field>
        <Segmented
          label="Type"
          value={(t.type ?? "CONTAINER") as "CONTAINER" | "COMPOSE"}
          onChange={(v) => setT((x) => ({ ...x, type: v }))}
          options={[
            { value: "CONTAINER", label: "Single container" },
            { value: "COMPOSE", label: "Compose stack" },
          ]}
        />
        {t.type === "COMPOSE" ? (
          <CodeEditor label="Compose file" value={t.composeContent ?? ""} onChange={(v) => set("composeContent", v)} rows={14} />
        ) : (
          <div className="grid gap-4 sm:grid-cols-2">
            <Field label="Image">
              <input className={inputClass} value={t.imageName ?? ""} onChange={(e) => set("imageName", e.target.value)} placeholder="nginx:latest" />
            </Field>
            <Field label="Ports" optional hint="Comma separated, e.g. 8080:80,53:53/udp">
              <input className={inputClass} value={t.defaultPorts ?? ""} onChange={(e) => set("defaultPorts", e.target.value)} />
            </Field>
            <Field label="Environment" optional hint="Comma separated NAME=value">
              <input className={inputClass} value={t.defaultEnv ?? ""} onChange={(e) => set("defaultEnv", e.target.value)} />
            </Field>
            <Field label="Volumes" optional hint="Comma separated, e.g. data:/data">
              <input className={inputClass} value={t.defaultVolumes ?? ""} onChange={(e) => set("defaultVolumes", e.target.value)} />
            </Field>
          </div>
        )}
        {error && <Notice tone="error">{error}</Notice>}
      </div>
    </Modal>
  );
}
