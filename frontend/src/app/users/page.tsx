"use client";

import { useState } from "react";
import { X } from "lucide-react";
import { useHost, useSession } from "@/components/app";
import { Badge, Button, Copyable, Empty, Field, IconButton, Menu, Modal, Notice, PageHeader, Panel, Select, Spinner, Tabs, Toggle, inputClass, useConfirm, useToast } from "@/components/ui";
import { api } from "@/lib/api";
import { useManaged } from "@/lib/managed";
import { ManagedBadge } from "@/components/managed";
import { ago, emailDeliverable, hostLabel, titleCase } from "@/lib/format";
import { usePoll } from "@/lib/hooks";
import type { Group, Permission, User } from "@/lib/types";

export default function UsersPage() {
  const { session } = useSession();
  const [tab, setTab] = useState<"users" | "groups">("users");
  if (!session?.admin) return <Notice>Only administrators can manage users.</Notice>;
  return (
    <>
      <PageHeader title="Users" intro="Administrators can do everything. Everyone else can only do what their permissions, or their groups' permissions, allow." />
      <Tabs
        value={tab}
        onChange={setTab}
        tabs={[
          { value: "users", label: "Users" },
          { value: "groups", label: "Groups" },
        ]}
      />
      {tab === "users" ? <Users /> : <Groups />}
    </>
  );
}

