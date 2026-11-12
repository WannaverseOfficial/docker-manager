"use client";

import Link from "next/link";
import { useMemo, useState } from "react";
import { Trash2 } from "lucide-react";
import { useHost, useSession } from "@/components/app";
import { Badge, BulkBar, Button, Checkbox, Empty, Field, IconButton, Modal, Notice, PageHeader, Pagination, Search, SelectHeader, Spinner, inputClass, useConfirm, useToast } from "@/components/ui";
import { ManagedBadge } from "@/components/managed";
import { api } from "@/lib/api";
import { ago, containerName, plural } from "@/lib/format";
import { runBulk, usePaged, usePoll, useSelection } from "@/lib/hooks";
import { useManaged } from "@/lib/managed";
import type { Container, Volume } from "@/lib/types";

const PROJECT = "com.docker.compose.project";

export default function VolumesPage() {
  const { hostId } = useHost();
  const { session } = useSession();
  const toast = useToast();
  const confirm = useConfirm();
  const { data, error, refresh } = usePoll<{ Volumes: Volume[] | null }>(hostId ? `/api/docker/hosts/${hostId}/volumes` : null, 15000);
  const { data: containers } = usePoll<Container[]>(hostId ? `/api/docker/hosts/${hostId}/containers?all=true` : null, 15000);
  const [q, setQ] = useState("");
  const [creating, setCreating] = useState(false);
  const [name, setName] = useState("");
  const [busy, setBusy] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const [createError, setCreateError] = useState<string | null>(null);

  const usedBy = useMemo(() => {
    const m = new Map<string, Container[]>();
    for (const c of containers ?? []) for (const mnt of c.Mounts ?? []) if (mnt.Name) m.set(mnt.Name, [...(m.get(mnt.Name) ?? []), c]);
    return m;
  }, [containers]);

  const volumes = data?.Volumes ?? null;
  const list = useMemo(
    () => (volumes ?? []).filter((v) => !q || v.Name.toLowerCase().includes(q.toLowerCase())).sort((a, b) => Number(!!usedBy.get(b.Name)) - Number(!!usedBy.get(a.Name)) || a.Name.localeCompare(b.Name)),
    [volumes, q, usedBy],
  );

  // Volumes a container still mounts can't be deleted, so they can't be selected. Neither can volumes the
  // configuration file manages: they are removed through the file.
  const managed = useManaged();
  const managedEntry = (v: Volume) => managed.find("volumes", v.Name, hostId)?.key;
  const ready = containers != null;
  // eslint-disable-next-line react-hooks/exhaustive-deps
  const unused = useMemo(() => (ready ? list.filter((v) => !usedBy.has(v.Name) && !managedEntry(v)) : []), [list, usedBy, ready, managed.items]);
  const unusedIds = useMemo(() => unused.map((v) => v.Name), [unused]);
  const sel = useSelection(unusedIds);
  const paged = usePaged(list, `${q}|${hostId}`);
  const pageUnused = paged.slice.filter((v) => ready && !usedBy.has(v.Name) && !managedEntry(v)).map((v) => v.Name);
  const pageAll = pageUnused.length > 0 && pageUnused.every(sel.isSelected);
  const pageSome = pageUnused.some(sel.isSelected);

  const short = (n: string) => (/^[0-9a-f]{64}$/.test(n) ? `${n.slice(0, 12)}…` : n);

  const remove = async (v: Volume) => {
    const ok = await confirm({ title: `Delete volume ${short(v.Name)}?`, body: "Everything stored in it is deleted. This can't be undone.", confirm: "Delete", danger: true });
    if (!ok) return;
    try {
      await api.del(`/api/docker/hosts/${hostId}/volumes/${encodeURIComponent(v.Name)}`);
      toast("Volume deleted");
      refresh();
    } catch (e) {
      toast((e as Error).message, "error");
    }
  };

  const removeSelected = async () => {
    const picked = (volumes ?? []).filter((v) => sel.selected.includes(v.Name));
    const stacks = [...new Set(picked.map((v) => v.Labels?.[PROJECT]).filter(Boolean))] as string[];
    const ok = await confirm({
      title: `Delete ${plural(picked.length, "volume")}?`,
      body: (
        <>
          Everything stored in them is deleted. This can&apos;t be undone.
          {stacks.length > 0 && (
            <span className="block mt-2">
              Some belong to {stacks.length === 1 ? "the stack" : "the stacks"} <b className="text-ink">{stacks.join(", ")}</b>, which will start with empty data on its next deploy.
            </span>
          )}
          <span className="block mt-2 text-[13px] break-words">
            {picked.slice(0, 6).map((v) => short(v.Name)).join(", ")}
            {picked.length > 6 ? ` and ${picked.length - 6} more` : ""}
          </span>
        </>
      ),
      confirm: `Delete ${plural(picked.length, "volume")}`,
      danger: true,
    });
    if (!ok) return;
    setDeleting(true);
    const r = await runBulk(picked, (v) => api.del(`/api/docker/hosts/${hostId}/volumes/${encodeURIComponent(v.Name)}`));
    setDeleting(false);
    sel.clear();
    refresh();
    if (r.failed.length) toast(`Deleted ${r.done}. ${plural(r.failed.length, "volume")} couldn't be deleted: ${r.failed[0].message}`, "error");
    else toast(`${plural(r.done, "volume")} deleted`);
  };

  return (
    <>
      <PageHeader title="Volumes" intro="Storage that outlives containers. Databases and uploads belong here.">
        <Button onClick={() => setCreating(true)}>New volume</Button>
      </PageHeader>
      <div className="flex justify-end mb-4">
        <Search value={q} onChange={setQ} placeholder="Search volumes" />
      </div>
      {error && <Notice tone="error">{error}</Notice>}
      {!data && !error && <Spinner />}
      {volumes && list.length === 0 && <Empty title="No volumes">{volumes.length ? "Nothing matches." : "Volumes are created when containers ask for them."}</Empty>}
      {list.length > 0 && (
        <div className="rounded-2xl border border-line bg-paper">
          <SelectHeader
            label="Select the unused volumes on this page"
            pageIds={pageUnused}
            all={pageAll}
            some={pageSome}
            onPage={(on) => sel.setMany(pageUnused, on)}
            summary={unused.length ? `${plural(unused.length, "volume")} not in use` : ready ? "Every volume is in use" : "Checking which volumes are in use"}
            selectAll={unused.length > 0 && sel.selected.length < unused.length ? { text: `Select all ${unused.length} unused`, onClick: () => sel.setMany(unusedIds, true) } : null}
          />
          <ul className="divide-y divide-line">
            {paged.slice.map((v) => {
              const users = usedBy.get(v.Name) ?? [];
              const anonymous = /^[0-9a-f]{64}$/.test(v.Name);
              const project = v.Labels?.[PROJECT];
              return (
                <li key={v.Name} className={`flex items-center gap-3 px-4 py-3 ${sel.isSelected(v.Name) ? "bg-well/50" : ""}`}>
                  <Checkbox
                    label={`Select ${short(v.Name)}`}
                    title={managedEntry(v) ? "Managed by the configuration file" : users.length ? "In use by a container" : undefined}
                    checked={sel.isSelected(v.Name)}
                    disabled={!ready || users.length > 0 || !!managedEntry(v)}
                    onChange={(on) => sel.toggle(v.Name, on)}
                  />
                  <div className="flex-1 min-w-0 grid gap-x-6 gap-y-1 sm:grid-cols-[minmax(0,1.5fr)_minmax(0,1fr)_7rem] items-center">
                    <div className="min-w-0">
                      {session?.admin ? (
                        <Link href={`/volumes/view/?name=${encodeURIComponent(v.Name)}`} className="block truncate font-mono text-[13.5px] hover:underline underline-offset-2" title="Browse files">
                          {short(v.Name)}
                        </Link>
                      ) : (
                        <div className="truncate font-mono text-[13.5px]">{short(v.Name)}</div>
                      )}
                      <div className="text-[12.5px] text-slate flex gap-2">
                        {anonymous && <span>anonymous</span>}
                        {project && <span>stack {project}</span>}
                        <span>{v.Driver}</span>
                        {managedEntry(v) && <ManagedBadge entry={`volumes.${managedEntry(v)}`} />}
                      </div>
                    </div>
                    <div className="text-[13px]">
                      {users.length ? (
                        <span className="flex flex-wrap gap-1">
                          {users.map((c) => (
                            <Link key={c.Id} href={`/containers/view/?id=${c.Id}`}>
                              <Badge tone={c.State === "running" ? "live" : "plain"}>{containerName(c)}</Badge>
                            </Link>
                          ))}
                        </span>
                      ) : (
                        <span className="text-slate">Not in use</span>
                      )}
                    </div>
                    <div className="text-[12.5px] text-slate">{v.CreatedAt ? ago(v.CreatedAt) : ""}</div>
                  </div>
                  {!managedEntry(v) && (
                    <IconButton label="Delete volume" onClick={() => remove(v)} disabled={users.length > 0} title={users.length ? "In use" : "Delete volume"}>
                      <Trash2 size={16} />
                    </IconButton>
                  )}
                </li>
              );
            })}
          </ul>
        </div>
      )}
      <Pagination page={paged.page} pages={paged.pages} total={paged.total} size={paged.size} onPage={paged.setPage} onSize={paged.setSize} noun="volume" />
      <BulkBar count={sel.selected.length} noun="volume" onClear={sel.clear}>
        <Button tone="danger" size="sm" busy={deleting} onClick={removeSelected}>
          <Trash2 size={14} aria-hidden /> Delete
        </Button>
      </BulkBar>
      <Modal open={creating} onClose={() => setCreating(false)} title="New volume">
        <form
          className="flex flex-col gap-4"
          onSubmit={async (e) => {
            e.preventDefault();
            setBusy(true);
            setCreateError(null);
            try {
              await api.post(`/api/docker/hosts/${hostId}/volumes?volumeName=${encodeURIComponent(name.trim())}`);
              setCreating(false);
              setName("");
              refresh();
            } catch (err) {
              setCreateError((err as Error).message);
            } finally {
              setBusy(false);
            }
          }}
        >
          <Field label="Name">
            <input className={inputClass} value={name} onChange={(e) => setName(e.target.value)} autoFocus placeholder="app-data" />
          </Field>
          {createError && <Notice tone="error">{createError}</Notice>}
          <div className="flex justify-end gap-2">
            <Button type="button" tone="quiet" onClick={() => setCreating(false)}>
              Cancel
            </Button>
            <Button type="submit" busy={busy} disabled={!name.trim()}>
              Create
            </Button>
          </div>
        </form>
      </Modal>
    </>
  );
}
