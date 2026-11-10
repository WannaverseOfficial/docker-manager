"use client";

import Link from "next/link";
import { Badge, Empty, LinkButton, Notice, PageHeader, Spinner } from "@/components/ui";
import { ago, shortId } from "@/lib/format";
import { usePoll } from "@/lib/hooks";
import { useSession } from "@/components/app";
import { JobBadge } from "@/components/badges";
import type { DeploymentJob, GitRepo } from "@/lib/types";

export default function GitPage() {
  const { session } = useSession();
  const { data: repos, error } = usePoll<GitRepo[]>("/api/git/repositories", 15000);
  const { data: jobs } = usePoll<DeploymentJob[]>("/api/git/jobs", 5000);
  const latest = new Map<string, DeploymentJob>();
  for (const j of jobs ?? []) if (!latest.has(j.gitRepositoryId)) latest.set(j.gitRepositoryId, j);

  return (
    <>
      <PageHeader title="Git" art="deploy" intro="Deploy straight from a repository: on every push, on a schedule, or when you press Deploy.">
        {session?.admin && <LinkButton href="/git/new/">Add repository</LinkButton>}
      </PageHeader>
      {error && <Notice tone="error">{error}</Notice>}
      {!repos && !error && <Spinner />}
      {repos && repos.length === 0 && (
        <Empty title="No repositories yet" action={session?.admin && <LinkButton href="/git/new/">Add a repository</LinkButton>}>
          Point Docker Manager at a repository with a Dockerfile or a compose file.
        </Empty>
      )}
      {repos && repos.length > 0 && (
        <ul className="rounded-2xl border border-line bg-paper divide-y divide-line">
          {repos.map((r) => {
            const j = latest.get(r.id);
            return (
              <li key={r.id}>
                <Link href={`/git/view/?id=${r.id}`} className="grid gap-x-6 gap-y-1 sm:grid-cols-[minmax(0,1.4fr)_minmax(0,1fr)_9rem] items-center px-4 py-3 group">
                  <span className="min-w-0">
                    <span className="block truncate font-medium group-hover:underline underline-offset-2">{r.name}</span>
                    <span className="block text-[13px] text-slate truncate">
                      {r.repositoryUrl.replace(/^https?:\/\//, "")} · {r.branch}
                    </span>
                  </span>
                  <span className="text-[13px] text-slate flex flex-wrap gap-1.5 items-center">
                    <Badge>{r.deploymentType === "DOCKERFILE" ? "Dockerfile" : "Compose"}</Badge>
                    {r.webhookEnabled && <Badge>on push</Badge>}
                    {r.pollingEnabled && <Badge>polling</Badge>}
                    {r.driftStatus === "BEHIND" && <Badge tone="warn">new commits</Badge>}
                  </span>
                  <span className="text-[13px]">
                    {j ? <JobBadge status={j.status} /> : <span className="text-slate">Never deployed</span>}
                    <span className="block text-[12px] text-slate">
                      {r.lastDeployedAt ? `${ago(r.lastDeployedAt)}` : ""}
                      {r.lastCommitSha ? ` · ${shortId(r.lastCommitSha).slice(0, 7)}` : ""}
                    </span>
                  </span>
                </Link>
              </li>
            );
          })}
        </ul>
      )}
    </>
  );
}
