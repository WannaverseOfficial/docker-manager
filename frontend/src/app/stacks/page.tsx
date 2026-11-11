"use client";

import Link from "next/link";
import { useMemo, useState } from "react";
import { useHost, useSession } from "@/components/app";
import { Badge, Button, Empty, LinkButton, Modal, Notice, PageHeader, Search, Select, Spinner, useConfirm } from "@/components/ui";
import { api } from "@/lib/api";
import { ago } from "@/lib/format";
import { usePoll, useStored } from "@/lib/hooks";
import type { StackSummary } from "@/lib/types";

const SOURCE: Record<StackSummary["source"], string> = {
  UI: "Created here",
  TEMPLATE: "From a template",
  GIT: "From git",
  IMPORTED: "Imported",
  EXTERNAL: "Not managed here",
};

type ImportResult = { name: string; imported: boolean; message: string; warnings: string[] };

type Sort = "attention" | "name" | "deployed" | "running" | "managed";

/** Lower comes first: failed or partly down, then running, then stopped. */
function attention(s: StackSummary) {
  if (s.lastError || (s.running > 0 && s.running < s.total)) return 0;
  if (s.running > 0) return 1;
  return 2;
}

export default function StacksPage() {
  const { hostId } = useHost();
  const { session } = useSession();
  const admin = !!session?.admin;
  const { data, error, refresh } = usePoll<StackSummary[]>(hostId ? `/api/docker/hosts/${hostId}/stacks` : null, 8000);
  const confirm = useConfirm();
  const [importing, setImporting] = useState(false);
  const [results, setResults] = useState<ImportResult[] | null>(null);
  const unmanaged = (data ?? []).filter((s) => !s.managed);
  const importAll = async () => {
    const ok = await confirm({
      title: `Import ${unmanaged.length === 1 ? "1 stack" : `all ${unmanaged.length} stacks`} started outside Docker Manager?`,
      body: "Their compose files are read from where they were started, along with their .env files (or one rebuilt from the running containers). Nothing is restarted; you can deploy each one when you're ready.",
      confirm: "Import",
    });
    if (!ok) return;
    setImporting(true);
    try {
      setResults(await api.post<ImportResult[]>(`/api/docker/hosts/${hostId}/stacks/import-all`));
      refresh();
    } catch (e) {
      setResults([{ name: "", imported: false, message: (e as Error).message, warnings: [] }]);
    } finally {
      setImporting(false);
    }
  };
  const [q, setQ] = useState("");
  const [sort, setSort] = useStored<Sort>("dm.stackSort", "attention");
  const list = useMemo(() => {
    const needle = q.toLowerCase();
    const found = (data ?? []).filter((s) => !needle || s.name.includes(needle) || s.services.some((x) => x.includes(needle)));
    const byName = (a: StackSummary, b: StackSummary) => a.name.localeCompare(b.name);
    const compare: Record<Sort, (a: StackSummary, b: StackSummary) => number> = {
      attention: (a, b) => attention(a) - attention(b) || byName(a, b),
      name: byName,
      deployed: (a, b) => (b.lastDeployedAt ?? 0) - (a.lastDeployedAt ?? 0) || byName(a, b),
      running: (a, b) => b.running - a.running || byName(a, b),
      managed: (a, b) => Number(b.managed) - Number(a.managed) || byName(a, b),
    };
    return [...found].sort(compare[sort] ?? byName);
  }, [data, q, sort]);

  return (
    <>
      <PageHeader
        title="Stacks"
        art="stacks"
        intro="Groups of containers defined by a Docker Compose file. Edit the file and redeploy at any time; every deploy is kept as a version you can go back to."
      >
        {admin && (
          <>
            {unmanaged.length > 0 && (
              <Button tone="quiet" busy={importing} onClick={importAll}>
                Import {unmanaged.length === 1 ? "1 unmanaged" : `${unmanaged.length} unmanaged`}
              </Button>
            )}
            <LinkButton href="/templates/?type=COMPOSE" tone="quiet">
              From a template
            </LinkButton>
            <LinkButton href="/stacks/new/">New stack</LinkButton>
          </>
        )}
      </PageHeader>
      <div className="flex flex-wrap items-center justify-end gap-3 mb-4">
        <Select value={sort} onChange={(v) => setSort(v as Sort)} aria-label="Sort stacks" className="w-52">
          <option value="attention">Needs attention first</option>
          <option value="name">Name, A to Z</option>
          <option value="deployed">Recently deployed</option>
          <option value="running">Most containers running</option>
          <option value="managed">Managed here first</option>
        </Select>
        <Search value={q} onChange={setQ} placeholder="Search stacks" />
      </div>
      {error && !data && <Notice tone="error">{error}</Notice>}
      {!data && !error && <Spinner />}
      {data && list.length === 0 && (
        <Empty title={data.length ? "Nothing matches" : "No stacks yet"} action={!data.length && admin && <LinkButton href="/stacks/new/">Create a stack</LinkButton>}>
          {data.length ? "Try another search." : "Paste a compose file, or start from a template like WordPress or Pi-hole."}
        </Empty>
      )}
      {list.length > 0 && (
        <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-3">
          {list.map((s) => {
            const state = s.total === 0 ? "down" : s.running === s.total ? "up" : s.running === 0 ? "stopped" : "partial";
            return (
              <Link
                key={s.name}
                href={`/stacks/view/?name=${encodeURIComponent(s.name)}`}
                className="rounded-2xl bg-paper border border-line p-5 hover:border-slate/60 transition-colors min-w-0 flex flex-col gap-3"
              >
                <div className="flex items-start justify-between gap-3">
                  <div className="min-w-0">
                    <div className="font-display text-[1.05rem] truncate">{s.name}</div>
                    <div className="text-[13px] text-slate">{s.templateName ? `From ${s.templateName}` : SOURCE[s.source]}</div>
                  </div>
                  <span className="flex items-center gap-1.5 text-[13px] shrink-0">
                    <span
                      className={`size-2 rounded-full ${state === "up" ? "bg-marigold" : state === "partial" ? "bg-caution" : s.lastError ? "bg-danger" : "bg-slate/60"}`}
                      aria-hidden
                    />
                    {state === "down" ? "Not running" : `${s.running}/${s.total} running`}
                  </span>
                </div>
                <div className="flex flex-wrap gap-1.5">
                  {s.services.slice(0, 6).map((x) => (
                    <Badge key={x}>{x}</Badge>
                  ))}
                  {s.services.length > 6 && <Badge>+{s.services.length - 6}</Badge>}
                </div>
                <div className="text-[12.5px] text-slate mt-auto">
                  {s.lastError ? <span className="text-danger line-clamp-1">Last deploy failed</span> : s.lastDeployedAt
                      ? `Deployed ${ago(s.lastDeployedAt)}`
                      : s.source === "IMPORTED"
                        ? "Imported, not redeployed from here yet"
                        : s.managed
                          ? "Not deployed yet"
                          : "Started outside Docker Manager"}
                </div>
              </Link>
            );
          })}
        </div>
      )}
      <Modal open={!!results} onClose={() => setResults(null)} title="Import results" wide>
        <ul className="flex flex-col divide-y divide-line">
          {(results ?? []).map((r, i) => (
            <li key={r.name || i} className="py-3 first:pt-0">
              <div className="flex items-center gap-2 text-[14px]">
                <Badge tone={r.imported ? "ok" : "warn"}>{r.imported ? "imported" : "skipped"}</Badge>
                <span className="font-medium">{r.name}</span>
              </div>
              <p className="text-[13px] text-slate mt-1">{r.message}</p>
              {r.warnings.map((w) => (
                <p key={w} className="text-[13px] text-caution mt-1">
                  {w}
                </p>
              ))}
            </li>
          ))}
        </ul>
        <div className="flex justify-end mt-4">
          <Button onClick={() => setResults(null)}>Done</Button>
        </div>
      </Modal>
    </>
  );
}