function Users() {
  const { session } = useSession();
  const { data, error, refresh } = usePoll<User[]>("/api/users");
  const { data: groups } = usePoll<Group[]>("/api/groups");
  const [creating, setCreating] = useState(false);
  const [perms, setPerms] = useState<User | null>(null);
  const [editing, setEditing] = useState<User | null>(null);
  const [temp, setTemp] = useState<{ username: string; password: string } | null>(null);
  const toast = useToast();
  const confirm = useConfirm();
  const managed = useManaged();

  const update = async (u: User, body: Partial<User>, msg: string) => {
    try {
      await api.put(`/api/users/${u.id}`, body);
      toast(msg);
      refresh();
    } catch (e) {
      toast((e as Error).message, "error");
    }
  };

  return (
    <>
      <div className="flex justify-end mb-4">
        <Button onClick={() => setCreating(true)}>Add user</Button>
      </div>
      {error && <Notice tone="error">{error}</Notice>}
      {!data && !error && <Spinner />}
      {data && (
        <ul className="rounded-2xl border border-line bg-paper divide-y divide-line">
          {data.map((u) => (
            <li key={u.id} className={`flex items-center gap-3 px-4 py-3 ${u.enabled ? "" : "opacity-60"}`}>
              <div className="flex-1 min-w-0">
                <div className="flex flex-wrap items-center gap-2">
                  <span className="font-medium">{u.username}</span>
                  {u.id === session?.userId && <Badge>you</Badge>}
                  {u.admin && <Badge tone="live">administrator</Badge>}
                  {!u.enabled && <Badge tone="danger">disabled</Badge>}
                  {u.mustChangePassword && <Badge tone="warn">temporary password</Badge>}
                  {managed.isManaged("users", u.id) && <ManagedBadge entry={managed.find("users", u.id)?.key} />}
                  {u.groupNames.map((g) => (
                    <Badge key={g}>{g}</Badge>
                  ))}
                </div>
                <div className="text-[13px] text-slate">
                  {emailDeliverable(u.email) ? u.email : <span className="text-caution">{u.email ? `${u.email} (can't receive mail)` : "No email address"}</span>}
                  {` · last signed in ${ago(u.lastLoginAt)}`}
                </div>
              </div>
              {!managed.isManaged("users", u.id) && (
              <Menu
                items={[
                  { label: "Edit", onClick: () => setEditing(u) },
                  !u.admin && { label: "Permissions", onClick: () => setPerms(u) },
                  u.id !== session?.userId && { label: u.admin ? "Remove administrator" : "Make administrator", onClick: () => update(u, { admin: !u.admin }, "Saved") },
                  u.id !== session?.userId && { label: u.enabled ? "Disable" : "Enable", onClick: () => update(u, { enabled: !u.enabled }, u.enabled ? "Disabled and signed out" : "Enabled") },
                  {
                    label: "Reset password",
                    onClick: async () => {
                      if (!(await confirm({ title: `Reset ${u.username}'s password?`, body: "They are signed out and must choose a new password with the temporary one you give them.", confirm: "Reset" })))
                        return;
                      try {
                        const r = await api.post<{ temporaryPassword: string }>(`/api/users/${u.id}/reset-password`);
                        setTemp({ username: u.username, password: r.temporaryPassword });
                        refresh();
                      } catch (e) {
                        toast((e as Error).message, "error");
                      }
                    },
                  },
                  ...(groups ?? []).map((g) => ({
                    label: u.groupIds.includes(g.id) ? `Remove from ${g.name}` : `Add to ${g.name}`,
                    onClick: async () => {
                      try {
                        if (u.groupIds.includes(g.id)) await api.del(`/api/users/${u.id}/groups/${g.id}`);
                        else await api.post(`/api/users/${u.id}/groups/${g.id}`);
                        refresh();
                      } catch (e) {
                        toast((e as Error).message, "error");
                      }
                    },
                  })),
                  u.id !== session?.userId && {
                    label: "Delete",
                    danger: true,
                    onClick: async () => {
                      if (!(await confirm({ title: `Delete ${u.username}?`, confirm: "Delete", danger: true }))) return;
                      try {
                        await api.del(`/api/users/${u.id}`);
                        refresh();
                      } catch (e) {
                        toast((e as Error).message, "error");
                      }
                    },
                  },
                ]}
              />
              )}
            </li>
          ))}
        </ul>
      )}
      {creating && (
        <CreateUser
          onClose={() => setCreating(false)}
          onCreated={(username, password) => {
            setCreating(false);
            setTemp({ username, password });
            refresh();
          }}
        />
      )}
      {temp && (
        <Modal open onClose={() => setTemp(null)} title={`Temporary password for ${temp.username}`} footer={<Button onClick={() => setTemp(null)}>Done</Button>}>
          <p className="text-[14px] text-slate mb-3">Give it to {temp.username} privately. They choose their own password the first time they sign in. It isn&apos;t shown again.</p>
          <div className="rounded-xl bg-well px-4 py-3">
            <Copyable text={temp.password} />
          </div>
        </Modal>
      )}
      {editing && (
        <EditUser
          user={editing}
          onClose={() => setEditing(null)}
          onSaved={() => {
            setEditing(null);
            toast("Saved");
            refresh();
          }}
        />
      )}
      {perms && <PermissionsDialog title={`${perms.username}'s permissions`} base={`/api/users/${perms.id}/permissions`} onClose={() => setPerms(null)} />}
    </>
  );
}

