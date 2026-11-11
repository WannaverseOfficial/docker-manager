"use client";

import { useState, type ReactNode } from "react";
import { ChevronRight } from "lucide-react";
import { useHost } from "./app";
import { Badge, Button, Empty, Field, KeyValues, Modal, Notice, Panel, Segmented, Spinner, Toggle, copyText, inputClass, useConfirm, useToast } from "./ui";
import { api, enc, qs } from "@/lib/api";
import { bytes, dateTime, plural } from "@/lib/format";
import { usePoll } from "@/lib/hooks";

interface Hosted {
  registry: {
    id: string;
    mode: "LOCAL" | "PUBLIC";
    hostname: string | null;
    localPort: number | null;
    containerName: string;
    volumeName: string;
    createdAt: number;
    lastError: string | null;
  } | null;
  address?: string;
  users?: string[];
  certificate?: { status: "PENDING" | "ACTIVE" | "EXPIRED" | "RENEWAL_PENDING" | "ERROR" | "MISSING"; message: string } | null;
}

function Https({ cert }: { cert: NonNullable<Hosted["certificate"]> }) {
  if (cert.status === "ACTIVE" || cert.status === "RENEWAL_PENDING") return <Badge tone="ok">Certificate active</Badge>;
  if (cert.status === "PENDING")
    return (
      <span className="text-[13px]">
        <Badge tone="warn">Waiting for the certificate</Badge>
        {cert.message ? (
          <span className="block text-danger mt-1 break-words">Let&apos;s Encrypt said: {cert.message}</span>
        ) : (
          <span className="block text-slate mt-1">Pushes and pulls work once Let&apos;s Encrypt has issued it. This page updates by itself.</span>
        )}
      </span>
    );
  return (
    <span className="text-[13px]">
      <Badge tone="danger">{cert.status === "MISSING" ? "Route missing" : cert.status === "EXPIRED" ? "Certificate expired" : "Certificate failed"}</Badge>
      {cert.message && <span className="block text-danger mt-1 break-words">{cert.message}</span>}
      <span className="block text-slate mt-1">Check that the hostname&apos;s DNS points at this server and ports 80 and 443 are open, then retry from Ingress.</span>
    </span>
  );
}

interface Repo {
  name: string;
  tags: string[];
}

interface TagInfo {
  tag: string;
  digest: string | null;
  size: number | null;
  mediaType: string | null;
  platforms: number;
}

const USER = /^[a-z0-9][a-z0-9._-]{0,63}$/;

/** The private registry Docker Manager can run on the selected host. */
export function HostedRegistry() {
  const { hostId } = useHost();
  const base = hostId ? `/api/hosted-registry/hosts/${hostId}` : null;
  const { data, error, refresh } = usePoll<Hosted>(base, 15000);
  if (!base) return null;
  if (error) return <Notice tone="error">{error}</Notice>;
  if (!data) return <Spinner />;
  if (!data.registry) return <CreateRegistry base={base} onCreated={refresh} />;
  return <Registry base={base} data={data} onChange={refresh} />;
}

