"use client";

import Link from "next/link";
import { useMemo, useState } from "react";
import { Trash2 } from "lucide-react";
import { useHost } from "@/components/app";
import { Badge, BulkBar, Button, Checkbox, Empty, Field, IconButton, Modal, Notice, PageHeader, Pagination, Search, SelectHeader, Spinner, inputClass, useConfirm, useToast } from "@/components/ui";
import { ManagedBadge } from "@/components/managed";
import { api } from "@/lib/api";
import { containerName, plural } from "@/lib/format";
import { runBulk, usePaged, usePoll, useSelection } from "@/lib/hooks";
import { useManaged } from "@/lib/managed";
import type { Container, Network } from "@/lib/types";

const BUILTIN = ["bridge", "host", "none"];

export default function NetworksPage() {
  const { hostId } = useHost();
  const toast = useToast();
  const confirm = useConfirm();
  const { data, error, refresh } = usePoll<Network[]>(hostId ? `/api/docker/hosts/${hostId}/networks` : null, 15000);
  const { data: containers } = usePoll<Container[]>(hostId ? `/api/docker/hosts/${hostId}/containers?all=true` : null, 15000);
  const [q, setQ] = useState("");
  const [creating, setCreating] = useState(false);
  const [name, setName] = useState("");
  const [busy, setBusy] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const [createError, setCreateError] = useState<string | null>(null);
  const managed = useManaged();
  // Networks the configuration file manages are deleted by removing them from the file.
  const managedEntry = (n: Network) => managed.find("networks", n.Name, hostId)?.key;

  const members = useMemo(() => {
    const m = new Map<string, Container[]>();
    for (const c of containers ?? []) for (const n of Object.keys(c.NetworkSettings?.Networks ?? {})) m.set(n, [...(m.get(n) ?? []), c]);
    return m;
  }, [containers]);

  const list = useMemo(
    () =>
      [...(data ?? [])]
        .filter((n) => !q || n.Name.toLowerCase().includes(q.toLowerCase()))
        .sort((a, b) => Number(BUILTIN.includes(a.Name)) - Number(BUILTIN.includes(b.Name)) || a.Name.localeCompare(b.Name)),
    [data, q],
  );

  // Built-in networks, networks with containers on them and managed ones can't be deleted.
  const ready = containers != null;
  const removable = (n: Network) => ready && !BUILTIN.includes(n.Name) && !members.has(n.Name) && !managedEntry(n);
  // eslint-disable-next-line react-hooks/exhaustive-deps
  const unused = useMemo(() => list.filter(removable), [list, members, ready, managed.items]);
  const unusedIds = useMemo(() => unused.map((n) => n.Id), [unused]);
  const sel = useSelection(unusedIds);
  const paged = usePaged(list, `${q}|${hostId}`);
  const pageUnused = paged.slice.filter(removable).map((n) => n.Id);
  const pageAll = pageUnused.length > 0 && pageUnused.every(sel.isSelected);
  const pageSome = pageUnused.some(sel.isSelected);

  const removeSelected = async () => {
    const picked = (data ?? []).filter((n) => sel.selected.includes(n.Id));
    const ok = await confirm({
      title: `Delete ${plural(picked.length, "network")}?`,
      body: (
        <>
          No container is connected to them.
          <span className="block mt-2 text-[13px] break-words">
            {picked.slice(0, 8).map((n) => n.Name).join(", ")}
            {picked.length > 8 ? ` and ${picked.length - 8} more` : ""}
          </span>
        </>
      ),
      confirm: "Delete",
      danger: true,
    });
    if (!ok) return;
    setDeleting(true);
    const r = await runBulk(picked, (n) => api.del(`/api/docker/hosts/${hostId}/networks/${n.Id}`));
    setDeleting(false);
    sel.clear();
    refresh();
    if (r.failed.length) toast(`Deleted ${r.done}. ${plural(r.failed.length, "network")} couldn't be deleted: ${r.failed[0].message}`, "error");
    else toast(`${plural(r.done, "network")} deleted`);
  };

  return (
    <>
      <PageHeader title="Networks" intro="Containers on the same network can reach each other by container name.">
        <Button onClick={() => setCreating(true)}>New network</Button>
      </PageHeader>
      <div className="flex justify-end mb-4">
        <Search value={q} onChange={setQ} placeholder="Search networks" />
      </div>
      {error && <Notice tone="error">{error}</Notice>}
      {!data && !error && <Spinner />}
      {data && list.length === 0 && <Empty title="No networks">{data.length ? "Nothing matches." : undefined}</Empty>}
      {list.length > 0 && (
        <div className="rounded-2xl border border-line bg-paper">
          <SelectHeader
            label="Select the unused networks on this page"
            pageIds={pageUnused}
            all={pageAll}
            some={pageSome}
            onPage={(on) => sel.setMany(pageUnused, on)}
            summary={unused.length ? `${plural(unused.length, "network")} without containers` : ready ? "Every network is in use" : "Checking which networks are in use"}
            selectAll={unused.length > 0 && sel.selected.length < unused.length ? { text: `Select all ${unused.length} unused`, onClick: () => sel.setMany(unusedIds, true) } : null}
          />
          <ul className="divide-y divide-line">
            {paged.slice.map((n) => {
              const builtin = BUILTIN.includes(n.Name);
              const users = members.get(n.Name) ?? [];
              const subnet = n.IPAM?.Config?.[0]?.Subnet;
              return (
                <li key={n.Id} className={`flex items-center gap-3 px-4 py-3 ${sel.isSelected(n.Id) ? "bg-well/50" : ""}`}>
                  <Checkbox
                    label={`Select ${n.Name}`}
                    title={builtin ? "Built into Docker" : managedEntry(n) ? "Managed by the configuration file" : users.length ? "Containers are connected" : undefined}
                    checked={sel.isSelected(n.Id)}
                    disabled={!removable(n)}
                    onChange={(on) => sel.toggle(n.Id, on)}
                  />
                  <div className="flex-1 min-w-0 grid gap-x-6 gap-y-1 sm:grid-cols-[minmax(0,1.2fr)_minmax(0,1.4fr)] items-center">
                    <div className="min-w-0">
                      <div className="truncate flex items-center gap-2">
                        {n.Name}
                        {builtin && <Badge>built-in</Badge>}
                        {n.Internal && <Badge>internal</Badge>}
                        {managedEntry(n) && <ManagedBadge entry={`networks.${managedEntry(n)}`} />}
                      </div>
                      <div className="text-[12.5px] text-slate">
                        {n.Driver}
                        {subnet && ` · ${subnet}`}
                      </div>
                    </div>
                    <div className="text-[13px] min-w-0">
                      {users.length ? (
                        <span className="flex flex-wrap gap-1">
                          {users.slice(0, 6).map((c) => (
                            <Link key={c.Id} href={`/containers/view/?id=${c.Id}`}>
                              <Badge tone={c.State === "running" ? "live" : "plain"}>{containerName(c)}</Badge>
                            </Link>
                          ))}
                          {users.length > 6 && <Badge>+{users.length - 6}</Badge>}
                        </span>
                      ) : (
                        <span className="text-slate">No containers</span>
                      )}
                    </div>
                  </div>
                  {!builtin && !managedEntry(n) && (
                    <IconButton
                      label="Delete network"
                      disabled={users.length > 0}
                      onClick={async () => {
                        if (!(await confirm({ title: `Delete network ${n.Name}?`, confirm: "Delete", danger: true }))) return;
                        try {
                          await api.del(`/api/docker/hosts/${hostId}/networks/${n.Id}`);
                          refresh();
                        } catch (e) {
                          toast((e as Error).message, "error");
                        }
                      }}
                    >
                      <Trash2 size={16} />
                    </IconButton>
                  )}
                </li>
              );
            })}
          </ul>
        </div>
      )}
      <Pagination page={paged.page} pages={paged.pages} total={paged.total} size={paged.size} onPage={paged.setPage} onSize={paged.setSize} noun="network" />
      <BulkBar count={sel.selected.length} noun="network" onClear={sel.clear}>
        <Button tone="danger" size="sm" busy={deleting} onClick={removeSelected}>
          <Trash2 size={14} aria-hidden /> Delete
        </Button>
      </BulkBar>
      <Modal open={creating} onClose={() => setCreating(false)} title="New network">
        <form
          className="flex flex-col gap-4"
          onSubmit={async (e) => {
            e.preventDefault();
            setBusy(true);
            setCreateError(null);
            try {
              await api.post(`/api/docker/hosts/${hostId}/networks?networkName=${encodeURIComponent(name.trim())}`);
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
          <Field label="Name" hint="A bridge network. Attach containers to it in their settings.">
            <input className={inputClass} value={name} onChange={(e) => setName(e.target.value)} autoFocus placeholder="backend" />
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
