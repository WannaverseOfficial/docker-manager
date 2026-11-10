"use client";

import Link from "next/link";
import { useHost, useSession } from "@/components/app";
import { Meter, Notice, PageHeader, Panel, Spinner, Stat, StateBadge } from "@/components/ui";
import { ago, bytes, hostLabel, mem, percent, plural } from "@/lib/format";
import { usePoll } from "@/lib/hooks";
import type { ContainerStats, HealthEvent, Overview } from "@/lib/types";

export default function OverviewPage() {
  const { host, hostId } = useHost();
  const { session } = useSession();
  const { data, error } = usePoll<Overview>(hostId ? `/api/docker/hosts/${hostId}/overview` : null, 10000);
  const { data: stats } = usePoll<ContainerStats[]>(hostId ? `/api/docker/hosts/${hostId}/stats` : null, 5000);
  const { data: issues } = usePoll<HealthEvent[]>(hostId ? `/api/container-health/hosts/${hostId}/active` : null, 30000);

  if (!hostId) return null;

  return (
    <>
      <PageHeader
        title={hostLabel(host)}
        art="harbour"
        intro={
          data ? (
            <>
              Docker {data.host.dockerVersion} on {data.host.operatingSystem}, {data.host.cpus} CPUs and {mem(data.host.memoryBytes)} of memory.
            </>
          ) : (
            "Everything running on this Docker host."
          )
        }
      />
      {error && !data && <Notice tone="error">Can&apos;t load this host: {error}</Notice>}
      {!data && !error && <Spinner />}
      {data && (
        <>
          <div className="grid grid-cols-2 lg:grid-cols-5 gap-3">
            <Stat
              label="Containers"
              href="/containers/"
              value={
                <>
                  {data.containers.running}
                  <span className="text-slate text-[1rem] font-normal"> / {data.containers.total}</span>
                </>
              }
              sub={`${data.containers.running} running${data.containers.stopped ? `, ${data.containers.stopped} stopped` : ""}`}
            />
            <Stat label="Stacks" value={data.stacks} href="/stacks/" sub="Compose projects" />
            <Stat label="Images" value={data.images} href="/images/" sub={bytes(data.imagesBytes)} />
            <Stat label="Volumes" value={data.volumes} href="/volumes/" sub={data.unusedVolumes ? `${data.unusedVolumes} not in use` : "All in use"} />
            <Stat label="Networks" value={data.networks} href="/networks/" sub="Excluding built-in" />
          </div>

          {(data.containers.unhealthy > 0 || (issues?.length ?? 0) > 0) && (
            <div className="mt-6">
              <Notice tone="warn" title="Needs attention">
                {data.containers.unhealthy > 0 && <>{plural(data.containers.unhealthy, "container")} failing health checks. </>}
                {(issues?.length ?? 0) > 0 && (
                  <>
                    {plural(issues!.length, "open health issue")}.{" "}
                    <Link href="/health/" className="underline underline-offset-2">
                      See health
                    </Link>
                  </>
                )}
              </Notice>
            </div>
          )}

          <div className="grid gap-6 lg:grid-cols-[1.3fr_1fr] items-start mt-6">
            <Usage stats={stats} cpus={data.host.cpus} memory={data.host.memoryBytes} running={data.containers.running} />
            <Panel title="Recently created" action={<Link href="/containers/" className="text-[13px] text-slate hover:text-ink">All containers</Link>}>
              {data.recentContainers.length === 0 ? (
                <p className="text-slate text-[14px]">No containers yet.</p>
              ) : (
                <ul className="flex flex-col divide-y divide-line -my-2">
                  {data.recentContainers.map((c) => (
                    <li key={c.id}>
                      <Link href={`/containers/view/?id=${c.id}`} className="flex items-center justify-between gap-3 py-2.5 group">
                        <span className="min-w-0">
                          <span className="block truncate group-hover:underline underline-offset-2">{c.name}</span>
                          <span className="block text-[12.5px] text-slate truncate">{c.image}</span>
                        </span>
                        <span className="text-right shrink-0">
                          <StateBadge state={c.state} status={c.status} />
                          <span className="block text-[12px] text-slate">{ago(c.created)}</span>
                        </span>
                      </Link>
                    </li>
                  ))}
                </ul>
              )}
            </Panel>
          </div>

          {session?.admin && (data.danglingImages > 0 || data.unusedVolumes > 0 || data.containers.stopped > 0) && (
            <p className="text-[14px] text-slate mt-6">
              Unused things take up space: {data.containers.stopped > 0 && <>{plural(data.containers.stopped, "stopped container")}, </>}
              {data.danglingImages > 0 && <>{plural(data.danglingImages, "untagged image")}, </>}
              {plural(data.unusedVolumes, "unused volume")}.{" "}
              <Link href="/cleanup/" className="text-ink underline underline-offset-2">
                Review and clean up
              </Link>
            </p>
          )}
        </>
      )}
    </>
  );
}