function CreateRegistry({ base, onCreated }: { base: string; onCreated: () => void }) {
  const [mode, setMode] = useState<"LOCAL" | "PUBLIC">("PUBLIC");
  const [hostname, setHostname] = useState("");
  const [port, setPort] = useState("5000");
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const valid = USER.test(username) && password.length >= 10 && (mode === "LOCAL" ? /^\d+$/.test(port) : /^[a-z0-9.-]+\.[a-z]{2,}$/i.test(hostname));
  return (
    <div className="grid gap-5 lg:grid-cols-[minmax(0,1.2fr)_minmax(0,1fr)]">
      <Panel title="Run your own registry">
        <div className="flex flex-col gap-4">
          <p className="text-[14px] text-slate">
            A private Docker registry (the official <span className="font-mono">registry:3</span>) on this host, for images you build yourself. Sign-ins are required for pushing and pulling, and
            Docker Manager adds it under Sign-ins so your deployments can pull from it.
          </p>
          <div>
            <div className="text-[13px] font-medium mb-1.5">Reachable from</div>
            <Segmented
              label="Reachable from"
              value={mode}
              onChange={setMode}
              options={[
                { value: "PUBLIC", label: "Anywhere, over HTTPS" },
                { value: "LOCAL", label: "This server only" },
              ]}
            />
          </div>
          {mode === "PUBLIC" ? (
            <Field label="Hostname" hint="Point its DNS at this server first. Ingress gets a Let's Encrypt certificate for it.">
              <input className={`${inputClass} font-mono text-[13px]`} value={hostname} onChange={(e) => setHostname(e.target.value.trim().toLowerCase())} placeholder="registry.example.com" />
            </Field>
          ) : (
            <Field label="Port" hint="Published on 127.0.0.1 only. Docker trusts localhost without HTTPS.">
              <input className={inputClass} inputMode="numeric" value={port} onChange={(e) => setPort(e.target.value.replace(/\D/g, ""))} />
            </Field>
          )}
          <div className="grid gap-4 sm:grid-cols-2">
            <Field label="First user" error={username && !USER.test(username) ? "Lowercase letters, digits and . _ -" : null}>
              <input className={`${inputClass} font-mono text-[13px]`} value={username} onChange={(e) => setUsername(e.target.value.toLowerCase())} autoComplete="off" />
            </Field>
            <Field label="Password" hint="At least 10 characters.">
              <input className={inputClass} type="password" value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="new-password" />
            </Field>
          </div>
          {error && <Notice tone="error">{error}</Notice>}
          <div>
            <Button
              busy={busy}
              disabled={!valid}
              onClick={async () => {
                setBusy(true);
                setError(null);
                try {
                  await api.post(base, { mode, hostname: mode === "PUBLIC" ? hostname : null, port: mode === "LOCAL" ? Number(port) : null, username, password });
                  onCreated();
                } catch (e) {
                  setError((e as Error).message);
                  setBusy(false);
                }
              }}
            >
              Create registry
            </Button>
          </div>
        </div>
      </Panel>
      <Panel title="Good to know">
        <ul className="list-disc pl-5 flex flex-col gap-2 text-[14px] text-slate">
          <li>
            <span className="text-ink">Anywhere</span> needs Ingress turned on and ports 80 and 443 open, so Let&apos;s Encrypt can issue the certificate. Uploads have no size limit on this route.
          </li>
          <li>
            <span className="text-ink">This server only</span> is enough when you build and run images on the same machine.
          </li>
          <li>Images are stored in the dm-registry-data volume. Deleting a tag frees space after you reclaim it.</li>
          <li>Passwords are stored as bcrypt hashes. Docker Manager has its own account for listing images and pulling.</li>
        </ul>
      </Panel>
    </div>
  );
}

