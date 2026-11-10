"use client";

import { Suspense, useMemo, useRef, useState } from "react";
import { useSearchParams } from "next/navigation";
import { ChevronRight, Download, File, FileSymlink, Folder, FolderPlus, Lock, Trash2, Unlock, Upload } from "lucide-react";
import { useHost, useSession } from "@/components/app";
import { Button, Empty, Field, IconButton, LogView, Modal, Notice, PageHeader, Panel, Search, Spinner, inputClass, useConfirm, useToast } from "@/components/ui";
import { api, enc, qs } from "@/lib/api";
import { ago, bytes } from "@/lib/format";
import { usePoll } from "@/lib/hooks";

type Entry = { name: string; type: "dir" | "file" | "link" | "other"; size: number; modified: number; mode: string; target: string | null };
type Listing = { path: string; entries: Entry[]; truncated: boolean };
type Preview = { path: string; size: number; binary: boolean; truncated: boolean; content: string | null };

export default function VolumeBrowserPage() {
  return (
    <Suspense>
      <VolumeBrowser />
    </Suspense>
  );
}

const join = (dir: string, name: string) => (dir === "/" ? `/${name}` : `${dir}/${name}`);

function VolumeBrowser() {
  const params = useSearchParams();
  const name = params.get("name") ?? "";
  const { hostId } = useHost();
  const { session } = useSession();
  const toast = useToast();
  const confirm = useConfirm();
  const [path, setPath] = useState("/");
  const [q, setQ] = useState("");
  const [writable, setWritable] = useState(false);
  const [open, setOpen] = useState<string | null>(null);
  const [folderOpen, setFolderOpen] = useState(false);
  const [uploading, setUploading] = useState(false);
  const fileInput = useRef<HTMLInputElement>(null);

  const base = hostId && name ? `/api/docker/hosts/${hostId}/volumes/${enc(name)}/files` : null;
  const { data, error, loading, refresh } = usePoll<Listing>(base ? `${base}${qs({ path })}` : null);
  const entries = useMemo(() => (data?.entries ?? []).filter((e) => !q || e.name.toLowerCase().includes(q.toLowerCase())), [data, q]);
  const crumbs = path === "/" ? [] : path.slice(1).split("/");

  if (!session?.admin) {
    return (
      <>
        <PageHeader title={name} back={{ href: "/volumes/", label: "Volumes" }} />
        <Notice>Browsing volumes is for administrators: volumes hold every app&apos;s data, including passwords and databases.</Notice>
      </>
    );
  }

  const go = (p: string) => {
    setPath(p);
    setQ("");
    setOpen(null);
  };

  const toggleWrite = async () => {
    if (writable) return setWritable(false);
    const ok = await confirm({
      title: "Allow changes to this volume?",
      body: "You'll be able to upload, create folders and delete files. Apps using the volume see the changes straight away, and deleted files can't be brought back.",
      confirm: "Allow changes",
    });
    if (ok) setWritable(true);
  };

  const remove = async (e: Entry) => {
    const ok = await confirm({
      title: `Delete ${e.name}?`,
      body: e.type === "dir" ? "The folder and everything in it is deleted. This can't be undone." : "The file is deleted. This can't be undone.",
      confirm: "Delete",
      danger: true,
    });
    if (!ok) return;
    try {
      await api.del(`${base}${qs({ path: join(path, e.name) })}`);
      toast(`${e.name} deleted`);
      if (open === join(path, e.name)) setOpen(null);
      refresh();
    } catch (err) {
      toast((err as Error).message, "error");
    }
  };

  const upload = async (files: FileList | null) => {
    if (!files?.length) return;
    const form = new FormData();
    for (const f of Array.from(files)) form.append("files", f, f.name);
    setUploading(true);
    try {
      await api.upload(`${base}/upload${qs({ path })}`, form);
      toast(files.length === 1 ? `${files[0].name} uploaded` : `${files.length} files uploaded`);
      refresh();
    } catch (err) {
      toast((err as Error).message, "error");
    } finally {
      setUploading(false);
      if (fileInput.current) fileInput.current.value = "";
    }
  };

  const save = async (p: string, label: string) => {
    try {
      await api.download(`${base}/download${qs({ path: p })}`, label);
    } catch (err) {
      toast((err as Error).message, "error");
    }
  };

  return (
    <>
      <PageHeader
        title={name}
        back={{ href: "/volumes/", label: "Volumes" }}
        intro={writable ? "Changes are on. Anything you do here affects the apps using this volume." : "Read-only. Turn on changes to upload or delete files."}
      >
        <Button tone={writable ? "danger" : "quiet"} onClick={toggleWrite}>
          {writable ? <Unlock size={15} aria-hidden /> : <Lock size={15} aria-hidden />}
          {writable ? "Changes on" : "Read-only"}
        </Button>
        <Button tone="quiet" onClick={() => save(path, path === "/" ? `${name}.tar` : `${crumbs[crumbs.length - 1]}.tar`)}>
          <Download size={15} aria-hidden /> Download {path === "/" ? "all" : "folder"}
        </Button>
      </PageHeader>

      <div className="flex flex-wrap items-center justify-between gap-3 mb-4">
        <nav aria-label="Folder" className="flex flex-wrap items-center gap-1 text-[14px] min-w-0">
          <button className={`rounded-md px-1.5 py-0.5 hover:bg-well ${path === "/" ? "text-ink font-medium" : "text-slate"}`} onClick={() => go("/")}>
            {name.length > 24 ? `${name.slice(0, 12)}…` : name}
          </button>
          {crumbs.map((c, i) => {
            const p = "/" + crumbs.slice(0, i + 1).join("/");
            return (
              <span key={p} className="inline-flex items-center gap-1 min-w-0">
                <ChevronRight size={14} className="text-slate shrink-0" aria-hidden />
                <button className={`rounded-md px-1.5 py-0.5 hover:bg-well truncate max-w-[14rem] ${i === crumbs.length - 1 ? "text-ink font-medium" : "text-slate"}`} onClick={() => go(p)}>
                  {c}
                </button>
              </span>
            );
          })}
        </nav>
        <div className="flex flex-wrap items-center gap-2">
          {writable && (
            <>
              <input ref={fileInput} type="file" multiple className="hidden" onChange={(e) => upload(e.target.files)} />
              <Button size="sm" tone="quiet" busy={uploading} onClick={() => fileInput.current?.click()}>
                <Upload size={14} aria-hidden /> Upload
              </Button>
              <Button size="sm" tone="quiet" onClick={() => setFolderOpen(true)}>
                <FolderPlus size={14} aria-hidden /> New folder
              </Button>
            </>
          )}
          <Search value={q} onChange={setQ} placeholder="Filter this folder" />
        </div>
      </div>

      {error && <Notice tone="error">{error}</Notice>}
      {loading && !data && <Spinner label="Opening the volume" />}
      {data && (
        <div className={`grid gap-6 ${open ? "lg:grid-cols-[minmax(0,1fr)_minmax(0,1.1fr)]" : ""}`}>
          <div className="min-w-0">
            {entries.length === 0 ? (
              <Empty title={data.entries.length ? "Nothing matches" : "This folder is empty"}>{writable && !data.entries.length ? "Upload files or create a folder." : undefined}</Empty>
            ) : (
              <ul className="rounded-2xl border border-line bg-paper divide-y divide-line">
                {path !== "/" && (
                  <li>
                    <button className="w-full flex items-center gap-3 px-4 py-2.5 text-left text-[14px] text-slate hover:bg-well/60" onClick={() => go(path.slice(0, path.lastIndexOf("/")) || "/")}>
                      <Folder size={16} aria-hidden /> ..
                    </button>
                  </li>
                )}
                {entries.map((e) => {
                  const p = join(path, e.name);
                  const Icon = e.type === "dir" ? Folder : e.type === "link" ? FileSymlink : File;
                  return (
                    <li key={e.name} className={`flex items-center gap-2 pr-2 ${open === p ? "bg-well/60" : ""}`}>
                      <button
                        className="flex-1 min-w-0 flex items-center gap-3 pl-4 py-2.5 text-left hover:bg-well/40"
                        onClick={() => (e.type === "dir" ? go(p) : e.type === "file" ? setOpen(p) : undefined)}
                        title={e.target ? `${e.name} → ${e.target}` : e.name}
                      >
                        <Icon size={16} className={e.type === "dir" ? "text-ink shrink-0" : "text-slate shrink-0"} aria-hidden />
                        <span className="flex-1 min-w-0">
                          <span className="block truncate text-[14px]">{e.name}</span>
                          <span className="block text-[12px] text-slate truncate">
                            {e.type === "dir" ? "Folder" : e.type === "link" ? `Link to ${e.target ?? "?"}` : bytes(e.size)}
                            {e.modified ? ` · ${ago(e.modified)}` : ""}
                            <span className="hidden sm:inline font-mono"> · {e.mode}</span>
                          </span>
                        </span>
                      </button>
                      {(e.type === "file" || e.type === "dir") && (
                        <IconButton label={`Download ${e.name}`} onClick={() => save(p, e.type === "dir" ? `${e.name}.tar` : e.name)}>
                          <Download size={15} />
                        </IconButton>
                      )}
                      {writable && (
                        <IconButton label={`Delete ${e.name}`} onClick={() => remove(e)}>
                          <Trash2 size={15} />
                        </IconButton>
                      )}
                    </li>
                  );
                })}
              </ul>
            )}
            {data.truncated && <p className="text-[13px] text-slate mt-2">This folder has more than 5,000 entries; only the first 5,000 are shown.</p>}
          </div>
          {open && base && <FilePreview base={base} path={open} onClose={() => setOpen(null)} onDownload={() => save(open, open.slice(open.lastIndexOf("/") + 1))} />}
        </div>
      )}

      {folderOpen && base && (
        <NewFolder
          onClose={() => setFolderOpen(false)}
          onCreate={async (n) => {
            await api.post(`${base}/folder`, { path, name: n });
            setFolderOpen(false);
            refresh();
          }}
        />
      )}
    </>
  );
}

