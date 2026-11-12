"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import { useSession } from "@/components/app";
import { Badge, Button, CodeEditor, Copyable, KeyValues, Notice, PageHeader, Panel, Spinner, useConfirm, useToast } from "@/components/ui";
import { API_BASE, api } from "@/lib/api";
import { ago, dateTime, plural } from "@/lib/format";
import { usePoll } from "@/lib/hooks";
import { refreshManaged } from "@/lib/managed";

type Action = "CREATE" | "ADOPT" | "UPDATE" | "RECREATE" | "UNCHANGED" | "DELETE" | "RELEASE";
type PlanItem = { section: string; key: string; action: Action; fields: string[]; label: string | null; hostId: string | null };
type Plan = { items: PlanItem[]; problems: string[]; warnings: string[] };
type RunStatus = "RUNNING" | "SUCCEEDED" | "PARTIAL" | "FAILED";
type RunItem = PlanItem & { status: "PENDING" | "RUNNING" | "DONE" | "FAILED" | "SKIPPED"; message: string | null };
type Run = {
  id: string;
  source: "FILE" | "UI";
  startedBy: string;
  startedAt: number;
  finishedAt: number | null;
  status: RunStatus;
  problems: string[];
  warnings: string[];
  items: RunItem[];
  notes?: string[];
};
type State = {
  fileMode: boolean;
  file: string | null;
  source: "FILE" | "UI" | null;
  appliedAt: number | null;
  appliedBy: string | null;
  lastStatus: RunStatus | null;
  document: string | null;
  currentRun: Run | null;
  lastRun: Run | null;
  managedCount: number;
};

const ACTIONS: Record<Action, { label: string; tone: "plain" | "ok" | "warn" | "danger" | "live" }> = {
  CREATE: { label: "create", tone: "ok" },
  ADOPT: { label: "take over", tone: "live" },
  UPDATE: { label: "update", tone: "warn" },
  RECREATE: { label: "re-create", tone: "warn" },
  DELETE: { label: "delete", tone: "danger" },
  RELEASE: { label: "stop managing", tone: "plain" },
  UNCHANGED: { label: "unchanged", tone: "plain" },
};

const STATUS: Record<RunStatus, { label: string; tone: "plain" | "ok" | "warn" | "danger" | "live" }> = {
  RUNNING: { label: "applying", tone: "live" },
  SUCCEEDED: { label: "applied", tone: "ok" },
  PARTIAL: { label: "partly applied", tone: "warn" },
  FAILED: { label: "failed", tone: "danger" },
};

const STARTER = `# Docker Manager configuration. Preview the changes, then apply.
# Secrets: write \${env:NAME} or \${file:/run/secrets/name} instead of the value.
version: 1

hosts:
  local:
    url: unix:///var/run/docker.sock

groups:
  developers:
    description: Can run and inspect containers
    permissions:
      - resource: containers
        actions: [list, read, start, stop, restart, logs]

users:
  alice:
    email: alice@example.com
    groups: [developers]
`;

export default function ConfigPage() {
  const { session } = useSession();
  if (!session?.admin) {
    return (
      <>
        <PageHeader title="Configuration" />
        <Notice>Only administrators can see and apply the configuration file.</Notice>
      </>
    );
  }
  return <Config />;
}

