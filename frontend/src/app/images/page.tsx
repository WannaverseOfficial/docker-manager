"use client";

import Link from "next/link";
import { useMemo, useState } from "react";
import { useHost } from "@/components/app";
import { Badge, BulkBar, Button, Checkbox, Empty, SelectHeader, Field, IconButton, Modal, Notice, PageHeader, Pagination, Search, Segmented, Spinner, inputClass, useConfirm, useToast } from "@/components/ui";
import { Trash2 } from "lucide-react";
import { api } from "@/lib/api";
import { ago, bytes, containerName, plural, shortId, splitImage } from "@/lib/format";
import { runBulk, usePaged, usePoll, useSelection } from "@/lib/hooks";
import type { Container, Image } from "@/lib/types";

type Filter = "all" | "used" | "unused";

export default function ImagesPage() {
  const { hostId } = useHost();
  const toast = useToast();
  const confirm = useConfirm();
  const { data, error, refresh } = usePoll<Image[]>(hostId ? `/api/docker/hosts/${hostId}/images` : null, 15000);
  const { data: containers } = usePoll<Container[]>(hostId ? `/api/docker/hosts/${hostId}/containers?all=true` : null, 15000);
  const [q, setQ] = useState("");
  const [filter, setFilter] = useState<Filter>("all");
  const [pulling, setPulling] = useState(false);
  const [deleting, setDeleting] = useState(false);

  const usedBy = useMemo(() => {
    const m = new Map<string, Container[]>();
    for (const c of containers ?? []) m.set(c.ImageID, [...(m.get(c.ImageID) ?? []), c]);
    return m;
  }, [containers]);

  const list = useMemo(() => {
    const needle = q.toLowerCase();
    return (data ?? [])
      .filter((i) => (filter === "used" ? usedBy.has(i.Id) : filter === "unused" ? !usedBy.has(i.Id) : true))
      .filter((i) => !needle || (i.RepoTags ?? []).some((t) => t.toLowerCase().includes(needle)) || i.Id.includes(needle))
      .sort((a, b) => b.Created - a.Created);
  }, [data, q, filter, usedBy]);

  // Only images no container uses can be deleted in bulk, so only those can be selected.
  const ready = containers != null;
  const unused = useMemo(() => (ready ? list.filter((i) => !usedBy.has(i.Id)) : []), [list, usedBy, ready]);
  const unusedIds = useMemo(() => unused.map((i) => i.Id), [unused]);
  const sel = useSelection(unusedIds);
  const paged = usePaged(list, `${q}|${filter}|${hostId}`);
  const pageUnused = paged.slice.filter((i) => ready && !usedBy.has(i.Id)).map((i) => i.Id);
  const pageAll = pageUnused.length > 0 && pageUnused.every(sel.isSelected);
  const pageSome = pageUnused.some(sel.isSelected);

  const total = (data ?? []).reduce((a, i) => a + i.Size, 0);
  const label = (img: Image) => (img.RepoTags ?? []).find((t) => t !== "<none>:<none>") ?? shortId(img.Id);

  const remove = async (img: Image) => {
    const tag = label(img);
    const users = usedBy.get(img.Id) ?? [];
    const ok = await confirm({
      title: `Delete ${tag}?`,
      body: users.length ? `It is used by ${users.map(containerName).join(", ")}. Remove those containers first.` : `Frees ${bytes(img.Size)}. It can be pulled again later.`,
      confirm: "Delete",
      danger: true,
    });
    if (!ok) return;
    try {
      await api.del(`/api/docker/hosts/${hostId}/images/${encodeURIComponent(img.Id)}?force=${users.length ? "false" : "true"}`);
      toast(`${tag} deleted`);
      refresh();
    } catch (e) {
      toast((e as Error).message, "error");
    }
  };

  const removeSelected = async () => {
    const picked = (data ?? []).filter((i) => sel.selected.includes(i.Id));
    const size = picked.reduce((a, i) => a + i.Size, 0);
    const ok = await confirm({
      title: `Delete ${plural(picked.length, "image")}?`,
      body: (
        <>
          Frees up to {bytes(size)}. No container uses them, and they can be pulled again later.
          <span className="block mt-2 text-[13px] break-words">
            {picked.slice(0, 6).map(label).join(", ")}
            {picked.length > 6 ? ` and ${picked.length - 6} more` : ""}
          </span>
        </>
      ),
      confirm: "Delete",
      danger: true,
    });
    if (!ok) return;
    setDeleting(true);
    // Unused only, so forcing just lets images with several tags go in one step.
    const r = await runBulk(picked, (img) => api.del(`/api/docker/hosts/${hostId}/images/${encodeURIComponent(img.Id)}?force=true`));
    setDeleting(false);
    sel.clear();
    refresh();
    if (r.failed.length) toast(`Deleted ${r.done}. ${plural(r.failed.length, "image")} couldn't be deleted: ${r.failed[0].message}`, "error");
    else toast(`${plural(r.done, "image")} deleted`);
  };

  return (
    <>
      <PageHeader title="Images" art="images" intro={data ? `${data.length} images using ${bytes(total)}.` : "The templates containers are created from."}>
        <Button onClick={() => setPulling(true)}>Pull image</Button>
      </PageHeader>
      <div className="flex flex-wrap items-center justify-between gap-3 mb-4">
        <Segmented<Filter>
          label="Show"
          value={filter}
          onChange={setFilter}
          options={[
            { value: "all", label: "All" },
            { value: "used", label: "In use" },
            { value: "unused", label: "Not in use" },
          ]}
        />
        <Search value={q} onChange={setQ} placeholder="Search images" />
      </div>
      {error && <Notice tone="error">{error}</Notice>}
      {!data && !error && <Spinner />}
      {data && list.length === 0 && <Empty title="No images">{data.length ? "Nothing matches." : "Pull an image or create a container to download one."}</Empty>}
      {list.length > 0 && (
        <div className="rounded-2xl border border-line bg-paper">
          <SelectHeader
            label="Select the unused images on this page"
            pageIds={pageUnused}
            all={pageAll}
            some={pageSome}
            onPage={(on) => sel.setMany(pageUnused, on)}
            summary={unused.length ? `${plural(unused.length, "image")} not in use` : ready ? "Every image is in use" : "Checking which images are in use"}
            selectAll={unused.length > 0 && sel.selected.length < unused.length ? { text: `Select all ${unused.length} unused`, onClick: () => sel.setMany(unusedIds, true) } : null}
          />
          <ul className="divide-y divide-line">
            {paged.slice.map((img) => {
              const tags = (img.RepoTags ?? []).filter((t) => t !== "<none>:<none>");
              const users = usedBy.get(img.Id) ?? [];
              return (
                <li key={img.Id} className={`flex items-center gap-3 px-4 py-3 ${sel.isSelected(img.Id) ? "bg-well/50" : ""}`}>
                  <Checkbox
                    label={`Select ${label(img)}`}
                    title={users.length ? "In use by a container" : undefined}
                    checked={sel.isSelected(img.Id)}
                    disabled={!ready || users.length > 0}
                    onChange={(on) => sel.toggle(img.Id, on)}
                  />
                  <div className="flex-1 min-w-0 grid gap-x-6 gap-y-1 sm:grid-cols-[minmax(0,1.6fr)_minmax(0,1fr)_6rem] items-center">
                    <div className="min-w-0">
                      {tags.length ? (
                        tags.slice(0, 3).map((t) => {
                          const { repo, tag } = splitImage(t);
                          return (
                            <div key={t} className="truncate">
                              {repo}
                              <span className="text-slate">:{tag}</span>
                            </div>
                          );
                        })
                      ) : (
                        <div className="text-slate">Untagged</div>
                      )}
                      <div className="text-[12.5px] text-slate font-mono">{shortId(img.Id)}</div>
                    </div>
                    <div className="text-[13px] min-w-0">
                      {users.length ? (
                        <span className="flex flex-wrap gap-1">
                          {users.slice(0, 3).map((c) => (
                            <Link key={c.Id} href={`/containers/view/?id=${c.Id}`}>
                              <Badge tone={c.State === "running" ? "live" : "plain"}>{containerName(c)}</Badge>
                            </Link>
                          ))}
                          {users.length > 3 && <Badge>+{users.length - 3}</Badge>}
                        </span>
                      ) : (
                        <span className="text-slate">Not in use</span>
                      )}
                    </div>
                    <div className="text-[13px] tabular-nums">
                      {bytes(img.Size)}
                      <div className="text-[12px] text-slate">{ago(img.Created)}</div>
                    </div>
                  </div>
                  <IconButton label="Delete image" onClick={() => remove(img)}>
                    <Trash2 size={16} />
                  </IconButton>
                </li>
              );
            })}
          </ul>
        </div>
      )}
      <Pagination page={paged.page} pages={paged.pages} total={paged.total} size={paged.size} onPage={paged.setPage} onSize={paged.setSize} noun="image" />
      <BulkBar count={sel.selected.length} noun="image" onClear={sel.clear}>
        <Button tone="danger" size="sm" busy={deleting} onClick={removeSelected}>
          <Trash2 size={14} aria-hidden /> Delete
        </Button>
      </BulkBar>
      <PullDialog open={pulling} onClose={() => setPulling(false)} hostId={hostId} onPulled={refresh} />
    </>
  );
}