function Registry({ base, data, onChange }: { base: string; data: Hosted; onChange: () => void }) {
  const r = data.registry!;
  const address = data.address!;
  const toast = useToast();
  const confirm = useConfirm();
  const [userDialog, setUserDialog] = useState<string | "new" | null>(null);
  const [removing, setRemoving] = useState(false);
  const [reclaiming, setReclaiming] = useState(false);
  const steps = `docker login ${address}\ndocker tag myapp:latest ${address}/myapp:latest\ndocker push ${address}/myapp:latest`;

  return (
    <div className="flex flex-col gap-5">
      <div className="grid gap-5 lg:grid-cols-[minmax(0,1fr)_minmax(0,1fr)]">
        <Panel title="Your registry">
          <KeyValues
            rows={[
              ["Address", <Copyable2 key="a" text={address} />],
              ["Reachable from", r.mode === "PUBLIC" ? "Anywhere, over HTTPS (Let's Encrypt)" : "This server only (127.0.0.1)"],
              ...(data.certificate ? ([["HTTPS", <Https key="h" cert={data.certificate} />]] as [string, ReactNode][]) : []),
              ["Container", <span key="c" className="font-mono text-[13px]">{r.containerName}</span>],
              ["Storage", <span key="v" className="font-mono text-[13px]">{r.volumeName}</span>],
              ["Created", dateTime(r.createdAt)],
            ]}
          />
          <div className="mt-5 pt-4 border-t border-line flex flex-wrap gap-2">
            <Button
              tone="quiet"
              size="sm"
              busy={reclaiming}
              onClick={async () => {
                const ok = await confirm({
                  title: "Reclaim space?",
                  body: "Deletes the layers no tag uses any more. The registry stops for the few seconds this takes, so don't run it during a push.",
                  confirm: "Reclaim space",
                });
                if (!ok) return;
                setReclaiming(true);
                try {
                  const res = await api.post<{ result: string }>(`${base}/reclaim`);
                  toast(res.result || "Done");
                } catch (e) {
                  toast((e as Error).message, "error");
                } finally {
                  setReclaiming(false);
                }
              }}
            >
              Reclaim space
            </Button>
            <Button tone="danger" size="sm" onClick={() => setRemoving(true)}>
              Remove registry
            </Button>
          </div>
        </Panel>
        <Panel title="Push an image">
          <pre className="code rounded-xl bg-well border border-line p-4 overflow-x-auto text-[12.5px]">{steps}</pre>
          <Button tone="quiet" size="sm" className="mt-3" onClick={async () => toast((await copyText(steps)) ? "Copied" : "Couldn't copy")}>
            Copy
          </Button>
          {r.mode === "LOCAL" && <p className="text-[13px] text-slate mt-3">Run these on the server itself; the registry only listens on 127.0.0.1.</p>}
        </Panel>
      </div>
      <Panel
        title="Users"
        action={
          <Button size="sm" tone="quiet" onClick={() => setUserDialog("new")}>
            Add user
          </Button>
        }
      >
        <ul className="divide-y divide-line">
          {(data.users ?? []).map((u) => (
            <li key={u} className="flex items-center justify-between gap-3 py-2">
              <span className="font-mono text-[13px]">{u}</span>
              <span className="flex gap-1">
                <Button tone="ghost" size="sm" onClick={() => setUserDialog(u)}>
                  New password
                </Button>
                <Button
                  tone="ghost"
                  size="sm"
                  disabled={(data.users ?? []).length < 2}
                  title={(data.users ?? []).length < 2 ? "Keep at least one user" : undefined}
                  onClick={async () => {
                    if (!(await confirm({ title: `Remove ${u}?`, body: "They can no longer push or pull.", confirm: "Remove", danger: true }))) return;
                    try {
                      await api.del(`${base}/users/${enc(u)}`);
                      onChange();
                    } catch (e) {
                      toast((e as Error).message, "error");
                    }
                  }}
                >
                  Remove
                </Button>
              </span>
            </li>
          ))}
        </ul>
      </Panel>
      <Repositories base={base} address={address} />
      {userDialog && <UserDialog base={base} user={userDialog === "new" ? null : userDialog} onClose={() => setUserDialog(null)} onSaved={onChange} />}
      {removing && <RemoveRegistry base={base} onClose={() => setRemoving(false)} onRemoved={onChange} />}
    </div>
  );
}

function Copyable2({ text }: { text: string }) {
  const [copied, setCopied] = useState(false);
  return (
    <button
      className="font-mono text-[13px] underline decoration-dotted underline-offset-2"
      title="Copy"
      onClick={async () => {
        setCopied(await copyText(text));
        setTimeout(() => setCopied(false), 1500);
      }}
    >
      {copied ? "Copied" : text}
    </button>
  );
}

function Repositories({ base, address }: { base: string; address: string }) {
  const { data, error, refresh, loading } = usePoll<Repo[]>(`${base}/repositories`);
  const [open, setOpen] = useState<string | null>(null);
  return (
    <Panel
      title="Images"
      action={
        <Button tone="ghost" size="sm" onClick={refresh} busy={loading}>
          Refresh
        </Button>
      }
    >
      {error && <Notice tone="error">{error}</Notice>}
      {!data && !error && <Spinner />}
      {data && data.length === 0 && <Empty title="No images yet">Push one with the commands above.</Empty>}
      {data && data.length > 0 && (
        <ul className="divide-y divide-line -my-2">
          {data.map((repo) => (
            <li key={repo.name} className="py-2">
              <button className="w-full flex items-center gap-2 text-left" onClick={() => setOpen(open === repo.name ? null : repo.name)} aria-expanded={open === repo.name}>
                <ChevronRight size={16} className={`text-slate transition-transform ${open === repo.name ? "rotate-90" : ""}`} aria-hidden />
                <span className="font-mono text-[13px] flex-1 truncate">
                  {address}/{repo.name}
                </span>
                <span className="text-[13px] text-slate">{plural(repo.tags.length, "tag")}</span>
              </button>
              {open === repo.name && <Tags base={base} repo={repo} onDeleted={refresh} />}
            </li>
          ))}
        </ul>
      )}
    </Panel>
  );
}