function Config() {
  const { data: state, error, refresh } = usePoll<State>("/api/config", 15000);
  // What the user typed; until then the stored document (or, in file mode, always the file).
  const [draft, setText] = useState<string | null>(null);
  const [plan, setPlan] = useState<{ plan: Plan; text: string } | null>(null);
  const [busy, setBusy] = useState<"plan" | "apply" | "export" | "reapply" | null>(null);
  const [runId, setRunId] = useState<string | null>(null);
  const [notes, setNotes] = useState<string[]>([]);
  const toast = useToast();
  const confirm = useConfirm();

  const running = state?.currentRun?.status === "RUNNING" ? state.currentRun.id : null;
  const watching = runId ?? running;

  if (error) return <Notice tone="error">{error}</Notice>;
  if (!state) return <Spinner />;
  const text = state.fileMode ? (state.document ?? "") : (draft ?? state.document ?? STARTER);

  const planIsCurrent = plan != null && plan.text === text;
  const deletes = plan?.plan.items.filter((i) => i.action === "DELETE") ?? [];

  const preview = async () => {
    setBusy("plan");
    try {
      setPlan({ plan: await api.post<Plan>("/api/config/plan", { yaml: text }), text });
    } catch (e) {
      toast((e as Error).message, "error");
    } finally {
      setBusy(null);
    }
  };

  const apply = async () => {
    if (deletes.length > 0) {
      const ok = await confirm({
        title: `Delete ${plural(deletes.length, "item")}?`,
        body: `They were removed from the file: ${deletes.map((d) => `${d.section} ${d.key}`).join(", ")}. This can't be undone.`,
        confirm: "Apply and delete",
        danger: true,
      });
      if (!ok) return;
    }
    setBusy("apply");
    try {
      const run = await api.post<Run>("/api/config/apply", { yaml: text });
      setNotes(run.notes ?? []);
      setRunId(run.id);
      setPlan(null);
      refresh();
    } catch (e) {
      toast((e as Error).message, "error");
    } finally {
      setBusy(null);
    }
  };

  const reapply = async () => {
    setBusy("reapply");
    try {
      const run = await api.post<Run>("/api/config/reapply");
      setNotes(run.notes ?? []);
      setRunId(run.id);
      refresh();
    } catch (e) {
      toast((e as Error).message, "error");
    } finally {
      setBusy(null);
    }
  };

  const loadExport = async () => {
    if (text.trim() && text !== STARTER && text !== state.document) {
      const ok = await confirm({ title: "Replace the editor's text?", body: "It is replaced with an export of today's setup.", confirm: "Replace" });
      if (!ok) return;
    }
    setBusy("export");
    try {
      setText(await api.get<string>("/api/config/export"));
      setPlan(null);
    } catch (e) {
      toast((e as Error).message, "error");
    } finally {
      setBusy(null);
    }
  };

  return (
    <>
      <PageHeader
        title="Configuration"
        art="setup"
        intro="Describe the whole setup in one YAML file: hosts, users, registries, stacks, ingress, databases and the rest. Applying it creates what is missing, updates what changed and removes what you took out. Things made by hand are left alone; things the file manages can only be changed through it."
      >
        <Button tone="quiet" onClick={() => api.download("/api/config/export", "docker-manager.yaml")}>
          Download current setup
        </Button>
        <a href={`${API_BASE}/api/config/schema`} target="_blank" rel="noreferrer" className="text-[14px] text-slate hover:text-ink px-2">
          Schema
        </a>
      </PageHeader>

      <div className="grid gap-6 xl:grid-cols-[minmax(0,1fr)_22rem] items-start">
        <div className="flex flex-col gap-6 min-w-0">
          {state.fileMode && (
            <Notice title="Read from a file">
              Docker Manager reads its configuration from <code className="font-mono">{state.file}</code> (DM_CONFIG_FILE) and applies it within seconds of a change. Edit that file to make changes; this
              page only shows it.
            </Notice>
          )}
          <Panel
            title={state.fileMode ? "The file" : "Document"}
            action={
              !state.fileMode && (
                <Button tone="ghost" size="sm" busy={busy === "export"} onClick={loadExport}>
                  Start from current setup
                </Button>
              )
            }
          >
            <CodeEditor
              label="Configuration document"
              value={text}
              onChange={state.fileMode ? undefined : (v) => setText(v)}
              readOnly={state.fileMode}
              rows={24}
            />
            <div className="flex flex-wrap items-center gap-2 mt-4">
              {state.fileMode ? (
                <Button busy={busy === "reapply"} disabled={!!running} onClick={reapply}>
                  Apply again now
                </Button>
              ) : (
                <>
                  <Button tone={planIsCurrent ? "quiet" : "primary"} busy={busy === "plan"} disabled={!text.trim()} onClick={preview}>
                    Preview changes
                  </Button>
                  <Button
                    busy={busy === "apply"}
                    disabled={!planIsCurrent || !!running || (plan?.plan.problems.length ?? 0) > 0}
                    onClick={apply}
                    title={planIsCurrent ? undefined : "Preview the changes first"}
                  >
                    Apply
                  </Button>
                  {!planIsCurrent && plan && <span className="text-[13px] text-slate">The text changed since the preview.</span>}
                </>
              )}
            </div>
          </Panel>

          {plan && <PlanView plan={plan.plan} />}
          {watching && <RunView id={watching} notes={notes} onDone={() => (refresh(), refreshManaged())} />}
        </div>

        <div className="flex flex-col gap-6 min-w-0">
          <Panel title="Status">
            <KeyValues
              rows={[
                ["Source", state.fileMode ? "File" : state.source === "UI" ? "This page" : "Nothing applied yet"],
                ["Last applied", state.appliedAt ? `${ago(state.appliedAt)} by ${state.appliedBy ?? "?"}` : "Never"],
                ["Result", state.lastStatus ? <Badge tone={STATUS[state.lastStatus].tone}>{STATUS[state.lastStatus].label}</Badge> : "None"],
                ["Managed", plural(state.managedCount, "item")],
              ]}
            />
          </Panel>
          {state.lastRun && !watching && <LastRun run={state.lastRun} />}
          <Panel title="How it works">
            <ul className="text-[13.5px] text-slate flex flex-col gap-2 list-disc pl-4">
              <li>Each section (hosts, users, stacks...) holds named entries. Other entries refer to them by that name.</li>
              <li>
                Keep secrets out of the file with <code className="font-mono">{"${env:NAME}"}</code> or <code className="font-mono">{"${file:/run/secrets/x}"}</code>.
              </li>
              <li>Something that already exists with the same name is taken over, not duplicated.</li>
              <li>Removing an entry deletes what it made, except data (databases, volumes), which is kept unless the entry said onRemove: delete.</li>
              <li>Every few minutes, managed things removed by other means are put back.</li>
            </ul>
          </Panel>
        </div>
      </div>
    </>
  );
}

