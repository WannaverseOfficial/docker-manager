"use client";

import Link from "next/link";
import { useSession } from "@/components/app";
import { RolloutBadge } from "@/components/badges";
import { Badge, Empty, LinkButton, Notice, PageHeader, Spinner } from "@/components/ui";
import { ago } from "@/lib/format";
import { usePoll } from "@/lib/hooks";
import { useManaged } from "@/lib/managed";
import { ManagedBadge } from "@/components/managed";
import type { Rollout } from "@/lib/types";

export default function RolloutsPage() {
  const { session } = useSession();
  const { data, error } = usePoll<Rollout[]>("/api/rollouts", 5000);
  const { isManaged } = useManaged();
  return (
    <>
      <PageHeader
        title="Rollouts"
        intro="Blue/green and canary releases: each new version starts next to the current one, is tested, and only then gets visitors. If it misbehaves, visitors go back automatically."
      >
        {session?.admin && <LinkButton href="/rollouts/new/">New rollout</LinkButton>}
      </PageHeader>
      {error && <Notice tone="error">{error}</Notice>}
      {!data && !error && <Spinner />}
      {data && data.length === 0 && (
        <Empty title="No rollouts yet" action={session?.admin && <LinkButton href="/rollouts/new/">Create a rollout</LinkButton>}>
          A rollout gives an app on an ingress address two slots, blue and green. Release images by hand, from a Git repository or from a pipeline step.
        </Empty>
      )}
      {data && data.length > 0 && (
        <ul className="rounded-2xl border border-line bg-paper divide-y divide-line">
          {data.map((r) => {
            const run = r.currentRun ?? r.lastRun;
            return (
              <li key={r.id}>
                <Link href={`/rollouts/view/?id=${r.id}`} className="grid gap-x-6 gap-y-1 sm:grid-cols-[minmax(0,1.4fr)_minmax(0,1fr)_10rem] items-center px-4 py-3 group">
                  <span className="min-w-0">
                    <span className="block truncate font-medium group-hover:underline underline-offset-2">{r.name}</span>
                    <span className="block text-[13px] text-slate truncate">{r.url ?? "No address yet"}</span>
                  </span>
                  <span className="text-[13px] text-slate flex flex-wrap gap-1.5 items-center min-w-0">
                    <Badge>{r.strategy === "CANARY" ? "Canary" : "Blue/green"}</Badge>
                    {/* A plain badge: the row is a link already. */}
                    {isManaged("rollouts", r.id) && <ManagedBadge link={false} />}
                    <span className="truncate">{r.activeImage ?? "Nothing released yet"}</span>
                  </span>
                  <span className="text-[13px]">
                    {run ? <RolloutBadge status={run.status} kind={run.kind} /> : <span className="text-slate">Never released</span>}
                    {run && (
                      <span className="block text-[12px] text-slate truncate">
                        #{run.number} · {r.currentRun ? run.phase : ago(run.finishedAt || run.createdAt)}
                      </span>
                    )}
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
