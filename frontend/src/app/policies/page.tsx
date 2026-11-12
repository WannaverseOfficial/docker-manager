"use client";

import { useState } from "react";
import { X } from "lucide-react";
import { useSession } from "@/components/app";
import { ManagedBadge } from "@/components/managed";
import { Badge, Button, Empty, Field, IconButton, Menu, Modal, Notice, PageHeader, Panel, Segmented, Spinner, Toggle, inputClass, useConfirm, useToast } from "@/components/ui";
import { api } from "@/lib/api";
import { usePoll } from "@/lib/hooks";
import { useManaged } from "@/lib/managed";
import type { Policy } from "@/lib/types";

export default function PoliciesPage() {
  const { session } = useSession();
  const { data, error, refresh } = usePoll<Policy[]>("/api/image-policies");
  const managed = useManaged();
  const [creating, setCreating] = useState(false);
  const [image, setImage] = useState("");
  const [check, setCheck] = useState<{ allowed: boolean; reason: string; policyName: string | null } | null>(null);
  const toast = useToast();
  const confirm = useConfirm();

  return (
    <>
      <PageHeader title="Image rules" intro="Allow only images you trust, or block ones you don't. Rules apply to new containers, stacks, git deployments and pulls.">
        {session?.admin && <Button onClick={() => setCreating(true)}>New rule set</Button>}
      </PageHeader>
      <Panel title="Check an image" className="mb-6">
        <form
          className="flex flex-wrap gap-2"
          onSubmit={async (e) => {
            e.preventDefault();
            try {
              setCheck(await api.post("/api/image-policies/validate", { image: image.trim() }));
            } catch (err) {
              toast((err as Error).message, "error");
            }
          }}
        >
          <input className={`${inputClass} sm:max-w-md font-mono text-[13px]`} value={image} onChange={(e) => (setImage(e.target.value), setCheck(null))} placeholder="docker.io/library/nginx:latest" aria-label="Image" />
          <Button type="submit" tone="quiet" disabled={!image.trim()}>
            Check
          </Button>
        </form>
        {check && (
          <div className="mt-3">
            <Notice tone={check.allowed ? "ok" : "error"}>
              {check.allowed ? "Allowed" : "Blocked"}
              {check.policyName && ` by “${check.policyName}”`}. {check.reason}
            </Notice>
          </div>
        )}
      </Panel>
      {error && <Notice tone="error">{error}</Notice>}
      {!data && !error && <Spinner />}
      {data && data.length === 0 && <Empty title="No rules">Every image is allowed. Add an allow list to only permit images you trust.</Empty>}
      <div className="flex flex-col gap-4">
        {(data ?? []).map((p) => (
          <PolicyCard
            key={p.id}
            policy={p}
            admin={!!session?.admin}
            managedKey={managed.find("imagePolicies", p.id)?.key}
            onChange={refresh}
            onDelete={async () => {
              if (!(await confirm({ title: `Delete “${p.name}”?`, confirm: "Delete", danger: true }))) return;
              await api.del(`/api/image-policies/${p.id}`);
              refresh();
            }}
          />
        ))}
      </div>
      {creating && <CreatePolicy onClose={() => setCreating(false)} onSaved={() => (setCreating(false), refresh())} />}
    </>
  );
}

