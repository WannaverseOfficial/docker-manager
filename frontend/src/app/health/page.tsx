"use client";

import Link from "next/link";
import { useState } from "react";
import { useHost } from "@/components/app";
import { Badge, Button, Empty, Notice, PageHeader, Panel, Segmented, Spinner, Stat, useToast } from "@/components/ui";
import { api } from "@/lib/api";
import { ago, dateTime } from "@/lib/format";
import { usePoll } from "@/lib/hooks";
import type { HealthEvent, HealthSummary, Page } from "@/lib/types";

const TYPE: Record<HealthEvent["eventType"], { label: string; help: string }> = {
  CRASH: { label: "Crashed", help: "Stopped with an error while it was running." },
  RESTART_LOOP: { label: "Restarting over and over", help: "Keeps crashing and being restarted." },
  HEALTH_UNHEALTHY: { label: "Failing its health check", help: "Running, but its own health check reports a problem." },
  OOM_KILLED: { label: "Ran out of memory", help: "Stopped by the kernel because it used more memory than allowed." },
};

export default function HealthPage() {
  const { hostId } = useHost();
  const toast = useToast();
  const [view, setView] = useState<"open" | "all">("open");
  const { data: summary } = usePoll<HealthSummary>(hostId ? `/api/container-health/hosts/${hostId}/summary` : null, 30000);
  const { data: open, refresh: refreshOpen, error } = usePoll<HealthEvent[]>(hostId ? `/api/container-health/hosts/${hostId}/active` : null, 15000);
  const { data: all } = usePoll<Page<HealthEvent> & { events?: HealthEvent[] }>(view === "all" && hostId ? `/api/container-health/hosts/${hostId}/events?size=50` : null, 30000);
  const list = view === "open" ? open : (all?.content ?? all?.events ?? null);

  if (!hostId) return null;
  return (
    <>
      <PageHeader title="Health" art="health" intro="Docker Manager checks every container every 30 seconds and tells you (by email too, if set up) when one crashes, loops or runs out of memory." />
      {summary && (
        <div className="grid grid-cols-3 gap-3 mb-6">
          <Stat label="Open issues" value={summary.activeIssues} />
          <Stat label="Last 24 hours" value={summary.totalEvents24h} sub="events" />
          <Stat label="Last 7 days" value={summary.totalEvents7d} sub="events" />
        </div>
      )}
      <div className="mb-4">
        <Segmented
          label="Show"
          value={view}
          onChange={setView}
          options={[
            { value: "open", label: "Open" },
            { value: "all", label: "Recent" },
          ]}
        />
      </div>
      {error && <Notice tone="error">{error}</Notice>}
      {!list && !error && <Spinner />}
      {list && list.length === 0 && <Empty title={view === "open" ? "All healthy" : "Nothing recorded"}>{view === "open" ? "No container needs attention right now." : "No problems were seen recently."}</Empty>}
      {list && list.length > 0 && (
        <div className="flex flex-col gap-3">
          {list.map((e) => (
            <Panel key={e.id}>
              <div className="flex flex-wrap items-start justify-between gap-3">
                <div className="min-w-0">
                  <div className="flex flex-wrap items-center gap-2">
                    <Link href={`/containers/view/?id=${e.containerId}`} className="font-medium hover:underline underline-offset-2">
                      {e.containerName}
                    </Link>
                    <Badge tone={e.active ? "danger" : "plain"}>{TYPE[e.eventType]?.label ?? e.eventType}</Badge>
                    {!e.active && <Badge tone="ok">resolved</Badge>}
                  </div>
                  <p className="text-[14px] text-slate mt-1">
                    {TYPE[e.eventType]?.help}
                    {e.exitCode != null && ` Exit code ${e.exitCode}.`}
                    {e.restartCount != null && ` ${e.restartCount} restarts.`}
                  </p>
                  <p className="text-[12.5px] text-slate mt-1">
                    Seen {ago(e.detectedAt)} ({dateTime(e.detectedAt)}){e.resolvedAt ? `, resolved ${ago(e.resolvedAt)}` : ""}
                    {e.notificationSent && " · email sent"}
                  </p>
                </div>
                {e.active && (
                  <div className="flex gap-2">
                    <Link href={`/containers/view/?id=${e.containerId}`} className="inline-flex items-center rounded-full px-3 h-8 text-[13px] border border-line hover:bg-well">
                      See logs
                    </Link>
                    <Button
                      tone="quiet"
                      size="sm"
                      onClick={async () => {
                        try {
                          await api.post(`/api/container-health/events/${e.id}/resolve`);
                          refreshOpen();
                        } catch (err) {
                          toast((err as Error).message, "error");
                        }
                      }}
                    >
                      Mark resolved
                    </Button>
                  </div>
                )}
              </div>
            </Panel>
          ))}
        </div>
      )}
    </>
  );
}
