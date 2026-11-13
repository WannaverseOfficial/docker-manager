"use client";

import { useState, type ReactNode } from "react";
import { useHost, useSession } from "@/components/app";
import { Badge, Button, Empty, Field, Menu, Modal, Notice, PageHeader, Segmented, Spinner, inputClass, useConfirm, useToast } from "@/components/ui";
import { api } from "@/lib/api";
import { useManaged } from "@/lib/managed";
import { ManagedBadge } from "@/components/managed";
import { hostLabel } from "@/lib/format";
import { usePoll } from "@/lib/hooks";
import type { Host } from "@/lib/types";

export default function HostsPage() {
  const { hosts, loaded, reload, hostId, select } = useHost();
  const { session } = useSession();
  const [adding, setAdding] = useState(false);
  const [editing, setEditing] = useState<Host | null>(null);
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
              onEdit={() => setEditing(h)}
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
      {editing && <EditHost host={editing} onClose={() => setEditing(null)} onSaved={() => (setEditing(null), reload())} />}
    </>
  );
}

function HostRow({ host, current, onSelect, onEdit, onRemove, canEdit }: { host: Host; current: boolean; onSelect: () => void; onEdit: () => void; onRemove: () => void; canEdit: boolean }) {
  const { data: ping } = usePoll<{ connected: boolean }>(`/api/docker/hosts/${host.id}/ping`, 30000);
  const managed = useManaged().find("hosts", host.id);
  const { data: info } = usePoll<{ dockerVersion: string; operatingSystem: string; containersRunning: number; containersTotal: number }>(
    ping?.connected ? `/api/docker/hosts/${host.id}/info` : null,
  );
  return (
    <li className="flex items-center gap-3 px-4 py-3">
      <div className="flex-1 min-w-0">
        <div className="flex flex-wrap items-center gap-2">
          <span className="font-medium">{hostLabel(host)}</span>
          {current && <Badge tone="live">selected</Badge>}
          {managed && <ManagedBadge entry={managed.key} />}
          <Badge>{host.dockerHostUrl.startsWith("ssh://") ? "SSH" : host.tls ? "TLS" : host.dockerHostUrl.startsWith("unix://") ? "local" : "TCP"}</Badge>
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
        {(host.publicIpv4 || host.publicIpv6) && (
          <div className="text-[12.5px] text-slate">
            Public address <span className="font-mono">{[host.publicIpv4, host.publicIpv6].filter(Boolean).join(", ")}</span>
          </div>
        )}
      </div>
      {!current && (
        <Button tone="quiet" size="sm" onClick={onSelect}>
          Select
        </Button>
      )}
      {canEdit && !managed && (
        <Menu
          items={[
            { label: "Edit", onClick: onEdit },
            { label: "Remove", danger: true, onClick: onRemove },
          ]}
        />
      )}
    </li>
  );
}

type Kind = "local" | "tcp" | "tls" | "ssh";