function PolicyCard({
  policy,
  admin,
  managedKey,
  onChange,
  onDelete,
}: {
  policy: Policy;
  admin: boolean;
  managedKey?: string;
  onChange: () => void;
  onDelete: () => void;
}) {
  const { data, refresh } = usePoll<Policy>(`/api/image-policies/${policy.id}`);
  // Policies the configuration file manages are changed there, not here.
  const canEdit = admin && !managedKey;
  const [pattern, setPattern] = useState("");
  const toast = useToast();
  return (
    <Panel
      title={
        <span className="flex flex-wrap items-center gap-2">
          {policy.name}
          <Badge tone={policy.policyType === "ALLOW" ? "ok" : "danger"}>{policy.policyType === "ALLOW" ? "allow list" : "block list"}</Badge>
          {!policy.enabled && <Badge>off</Badge>}
          {managedKey && <ManagedBadge entry={managedKey} />}
        </span>
      }
      action={
        canEdit && (
          <Menu
            items={[
              {
                label: policy.enabled ? "Turn off" : "Turn on",
                onClick: async () => {
                  await api.put(`/api/image-policies/${policy.id}`, { enabled: !policy.enabled });
                  onChange();
                },
              },
              { label: "Delete", danger: true, onClick: onDelete },
            ]}
          />
        )
      }
    >
      {policy.description && <p className="text-[14px] text-slate mb-3">{policy.description}</p>}
      <ul className="flex flex-wrap gap-2">
        {(data?.rules ?? []).map((r) => (
          <li key={r.id} className="inline-flex items-center gap-1 rounded-full bg-well pl-3 pr-1 h-8 font-mono text-[12.5px]">
            {r.pattern}
            {canEdit && (
              <IconButton
                label={`Remove ${r.pattern}`}
                className="size-6"
                onClick={async () => {
                  await api.del(`/api/image-policies/${policy.id}/rules/${r.id}`);
                  refresh();
                  onChange();
                }}
              >
                <X size={13} />
              </IconButton>
            )}
          </li>
        ))}
        {data && !data.rules?.length && <li className="text-[14px] text-slate">No patterns yet.</li>}
      </ul>
      {canEdit && (
        <form
          className="flex gap-2 mt-4"
          onSubmit={async (e) => {
            e.preventDefault();
            try {
              await api.post(`/api/image-policies/${policy.id}/rules`, { pattern: pattern.trim() });
              setPattern("");
              refresh();
              onChange();
            } catch (err) {
              toast((err as Error).message, "error");
            }
          }}
        >
          <input className={`${inputClass} sm:max-w-sm font-mono text-[13px]`} value={pattern} onChange={(e) => setPattern(e.target.value)} placeholder="docker.io/library/* or ghcr.io/my-org/*" aria-label="Pattern" />
          <Button type="submit" tone="quiet" disabled={!pattern.trim()}>
            Add
          </Button>
        </form>
      )}
    </Panel>
  );
}

function CreatePolicy({ onClose, onSaved }: { onClose: () => void; onSaved: () => void }) {
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const [type, setType] = useState<"ALLOW" | "DENY">("ALLOW");
  const [enabled, setEnabled] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  return (
    <Modal
      open
      onClose={onClose}
      title="New rule set"
      footer={
        <>
          <Button tone="quiet" onClick={onClose}>
            Cancel
          </Button>
          <Button
            busy={busy}
            disabled={!name}
            onClick={async () => {
              setBusy(true);
              setError(null);
              try {
                await api.post("/api/image-policies", { name, description, policyType: type, enabled, priority: 100 });
                onSaved();
              } catch (e) {
                setError((e as Error).message);
                setBusy(false);
              }
            }}
          >
            Create
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-4">
        <Segmented
          label="Kind"
          value={type}
          onChange={setType}
          options={[
            { value: "ALLOW", label: "Allow list" },
            { value: "DENY", label: "Block list" },
          ]}
        />
        <p className="text-[13px] text-slate">
          {type === "ALLOW" ? "Only images matching one of its patterns may be used." : "Images matching one of its patterns are refused."} Add patterns after creating it.
        </p>
        <Field label="Name">
          <input className={inputClass} value={name} onChange={(e) => setName(e.target.value)} placeholder="Official images only" />
        </Field>
        <Field label="Description" optional>
          <input className={inputClass} value={description} onChange={(e) => setDescription(e.target.value)} />
        </Field>
        <Toggle checked={enabled} onChange={setEnabled} label="Turn on now" />
        {error && <Notice tone="error">{error}</Notice>}
      </div>
    </Modal>
  );
}