function FilePreview({ base, path, onClose, onDownload }: { base: string; path: string; onClose: () => void; onDownload: () => void }) {
  const { data, error } = usePoll<Preview>(`${base}/preview${qs({ path })}`);
  const name = path.slice(path.lastIndexOf("/") + 1);
  return (
    <Panel
      title={name}
      className="min-w-0 lg:sticky lg:top-6 self-start"
      action={
        <span className="flex items-center gap-1">
          <Button size="sm" tone="ghost" onClick={onDownload}>
            <Download size={14} aria-hidden /> Download
          </Button>
          <Button size="sm" tone="ghost" onClick={onClose}>
            Close
          </Button>
        </span>
      }
    >
      {error && <Notice tone="error">{error}</Notice>}
      {!data && !error && <Spinner />}
      {data && data.binary && <p className="text-[14px] text-slate">This is a binary file ({bytes(data.size)}), so it can&apos;t be shown here. Download it instead.</p>}
      {data && !data.binary && (
        <>
          <LogView text={data.content ?? ""} height="h-[520px]" wrap filename={name} empty="This file is empty." />
          {data.truncated && <p className="text-[12.5px] text-slate mt-2">Showing the first 512 KB of {bytes(data.size)}. Download it to see the rest.</p>}
        </>
      )}
    </Panel>
  );
}

function NewFolder({ onClose, onCreate }: { onClose: () => void; onCreate: (name: string) => Promise<void> }) {
  const [name, setName] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  return (
    <Modal open onClose={onClose} title="New folder">
      <form
        className="flex flex-col gap-4"
        onSubmit={async (e) => {
          e.preventDefault();
          setBusy(true);
          setError(null);
          try {
            await onCreate(name.trim());
          } catch (err) {
            setError((err as Error).message);
            setBusy(false);
          }
        }}
      >
        <Field label="Name">
          <input className={inputClass} value={name} onChange={(e) => setName(e.target.value)} autoFocus spellCheck={false} />
        </Field>
        {error && <Notice tone="error">{error}</Notice>}
        <div className="flex justify-end gap-2">
          <Button type="button" tone="quiet" onClick={onClose}>
            Cancel
          </Button>
          <Button type="submit" busy={busy} disabled={!name.trim() || /[\\/]/.test(name)}>
            Create
          </Button>
        </div>
      </form>
    </Modal>
  );
}

