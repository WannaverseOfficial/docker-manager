"use client";

import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { Suspense, useMemo, useRef, useState, type ReactNode } from "react";
import { Play } from "lucide-react";
import { useHost, useSession } from "@/components/app";
import { ReachPicker, validPort, type Reach } from "@/components/databases";
import {
  Badge,
  Button,
  Checkbox,
  CodeEditor,
  Copyable,
  Empty,
  Field,
  KeyValues,
  Menu,
  Modal,
  Notice,
  PageHeader,
  Panel,
  Segmented,
  Select,
  Spinner,
  StateBadge,
  Table,
  Tabs,
  Toggle,
  copyText,
  inputClass,
  useConfirm,
  useToast,
} from "@/components/ui";
import { api, enc, qs } from "@/lib/api";
import {
  IDENT,
  INTERVALS,
  ORIGINS,
  backupSchedule,
  toCsv,
  type Backup,
  type DatabaseInfo,
  type DatabaseServer,
  type QueryResult,
  type Role,
  type ServerView,
} from "@/lib/databases";
import { ago, bytes, dateTime, plural } from "@/lib/format";
import { usePoll } from "@/lib/hooks";
import type { Container } from "@/lib/types";

type Tab = "overview" | "databases" | "users" | "query" | "backups" | "containers";

export default function DatabaseServerPage() {
  return (
    <Suspense>
      <ServerPage />
    </Suspense>
  );
}

function ServerPage() {
  const params = useSearchParams();
  const id = params.get("id") ?? "";
  const { session } = useSession();
  const [tab, setTab] = useState<Tab>(() => {
    const t = params.get("tab");
    return t === "databases" || t === "users" || t === "query" || t === "backups" || t === "containers" ? t : "overview";
  });
  const [queryDb, setQueryDb] = useState<string | null>(null);
  const base = `/api/databases/servers/${id}`;
  const { data: view, error, refresh } = usePoll<ServerView>(id && session?.admin ? base : null, 3000);
  const s = view?.server;
  const running = view?.state === "running";

  if (!session?.admin) {
    return (
      <>
        <PageHeader title="Database" back={{ href: "/databases/", label: "Databases" }} />
        <Notice>Databases are managed by administrators.</Notice>
      </>
    );
  }

  return (
    <>
      <PageHeader
        title={s?.name ?? "Database server"}
        back={{ href: "/databases/", label: "Databases" }}
        intro={
          view && (
            <span className="inline-flex flex-wrap items-center gap-x-3 gap-y-1">
              <StateBadge state={view.state} status={view.health === "unhealthy" ? "(unhealthy)" : undefined} />
              {view.version && <span>PostgreSQL {view.version}</span>}
              {view.containerId && (
                <Link className="underline underline-offset-2" href={`/containers/view/?id=${view.containerId}`}>
                  Container
                </Link>
              )}
            </span>
          )
        }
      />
      {error && <Notice tone="error">{error}</Notice>}
      {!view && !error && <Spinner />}
      {s && (
        <>
          <ActivityBanner server={s} onDismiss={refresh} />
          {!running && (
            <div className="mb-5">
              <Notice tone="warn" title="The server isn't running">
                Start its container to work with databases, users and backups.
              </Notice>
            </div>
          )}
          <Tabs
            value={tab}
            onChange={setTab}
            tabs={[
              { value: "overview", label: "Overview" },
              { value: "databases", label: "Databases" },
              { value: "users", label: "Users" },
              { value: "query", label: "Query" },
              { value: "backups", label: "Backups" },
              { value: "containers", label: "Containers" },
            ]}
          />
          {tab === "overview" && <Overview view={view} onChange={refresh} />}
          {tab === "databases" && running && (
            <Databases
              server={s}
              onQuery={(db) => {
                setQueryDb(db);
                setTab("query");
              }}
            />
          )}
          {tab === "users" && running && <Users server={s} />}
          {tab === "query" && running && <Query server={s} initialDb={queryDb} />}
          {tab === "backups" && <Backups server={s} running={running} onChange={refresh} />}
          {tab === "containers" && <Containers server={s} />}
        </>
      )}
    </>
  );
}

function ActivityBanner({ server, onDismiss }: { server: DatabaseServer; onDismiss: () => void }) {
  const [hidden, setHidden] = useState<string | null>(null);
  if (server.activity) {
    return (
      <div className="mb-5 flex items-center gap-3 rounded-xl border border-line bg-paper px-4 py-3 text-[14px]" role="status">
        <span className="size-2 rounded-full bg-marigold animate-pulse shrink-0" aria-hidden />
        {server.activity}...
      </div>
    );
  }
  if (!server.lastResult || hidden === server.lastResult) return null;
  return (
    <div className="mb-5">
      <Notice tone={server.lastResultFailed ? "error" : "ok"} title={server.lastResultFailed ? "That didn't work" : "Done"}>
        <div className="whitespace-pre-wrap break-words">{server.lastResult}</div>
        <button
          className="mt-2 text-[13px] text-slate underline underline-offset-2"
          onClick={async () => {
            setHidden(server.lastResult);
            try {
              await api.del(`/api/databases/servers/${server.id}/result`);
            } catch {}
            onDismiss();
          }}
        >
          Dismiss
        </button>
      </Notice>
    </div>
  );
}

// --- overview ------------------------------------------------------------------------------------