function PullDialog({ open, onClose, hostId, onPulled }: { open: boolean; onClose: () => void; hostId: string | null; onPulled: () => void }) {
  const [name, setName] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const toast = useToast();
  const pull = async (e: React.FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const image = name.trim().includes(":") || name.includes("@") ? name.trim() : `${name.trim()}:latest`;
      await api.post(`/api/docker/hosts/${hostId}/images/pull?imageName=${encodeURIComponent(image)}`);
      toast(`${image} pulled`);
      setName("");
      onPulled();
      onClose();
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setBusy(false);
    }
  };
  return (
    <Modal open={open} onClose={onClose} title="Pull an image">
      <form onSubmit={pull} className="flex flex-col gap-4">
        <Field label="Image" hint="Saved credentials from Registries are used for private images. Large images can take a few minutes.">
          <input className={inputClass} value={name} onChange={(e) => setName(e.target.value)} placeholder="postgres:17-alpine" autoFocus autoCapitalize="off" spellCheck={false} />
        </Field>
        {error && <Notice tone="error">{error}</Notice>}
        <div className="flex justify-end gap-2">
          <Button type="button" tone="quiet" onClick={onClose}>
            Cancel
          </Button>
          <Button type="submit" busy={busy} disabled={!name.trim()}>
            {busy ? "Pulling" : "Pull"}
          </Button>
        </div>
      </form>
    </Modal>
  );
}