function Tags({ base, repo, onDeleted }: { base: string; repo: Repo; onDeleted: () => void }) {
  const toast = useToast();
  const confirm = useConfirm();
  const [info, setInfo] = useState<Record<string, TagInfo | "loading">>({});
  const load = async (tag: string) => {
    setInfo((i) => ({ ...i, [tag]: "loading" }));
    try {
      const t = await api.get<TagInfo>(`${base}/tag${qs({ repo: repo.name, tag })}`);
      setInfo((i) => ({ ...i, [tag]: t }));
    } catch (e) {
      toast((e as Error).message, "error");
      setInfo((i) => {
        const n = { ...i };
        delete n[tag];
        return n;
      });
    }
  };
  return (
    <ul className="mt-2 ml-6 flex flex-col gap-1.5">
      {repo.tags.map((tag) => {
        const t = info[tag];
        return (
          <li key={tag} className="flex flex-wrap items-center gap-2 rounded-lg bg-well px-3 py-1.5 text-[13px]">
            <span className="font-mono">{tag}</span>
            {t && t !== "loading" && (
              <span className="text-slate">
                {t.size != null ? bytes(t.size) : `${t.platforms} platforms`}
                {t.digest && <span className="font-mono ml-2">{t.digest.slice(0, 19)}</span>}
              </span>
            )}
            <span className="flex-1" />
            {!t && (
              <Button tone="ghost" size="sm" onClick={() => load(tag)}>
                Details
              </Button>
            )}
            <Button
              tone="ghost"
              size="sm"
              onClick={async () => {
                const ok = await confirm({
                  title: `Delete ${repo.name}:${tag}?`,
                  body: "Other tags pointing at the same image are deleted with it. Pulls of this tag stop working. Reclaim space afterwards to free the disk.",
                  confirm: "Delete",
                  danger: true,
                });
                if (!ok) return;
                try {
                  await api.del(`${base}/tag${qs({ repo: repo.name, tag })}`);
                  toast(`Deleted ${repo.name}:${tag}`);
                  onDeleted();
                } catch (e) {
                  toast((e as Error).message, "error");
                }
              }}
            >
              Delete
            </Button>
          </li>
        );
      })}
    </ul>
  );
}

function UserDialog({ base, user, onClose, onSaved }: { base: string; user: string | null; onClose: () => void; onSaved: () => void }) {
  const [name, setName] = useState(user ?? "");
  const [password, setPassword] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  return (
    <Modal
      open
      onClose={onClose}
      title={user ? `New password for ${user}` : "Add a user"}
      footer={
        <>
          <Button tone="quiet" onClick={onClose}>
            Cancel
          </Button>
          <Button
            busy={busy}
            disabled={!USER.test(name) || password.length < 10}
            onClick={async () => {
              setBusy(true);
              setError(null);
              try {
                await api.put(`${base}/users`, { username: name, password });
                onSaved();
                onClose();
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
        {!user && (
          <Field label="Username">
            <input className={`${inputClass} font-mono text-[13px]`} value={name} onChange={(e) => setName(e.target.value.toLowerCase())} autoComplete="off" autoFocus />
          </Field>
        )}
        <Field label="Password" hint="At least 10 characters. Takes effect right away, without restarting the registry.">
          <input className={inputClass} type="password" value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="new-password" />
        </Field>
        {error && <Notice tone="error">{error}</Notice>}
      </div>
    </Modal>
  );
}

function RemoveRegistry({ base, onClose, onRemoved }: { base: string; onClose: () => void; onRemoved: () => void }) {
  const [deleteImages, setDeleteImages] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  return (
    <Modal
      open
      onClose={onClose}
      title="Remove the registry?"
      footer={
        <>
          <Button tone="quiet" onClick={onClose}>
            Cancel
          </Button>
          <Button
            tone="danger"
            busy={busy}
            onClick={async () => {
              setBusy(true);
              setError(null);
              try {
                await api.del(`${base}${qs({ deleteImages })}`);
                onRemoved();
                onClose();
              } catch (e) {
                setError((e as Error).message);
                setBusy(false);
              }
            }}
          >
            Remove
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-4 text-[14px]">
        <p className="text-slate">The registry container, its route and its sign-in entry are removed. Deployments that pull from it will fail.</p>
        <Toggle checked={deleteImages} onChange={setDeleteImages} label="Also delete the stored images" hint="Otherwise the dm-registry-data volume is kept, and a new registry picks the images up again." />
        {deleteImages && <Badge tone="danger">This can&apos;t be undone</Badge>}
        {error && <Notice tone="error">{error}</Notice>}
      </div>
    </Modal>
  );
}
