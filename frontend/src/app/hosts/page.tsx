"use client";

import { useState } from "react";
import { useHost, useSession } from "@/components/app";
import { Badge, Button, Empty, Field, Menu, Modal, Notice, PageHeader, Spinner, inputClass, useConfirm, useToast } from "@/components/ui";
import { api } from "@/lib/api";
import { hostLabel } from "@/lib/format";
import { usePoll } from "@/lib/hooks";
import type { Host } from "@/lib/types";

export default function HostsPage() {
  const { hosts, loaded, reload, hostId, select } = useHost();
  const { session } = useSession();
  const [adding, setAdding] = useState(false);
  const [renaming, setRenaming] = useState<Host | null>(null);
  const toast = useToast();
  const confirm = useConfirm();

  return (
    <>
      <PageHeader title="Hosts" art="setup" intro="The Docker engines Docker Manager looks after. Switch between them in the sidebar.">
        <Button onClick={() => setAdding(true)}>Add host</Button>
      </PageHeader>
      {!loaded && <Spinner />}
      {loaded && hosts.length === 0 && (
        <Empty title="No hosts yet" action={<Button onClick={() => setAdding(true)}>Add a host</Button>}>
          Mount <code className="font-mono">/var/run/docker.sock</code> into the Docker Manager container to add the local engine automatically, or add one by address.
        </Empty>
      )}
      {hosts.length > 0 && (
        <ul className="rounded-2xl border border-line bg-paper divide-y divide-line">
          {hosts.map((h) => (
            <HostRow
              key={h.id}
              host={h}
              current={h.id === hostId}
              onSelect={() => select(h.id)}
              onRename={() => setRenaming(h)}
              onRemove={async () => {
                const ok = await confirm({
                  title: `Remove ${hostLabel(h)}?`,
                  body: "Docker Manager stops managing it. Its containers keep running. Stacks, routes and repositories set up for it stop working here.",
                  confirm: "Remove",
                  danger: true,
                });
                if (!ok) return;
                try {
                  await api.del(`/api/docker/hosts/${h.id}`);
                  reload();
                } catch (e) {
                  toast((e as Error).message, "error");
                }
              }}
              canEdit={!!session?.admin}
            />
          ))}
        </ul>
      )}
      <AddHost open={adding} onClose={() => setAdding(false)} onAdded={(h) => (setAdding(false), reload(), select(h.id))} />
      {renaming && <RenameHost host={renaming} onClose={() => setRenaming(null)} onSaved={() => (setRenaming(null), reload())} />}
    </>
  );
}

function HostRow({ host, current, onSelect, onRename, onRemove, canEdit }: { host: Host; current: boolean; onSelect: () => void; onRename: () => void; onRemove: () => void; canEdit: boolean }) {
  const { data: ping } = usePoll<{ connected: boolean }>(`/api/docker/hosts/${host.id}/ping`, 30000);
  const { data: info } = usePoll<{ dockerVersion: string; operatingSystem: string; containersRunning: number; containersTotal: number }>(
    ping?.connected ? `/api/docker/hosts/${host.id}/info` : null,
  );
  return (
    <li className="flex items-center gap-3 px-4 py-3">
      <div className="flex-1 min-w-0">
        <div className="flex flex-wrap items-center gap-2">
          <span className="font-medium">{hostLabel(host)}</span>
          {current && <Badge tone="live">selected</Badge>}
          <span className="flex items-center gap-1.5 text-[13px] text-slate">
            <span className={`size-1.5 rounded-full ${ping == null ? "bg-slate/60" : ping.connected ? "bg-ok" : "bg-danger"}`} aria-hidden />
            {ping == null ? "Checking" : ping.connected ? "Connected" : "Not reachable"}
          </span>
        </div>
        <div className="text-[13px] text-slate truncate font-mono">{host.dockerHostUrl}</div>
        {info && (
          <div className="text-[12.5px] text-slate">
            Docker {info.dockerVersion} on {info.operatingSystem}, {info.containersRunning} of {info.containersTotal} containers running
          </div>
        )}
      </div>
      {!current && (
        <Button tone="quiet" size="sm" onClick={onSelect}>
          Select
        </Button>
      )}
      {canEdit && (
        <Menu
          items={[
            { label: "Rename", onClick: onRename },
            { label: "Remove", danger: true, onClick: onRemove },
          ]}
        />
      )}
    </li>
  );
}

function AddHost({ open, onClose, onAdded }: { open: boolean; onClose: () => void; onAdded: (h: Host) => void }) {
  const [name, setName] = useState("");
  const [url, setUrl] = useState("unix:///var/run/docker.sock");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  return (
    <Modal open={open} onClose={onClose} title="Add a Docker host">
      <form
        className="flex flex-col gap-4"
        onSubmit={async (e) => {
          e.preventDefault();
          setBusy(true);
          setError(null);
          try {
            const h = await api.post<Host>("/api/docker/hosts", { name: name.trim() || null, dockerHostUrl: url.trim() });
            onAdded(h);
          } catch (err) {
            setError((err as Error).message);
          } finally {
            setBusy(false);
          }
        }}
      >
        <Field label="Name" optional>
          <input className={inputClass} value={name} onChange={(e) => setName(e.target.value)} placeholder="Home server" />
        </Field>
        <Field
          label="Address"
          hint={
            <>
              <code className="font-mono">unix:///var/run/docker.sock</code> for this machine, <code className="font-mono">ssh://user@server</code> for another one over SSH,
              or <code className="font-mono">tcp://server:2376</code>. Only expose Docker over TCP with TLS on a trusted network.
            </>
          }
        >
          <input className={`${inputClass} font-mono text-[13px]`} value={url} onChange={(e) => setUrl(e.target.value)} required spellCheck={false} autoCapitalize="off" />
        </Field>
        {error && <Notice tone="error">{error}</Notice>}
        <div className="flex justify-end gap-2">
          <Button type="button" tone="quiet" onClick={onClose}>
            Cancel
          </Button>
          <Button type="submit" busy={busy}>
            Connect
          </Button>
        </div>
      </form>
    </Modal>
  );
}

function RenameHost({ host, onClose, onSaved }: { host: Host; onClose: () => void; onSaved: () => void }) {
  const [name, setName] = useState(host.name ?? "");
  const [busy, setBusy] = useState(false);
  return (
    <Modal
      open
      onClose={onClose}
      title="Rename host"
      footer={
        <>
          <Button tone="quiet" onClick={onClose}>
            Cancel
          </Button>
          <Button
            busy={busy}
            onClick={async () => {
              setBusy(true);
              await api.put(`/api/docker/hosts/${host.id}`, { name });
              onSaved();
            }}
          >
            Save
          </Button>
        </>
      }
    >
      <Field label="Name">
        <input className={inputClass} value={name} onChange={(e) => setName(e.target.value)} autoFocus />
      </Field>
    </Modal>
  );
}
