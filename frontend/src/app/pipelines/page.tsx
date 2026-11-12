"use client";

import Link from "next/link";
import { useSession } from "@/components/app";
import { JobBadge } from "@/components/badges";
import { Badge, Empty, LinkButton, Notice, PageHeader, Spinner } from "@/components/ui";
import { ago } from "@/lib/format";
import { usePoll } from "@/lib/hooks";
import { useManaged } from "@/lib/managed";
import { ManagedBadge } from "@/components/managed";
import type { Pipeline } from "@/lib/types";

export default function PipelinesPage() {
  const { session } = useSession();
  const { data, error } = usePoll<Pipeline[]>("/api/pipelines", 10000);
  const { isManaged } = useManaged();
  return (
    <>
      <PageHeader title="Pipelines" intro="Build, test and deploy in stages. Each step runs in its own container with the repository checked out in /workspace.">
        {session?.admin && <LinkButton href="/pipelines/view/?new=1">New pipeline</LinkButton>}
      </PageHeader>
      {error && <Notice tone="error">{error}</Notice>}
      {!data && !error && <Spinner />}
      {data && data.length === 0 && (
        <Empty title="No pipelines yet" action={session?.admin && <LinkButton href="/pipelines/view/?new=1">Create a pipeline</LinkButton>}>
          For one-click deploys from git, Git is simpler. Pipelines are for when you need build and test steps first.
        </Empty>
      )}
      {data && data.length > 0 && (
        <ul className="rounded-2xl border border-line bg-paper divide-y divide-line">
          {data.map((p) => (
            <li key={p.id}>
              <Link href={`/pipelines/view/?id=${p.id}`} className="grid gap-x-6 gap-y-1 sm:grid-cols-[minmax(0,1.4fr)_minmax(0,1fr)_9rem] items-center px-4 py-3 group">
                <span className="min-w-0">
                  <span className="flex items-center gap-2">
                    <span className="truncate font-medium group-hover:underline underline-offset-2">{p.name}</span>
                    {!p.enabled && <Badge>off</Badge>}
                    {isManaged("pipelines", p.id) && <ManagedBadge link={false} />}
                  </span>
                  <span className="block text-[13px] text-slate truncate">{p.gitRepositoryName ?? "No repository"}</span>
                </span>
                <span className="text-[13px] text-slate">
                  {p.stages.length} stage{p.stages.length === 1 ? "" : "s"} · {p.successfulExecutions}/{p.totalExecutions} runs passed
                </span>
                <span className="text-[13px]">
                  {p.lastExecutionStatus ? <JobBadge status={p.lastExecutionStatus} /> : <span className="text-slate">Never run</span>}
                  {p.lastExecutionAt && <span className="block text-[12px] text-slate">{ago(p.lastExecutionAt)}</span>}
                </span>
              </Link>
            </li>
          ))}
        </ul>
      )}
    </>
  );
}