function AddHost({ open, onClose, onAdded }: { open: boolean; onClose: () => void; onAdded: (h: Host) => void }) {
  const [name, setName] = useState("");
  const [kind, setKind] = useState<Kind>("ssh");
  const [server, setServer] = useState("");
  const [user, setUser] = useState("");
  const [port, setPort] = useState("");
  const [ca, setCa] = useState("");
  const [cert, setCert] = useState("");
  const [key, setKey] = useState("");
  const [sshKey, setSshKey] = useState("");
  const [publicIpv4, setPublicIpv4] = useState("");
  const [publicIpv6, setPublicIpv6] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const address =
    kind === "local"
      ? "unix:///var/run/docker.sock"
      : kind === "ssh"
        ? `ssh://${user.trim() || "user"}@${server.trim() || "server"}${port.trim() && port.trim() !== "22" ? `:${port.trim()}` : ""}`
        : `tcp://${server.trim() || "server"}:${port.trim() || (kind === "tls" ? "2376" : "2375")}`;
  const ready =
    kind === "local" ||
    (kind === "tcp" && server.trim()) ||
    (kind === "tls" && server.trim() && ca.trim() && cert.trim() && key.trim()) ||
    (kind === "ssh" && server.trim() && user.trim() && sshKey.trim());

  return (
    <Modal open={open} onClose={onClose} title="Add a Docker host" wide>
      <form
        className="flex flex-col gap-4"
        onSubmit={async (e) => {
          e.preventDefault();
          setBusy(true);
          setError(null);
          try {
            const h = await api.post<Host>("/api/docker/hosts", {
              name: name.trim() || null,
              dockerHostUrl: address,
              tlsCaPem: kind === "tls" ? ca : null,
              tlsCertPem: kind === "tls" ? cert : null,
              tlsKeyPem: kind === "tls" ? key : null,
              sshPrivateKey: kind === "ssh" ? sshKey : null,
              publicIpv4: publicIpv4.trim() || null,
              publicIpv6: publicIpv6.trim() || null,
            });
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
        <Segmented<Kind>
          label="Connect with"
          value={kind}
          onChange={setKind}
          options={[
            { value: "ssh", label: "SSH" },
            { value: "tls", label: "TCP with TLS" },
            { value: "tcp", label: "TCP" },
            { value: "local", label: "This server" },
          ]}
        />
        {kind === "local" && (
          <p className="text-[14px] text-slate">
            The Docker engine on the machine Docker Manager runs on, through <code className="font-mono text-[13px]">/var/run/docker.sock</code>. It&apos;s added automatically
            when that socket is mounted.
          </p>
        )}
        {kind !== "local" && (
          <div className={`grid gap-4 ${kind === "ssh" ? "sm:grid-cols-[10rem_1fr_6rem]" : "sm:grid-cols-[1fr_7rem]"}`}>
            {kind === "ssh" && (
              <Field label="User" hint="In the docker group there">
                <input className={inputClass} value={user} onChange={(e) => setUser(e.target.value)} placeholder="deploy" autoCapitalize="off" spellCheck={false} />
              </Field>
            )}
            <Field label="Server" hint={kind === "ssh" ? undefined : "Name or address"}>
              <input className={inputClass} value={server} onChange={(e) => setServer(e.target.value)} placeholder="server.example.com" autoCapitalize="off" spellCheck={false} />
            </Field>
            <Field label="Port" optional>
              <input
                className={inputClass}
                inputMode="numeric"
                value={port}
                onChange={(e) => setPort(e.target.value.replace(/\D/g, "").slice(0, 5))}
                placeholder={kind === "ssh" ? "22" : kind === "tls" ? "2376" : "2375"}
              />
            </Field>
          </div>
        )}
        {kind === "ssh" && (
          <>
            <Field label="Private key" hint="An OpenSSH key without a passphrase, whose public key is in the user's ~/.ssh/authorized_keys. Stored encrypted.">
              <textarea
                className={`${inputClass} h-28 py-2 font-mono text-[12px]`}
                value={sshKey}
                onChange={(e) => setSshKey(e.target.value)}
                placeholder="-----BEGIN OPENSSH PRIVATE KEY-----"
                spellCheck={false}
              />
            </Field>
            <p className="text-[13px] text-slate">The server&apos;s identity is remembered on the first connection and checked every time after.</p>
          </>
        )}
        {kind === "tls" && (
          <div className="grid gap-4 sm:grid-cols-3">
            {(
              [
                ["CA certificate", "ca.pem", ca, setCa],
                ["Client certificate", "cert.pem", cert, setCert],
                ["Client key", "key.pem", key, setKey],
              ] as const
            ).map(([label, file, value, set]) => (
              <Field key={file} label={label} hint={file}>
                <textarea
                  className={`${inputClass} h-28 py-2 font-mono text-[11.5px]`}
                  value={value}
                  onChange={(e) => set(e.target.value)}
                  placeholder="-----BEGIN ..."
                  spellCheck={false}
                />
              </Field>
            ))}
          </div>
        )}
        {kind === "tcp" && (
          <Notice tone="warn">
            Docker&apos;s plain TCP port gives full control of the server to anyone who can reach it, with no password. Only use it on a private network, or choose SSH or TLS.
          </Notice>
        )}
        <p className="text-[13px] text-slate">
          Address: <code className="font-mono">{address}</code>
        </p>
        <PublicAddressFields ipv4={publicIpv4} setIpv4={setPublicIpv4} ipv6={publicIpv6} setIpv6={setPublicIpv6} />
        {error && <Notice tone="error">{error}</Notice>}
        <div className="flex justify-end gap-2">
          <Button type="button" tone="quiet" onClick={onClose}>
            Cancel
          </Button>
          <Button type="submit" busy={busy} disabled={!ready}>
            Connect
          </Button>
        </div>
      </form>
    </Modal>
  );
}

/** The host's public addresses, which DNS records for its ingress routes point at. */
function PublicAddressFields({
  ipv4,
  setIpv4,
  ipv6,
  setIpv6,
  detect,
}: {
  ipv4: string;
  setIpv4: (v: string) => void;
  ipv6: string;
  setIpv6: (v: string) => void;
  detect?: ReactNode;
}) {
  return (
    <div className="flex flex-col gap-2">
      <div className="grid gap-4 sm:grid-cols-2">
        <Field label="Public IPv4" optional>
          <input
            className={`${inputClass} font-mono text-[13px]`}
            value={ipv4}
            onChange={(e) => setIpv4(e.target.value)}
            placeholder="203.0.113.10"
            autoCapitalize="off"
            spellCheck={false}
          />
        </Field>
        <Field label="Public IPv6" optional>
          <input
            className={`${inputClass} font-mono text-[13px]`}
            value={ipv6}
            onChange={(e) => setIpv6(e.target.value)}
            placeholder="2001:db8::10"
            autoCapitalize="off"
            spellCheck={false}
          />
        </Field>
      </div>
      <div className="flex flex-wrap items-center gap-3">
        <p className="text-[13px] text-slate flex-1 min-w-[14rem]">DNS records for this host&apos;s ingress routes point here.</p>
        {detect}
      </div>
    </div>
  );
}

function EditHost({ host, onClose, onSaved }: { host: Host; onClose: () => void; onSaved: () => void }) {
  const [name, setName] = useState(host.name ?? "");
  const [ipv4, setIpv4] = useState(host.publicIpv4 ?? "");
  const [ipv6, setIpv6] = useState(host.publicIpv6 ?? "");
  const [busy, setBusy] = useState(false);
  const [detecting, setDetecting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [found, setFound] = useState<string | null>(null);

  const detect = async () => {
    setDetecting(true);
    setError(null);
    setFound(null);
    try {
      const r = await api.post<{ ipv4: string | null; ipv6: string | null }>(`/api/docker/hosts/${host.id}/public-address/detect`);
      if (r.ipv4) setIpv4(r.ipv4);
      if (r.ipv6) setIpv6(r.ipv6);
      setFound(
        r.ipv4 || r.ipv6
          ? `Found ${[r.ipv4, r.ipv6].filter(Boolean).join(" and ")}. Save to keep ${r.ipv4 && r.ipv6 ? "them" : "it"}.`
          : "No public address was found from this host.",
      );
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setDetecting(false);
    }
  };

  const save = async () => {
    setBusy(true);
    setError(null);
    try {
      await api.put(`/api/docker/hosts/${host.id}`, { name, publicIpv4: ipv4.trim(), publicIpv6: ipv6.trim() });
      onSaved();
    } catch (e) {
      setError((e as Error).message);
      setBusy(false);
    }
  };

  return (
    <Modal
      open
      onClose={onClose}
      title="Edit host"
      footer={
        <>
          <Button tone="quiet" onClick={onClose}>
            Cancel
          </Button>
          <Button busy={busy} disabled={detecting} onClick={save}>
            Save
          </Button>
        </>
      }
    >
      <form
        className="flex flex-col gap-4"
        onSubmit={(e) => {
          e.preventDefault();
          if (!busy && !detecting) save();
        }}
      >
        <Field label="Name">
          <input className={inputClass} value={name} onChange={(e) => setName(e.target.value)} autoFocus />
        </Field>
        <PublicAddressFields
          ipv4={ipv4}
          setIpv4={setIpv4}
          ipv6={ipv6}
          setIpv6={setIpv6}
          detect={
            <Button type="button" tone="quiet" size="sm" busy={detecting} onClick={detect}>
              {detecting ? "Detecting" : "Detect"}
            </Button>
          }
        />
        {detecting && <p className="text-[13px] text-slate">Asking a public service from the host. This can take up to 20 seconds.</p>}
        {found && <Notice tone="ok">{found}</Notice>}
        {error && <Notice tone="error">{error}</Notice>}
        <button type="submit" hidden />
      </form>
    </Modal>
  );
}
