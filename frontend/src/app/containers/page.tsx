"use client";

import Link from "next/link";
import { useMemo, useState } from "react";
import { useHost } from "@/components/app";
import { COMPOSE_PROJECT, ContainerMenu } from "@/components/containers";
import { Badge, Empty, LinkButton, Notice, PageHeader, Pagination, Search, Segmented, Spinner, StateBadge } from "@/components/ui";
import { ago, containerName, portList } from "@/lib/format";
import { usePaged, usePending, usePoll } from "@/lib/hooks";
import type { Container } from "@/lib/types";

type Filter = "all" | "running" | "stopped";

export default function ContainersPage() {
  const { hostId } = useHost();
  const { data, error, refresh } = usePoll<Container[]>(hostId ? `/api/docker/hosts/${hostId}/containers?all=true` : null, 5000);
  const [q, setQ] = useState("");
  const [filter, setFilter] = useState<Filter>("all");

  const list = useMemo(() => {
    const needle = q.trim().toLowerCase();
    return (data ?? [])
      .filter((c) => (filter === "running" ? c.State === "running" : filter === "stopped" ? c.State !== "running" : true))
      .filter((c) => !needle || containerName(c).toLowerCase().includes(needle) || c.Image.toLowerCase().includes(needle) || (c.Labels?.[COMPOSE_PROJECT] ?? "").includes(needle))
      .sort((a, b) => (a.State === "running" ? 0 : 1) - (b.State === "running" ? 0 : 1) || containerName(a).localeCompare(containerName(b)));
  }, [data, q, filter]);

  const running = (data ?? []).filter((c) => c.State === "running").length;
  const paged = usePaged(list, `${q}|${filter}|${hostId}`);

  return (
    <>
      <PageHeader title="Containers" art="containers" intro={data ? `${running} of ${data.length} running.` : "Everything running on this host, and what's stopped."}>
        <LinkButton href="/templates/" tone="quiet">
          From a template
        </LinkButton>
        <LinkButton href="/containers/new/">New container</LinkButton>
      </PageHeader>

      <div className="flex flex-wrap items-center justify-between gap-3 mb-4">
        <Segmented<Filter>
          label="Show"
          value={filter}
          onChange={setFilter}
          options={[
            { value: "all", label: "All" },
            { value: "running", label: "Running" },
            { value: "stopped", label: "Stopped" },
          ]}
        />
        <Search value={q} onChange={setQ} placeholder="Search containers" />
      </div>

      {error && !data && <Notice tone="error">{error}</Notice>}
      {!data && !error && <Spinner />}
      {data && list.length === 0 && (
        <Empty title={data.length ? "Nothing matches" : "No containers yet"} action={!data.length && <LinkButton href="/containers/new/">Create a container</LinkButton>}>
          {data.length ? "Try another search or filter." : "Start one from an image or pick a ready-made template."}
        </Empty>
      )}
      {list.length > 0 && hostId && (
        <>
          <ul className="rounded-2xl border border-line bg-paper divide-y divide-line">
            {paged.slice.map((c) => (
              <Row key={c.Id} c={c} hostId={hostId} onChanged={refresh} />
            ))}
          </ul>
          <Pagination page={paged.page} pages={paged.pages} total={paged.total} size={paged.size} onPage={paged.setPage} onSize={paged.setSize} noun="container" />
        </>
      )}
    </>
  );
}

function Row({ c, hostId, onChanged }: { c: Container; hostId: string; onChanged: () => void }) {
  const pending = usePending(c.Id);
  const project = c.Labels?.[COMPOSE_PROJECT];
  const ports = portList(c.Ports);
  return (
    <li className={`flex items-center gap-3 px-4 py-3 transition-opacity ${pending ? "opacity-70" : ""}`} aria-busy={!!pending || undefined}>
      <Link href={`/containers/view/?id=${c.Id}`} className="flex-1 min-w-0 grid gap-x-6 gap-y-1 sm:grid-cols-[minmax(0,1.4fr)_minmax(0,1fr)_7rem] items-center group">
        <span className="min-w-0">
          <span className="flex items-center gap-2 min-w-0">
            <span className="truncate font-medium group-hover:underline underline-offset-2">{containerName(c)}</span>
            {project && <Badge>{project}</Badge>}
          </span>
          <span className="block text-[13px] text-slate truncate">{c.Image}</span>
        </span>
        <span className="text-[13px] text-slate truncate tabular-nums">{ports.length ? ports.join(", ") : <span className="text-slate/60">No published ports</span>}</span>
        <span className="flex sm:flex-col items-center sm:items-start gap-x-3">
          <StateBadge state={c.State} status={c.Status} pending={pending} />
          {!pending && <span className="text-[12px] text-slate">{c.State === "running" ? c.Status.replace(/^Up /, "up ") : ago(c.Created)}</span>}
        </span>
      </Link>
      <ContainerMenu c={c} hostId={hostId} onChanged={onChanged} />
    </li>
  );
}
