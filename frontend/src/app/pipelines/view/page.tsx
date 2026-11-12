"use client";

import { Suspense, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { ArrowDown, ArrowUp, X } from "lucide-react";
import { useHost, useSession } from "@/components/app";
import { JobBadge } from "@/components/badges";
import {
  Button,
  Copyable,
  Empty,
  Field,
  IconButton,
  LogView,
  Menu,
  Modal,
  Notice,
  PageHeader,
  Panel,
  Segmented,
  Select,
  Spinner,
  Tabs,
  Toggle,
  inputClass,
  useConfirm,
  useToast,
} from "@/components/ui";
import { API_BASE, api } from "@/lib/api";
import { ago, dateTime, duration } from "@/lib/format";
import { usePoll } from "@/lib/hooks";
import { useManaged } from "@/lib/managed";
import { ManagedBadge } from "@/components/managed";
import type { GitRepo, Pipeline, PipelineRun, PipelineStage, PipelineStep, Rollout, StepType } from "@/lib/types";

export default function PipelinePage() {
  return (
    <Suspense>
      <PipelineView />
    </Suspense>
  );
}

function PipelineView() {
  const params = useSearchParams();
  const id = params.get("id");
  const isNew = params.get("new") === "1";
  const { session } = useSession();
  const [tab, setTab] = useState<"runs" | "edit">(isNew ? "edit" : "runs");
  const { data: pipeline, error, refresh } = usePoll<Pipeline>(id ? `/api/pipelines/${id}` : null);
  // Managed by the configuration file: changed there, so no editor.
  const managed = !!useManaged().find("pipelines", id);

  if (isNew) return <Editor pipeline={null} />;
  if (!id) return null;
  if (error && !pipeline) return <Notice tone="error">{error}</Notice>;
  if (!pipeline) return <Spinner />;

  return (
    <>
      <Tabs
        value={tab}
        onChange={setTab}
        tabs={[{ value: "runs", label: "Runs" }, ...(session?.admin && !managed ? [{ value: "edit" as const, label: "Edit" }] : [])]}
      />
      {tab === "runs" || managed ? <Runs pipeline={pipeline} onChanged={refresh} /> : <Editor pipeline={pipeline} onSaved={refresh} />}
    </>
  );
}

function Runs({ pipeline, onChanged }: { pipeline: Pipeline; onChanged: () => void }) {
  const { data: runs, refresh } = usePoll<PipelineRun[]>(`/api/pipelines/${pipeline.id}/executions`, 4000);
  const [selected, setSelected] = useState<string | null>(null);
  const [trigger, setTrigger] = useState(false);
  const [branch, setBranch] = useState("");
  const [commit, setCommit] = useState("");
  const toast = useToast();
  const router = useRouter();
  const confirm = useConfirm();
  const { session } = useSession();
  const managed = useManaged().find("pipelines", pipeline.id);
  const shown = selected ?? runs?.[0]?.id ?? null;

  const run = async () => {
    try {
      const r = await api.post<PipelineRun>(`/api/pipelines/${pipeline.id}/trigger`, { branch: branch || undefined, commit: commit || undefined });
      setTrigger(false);
      setSelected(r.id);
      refresh();
      onChanged();
    } catch (e) {
      toast((e as Error).message, "error");
    }
  };

  return (
    <>
      <PageHeader title={pipeline.name} back={{ href: "/pipelines/", label: "Pipelines" }} intro={pipeline.description ?? `${pipeline.gitRepositoryName ?? "No repository"} · ${pipeline.dockerHostName ?? ""}`}>
        {managed && <ManagedBadge entry={managed.key} />}
        <Button disabled={!pipeline.enabled} onClick={() => setTrigger(true)}>
          Run
        </Button>
        {session?.admin && (
          <Menu
            items={[
              !managed && {
                label: pipeline.enabled ? "Turn off" : "Turn on",
                onClick: async () => {
                  await api.patch(`/api/pipelines/${pipeline.id}/enabled`, { enabled: !pipeline.enabled });
                  onChanged();
                },
              },
              {
                label: "Duplicate",
                onClick: async () => {
                  const p = await api.post<Pipeline>(`/api/pipelines/${pipeline.id}/duplicate`, { name: `${pipeline.name} copy` });
                  router.push(`/pipelines/view/?id=${p.id}`);
                },
              },
              !managed && {
                label: "Delete",
                danger: true,
                onClick: async () => {
                  if (!(await confirm({ title: `Delete ${pipeline.name}?`, body: "Its run history is deleted too.", confirm: "Delete", danger: true }))) return;
                  await api.del(`/api/pipelines/${pipeline.id}`);
                  router.push("/pipelines/");
                },
              },
            ]}
          />
        )}
      </PageHeader>
      {!pipeline.enabled && (
        <div className="mb-6">
          <Notice>This pipeline is turned off. Turn it on to run it.</Notice>
        </div>
      )}
      {pipeline.webhookEnabled && pipeline.webhookSecret && (
        <div className="mb-6 rounded-xl bg-well px-4 py-3 text-[13px] flex flex-wrap items-center gap-2">
          <span className="text-slate">Push webhook:</span>
          <Copyable text={`${API_BASE || window.location.origin}/api/pipelines/webhook/${pipeline.webhookSecret}`} display={`/api/pipelines/webhook/${pipeline.webhookSecret.slice(0, 6)}…`} />
          {pipeline.branchFilter && <span className="text-slate">· branches matching {pipeline.branchFilter}</span>}
        </div>
      )}
      {!runs ? (
        <Spinner />
      ) : runs.length === 0 ? (
        <Empty title="Not run yet">Press Run to start the first build.</Empty>
      ) : (
        <div className="grid gap-6 lg:grid-cols-[minmax(0,18rem)_1fr]">
          <Panel pad={false} title="Runs">
            <ul className="divide-y divide-line border-t border-line">
              {runs.slice(0, 40).map((r) => (
                <li key={r.id}>
                  <button onClick={() => setSelected(r.id)} className={`w-full text-left px-5 py-3 hover:bg-well/60 ${shown === r.id ? "bg-well" : ""}`}>
                    <span className="flex items-center justify-between gap-2">
                      <span className="text-[14px]">#{r.buildNumber}</span>
                      <JobBadge status={r.status} />
                    </span>
                    <span className="block text-[12.5px] text-slate mt-0.5">
                      {ago(r.createdAt)}
                      {r.triggerBranch && ` · ${r.triggerBranch}`}
                      {r.triggerCommit && ` · ${r.triggerCommit.slice(0, 7)}`}
                    </span>
                  </button>
                </li>
              ))}
            </ul>
          </Panel>
          {shown && <RunDetail runId={shown} onCancel={refresh} />}
        </div>
      )}
      <Modal
        open={trigger}
        onClose={() => setTrigger(false)}
        title={`Run ${pipeline.name}`}
        footer={
          <>
            <Button tone="quiet" onClick={() => setTrigger(false)}>
              Cancel
            </Button>
            <Button onClick={run}>Run</Button>
          </>
        }
      >
        <div className="flex flex-col gap-4">
          <Field label="Branch" optional hint="The repository's branch when empty.">
            <input className={inputClass} value={branch} onChange={(e) => setBranch(e.target.value)} />
          </Field>
          <Field label="Commit" optional hint="The newest commit on the branch when empty.">
            <input className={`${inputClass} font-mono`} value={commit} onChange={(e) => setCommit(e.target.value)} />
          </Field>
        </div>
      </Modal>
    </>
  );
}

function RunDetail({ runId, onCancel }: { runId: string; onCancel: () => void }) {
  const { data: run } = usePoll<PipelineRun>(`/api/pipelines/executions/${runId}`, 2000);
  const { data: logs } = usePoll<{ logs: string }>(`/api/pipelines/executions/${runId}/logs`, run && (run.status === "RUNNING" || run.status === "PENDING") ? 2000 : 0);
  const toast = useToast();
  if (!run) return <Spinner />;
  const active = run.status === "RUNNING" || run.status === "PENDING";
  return (
    <Panel
      title={
        <span className="flex items-center gap-2">
          #{run.buildNumber} <JobBadge status={run.status} />
        </span>
      }
      action={
        active ? (
          <Button
            tone="quiet"
            size="sm"
            onClick={async () => {
              await api.post(`/api/pipelines/executions/${runId}/cancel`);
              toast("Cancelling");
              onCancel();
            }}
          >
            Cancel
          </Button>
        ) : (
          <span className="text-[13px] text-slate">{run.durationMs ? `took ${duration(run.durationMs)}` : dateTime(run.createdAt)}</span>
        )
      }
    >
      <p className="text-[13px] text-slate mb-3">
        Started {dateTime(run.startedAt || run.createdAt)} by {run.triggeredBy ?? "a webhook"}
      </p>
      {run.errorMessage && (
        <div className="mb-3">
          <Notice tone="error">{run.errorMessage}</Notice>
        </div>
      )}
      {(run.stageExecutions ?? []).length > 0 && (
        <ol className="flex flex-wrap gap-2 mb-4">
          {run.stageExecutions!.map((s) => (
            <li key={s.id} className="rounded-xl border border-line px-3 py-2 text-[13px]">
              <div className="flex items-center gap-2">
                <span className="font-medium">{s.stageName}</span>
                <JobBadge status={s.status} />
              </div>
              <ul className="mt-1 text-slate">
                {s.stepExecutions.map((st) => (
                  <li key={st.id}>
                    {st.stepName}
                    {st.status === "FAILED" && st.exitCode ? ` (exit ${st.exitCode})` : ""}
                  </li>
                ))}
              </ul>
            </li>
          ))}
        </ol>
      )}
      <LogView text={logs?.logs ?? run.logs ?? ""} height="h-[460px]" empty={active ? "Starting…" : "No output."} />
    </Panel>
  );
}

// --- editor ----------------------------------------------------------------------------------

const STEP_TYPES: { value: StepType; label: string; hint: string }[] = [
  { value: "SHELL", label: "Shell script", hint: "Runs a script in a container (Alpine by default)." },
  { value: "DOCKERFILE", label: "Build image", hint: "Builds a Dockerfile from the workspace." },
  { value: "DOCKER_COMPOSE", label: "Compose", hint: "Runs docker compose with a file from the workspace." },
  { value: "CUSTOM_IMAGE", label: "Any image", hint: "Runs a command in an image of your choice, e.g. node:22 for npm test." },
  { value: "JAR", label: "Java JAR", hint: "Runs a JAR with Java 21." },
  { value: "ROLLOUT", label: "Release with a rollout", hint: "Releases an image through a blue/green or canary rollout on the same host." },
];

type Cfg = Record<string, string>;

function cfg(step: PipelineStep): Cfg {
  try {
    const v = JSON.parse(step.configuration || "{}");
    return Object.fromEntries(Object.entries(v).map(([k, x]) => [k, Array.isArray(x) ? x.join(" ") : String(x ?? "")]));
  } catch {
    return {};
  }
}

let tempId = 0;
const newId = () => `new-${Date.now()}-${tempId++}`;

function blankStep(): PipelineStep {
  return { name: "Step", stepType: "SHELL", configuration: JSON.stringify({ image: "alpine:3", script: "echo hello" }), timeoutSeconds: 1800, continueOnFailure: false };
}

function Editor({ pipeline, onSaved }: { pipeline: Pipeline | null; onSaved?: () => void }) {
  const { hosts, hostId } = useHost();
  const { data: repos } = usePoll<GitRepo[]>("/api/git/repositories");
  const { data: rollouts } = usePoll<Rollout[]>("/api/rollouts");
  const router = useRouter();
  const toast = useToast();
  const [name, setName] = useState(pipeline?.name ?? "");
  const [description, setDescription] = useState(pipeline?.description ?? "");
  const [repoId, setRepoId] = useState(pipeline?.gitRepositoryId ?? "");
  const [host, setHost] = useState(pipeline?.dockerHostId ?? hostId ?? "");
  const [webhook, setWebhook] = useState(pipeline?.webhookEnabled ?? false);
  const [branchFilter, setBranchFilter] = useState(pipeline?.branchFilter ?? "");
  const [stages, setStages] = useState<(PipelineStage & { id: string })[]>(
    pipeline?.stages.length
      ? pipeline.stages.map((s) => ({ ...s, id: s.id! }))
      : [{ id: newId(), name: "Build", executionMode: "SEQUENTIAL", dependsOn: [], stopOnFailure: true, steps: [blankStep()] }],
  );
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const setStage = (i: number, patch: Partial<PipelineStage>) => setStages((s) => s.map((x, j) => (j === i ? { ...x, ...patch } : x)));
  const setStep = (i: number, k: number, patch: Partial<PipelineStep>) =>
    setStages((s) => s.map((x, j) => (j === i ? { ...x, steps: x.steps.map((st, m) => (m === k ? { ...st, ...patch } : st)) } : x)));
  const setCfg = (i: number, k: number, key: string, value: string) => {
    const step = stages[i].steps[k];
    setStep(i, k, { configuration: JSON.stringify({ ...cfg(step), [key]: value }) });
  };

  const save = async () => {
    setBusy(true);
    setError(null);
    const body = {
      name,
      description,
      gitRepositoryId: repoId || null,
      dockerHostId: host,
      enabled: pipeline?.enabled ?? true,
      webhookEnabled: webhook,
      branchFilter: branchFilter || null,
      stages: stages.map((s, i) => ({
        id: s.id,
        name: s.name,
        orderIndex: i,
        executionMode: s.executionMode,
        dependsOn: s.dependsOn,
        stopOnFailure: s.stopOnFailure,
        steps: s.steps.map((st, k) => ({ ...st, orderIndex: k })),
      })),
    };
    try {
      if (pipeline) {
        await api.put(`/api/pipelines/${pipeline.id}`, body);
        toast("Saved");
        onSaved?.();
      } else {
        const p = await api.post<Pipeline>("/api/pipelines", body);
        router.push(`/pipelines/view/?id=${p.id}`);
      }
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <>
      {!pipeline && <PageHeader title="New pipeline" back={{ href: "/pipelines/", label: "Pipelines" }} />}
      <div className="flex flex-col gap-6">
        <Panel title="Pipeline">
          <div className="grid gap-4 sm:grid-cols-2">
            <Field label="Name">
              <input className={inputClass} value={name} onChange={(e) => setName(e.target.value)} />
            </Field>
            <Field label="Description" optional>
              <input className={inputClass} value={description} onChange={(e) => setDescription(e.target.value)} />
            </Field>
            <Field label="Repository" hint="Checked out into /workspace before the first stage.">
              <Select value={repoId} onChange={setRepoId}>
                <option value="">None</option>
                {(repos ?? []).map((r) => (
                  <option key={r.id} value={r.id}>
                    {r.name}
                  </option>
                ))}
              </Select>
            </Field>
            <Field label="Runs on">
              <Select value={host} onChange={setHost}>
                {hosts.map((h) => (
                  <option key={h.id} value={h.id}>
                    {h.name || h.dockerHostUrl}
                  </option>
                ))}
              </Select>
            </Field>
          </div>
          <div className="mt-4 grid gap-4 sm:grid-cols-2 items-start">
            <Toggle checked={webhook} onChange={setWebhook} label="Run on push" hint="Gives you a webhook URL to add to the repository." />
            {webhook && (
              <Field label="Only for branches matching" optional hint="A regular expression, e.g. main|release/.*">
                <input className={`${inputClass} font-mono`} value={branchFilter} onChange={(e) => setBranchFilter(e.target.value)} />
              </Field>
            )}
          </div>
        </Panel>

        {stages.map((stage, i) => (
          <Panel
            key={stage.id}
            title={
              <span className="flex items-center gap-2">
                <span className="text-slate tabular-nums">{i + 1}.</span>
                <input className="bg-transparent font-display focus:outline-none border-b border-transparent focus:border-line" value={stage.name} onChange={(e) => setStage(i, { name: e.target.value })} aria-label="Stage name" />
              </span>
            }
            action={
              <span className="flex">
                <IconButton label="Move up" disabled={i === 0} onClick={() => setStages((s) => [...s.slice(0, i - 1), s[i], s[i - 1], ...s.slice(i + 1)])}>
                  <ArrowUp size={16} />
                </IconButton>
                <IconButton label="Move down" disabled={i === stages.length - 1} onClick={() => setStages((s) => [...s.slice(0, i), s[i + 1], s[i], ...s.slice(i + 2)])}>
                  <ArrowDown size={16} />
                </IconButton>
                <IconButton
                  label="Remove stage"
                  disabled={stages.length === 1}
                  onClick={() => setStages((s) => s.filter((_, j) => j !== i).map((x) => ({ ...x, dependsOn: x.dependsOn.filter((d) => d !== stage.id) })))}
                >
                  <X size={16} />
                </IconButton>
              </span>
            }
          >
            <div className="flex flex-wrap items-center gap-x-6 gap-y-3 mb-4">
              <Segmented
                label="Steps run"
                value={stage.executionMode}
                onChange={(v) => setStage(i, { executionMode: v })}
                options={[
                  { value: "SEQUENTIAL", label: "One after another" },
                  { value: "PARALLEL", label: "At the same time" },
                ]}
              />
              <Toggle checked={stage.stopOnFailure} onChange={(v) => setStage(i, { stopOnFailure: v })} label="Stop the pipeline if it fails" />
            </div>
            {stages.length > 1 && (
              <div className="mb-4 text-[14px]">
                <span className="text-slate">Waits for: </span>
                {stages
                  .filter((s) => s.id !== stage.id)
                  .map((s) => (
                    <label key={s.id} className="inline-flex items-center gap-1.5 mr-4">
                      <input
                        type="checkbox"
                        checked={stage.dependsOn.includes(s.id)}
                        onChange={(e) => setStage(i, { dependsOn: e.target.checked ? [...stage.dependsOn, s.id] : stage.dependsOn.filter((d) => d !== s.id) })}
                      />
                      {s.name}
                    </label>
                  ))}
                {stage.dependsOn.length === 0 && <span className="text-slate">nothing, it starts right away</span>}
              </div>
            )}
            <div className="flex flex-col gap-4">
              {stage.steps.map((step, k) => {
                const c = cfg(step);
                return (
                  <div key={k} className="rounded-xl border border-line p-4">
                    <div className="grid gap-3 sm:grid-cols-[1fr_1fr_auto] items-end">
                      <Field label="Step name">
                        <input className={inputClass} value={step.name} onChange={(e) => setStep(i, k, { name: e.target.value })} />
                      </Field>
                      <Field label="Kind" hint={STEP_TYPES.find((t) => t.value === step.stepType)?.hint}>
                        <Select value={step.stepType} onChange={(v) => setStep(i, k, { stepType: v as StepType, configuration: "{}" })}>
                          {STEP_TYPES.map((t) => (
                            <option key={t.value} value={t.value}>
                              {t.label}
                            </option>
                          ))}
                        </Select>
                      </Field>
                      <IconButton label="Remove step" onClick={() => setStage(i, { steps: stage.steps.filter((_, m) => m !== k) })} className="mb-1.5">
                        <X size={16} />
                      </IconButton>
                    </div>
                    <div className="grid gap-3 sm:grid-cols-2 mt-3">
                      {step.stepType === "SHELL" && (
                        <>
                          <Field label="Image" optional>
                            <input className={inputClass} value={c.image ?? ""} onChange={(e) => setCfg(i, k, "image", e.target.value)} placeholder="alpine:3" />
                          </Field>
                          <div className="sm:col-span-2">
                            <Field label="Script">
                              <textarea className={`${inputClass} code`} rows={4} value={c.script ?? ""} onChange={(e) => setCfg(i, k, "script", e.target.value)} />
                            </Field>
                          </div>
                        </>
                      )}
                      {step.stepType === "CUSTOM_IMAGE" && (
                        <>
                          <Field label="Image">
                            <input className={inputClass} value={c.image ?? ""} onChange={(e) => setCfg(i, k, "image", e.target.value)} placeholder="node:22" />
                          </Field>
                          <Field label="Command">
                            <input className={`${inputClass} font-mono text-[13px]`} value={c.command ?? ""} onChange={(e) => setCfg(i, k, "command", e.target.value)} placeholder="npm ci && npm test" />
                          </Field>
                        </>
                      )}
                      {step.stepType === "DOCKERFILE" && (
                        <>
                          <Field label="Image name">
                            <input className={inputClass} value={c.imageName ?? ""} onChange={(e) => setCfg(i, k, "imageName", e.target.value)} placeholder="my-app:latest" />
                          </Field>
                          <Field label="Dockerfile" optional>
                            <input className={inputClass} value={c.dockerfile ?? ""} onChange={(e) => setCfg(i, k, "dockerfile", e.target.value)} placeholder="Dockerfile" />
                          </Field>
                          <Field label="Build context" optional hint="Folder in the workspace; the root when empty.">
                            <input className={inputClass} value={c.context ?? ""} onChange={(e) => setCfg(i, k, "context", e.target.value)} placeholder="." />
                          </Field>
                        </>
                      )}
                      {step.stepType === "DOCKER_COMPOSE" && (
                        <>
                          <Field label="Compose file" optional>
                            <input className={inputClass} value={c.composeFile ?? ""} onChange={(e) => setCfg(i, k, "composeFile", e.target.value)} placeholder="docker-compose.yml" />
                          </Field>
                          <Field label="Command" optional>
                            <input className={`${inputClass} font-mono text-[13px]`} value={c.command ?? ""} onChange={(e) => setCfg(i, k, "command", e.target.value)} placeholder="up -d" />
                          </Field>
                          <Field label="Project name" optional>
                            <input className={inputClass} value={c.projectName ?? ""} onChange={(e) => setCfg(i, k, "projectName", e.target.value)} />
                          </Field>
                        </>
                      )}
                      {step.stepType === "ROLLOUT" && (
                        <>
                          <Field label="Rollout">
                            <Select value={c.rolloutId ?? ""} onChange={(v) => setCfg(i, k, "rolloutId", v)}>
                              <option value="">Choose a rollout</option>
                              {(rollouts ?? [])
                                .filter((r) => r.dockerHostId === host)
                                .map((r) => (
                                  <option key={r.id} value={r.id}>
                                    {r.name}
                                  </option>
                                ))}
                            </Select>
                          </Field>
                          <Field label="Image" hint="${NAME} uses a variable, e.g. my-app:${GIT_COMMIT_SHORT}.">
                            <input className={`${inputClass} font-mono text-[13px]`} value={c.image ?? ""} onChange={(e) => setCfg(i, k, "image", e.target.value)} placeholder="my-app:${GIT_COMMIT_SHORT}" />
                          </Field>
                          <div className="sm:col-span-2">
                            <Toggle
                              checked={c.wait !== "false"}
                              onChange={(v) => setCfg(i, k, "wait", v ? "true" : "false")}
                              label="Wait for the release to finish"
                              hint="The step then fails if the release fails or is rolled back. Approval steps count against the time limit."
                            />
                          </div>
                        </>
                      )}
                      {step.stepType === "JAR" && (
                        <>
                          <Field label="JAR file">
                            <input className={inputClass} value={c.jarPath ?? ""} onChange={(e) => setCfg(i, k, "jarPath", e.target.value)} placeholder="build/libs/app.jar" />
                          </Field>
                          <Field label="Arguments" optional>
                            <input className={inputClass} value={c.args ?? ""} onChange={(e) => setCfg(i, k, "args", e.target.value)} />
                          </Field>
                          <Field label="Java options" optional>
                            <input className={inputClass} value={c.jvmOpts ?? ""} onChange={(e) => setCfg(i, k, "jvmOpts", e.target.value)} placeholder="-Xmx512m" />
                          </Field>
                        </>
                      )}
                      <Field label="Working directory" optional hint="Relative to /workspace">
                        <input className={inputClass} value={step.workingDirectory ?? ""} onChange={(e) => setStep(i, k, { workingDirectory: e.target.value })} />
                      </Field>
                      <Field label="Time limit (minutes)">
                        <input
                          className={inputClass}
                          inputMode="numeric"
                          value={Math.round(step.timeoutSeconds / 60) || ""}
                          onChange={(e) => setStep(i, k, { timeoutSeconds: (Number(e.target.value.replace(/\D/g, "")) || 0) * 60 })}
                        />
                      </Field>
                    </div>
                    <div className="mt-3">
                      <Toggle checked={step.continueOnFailure} onChange={(v) => setStep(i, k, { continueOnFailure: v })} label="Carry on if this step fails" />
                    </div>
                  </div>
                );
              })}
              <div>
                <Button tone="quiet" size="sm" onClick={() => setStage(i, { steps: [...stage.steps, blankStep()] })}>
                  Add step
                </Button>
              </div>
            </div>
          </Panel>
        ))}
        <div>
          <Button
            tone="quiet"
            onClick={() =>
              setStages((s) => [...s, { id: newId(), name: `Stage ${s.length + 1}`, executionMode: "SEQUENTIAL", dependsOn: s.length ? [s[s.length - 1].id] : [], stopOnFailure: true, steps: [blankStep()] }])
            }
          >
            Add stage
          </Button>
        </div>
        {error && <Notice tone="error">{error}</Notice>}
        <div>
          <Button onClick={save} busy={busy} disabled={!name || !host}>
            {pipeline ? "Save pipeline" : "Create pipeline"}
          </Button>
        </div>
      </div>
    </>
  );
}