function Overview({ view, onChange }: { view: ServerView; onChange: () => void }) {
  const s = view.server;
  const router = useRouter();
  const toast = useToast();
  const [password, setPassword] = useState<string | null>(null);
  const [removing, setRemoving] = useState(false);
  const [changingPort, setChangingPort] = useState(false);
  const reveal = async () => {
    try {
      const r = await api.get<{ password: string }>(`/api/databases/servers/${s.id}/password`);
      setPassword(r.password);
    } catch (e) {
      toast((e as Error).message, "error");
    }
  };
  return (
    <div className="grid gap-5 lg:grid-cols-[minmax(0,1fr)_minmax(0,1fr)]">
      <Panel title="Connecting">
        <KeyValues
          rows={[
            ["From containers", <Copyable key="h" text={`${s.containerName}:5432`} />],
            ["Network", <span key="n" className="font-mono text-[13px]">{s.networkName}</span>],
            [
              "From outside",
              <span key="p" className="flex flex-wrap items-center gap-x-3 gap-y-1">
                {s.publishedPort ? (
                  <Copyable text={`${s.publishedAddress === "0.0.0.0" ? "SERVER-ADDRESS" : "127.0.0.1"}:${s.publishedPort}`} />
                ) : (
                  <span className="text-slate">No port opened</span>
                )}
                <button className="underline underline-offset-2 disabled:opacity-40" disabled={!!s.activity} onClick={() => setChangingPort(true)}>
                  Change
                </button>
              </span>,
            ],
            ["Administrator", <span key="u" className="font-mono text-[13px]">{s.superuser}</span>],
            [
              "Password",
              password ? (
                <Copyable key="pw" text={password} />
              ) : (
                <button key="pw" className="underline underline-offset-2" onClick={reveal}>
                  Show
                </button>
              ),
            ],
          ]}
        />
        <p className="text-[13px] text-slate mt-4">
          Give each app its own database and user (Databases tab) instead of the administrator account. Then connect the app&apos;s container on the Containers tab.
        </p>
      </Panel>
      <Panel title="Server">
        <KeyValues
          rows={[
            ["Image", <span key="i" className="font-mono text-[13px]">{s.image}</span>],
            ["Container", <span key="c" className="font-mono text-[13px]">{s.containerName}</span>],
            ["Data volume", <Link key="v" className="font-mono text-[13px] underline underline-offset-2" href={`/volumes/view/?name=${enc(s.volumeName)}`}>{s.volumeName}</Link>],
            ["Backups", backupSchedule(s)],
            ["Created", dateTime(s.createdAt)],
          ]}
        />
        <div className="mt-5 pt-4 border-t border-line flex flex-wrap items-center justify-between gap-3">
          <p className="text-[13px] text-slate">Removing the server keeps its data volume and backups unless you choose otherwise.</p>
          <Button tone="danger" size="sm" onClick={() => setRemoving(true)}>
            Remove server
          </Button>
        </div>
      </Panel>
      {removing && <RemoveServer server={s} onClose={() => setRemoving(false)} onRemoved={() => router.push("/databases/")} />}
      {changingPort && <ChangePort server={s} running={view.state === "running"} onClose={() => setChangingPort(false)} onChanged={onChange} />}
    </div>
  );
}