function EditUser({ user, onClose, onSaved }: { user: User; onClose: () => void; onSaved: () => void }) {
  const [username, setUsername] = useState(user.username);
  const [email, setEmail] = useState(user.email ?? "");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const changed = username.trim() !== user.username || email.trim() !== (user.email ?? "");
  return (
    <Modal
      open
      onClose={onClose}
      title={`Edit ${user.username}`}
      footer={
        <>
          <Button tone="quiet" onClick={onClose}>
            Cancel
          </Button>
          <Button
            busy={busy}
            disabled={!username.trim() || !changed}
            onClick={async () => {
              setBusy(true);
              setError(null);
              try {
                await api.put(`/api/users/${user.id}`, { username: username.trim(), email: email.trim() });
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
        <Field label="Username" hint="Used to sign in. Changing it takes effect at their next sign-in.">
          <input className={inputClass} value={username} onChange={(e) => setUsername(e.target.value)} autoCapitalize="off" autoComplete="off" />
        </Field>
        <Field label="Email" optional hint="Where their notifications go. Leave empty for none." error={email.trim() && !emailDeliverable(email.trim()) ? "Mail can't be delivered to this address." : null}>
          <input className={inputClass} type="email" value={email} onChange={(e) => setEmail(e.target.value)} />
        </Field>
        {error && <Notice tone="error">{error}</Notice>}
      </div>
    </Modal>
  );
}

function CreateUser({ onClose, onCreated }: { onClose: () => void; onCreated: (username: string, password: string) => void }) {
  const [username, setUsername] = useState("");
  const [email, setEmail] = useState("");
  const [admin, setAdmin] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  return (
    <Modal
      open
      onClose={onClose}
      title="Add a user"
      footer={
        <>
          <Button tone="quiet" onClick={onClose}>
            Cancel
          </Button>
          <Button
            busy={busy}
            disabled={!username}
            onClick={async () => {
              setBusy(true);
              setError(null);
              try {
                const r = await api.post<{ user: User; temporaryPassword: string }>("/api/users", { username, email: email || null, admin });
                onCreated(r.user.username, r.temporaryPassword);
              } catch (e) {
                setError((e as Error).message);
                setBusy(false);
              }
            }}
          >
            Add user
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-4">
        <Field label="Username">
          <input className={inputClass} value={username} onChange={(e) => setUsername(e.target.value)} autoCapitalize="off" autoComplete="off" />
        </Field>
        <Field label="Email" optional hint="For notifications.">
          <input className={inputClass} type="email" value={email} onChange={(e) => setEmail(e.target.value)} />
        </Field>
        <Toggle checked={admin} onChange={setAdmin} label="Administrator" hint="Can do everything, including managing users." />
        {error && <Notice tone="error">{error}</Notice>}
      </div>
    </Modal>
  );
}

function Groups() {
  const { data, error, refresh } = usePoll<Group[]>("/api/groups");
  const [name, setName] = useState("");
  const [perms, setPerms] = useState<Group | null>(null);
  const toast = useToast();
  const confirm = useConfirm();
  const managed = useManaged();
  return (
    <>
      <Panel title="New group" className="mb-6">
        <form
          className="flex gap-2"
          onSubmit={async (e) => {
            e.preventDefault();
            try {
              await api.post("/api/groups", { name: name.trim() });
              setName("");
              refresh();
            } catch (err) {
              toast((err as Error).message, "error");
            }
          }}
        >
          <input className={`${inputClass} sm:max-w-xs`} value={name} onChange={(e) => setName(e.target.value)} placeholder="Developers" aria-label="Group name" />
          <Button type="submit" tone="quiet" disabled={!name.trim()}>
            Create
          </Button>
        </form>
      </Panel>
      {error && <Notice tone="error">{error}</Notice>}
      {data && data.length === 0 && <Empty title="No groups">Groups give the same permissions to several people.</Empty>}
      {data && data.length > 0 && (
        <ul className="rounded-2xl border border-line bg-paper divide-y divide-line">
          {data.map((g) => (
            <li key={g.id} className="flex items-center gap-3 px-4 py-3">
              <div className="flex-1 min-w-0">
                <div className="flex flex-wrap items-center gap-2">
                  <span className="font-medium">{g.name}</span>
                  {managed.isManaged("groups", g.id) && <ManagedBadge entry={managed.find("groups", g.id)?.key} />}
                </div>
                <div className="text-[13px] text-slate">{g.memberCount} members. Add people from the Users tab.</div>
              </div>
              {!managed.isManaged("groups", g.id) && (
              <>
              <Button tone="quiet" size="sm" onClick={() => setPerms(g)}>
                Permissions
              </Button>
              <Menu
                items={[
                  {
                    label: "Delete",
                    danger: true,
                    onClick: async () => {
                      if (!(await confirm({ title: `Delete ${g.name}?`, body: "Members lose the permissions they had through it.", confirm: "Delete", danger: true }))) return;
                      await api.del(`/api/groups/${g.id}`);
                      refresh();
                    },
                  },
                ]}
              />
              </>
              )}
            </li>
          ))}
        </ul>
      )}
      {perms && <PermissionsDialog title={`${perms.name} permissions`} base={`/api/groups/${perms.id}/permissions`} onClose={() => setPerms(null)} />}
    </>
  );
}

function PermissionsDialog({ title, base, onClose }: { title: string; base: string; onClose: () => void }) {
  const { hosts } = useHost();
  const { data, refresh } = usePoll<Permission[]>(base);
  const { data: catalog } = usePoll<Record<string, string[]>>("/api/users/permission-catalog");
  const [resource, setResource] = useState("CONTAINERS");
  const [action, setAction] = useState("*");
  const [hostId, setHostId] = useState("");
  const [target, setTarget] = useState("");
  const [error, setError] = useState<string | null>(null);
  const actions = catalog?.[resource] ?? [];
  const hostName = (id: string | null) => (id ? hostLabel(hosts.find((h) => h.id === id)) || id.slice(0, 8) : "all hosts");

  return (
    <Modal open wide onClose={onClose} title={title} footer={<Button onClick={onClose}>Done</Button>}>
      <div className="flex flex-col gap-5">
        {!data ? (
          <Spinner />
        ) : data.length === 0 ? (
          <p className="text-[14px] text-slate">No permissions yet: they can sign in but see nothing.</p>
        ) : (
          <ul className="flex flex-col divide-y divide-line rounded-xl border border-line">
            {data.map((p) => (
              <li key={p.id} className="flex items-center gap-3 px-3 py-2 text-[14px]">
                <span className="flex-1 min-w-0">
                  <b className="font-medium">{p.action === "*" ? "Everything" : titleCase(p.action)}</b> on {titleCase(p.resource).toLowerCase()}
                  <span className="text-slate">
                    {" "}
                    · {hostName(p.scopeHostId)}
                    {p.scopeResourceId && ` · only ${p.scopeResourceId}`}
                    {p.source && p.source !== "USER" && ` · from ${p.source.toLowerCase()}`}
                  </span>
                </span>
                <IconButton
                  label="Remove"
                  onClick={async () => {
                    await api.del(`${base}/${p.id}`);
                    refresh();
                  }}
                >
                  <X size={15} />
                </IconButton>
              </li>
            ))}
          </ul>
        )}
        <div className="rounded-xl bg-well/50 p-4">
          <div className="text-[14px] font-medium mb-3">Grant a permission</div>
          <div className="grid gap-3 sm:grid-cols-2">
            <Field label="Area">
              <Select value={resource} onChange={(v) => (setResource(v), setAction("*"))}>
                {Object.keys(catalog ?? {}).map((r) => (
                  <option key={r} value={r}>
                    {titleCase(r)}
                  </option>
                ))}
              </Select>
            </Field>
            <Field label="Action">
              <Select value={action} onChange={setAction}>
                <option value="*">Everything</option>
                {actions.map((a) => (
                  <option key={a} value={a}>
                    {titleCase(a)}
                  </option>
                ))}
              </Select>
            </Field>
            <Field label="Host">
              <Select value={hostId} onChange={setHostId}>
                <option value="">All hosts</option>
                {hosts.map((h) => (
                  <option key={h.id} value={h.id}>
                    {hostLabel(h)}
                  </option>
                ))}
              </Select>
            </Field>
            <Field label="Only this item" optional hint="A container or stack name, for example. Empty for all.">
              <input className={inputClass} value={target} onChange={(e) => setTarget(e.target.value)} />
            </Field>
          </div>
          {error && (
            <div className="mt-3">
              <Notice tone="error">{error}</Notice>
            </div>
          )}
          <div className="mt-3">
            <Button
              tone="quiet"
              onClick={async () => {
                setError(null);
                try {
                  await api.post(base, { resource, action, scopeHostId: hostId || null, scopeResourceId: target.trim() || null });
                  setTarget("");
                  refresh();
                } catch (e) {
                  setError((e as Error).message);
                }
              }}
            >
              Grant
            </Button>
          </div>
        </div>
      </div>
    </Modal>
  );
}