function ItemRow({ item, status, message }: { item: PlanItem; status?: RunItem["status"]; message?: string | null }) {
  const a = ACTIONS[item.action];
  return (
    <li className="flex flex-wrap items-start gap-x-3 gap-y-1 px-4 py-2.5">
      <span className="w-28 shrink-0">
        <Badge tone={a.tone}>{a.label}</Badge>
      </span>
      <div className="flex-1 min-w-0">
        <div className="text-[14px]">
          <span className="font-mono text-slate">{item.section}</span> <span className="font-medium break-all">{item.key}</span>
          {item.label && item.label !== item.key && <span className="text-slate"> ({item.label})</span>}
        </div>
        {item.fields.length > 0 && <div className="text-[12.5px] text-slate">Changed: {item.fields.join(", ")}</div>}
        {message && <div className={`text-[12.5px] ${status === "FAILED" ? "text-danger" : "text-slate"}`}>{message}</div>}
      </div>
      {status && <RunItemStatus status={status} />}
    </li>
  );
}

function RunItemStatus({ status }: { status: RunItem["status"] }) {
  const map = {
    PENDING: { label: "waiting", tone: "plain" },
    RUNNING: { label: "working", tone: "live" },
    DONE: { label: "done", tone: "ok" },
    FAILED: { label: "failed", tone: "danger" },
    SKIPPED: { label: "skipped", tone: "plain" },
  } as const;
  return <Badge tone={map[status].tone}>{map[status].label}</Badge>;
}

function Messages({ problems, warnings }: { problems: string[]; warnings: string[] }) {
  return (
    <>
      {problems.length > 0 && (
        <Notice tone="error" title={problems.length === 1 ? "The file has a problem" : `The file has ${problems.length} problems`}>
          <ul className="flex flex-col gap-1 mt-1">
            {problems.map((p, i) => (
              <li key={i} className="font-mono text-[12.5px] whitespace-pre-wrap break-words">
                {p}
              </li>
            ))}
          </ul>
        </Notice>
      )}
      {warnings.length > 0 && (
        <Notice tone="warn">
          <ul className="flex flex-col gap-1">
            {warnings.map((w, i) => (
              <li key={i} className="text-[13px]">
                {w}
              </li>
            ))}
          </ul>
        </Notice>
      )}
    </>
  );
}