function Usage({ stats, cpus, memory, running }: { stats: ContainerStats[] | null; cpus: number; memory: number; running: number }) {
  // docker stats reports CPU per core (one busy core = 100%); show it against the whole host as well.
  const cpuCores = (stats ?? []).reduce((a, s) => a + (s.cpuPercent || 0), 0);
  const hostCpu = cpus ? cpuCores / cpus : 0;
  const memUsed = (stats ?? []).reduce((a, s) => a + (s.memoryUsage || 0), 0);
  const hostMem = memory ? (memUsed / memory) * 100 : 0;
  const top = [...(stats ?? [])].sort((a, b) => b.cpuPercent + b.memoryPercent - (a.cpuPercent + a.memoryPercent)).slice(0, 6);

  return (
    <Panel title="Resource use" action={<span className="text-[12px] text-slate">Live, every 5 seconds</span>}>
      {!stats ? (
        <Spinner label="Measuring" />
      ) : (
        <>
          <div className="grid sm:grid-cols-2 gap-5">
            <div>
              <div className="flex justify-between text-[14px] mb-1.5">
                <span>CPU</span>
                <span className="text-slate tabular-nums">{percent(hostCpu)} of {cpus} CPUs</span>
              </div>
              <Meter value={hostCpu} label="CPU use by containers" />
            </div>
            <div>
              <div className="flex justify-between text-[14px] mb-1.5">
                <span>Memory</span>
                <span className="text-slate tabular-nums">
                  {mem(memUsed)} of {mem(memory)}
                </span>
              </div>
              <Meter value={hostMem} label="Memory use by containers" />
            </div>
          </div>
          <p className="text-[12.5px] text-slate mt-3">
            Used by {plural(running, "running container")}. Memory excludes reclaimable file cache, as <code className="font-mono">docker stats</code> does.
          </p>
          {top.length > 0 && (
            <div className="table-scroll mt-5 -mx-1">
              <table className="w-full text-[14px]">
                <thead>
                  <tr className="text-left text-[12.5px] text-slate">
                    <th className="font-medium px-1 pb-2">Container</th>
                    <th className="font-medium px-1 pb-2 text-right">CPU</th>
                    <th className="font-medium px-1 pb-2 text-right">Memory</th>
                  </tr>
                </thead>
                <tbody>
                  {top.map((s) => (
                    <tr key={s.containerId} className="border-t border-line">
                      <td className="px-1 py-2 max-w-0 w-full">
                        <Link href={`/containers/view/?id=${s.containerId}`} className="block truncate hover:underline underline-offset-2">
                          {s.containerName}
                        </Link>
                      </td>
                      <td className="px-1 py-2 text-right tabular-nums whitespace-nowrap">{percent(s.cpuPercent)}</td>
                      <td className="px-1 py-2 text-right tabular-nums whitespace-nowrap">{mem(s.memoryUsage)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </>
      )}
    </Panel>
  );
}