function ChangePort({ server, running, onClose, onChanged }: { server: DatabaseServer; running: boolean; onClose: () => void; onChanged: () => void }) {
  const toast = useToast();
  const [reach, setReach] = useState<Reach>(!server.publishedPort ? "containers" : server.publishedAddress === "0.0.0.0" ? "network" : "local");
  const [port, setPort] = useState(String(server.publishedPort ?? 5432));
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const newPort = reach === "containers" ? null : Number(port);
  const newAddress = reach === "network" ? "0.0.0.0" : reach === "local" ? "127.0.0.1" : null;
  const unchanged = newPort === (server.publishedPort ?? null) && newAddress === (server.publishedAddress ?? null);
  const valid = reach === "containers" || validPort(port);

  return (
    <Modal
      open
      onClose={busy ? () => {} : onClose}
      title="Change the port"
      footer={
        <>
          <Button tone="quiet" disabled={busy} onClick={onClose}>
            Cancel
          </Button>
          <Button
            busy={busy}
            disabled={!valid || unchanged}
            onClick={async () => {
              setBusy(true);
              setError(null);
              try {
                await api.put(`/api/databases/servers/${server.id}/port`, { port: newPort, address: newAddress });
                toast(newPort ? `PostgreSQL now listens on port ${newPort}` : "The port is closed");
                onChanged();
                onClose();
              } catch (e) {
                setError((e as Error).message);
                setBusy(false);
              }
            }}
          >
            {busy ? "Restarting PostgreSQL" : "Save and restart"}
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-4 text-[14px]">
        <ReachPicker reach={reach} setReach={setReach} port={port} setPort={setPort} />
        <Notice tone="warn">
          {running
            ? "The container is recreated with the new port, so PostgreSQL restarts and open connections drop for a few seconds. Data, users, passwords and connected containers stay as they are."
            : "The container is recreated with the new port. Data, users, passwords and connected containers stay as they are."}
        </Notice>
        <p className="text-[13px] text-slate">
          Containers on the server&apos;s network keep using <span className="font-mono text-ink">{server.containerName}:5432</span>; only tools connecting from outside need the new port.
        </p>
        {error && <Notice tone="error">{error}</Notice>}
      </div>
    </Modal>
  );
}

function RemoveServer({ server, onClose, onRemoved }: { server: DatabaseServer; onClose: () => void; onRemoved: () => void }) {
  const [deleteData, setDeleteData] = useState(false);
  const [deleteBackups, setDeleteBackups] = useState(false);
  const [typed, setTyped] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const destructive = deleteData || deleteBackups;
  return (
    <Modal
      open
      onClose={onClose}
      title={`Remove ${server.name}?`}
      footer={
        <>
          <Button tone="quiet" onClick={onClose}>
            Cancel
          </Button>
          <Button
            tone="danger"
            busy={busy}
            disabled={destructive && typed !== server.name}
            onClick={async () => {
              setBusy(true);
              setError(null);
              try {
                await api.del(`/api/databases/servers/${server.id}${qs({ deleteData, deleteBackups })}`);
                onRemoved();
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
        <p className="text-slate">The container and its network are removed. Apps that use the server lose their connection.</p>
        <Toggle checked={deleteData} onChange={setDeleteData} label="Also delete the data volume" hint={`Every database in ${server.volumeName} is gone for good.`} />
        <Toggle
          checked={deleteBackups}
          onChange={setDeleteBackups}
          label="Also delete its backups"
          hint="Kept backups come back if you create a server with the same name later."
        />
        {destructive && (
          <Field label={`Type ${server.name} to confirm`}>
            <input className={inputClass} value={typed} onChange={(e) => setTyped(e.target.value)} />
          </Field>
        )}
        {error && <Notice tone="error">{error}</Notice>}
      </div>
    </Modal>
  );
}

// --- databases -----------------------------------------------------------------------------------

function Databases({ server, onQuery }: { server: DatabaseServer; onQuery: (db: string) => void }) {
  const base = `/api/databases/servers/${server.id}`;
  const { data, error, refresh } = usePoll<DatabaseInfo[]>(`${base}/databases`, 15000);
  const confirm = useConfirm();
  const toast = useToast();
  const [creating, setCreating] = useState(false);
  const [access, setAccess] = useState<DatabaseInfo | null>(null);

  const backUp = async (db: string) => {
    try {
      await api.post(`${base}/backups`, { database: db });
      toast(`Backing up ${db}. It appears under Backups.`);
    } catch (e) {
      toast((e as Error).message, "error");
    }
  };

  return (
    <>
      <div className="flex flex-wrap items-center justify-between gap-3 mb-4">
        <p className="text-[14px] text-slate">Each database is closed to everyone except its owner and the users you give access to.</p>
        <Button onClick={() => setCreating(true)}>New database</Button>
      </div>
      {error && <Notice tone="error">{error}</Notice>}
      {!data && !error && <Spinner />}
      {data && (
        <Table head={["Database", "Owner", "Size", "Connections", "Access", ""]}>
          {data.map((d) => {
            const system = d.name === "postgres";
            return (
              <tr key={d.name}>
                <td>
                  <span className="font-medium">{d.name}</span>
                  {system && <span className="ml-2 text-[12.5px] text-slate">built in</span>}
                </td>
                <td className="font-mono text-[13px]">{d.owner}</td>
                <td className="tabular-nums whitespace-nowrap">{bytes(d.sizeBytes)}</td>
                <td className="tabular-nums">{d.connections}</td>
                <td>
                  <div className="flex flex-wrap gap-1">
                    {d.access.length === 0 && <span className="text-[13px] text-slate">Owner only</span>}
                    {d.access.map((a) => (
                      <Badge key={a.role} tone={a.level === "PUBLIC" ? "warn" : "plain"}>
                        {a.level === "PUBLIC" ? "Everyone can connect" : `${a.role}: ${a.level === "WRITE" ? "read and write" : "read only"}`}
                      </Badge>
                    ))}
                  </div>
                </td>
                <td className="text-right">
                  <Menu
                    items={[
                      { label: "Query", onClick: () => onQuery(d.name) },
                      !system && { label: "Manage access", onClick: () => setAccess(d) },
                      !system && { label: "Back up this database", onClick: () => backUp(d.name) },
                      !system && {
                        label: "Delete",
                        danger: true,
                        onClick: async () => {
                          const ok = await confirm({
                            title: `Delete ${d.name}?`,
                            body: `Everything in ${d.name} is deleted and ${plural(d.connections, "open connection")} are closed. Back it up first if you might need it.`,
                            confirm: "Delete database",
                            danger: true,
                          });
                          if (!ok) return;
                          try {
                            await api.del(`${base}/databases/${enc(d.name)}`);
                            toast(`Deleted ${d.name}`);
                            refresh();
                          } catch (e) {
                            toast((e as Error).message, "error");
                          }
                        },
                      },
                    ]}
                  />
                </td>
              </tr>
            );
          })}
        </Table>
      )}
      {creating && <CreateDatabase server={server} onClose={() => setCreating(false)} onCreated={refresh} />}
      {access && <AccessDialog server={server} db={access} onClose={() => setAccess(null)} onChanged={refresh} />}
    </>
  );
}

function OwnerPicker({
  server,
  newUser,
  setNewUser,
  owner,
  setOwner,
}: {
  server: DatabaseServer;
  newUser: boolean;
  setNewUser: (v: boolean) => void;
  owner: string;
  setOwner: (v: string) => void;
}) {
  const { data: roles } = usePoll<Role[]>(`/api/databases/servers/${server.id}/roles`);
  const choices = (roles ?? []).filter((r) => r.login);
  return (
    <>
      <div>
        <div className="text-[13px] font-medium mb-1.5">Owner</div>
        <Segmented
          label="Owner"
          value={newUser ? "new" : "existing"}
          onChange={(v) => setNewUser(v === "new")}
          options={[
            { value: "new", label: "A new user" },
            { value: "existing", label: "An existing user" },
          ]}
        />
      </div>
      {newUser ? (
        <Field label="User name" hint="A password is generated and shown once." error={owner && !IDENT.test(owner) ? "Lowercase letters, digits and _" : null}>
          <input className={`${inputClass} font-mono text-[13px]`} value={owner} onChange={(e) => setOwner(e.target.value.toLowerCase())} />
        </Field>
      ) : (
        <Field label="User">
          <Select value={owner} onChange={setOwner}>
            <option value="">Choose a user</option>
            {choices.map((r) => (
              <option key={r.name} value={r.name}>
                {r.name}
                {r.superuser ? " (administrator)" : ""}
              </option>
            ))}
          </Select>
        </Field>
      )}
    </>
  );
}

function Secret({ title, user, password, url, onClose }: { title: string; user: string; password: string | null; url?: string; onClose: () => void }) {
  const [copied, setCopied] = useState(false);
  return (
    <Modal
      open
      onClose={onClose}
      title={title}
      footer={
        <Button onClick={onClose} autoFocus>
          Done
        </Button>
      }
    >
      <div className="flex flex-col gap-4 text-[14px]">
        {password && <Notice tone="warn">Copy the password now. It isn&apos;t stored anywhere and won&apos;t be shown again.</Notice>}
        <KeyValues
          rows={[
            ["User", <Copyable key="u" text={user} />],
            ...(password ? ([["Password", <Copyable key="p" text={password} />]] as [string, ReactNode][]) : []),
          ]}
        />
        {url && (
          <div>
            <div className="text-[13px] font-medium mb-1.5">Connection URL for containers</div>
            <div className="rounded-xl bg-well px-3 py-2.5 font-mono text-[12.5px] break-all">{url}</div>
            <Button
              tone="quiet"
              size="sm"
              className="mt-2"
              onClick={async () => {
                setCopied(await copyText(url));
                setTimeout(() => setCopied(false), 1500);
              }}
            >
              {copied ? "Copied" : "Copy URL"}
            </Button>
          </div>
        )}
      </div>
    </Modal>
  );
}

function CreateDatabase({ server, onClose, onCreated }: { server: DatabaseServer; onClose: () => void; onCreated: () => void }) {
  const [name, setName] = useState("");
  const [newUser, setNewUser] = useState(true);
  const [owner, setOwner] = useState("");
  const [ownerTouched, setOwnerTouched] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [created, setCreated] = useState<{ user: string; password: string | null; url: string } | null>(null);
  const effectiveOwner = newUser && !ownerTouched ? name : owner;
  const valid = IDENT.test(name) && IDENT.test(effectiveOwner);

  if (created) {
    return <Secret title={`${name} is ready`} user={created.user} password={created.password} url={created.url} onClose={onClose} />;
  }
  return (
    <Modal
      open
      onClose={onClose}
      title="New database"
      footer={
        <>
          <Button tone="quiet" onClick={onClose}>
            Cancel
          </Button>
          <Button
            busy={busy}
            disabled={!valid}
            onClick={async () => {
              setBusy(true);
              setError(null);
              try {
                const r = await api.post<{ user: string; password: string | null; url: string }>(`/api/databases/servers/${server.id}/databases`, {
                  name,
                  owner: effectiveOwner,
                  newUser,
                });
                onCreated();
                setCreated(r);
              } catch (e) {
                setError((e as Error).message);
              } finally {
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
        <Field label="Name" error={name && !IDENT.test(name) ? "Lowercase letters, digits and _, starting with a letter" : null}>
          <input className={`${inputClass} font-mono text-[13px]`} value={name} onChange={(e) => setName(e.target.value.toLowerCase())} placeholder="myapp" autoFocus />
        </Field>
        <OwnerPicker
          server={server}
          newUser={newUser}
          setNewUser={(v) => {
            setNewUser(v);
            setOwner("");
            setOwnerTouched(v ? false : true);
          }}
          owner={effectiveOwner}
          setOwner={(v) => {
            setOwner(v);
            setOwnerTouched(true);
          }}
        />
        {error && <Notice tone="error">{error}</Notice>}
      </div>
    </Modal>
  );
}

function AccessDialog({ server, db, onClose, onChanged }: { server: DatabaseServer; db: DatabaseInfo; onClose: () => void; onChanged: () => void }) {
  const { data: roles } = usePoll<Role[]>(`/api/databases/servers/${server.id}/roles`);
  const [levels, setLevels] = useState<Record<string, string>>(() => Object.fromEntries(db.access.map((a) => [a.role, a.level])));
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const users = (roles ?? []).filter((r) => r.login && !r.superuser && r.name !== db.owner);
  const set = async (role: string, level: string) => {
    setBusy(role);
    setError(null);
    try {
      await api.put(`/api/databases/servers/${server.id}/access`, { database: db.name, role, level });
      setLevels((l) => ({ ...l, [role]: level }));
      onChanged();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(null);
    }
  };
  return (
    <Modal open onClose={onClose} title={`Access to ${db.name}`} footer={<Button onClick={onClose}>Done</Button>}>
      <div className="flex flex-col gap-4 text-[14px]">
        <p className="text-slate">
          <span className="font-mono text-ink">{db.owner}</span> owns this database and can do anything in it. Administrators always have access.
        </p>
        {!roles && <Spinner />}
        {roles && users.length === 0 && <p className="text-slate">There are no other users yet. Create one on the Users tab.</p>}
        <ul className="divide-y divide-line">
          {users.map((r) => (
            <li key={r.name} className="flex flex-wrap items-center justify-between gap-3 py-2.5">
              <span className="font-mono text-[13px]">{r.name}</span>
              <Segmented
                label={`Access for ${r.name}`}
                value={(levels[r.name] === "PUBLIC" ? "NONE" : levels[r.name]) ?? "NONE"}
                onChange={(v) => busy == null && set(r.name, v)}
                options={[
                  { value: "NONE", label: "None" },
                  { value: "READ", label: "Read" },
                  { value: "WRITE", label: "Read and write" },
                ]}
              />
            </li>
          ))}
        </ul>
        {busy && <p className="text-[13px] text-slate">Saving {busy}...</p>}
        {error && <Notice tone="error">{error}</Notice>}
      </div>
    </Modal>
  );
}

// --- users ---------------------------------------------------------------------------------------

function Users({ server }: { server: DatabaseServer }) {
  const base = `/api/databases/servers/${server.id}`;
  const { data, error, refresh } = usePoll<Role[]>(`${base}/roles`);
  const confirm = useConfirm();
  const toast = useToast();
  const [creating, setCreating] = useState(false);
  const [secret, setSecret] = useState<{ title: string; user: string; password: string } | null>(null);

  return (
    <>
      <div className="flex flex-wrap items-center justify-between gap-3 mb-4">
        <p className="text-[14px] text-slate">Users sign in with a password (SCRAM-SHA-256). Give them access to databases from the Databases tab.</p>
        <Button onClick={() => setCreating(true)}>New user</Button>
      </div>
      {error && <Notice tone="error">{error}</Notice>}
      {!data && !error && <Spinner />}
      {data && (
        <Table head={["User", "Kind", "Owns", ""]}>
          {data.map((r) => (
            <tr key={r.name}>
              <td className="font-mono text-[13px]">{r.name}</td>
              <td>{r.superuser ? <Badge tone="warn">Administrator</Badge> : r.login ? "Can sign in" : <span className="text-slate">Group (no sign-in)</span>}</td>
              <td className="text-[13px]">{r.owns.length ? r.owns.join(", ") : <span className="text-slate">Nothing</span>}</td>
              <td className="text-right">
                <Menu
                  items={[
                    r.login && {
                      label: "New password",
                      onClick: async () => {
                        const ok = await confirm({
                          title: `New password for ${r.name}?`,
                          body: "A new random password is set right away. Apps still using the old one can't sign in until you update them.",
                          confirm: "Set new password",
                        });
                        if (!ok) return;
                        try {
                          const res = await api.put<{ password: string }>(`${base}/roles/${enc(r.name)}/password`, {});
                          setSecret({ title: `New password for ${r.name}`, user: r.name, password: res.password });
                        } catch (e) {
                          toast((e as Error).message, "error");
                        }
                      },
                    },
                    r.name !== server.superuser && {
                      label: "Delete",
                      danger: true,
                      onClick: async () => {
                        const ok = await confirm({
                          title: `Delete ${r.name}?`,
                          body: "Their access to every database is removed, then the user. A user that still owns a database or tables can't be deleted, so no data is ever deleted with it.",
                          confirm: "Delete user",
                          danger: true,
                        });
                        if (!ok) return;
                        try {
                          await api.del(`${base}/roles/${enc(r.name)}`);
                          toast(`Deleted ${r.name}`);
                          refresh();
                        } catch (e) {
                          toast((e as Error).message, "error");
                        }
                      },
                    },
                  ]}
                />
              </td>
            </tr>
          ))}
        </Table>
      )}
      {creating && (
        <CreateUser
          server={server}
          onClose={() => setCreating(false)}
          onCreated={(user, password) => {
            setCreating(false);
            refresh();
            setSecret({ title: `${user} is ready`, user, password });
          }}
        />
      )}
      {secret && <Secret title={secret.title} user={secret.user} password={secret.password} onClose={() => setSecret(null)} />}
    </>
  );
}

function CreateUser({ server, onClose, onCreated }: { server: DatabaseServer; onClose: () => void; onCreated: (user: string, password: string) => void }) {
  const [name, setName] = useState("");
  const [own, setOwn] = useState(false);
  const [password, setPassword] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  return (
    <Modal
      open
      onClose={onClose}
      title="New user"
      footer={
        <>
          <Button tone="quiet" onClick={onClose}>
            Cancel
          </Button>
          <Button
            busy={busy}
            disabled={!IDENT.test(name) || (own && password.length < 12)}
            onClick={async () => {
              setBusy(true);
              setError(null);
              try {
                const r = await api.post<{ password: string }>(`/api/databases/servers/${server.id}/roles`, { name, password: own ? password : null });
                onCreated(name, r.password);
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
        <Field label="Name" error={name && !IDENT.test(name) ? "Lowercase letters, digits and _, starting with a letter" : null}>
          <input className={`${inputClass} font-mono text-[13px]`} value={name} onChange={(e) => setName(e.target.value.toLowerCase())} autoFocus />
        </Field>
        <Toggle checked={own} onChange={setOwn} label="Choose the password myself" hint="Otherwise a random 32-character password is generated." />
        {own && (
          <Field label="Password" hint="At least 12 characters, no spaces.">
            <input className={inputClass} type="password" autoComplete="new-password" value={password} onChange={(e) => setPassword(e.target.value)} />
          </Field>
        )}
        {error && <Notice tone="error">{error}</Notice>}
      </div>
    </Modal>
  );
}

// --- query ---------------------------------------------------------------------------------------

function Query({ server, initialDb }: { server: DatabaseServer; initialDb: string | null }) {
  const base = `/api/databases/servers/${server.id}`;
  const { data: dbs } = usePoll<DatabaseInfo[]>(`${base}/databases`);
  const [db, setDb] = useState(initialDb ?? "");
  const [sql, setSql] = useState("select now();");
  const [readOnly, setReadOnly] = useState(true);
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState<QueryResult | null>(null);
  const [error, setError] = useState<string | null>(null);
  const database = db || dbs?.find((d) => d.name !== "postgres")?.name || "postgres";

  const run = async () => {
    if (busy || !sql.trim()) return;
    setBusy(true);
    setError(null);
    try {
      setResult(await api.post<QueryResult>(`${base}/query`, { database, sql, readOnly }));
    } catch (e) {
      setError((e as Error).message);
      setResult(null);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap items-end gap-4">
        <Field label="Database">
          <Select value={database} onChange={setDb} className="w-56">
            {(dbs ?? [{ name: database } as DatabaseInfo]).map((d) => (
              <option key={d.name} value={d.name}>
                {d.name}
              </option>
            ))}
          </Select>
        </Field>
        <div className="pb-2">
          <Toggle checked={readOnly} onChange={setReadOnly} label="Read only" hint="Turn off to change data or the schema." />
        </div>
      </div>
      <div
        onKeyDown={(e) => {
          if ((e.ctrlKey || e.metaKey) && e.key === "Enter") {
            e.preventDefault();
            run();
          }
        }}
      >
        <CodeEditor label="SQL" value={sql} onChange={setSql} rows={8} />
      </div>
      <div className="flex flex-wrap items-center gap-3">
        <Button busy={busy} onClick={run}>
          {!busy && <Play size={14} aria-hidden />}
          Run
        </Button>
        <span className="text-[13px] text-slate">Ctrl+Enter runs it. Several statements run in one transaction. Queries stop after 5 minutes.</span>
      </div>
      {error && <Notice tone="error">{error}</Notice>}
      {result && <Results result={result} />}
    </div>
  );
}

function Results({ result }: { result: QueryResult }) {
  return (
    <div className="flex flex-col gap-4">
      {result.error && (
        <Notice tone="error" title="PostgreSQL said">
          <pre className="whitespace-pre-wrap font-mono text-[12.5px]">{result.error}</pre>
        </Notice>
      )}
      {result.results.map((r, i) => (
        <ResultTable key={i} r={r} />
      ))}
      {(result.messages.length > 0 || !result.error) && (
        <div className="text-[13px] text-slate flex flex-wrap gap-x-4 gap-y-1">
          {result.messages.map((m, i) => (
            <span key={i} className="font-mono">
              {m}
            </span>
          ))}
          <span>{result.millis} ms</span>
        </div>
      )}
      {result.outputCut && <Notice tone="warn">The output was larger than 8 MB, so it was cut off. Add a LIMIT to see everything you need.</Notice>}
    </div>
  );
}

function ResultTable({ r }: { r: { columns: string[]; rows: (string | null)[][]; rowCount: number; rowsCut: boolean } }) {
  const download = () => {
    const url = URL.createObjectURL(new Blob([toCsv(r)], { type: "text/csv;charset=utf-8" }));
    const a = document.createElement("a");
    a.href = url;
    a.download = "result.csv";
    a.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  };
  return (
    <div>
      <div className="flex items-center justify-between gap-3 mb-2 text-[13px] text-slate">
        <span>
          {plural(r.rowCount, "row")}
          {r.rowsCut && ` (showing the first ${r.rows.length})`}
        </span>
        {r.rows.length > 0 && (
          <button className="underline underline-offset-2 hover:text-ink" onClick={download}>
            Download CSV
          </button>
        )}
      </div>
      <div className="table-scroll max-h-[480px] overflow-auto rounded-2xl border border-line bg-paper">
        <table className="w-full text-[13px] border-collapse font-mono">
          <thead className="sticky top-0 bg-paper">
            <tr className="text-left text-slate border-b border-line">
              {r.columns.map((c, i) => (
                <th key={i} className="font-medium px-3 py-2 whitespace-nowrap">
                  {c}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {r.rows.map((row, i) => (
              <tr key={i} className="border-b border-line last:border-0 align-top">
                {row.map((v, j) => (
                  <td key={j} className="px-3 py-1.5 max-w-[420px] whitespace-pre-wrap break-words">
                    {v == null ? <span className="text-slate/70 italic">null</span> : v}
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}

// --- backups -------------------------------------------------------------------------------------

function Backups({ server, running, onChange }: { server: DatabaseServer; running: boolean; onChange: () => void }) {
  const base = `/api/databases/servers/${server.id}`;
  const { data, error, refresh } = usePoll<Backup[]>(`${base}/backups`, server.activity ? 2500 : 5000);
  const toast = useToast();
  const confirm = useConfirm();
  const fileInput = useRef<HTMLInputElement>(null);
  const [uploading, setUploading] = useState(false);
  const [restoring, setRestoring] = useState<Backup | null>(null);
  const [importing, setImporting] = useState(false);
  const [secret, setSecret] = useState<{ user: string; password: string } | null>(null);

  const act = async (fn: () => Promise<unknown>, done?: string) => {
    try {
      await fn();
      if (done) toast(done);
      refresh();
      onChange();
    } catch (e) {
      toast((e as Error).message, "error");
    }
  };

  const busy = !!server.activity;
  return (
    <div className="flex flex-col gap-6">
      <div className="flex flex-wrap items-center gap-2">
        <Button disabled={!running || busy} onClick={() => act(() => api.post(`${base}/backups`, {}), "Backing up the whole server")}>
          Back up now
        </Button>
        <Button tone="quiet" busy={uploading} disabled={busy} onClick={() => fileInput.current?.click()}>
          Upload a backup
        </Button>
        <Button tone="quiet" disabled={!running || busy} onClick={() => setImporting(true)}>
          Import from a container
        </Button>
        <input
          ref={fileInput}
          type="file"
          className="hidden"
          accept=".sql,.gz,.dump,.backup,application/gzip,application/sql"
          onChange={async (e) => {
            const f = e.target.files?.[0];
            e.target.value = "";
            if (!f) return;
            setUploading(true);
            await act(() => api.uploadFile(`${base}/backups/upload${qs({ name: f.name })}`, f), `Uploaded ${f.name}. Restore it from the list.`);
            setUploading(false);
          }}
        />
      </div>
      <BackupSettings server={server} onSaved={onChange} />
      {error && <Notice tone="error">{error}</Notice>}
      {!data && !error && <Spinner />}
      {data && data.length === 0 && <Empty title="No backups yet">Back up now, wait for the first automatic backup, or upload one you made elsewhere (pg_dump or pg_dumpall output).</Empty>}
      {data && data.length > 0 && (
        <Table head={["Taken", "Contents", "Kind", "Size", "Copy", ""]}>
          {data.map((b) => (
            <tr key={b.id}>
              <td className="whitespace-nowrap">
                <div>{dateTime(b.createdAt)}</div>
                <div className="text-[12.5px] text-slate">{ago(b.createdAt)}</div>
              </td>
              <td>
                <div>{b.scope === "SERVER" ? "Whole server" : b.databaseName ?? "One database"}</div>
                {b.note && <div className="text-[12.5px] text-slate truncate max-w-[260px]">{b.note}</div>}
                {b.status === "FAILED" && <div className="text-[12.5px] text-danger max-w-[320px] break-words">{b.error}</div>}
              </td>
              <td>
                <Badge tone={b.origin === "BEFORE_RESTORE" ? "warn" : "plain"}>{ORIGINS[b.origin]}</Badge>
              </td>
              <td className="tabular-nums whitespace-nowrap">
                {b.status === "RUNNING" ? <StateBadge state="running" pending="Running" /> : b.status === "FAILED" ? <span className="text-danger">Failed</span> : bytes(b.sizeBytes)}
              </td>
              <td className="text-[13px]">
                {b.s3Status === "DONE" && <span title={b.s3Key ?? ""}>S3</span>}
                {b.s3Status === "FAILED" && (
                  <span className="text-danger" title={b.s3Error ?? ""}>
                    S3 failed
                  </span>
                )}
                {!b.s3Status && <span className="text-slate">None</span>}
              </td>
              <td className="text-right">
                <Menu
                  disabled={b.status === "RUNNING"}
                  items={[
                    b.status === "DONE" && { label: "Restore", onClick: () => setRestoring(b), disabled: busy || !running },
                    b.status === "DONE" && { label: "Download", onClick: () => act(() => api.download(`/api/databases/backups/${b.id}/download`)) },
                    b.status === "DONE" && server.s3Enabled && b.s3Status !== "DONE" && {
                      label: "Copy to S3",
                      onClick: () => act(() => api.post(`/api/databases/backups/${b.id}/s3`), "Copied to S3"),
                    },
                    {
                      label: "Delete",
                      danger: true,
                      onClick: async () => {
                        const ok = await confirm({
                          title: "Delete this backup?",
                          body: b.s3Key ? "The file and its copy in S3 are deleted." : "The file is deleted.",
                          confirm: "Delete",
                          danger: true,
                        });
                        if (ok) act(() => api.del(`/api/databases/backups/${b.id}`), "Backup deleted");
                      },
                    },
                  ]}
                />
              </td>
            </tr>
          ))}
        </Table>
      )}
      {restoring && (
        <RestoreDialog
          server={server}
          backup={restoring}
          onClose={() => setRestoring(null)}
          onStarted={(user, password) => {
            setRestoring(null);
            refresh();
            onChange();
            if (password && user) setSecret({ user, password });
          }}
        />
      )}
      {importing && (
        <ImportDialog
          server={server}
          onClose={() => setImporting(false)}
          onStarted={(user, password) => {
            setImporting(false);
            refresh();
            onChange();
            if (password && user) setSecret({ user, password });
          }}
        />
      )}
      {secret && <Secret title={`${secret.user} was created`} user={secret.user} password={secret.password} onClose={() => setSecret(null)} />}
    </div>
  );
}

function BackupSettings({ server, onSaved }: { server: DatabaseServer; onSaved: () => void }) {
  const toast = useToast();
  const [interval, setInterval_] = useState(String(server.backupIntervalHours));
  const [keep, setKeep] = useState(String(server.backupKeep));
  const [s3, setS3] = useState(server.s3Enabled);
  const [endpoint, setEndpoint] = useState(server.s3Endpoint ?? "");
  const [region, setRegion] = useState(server.s3Region ?? "");
  const [bucket, setBucket] = useState(server.s3Bucket ?? "");
  const [prefix, setPrefix] = useState(server.s3Prefix ?? "");
  const [accessKey, setAccessKey] = useState(server.s3AccessKey ?? "");
  const [secretKey, setSecretKey] = useState("");
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const intervals = INTERVALS.some((i) => String(i.hours) === interval) ? INTERVALS : [...INTERVALS, { hours: Number(interval), label: `Every ${interval} hours` }];

  const save = async (test: boolean) => {
    setBusy(test ? "test" : "save");
    setError(null);
    try {
      await api.put(`/api/databases/servers/${server.id}/settings`, {
        intervalHours: Number(interval),
        keep: Number(keep),
        s3Enabled: s3,
        s3Endpoint: endpoint,
        s3Region: region,
        s3Bucket: bucket,
        s3Prefix: prefix,
        s3AccessKey: accessKey,
        s3SecretKey: secretKey || null,
      });
      setSecretKey("");
      onSaved();
      if (test) {
        await api.post(`/api/databases/servers/${server.id}/settings/s3-test`);
        toast("Saved. Docker Manager wrote and deleted a test file in the bucket.");
      } else toast("Backup settings saved");
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(null);
    }
  };

  return (
    <Panel title="Settings">
      <div className="grid gap-4 sm:grid-cols-2">
        <Field label="Automatic backups" hint="Whole-server backups (every database and user).">
          <Select value={interval} onChange={setInterval_}>
            {intervals.map((i) => (
              <option key={i.hours} value={i.hours}>
                {i.label}
              </option>
            ))}
          </Select>
        </Field>
        <Field label="Keep" hint="Older automatic backups are deleted. Manual and uploaded ones stay until you delete them.">
          <input className={inputClass} inputMode="numeric" value={keep} onChange={(e) => setKeep(e.target.value.replace(/\D/g, ""))} />
        </Field>
      </div>
      <div className="mt-5 pt-4 border-t border-line">
        <Toggle checked={s3} onChange={setS3} label="Copy backups to S3-compatible storage" hint="AWS S3, Cloudflare R2, Backblaze B2, MinIO, Wasabi and others. Each backup is uploaded when it finishes." />
        {s3 && (
          <div className="grid gap-4 sm:grid-cols-2 mt-4">
            <Field label="Endpoint" hint="For AWS: https://s3.REGION.amazonaws.com">
              <input className={`${inputClass} font-mono text-[13px]`} value={endpoint} onChange={(e) => setEndpoint(e.target.value)} placeholder="https://s3.eu-west-1.amazonaws.com" />
            </Field>
            <Field label="Region" optional hint="us-east-1 when empty; auto for R2.">
              <input className={`${inputClass} font-mono text-[13px]`} value={region} onChange={(e) => setRegion(e.target.value)} />
            </Field>
            <Field label="Bucket">
              <input className={`${inputClass} font-mono text-[13px]`} value={bucket} onChange={(e) => setBucket(e.target.value)} />
            </Field>
            <Field label="Folder" optional>
              <input className={`${inputClass} font-mono text-[13px]`} value={prefix} onChange={(e) => setPrefix(e.target.value)} placeholder="backups/postgres" />
            </Field>
            <Field label="Access key">
              <input className={`${inputClass} font-mono text-[13px]`} value={accessKey} onChange={(e) => setAccessKey(e.target.value)} autoComplete="off" />
            </Field>
            <Field label="Secret key" hint={server.hasS3Secret ? "Saved. Leave empty to keep it." : "Stored encrypted."}>
              <input className={inputClass} type="password" value={secretKey} onChange={(e) => setSecretKey(e.target.value)} autoComplete="new-password" />
            </Field>
          </div>
        )}
      </div>
      {error && (
        <div className="mt-4">
          <Notice tone="error">{error}</Notice>
        </div>
      )}
      <div className="mt-5 flex flex-wrap gap-2">
        <Button busy={busy === "save"} disabled={!!busy} onClick={() => save(false)}>
          Save
        </Button>
        {s3 && (
          <Button tone="quiet" busy={busy === "test"} disabled={!!busy} onClick={() => save(true)}>
            Save and test
          </Button>
        )}
      </div>
    </Panel>
  );
}

function RestoreDialog({
  server,
  backup,
  onClose,
  onStarted,
}: {
  server: DatabaseServer;
  backup: Backup;
  onClose: () => void;
  onStarted: (user: string | null, password: string | null) => void;
}) {
  const whole = backup.scope === "SERVER";
  const { data: dbs } = usePoll<DatabaseInfo[]>(whole ? null : `/api/databases/servers/${server.id}/databases`);
  const [target, setTarget] = useState(backup.databaseName ?? "");
  const [newUser, setNewUser] = useState(false);
  const [chosenOwner, setOwner] = useState("");
  const [typed, setTyped] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const existing = dbs?.find((d) => d.name === target);
  // Restoring over an existing database keeps its owner unless you pick another.
  const owner = chosenOwner || (!newUser && existing ? existing.owner : "");
  const valid = whole ? typed === server.name : IDENT.test(target) && target !== "postgres" && IDENT.test(owner);

  return (
    <Modal
      open
      onClose={onClose}
      title={whole ? "Restore the whole server?" : "Restore a database"}
      footer={
        <>
          <Button tone="quiet" onClick={onClose}>
            Cancel
          </Button>
          <Button
            tone="danger"
            busy={busy}
            disabled={!valid}
            onClick={async () => {
              setBusy(true);
              setError(null);
              try {
                const r = await api.post<{ owner: string | null; password: string | null }>(
                  `/api/databases/backups/${backup.id}/restore`,
                  whole ? {} : { database: target, owner, newOwner: newUser },
                );
                onStarted(r.owner, r.password);
              } catch (e) {
                setError((e as Error).message);
                setBusy(false);
              }
            }}
          >
            Restore
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-4 text-[14px]">
        <p className="text-slate">
          From {backup.scope === "SERVER" ? "a whole-server backup" : `a backup of ${backup.databaseName ?? "one database"}`} taken {dateTime(backup.createdAt)}.
        </p>
        {whole ? (
          <>
            <Notice tone="warn" title="Databases in the backup are replaced">
              Each database in the backup is deleted and recreated from it; apps connected to them are disconnected while this runs. Databases that aren&apos;t in the backup stay as they are.
              Users in the backup are created if missing.
            </Notice>
            <Field label={`Type ${server.name} to confirm`}>
              <input className={inputClass} value={typed} onChange={(e) => setTyped(e.target.value)} />
            </Field>
          </>
        ) : (
          <>
            <Field
              label="Restore into"
              hint={existing ? `${target} exists and will be replaced (${bytes(existing.sizeBytes)}).` : "A new database with this name is created."}
              error={target && (!IDENT.test(target) || target === "postgres") ? "Lowercase letters, digits and _ (not postgres)" : null}
            >
              <input className={`${inputClass} font-mono text-[13px]`} value={target} onChange={(e) => setTarget(e.target.value.toLowerCase())} list="restore-dbs" />
              <datalist id="restore-dbs">
                {(dbs ?? [])
                  .filter((d) => d.name !== "postgres")
                  .map((d) => (
                    <option key={d.name} value={d.name} />
                  ))}
              </datalist>
            </Field>
            <OwnerPicker server={server} newUser={newUser} setNewUser={(v) => (setNewUser(v), setOwner(v ? target : ""))} owner={owner} setOwner={setOwner} />
            {existing && <Notice tone="warn">Apps connected to {target} are disconnected while it is replaced.</Notice>}
          </>
        )}
        <p className="text-[13px] text-slate">A backup of the whole server is taken first, so you can go back.</p>
        {error && <Notice tone="error">{error}</Notice>}
      </div>
    </Modal>
  );
}

function ImportDialog({
  server,
  onClose,
  onStarted,
}: {
  server: DatabaseServer;
  onClose: () => void;
  onStarted: (user: string | null, password: string | null) => void;
}) {
  const { hostId } = useHost();
  const { data: sources } = usePoll<{ id: string; name: string; image: string }[]>(hostId ? `/api/databases/hosts/${hostId}/sources` : null);
  const [container, setContainer] = useState("");
  const [user, setUser] = useState("");
  const [password, setPassword] = useState("");
  const [databases, setDatabases] = useState<string[] | null>(null);
  const [source, setSource] = useState("");
  const [target, setTarget] = useState("");
  const [newUser, setNewUser] = useState(true);
  const [owner, setOwner] = useState("");
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = async () => {
    setBusy("load");
    setError(null);
    setDatabases(null);
    try {
      const r = await api.post<{ databases: string[] }>(`/api/databases/hosts/${hostId}/sources/databases`, { container, user: user || null, password: password || null });
      setDatabases(r.databases);
      const first = r.databases[0] ?? "";
      setSource(first);
      setTarget(first);
      if (newUser) setOwner(first);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(null);
    }
  };

  return (
    <Modal
      open
      onClose={onClose}
      wide
      title="Import from a container"
      footer={
        <>
          <Button tone="quiet" onClick={onClose}>
            Cancel
          </Button>
          <Button
            busy={busy === "import"}
            disabled={!source || !IDENT.test(target) || !IDENT.test(owner) || !!busy}
            onClick={async () => {
              setBusy("import");
              setError(null);
              try {
                const r = await api.post<{ owner: string | null; password: string | null }>(`/api/databases/servers/${server.id}/import`, {
                  container,
                  sourceDatabase: source,
                  user: user || null,
                  password: password || null,
                  database: target,
                  owner,
                  newOwner: newUser,
                });
                onStarted(r.owner, r.password);
              } catch (e) {
                setError((e as Error).message);
                setBusy(null);
              }
            }}
          >
            Import
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-4 text-[14px]">
        <p className="text-slate">
          Copies a database from another PostgreSQL container into {server.name}. The other container is only read; switch your app over and remove it when you&apos;re happy.
        </p>
        {sources && sources.length === 0 && <Notice>No other running PostgreSQL containers were found on this host.</Notice>}
        <div className="grid gap-4 sm:grid-cols-3">
          <Field label="Container">
            <Select
              value={container}
              onChange={(v) => {
                setContainer(v);
                setDatabases(null);
              }}
            >
              <option value="">Choose</option>
              {(sources ?? []).map((s) => (
                <option key={s.id} value={s.name}>
                  {s.name}
                </option>
              ))}
            </Select>
          </Field>
          <Field label="User" optional hint="From its POSTGRES_USER when empty.">
            <input className={`${inputClass} font-mono text-[13px]`} value={user} onChange={(e) => setUser(e.target.value)} autoComplete="off" />
          </Field>
          <Field label="Password" optional hint="From its POSTGRES_PASSWORD when empty.">
            <input className={inputClass} type="password" value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="new-password" />
          </Field>
        </div>
        <div>
          <Button tone="quiet" size="sm" busy={busy === "load"} disabled={!container || !!busy} onClick={load}>
            Find its databases
          </Button>
        </div>
        {databases && databases.length === 0 && <Notice>That server has no databases besides postgres.</Notice>}
        {databases && databases.length > 0 && (
          <div className="grid gap-4 sm:grid-cols-2">
            <Field label="Database to import">
              <Select
                value={source}
                onChange={(v) => {
                  setSource(v);
                  setTarget(v);
                  if (newUser) setOwner(v);
                }}
              >
                {databases.map((d) => (
                  <option key={d} value={d}>
                    {d}
                  </option>
                ))}
              </Select>
            </Field>
            <Field label="Name here" error={target && !IDENT.test(target) ? "Lowercase letters, digits and _" : null}>
              <input className={`${inputClass} font-mono text-[13px]`} value={target} onChange={(e) => setTarget(e.target.value.toLowerCase())} />
            </Field>
            <div className="sm:col-span-2 flex flex-col gap-4">
              <OwnerPicker server={server} newUser={newUser} setNewUser={(v) => (setNewUser(v), setOwner(v ? target : ""))} owner={owner} setOwner={setOwner} />
            </div>
          </div>
        )}
        <p className="text-[13px] text-slate">A backup of {server.name} is taken first. The imported copy is kept under Backups too.</p>
        {error && <Notice tone="error">{error}</Notice>}
      </div>
    </Modal>
  );
}

// --- containers ----------------------------------------------------------------------------------

function Containers({ server }: { server: DatabaseServer }) {
  const { hostId } = useHost();
  const base = `/api/databases/servers/${server.id}`;
  const { data, error, refresh } = usePoll<{ id: string; name: string }[]>(`${base}/members`, 10000);
  const { data: containers } = usePoll<Container[]>(hostId ? `/api/docker/hosts/${hostId}/containers?all=true` : null);
  const [pick, setPick] = useState("");
  const toast = useToast();
  const confirm = useConfirm();
  const [selected, setSelected] = useState<string[]>([]);
  const memberIds = useMemo(() => new Set((data ?? []).map((m) => m.id)), [data]);
  const candidates = (containers ?? [])
    .filter((c) => !memberIds.has(c.Id) && c.Names?.[0]?.replace(/^\//, "") !== server.containerName && !c.Labels?.["com.wannaverse.helper"])
    .map((c) => c.Names[0].replace(/^\//, ""))
    .sort();
  const snippet = `services:
  app:
    environment:
      DATABASE_URL: postgresql://USER:PASSWORD@${server.containerName}:5432/DATABASE
    networks: [default, ${server.networkName}]

networks:
  ${server.networkName}:
    external: true`;

  return (
    <div className="grid gap-5 lg:grid-cols-[minmax(0,1fr)_minmax(0,1fr)]">
      <Panel title="Connected containers">
        <p className="text-[13px] text-slate mb-4">
          These containers can reach the server at <span className="font-mono text-ink">{server.containerName}:5432</span>. Nothing else can, unless you opened a port.
        </p>
        {error && <Notice tone="error">{error}</Notice>}
        {data && data.length === 0 && <p className="text-[14px] text-slate">None yet.</p>}
        {data && data.length > 0 && (
          <ul className="divide-y divide-line mb-4">
            {data.map((m) => (
              <li key={m.id} className="flex items-center gap-3 py-2">
                <Checkbox label={`Select ${m.name}`} checked={selected.includes(m.id)} onChange={(v) => setSelected((s) => (v ? [...s, m.id] : s.filter((x) => x !== m.id)))} />
                <Link className="flex-1 truncate hover:underline underline-offset-2" href={`/containers/view/?id=${m.id}`}>
                  {m.name}
                </Link>
              </li>
            ))}
          </ul>
        )}
        {selected.length > 0 && (
          <Button
            tone="danger"
            size="sm"
            className="mb-4"
            onClick={async () => {
              const ok = await confirm({
                title: `Disconnect ${plural(selected.length, "container")}?`,
                body: "They can no longer reach the database server.",
                confirm: "Disconnect",
                danger: true,
              });
              if (!ok) return;
              for (const id of selected) {
                try {
                  await api.del(`${base}/members/${enc(id)}`);
                } catch (e) {
                  toast((e as Error).message, "error");
                }
              }
              setSelected([]);
              refresh();
            }}
          >
            Disconnect selected
          </Button>
        )}
        <div className="flex flex-wrap items-end gap-2 pt-4 border-t border-line">
          <Field label="Connect a container">
            <Select value={pick} onChange={setPick} className="w-64 max-w-full">
              <option value="">Choose</option>
              {candidates.map((n) => (
                <option key={n} value={n}>
                  {n}
                </option>
              ))}
            </Select>
          </Field>
          <Button
            disabled={!pick}
            onClick={async () => {
              try {
                await api.post(`${base}/members`, { container: pick });
                toast(`${pick} is connected`);
                setPick("");
                refresh();
              } catch (e) {
                toast((e as Error).message, "error");
              }
            }}
          >
            Connect
          </Button>
        </div>
      </Panel>
      <Panel title="In a compose file">
        <p className="text-[13px] text-slate mb-3">
          Containers recreated by docker compose lose networks added by hand. Declare the network in the stack instead, so the connection survives updates:
        </p>
        <pre className="code rounded-xl bg-well border border-line p-4 overflow-x-auto text-[12.5px]">{snippet}</pre>
        <Button tone="quiet" size="sm" className="mt-3" onClick={async () => toast((await copyText(snippet)) ? "Copied" : "Couldn't copy", "ok")}>
          Copy
        </Button>
      </Panel>
    </div>
  );
}