function PlanView({ plan }: { plan: Plan }) {
  const [showAll, setShowAll] = useState(false);
  const changes = plan.items.filter((i) => i.action !== "UNCHANGED");
  const unchanged = plan.items.length - changes.length;
  const shown = showAll ? plan.items : changes;
  return (
    <Panel title="Preview" pad={false}>
      <div className="px-5 flex flex-col gap-3">
        <Messages problems={plan.problems} warnings={plan.warnings} />
        {plan.problems.length === 0 && changes.length === 0 && <p className="text-[14px] text-slate">Nothing to change: everything already matches the file.</p>}
      </div>
      {shown.length > 0 && (
        <ul className="mt-3 border-t border-line divide-y divide-line">
          {shown.map((i) => (
            <ItemRow key={i.section + "/" + i.key} item={i} />
          ))}
        </ul>
      )}
      {unchanged > 0 && (
        <div className="px-5 py-3 border-t border-line">
          <Button tone="ghost" size="sm" onClick={() => setShowAll((v) => !v)}>
            {showAll ? "Hide unchanged" : `Show ${plural(unchanged, "unchanged item")}`}
          </Button>
        </div>
      )}
      {shown.length === 0 && unchanged === 0 && <div className="pb-5" />}
    </Panel>
  );
}

function RunView({ id, notes, onDone }: { id: string; notes: string[]; onDone: () => void }) {
  const { data: run } = usePoll<Run>(`/api/config/runs/${id}`, 1500);
  const finished = run != null && run.status !== "RUNNING";
  const reported = useRef(false);
  useEffect(() => {
    if (finished && !reported.current) {
      reported.current = true;
      onDone();
    }
  }, [finished, onDone]);
  const allNotes = useMemo(() => Array.from(new Set([...notes, ...(run?.notes ?? [])])), [notes, run?.notes]);
  if (!run) return <Spinner label="Applying" />;
  const changes = run.items.filter((i) => i.action !== "UNCHANGED");
  return (
    <Panel
      title="Applying"
      pad={false}
      action={
        <span className="flex items-center gap-2 text-[13px] text-slate">
          {run.finishedAt ? `${dateTime(run.finishedAt)}` : `started ${ago(run.startedAt)}`}
          <Badge tone={STATUS[run.status].tone}>{STATUS[run.status].label}</Badge>
        </span>
      }
    >
      <div className="px-5 flex flex-col gap-3">
        <Messages problems={run.problems} warnings={run.warnings} />
        {allNotes.length > 0 && (
          <Notice tone="warn" title="Shown once">
            <ul className="flex flex-col gap-2 mt-1">
              {allNotes.map((n) => {
                const m = /^(.*temporary password )(\S+)$/.exec(n);
                return (
                  <li key={n} className="text-[13.5px]">
                    {m ? (
                      <span className="flex flex-wrap items-center gap-2">
                        {m[1]}
                        <Copyable text={m[2]} />
                      </span>
                    ) : (
                      n
                    )}
                  </li>
                );
              })}
            </ul>
          </Notice>
        )}
        {changes.length === 0 && run.problems.length === 0 && <p className="text-[14px] text-slate pb-2">Nothing needed changing.</p>}
      </div>
      {changes.length > 0 && (
        <ul className="mt-3 border-t border-line divide-y divide-line">
          {changes.map((i) => (
            <ItemRow key={i.section + "/" + i.key} item={i} status={i.status} message={i.message} />
          ))}
        </ul>
      )}
    </Panel>
  );
}

function LastRun({ run: stored }: { run: Run }) {
  // Older summaries may leave out empty lists.
  const run = { ...stored, items: stored.items ?? [], problems: stored.problems ?? [] };
  const failed = run.items.filter((i) => i.status === "FAILED");
  const changed = run.items.filter((i) => i.action !== "UNCHANGED" && i.status === "DONE");
  return (
    <Panel title="Last apply">
      <div className="flex flex-col gap-2 text-[13.5px]">
        <div className="flex items-center gap-2">
          <Badge tone={STATUS[run.status].tone}>{STATUS[run.status].label}</Badge>
          <span className="text-slate">
            {dateTime(run.startedAt)}, {run.source === "FILE" ? "from the file" : `by ${run.startedBy}`}
          </span>
        </div>
        <div className="text-slate">
          {plural(changed.length, "change")}
          {failed.length > 0 && `, ${failed.length} failed`}
        </div>
        {run.problems.slice(0, 3).map((p, i) => (
          <div key={i} className="text-danger text-[12.5px] font-mono break-words">
            {p}
          </div>
        ))}
        {failed.slice(0, 5).map((i) => (
          <div key={i.section + i.key} className="text-[12.5px]">
            <span className="font-mono text-slate">{i.section}</span> {i.key}: <span className="text-danger">{i.message}</span>
          </div>
        ))}
      </div>
    </Panel>
  );
}
