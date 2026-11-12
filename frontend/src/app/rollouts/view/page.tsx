"use client";

import Link from "next/link";
import { Suspense, useEffect, useState, type ReactNode } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { Check, Circle, Loader2, Minus, Pause, X } from "lucide-react";
import { useSession } from "@/components/app";
import { RolloutBadge } from "@/components/badges";
import { LineChart } from "@/components/LineChart";
import { ManagedBadge, ManagedNote } from "@/components/managed";
import { RolloutForm, rolloutBody, rolloutFormFrom } from "@/components/RolloutForm";
import { Badge, Button, Copyable, Empty, Field, KeyValues, LogView, Menu, Modal, Notice, PageHeader, Panel, Spinner, Tabs, inputClass, useConfirm, useToast } from "@/components/ui";
import { api } from "@/lib/api";
import { ago, dateTime, duration } from "@/lib/format";
import { usePoll } from "@/lib/hooks";
import { useManaged } from "@/lib/managed";
import type { Rollout, RolloutRun, RolloutSample, RolloutSlot, RolloutStep } from "@/lib/types";

type Tab = "overview" | "releases" | "settings";

export default function RolloutPage() {
  return (
    <Suspense>
      <RolloutView />
    </Suspense>
  );
}

const active = (r: RolloutRun | null | undefined) => !!r && (r.status === "PENDING" || r.status === "RUNNING" || r.status === "WAITING_APPROVAL");

function RolloutView() {
  const id = useSearchParams().get("id");
  const { session } = useSession();
  const router = useRouter();
  const toast = useToast();
  const confirm = useConfirm();
  const [tab, setTab] = useState<Tab>("overview");
  const { data: r, error, refresh } = usePoll<Rollout>(id ? `/api/rollouts/${id}` : null, 3000);
  const [releaseOpen, setReleaseOpen] = useState(false);
  const [selected, setSelected] = useState<string | null>(null);
  const [saveBusy, setSaveBusy] = useState(false);
  const [saveError, setSaveError] = useState<string | null>(null);
  const managed = useManaged().find("rollouts", id);

  if (!id) return null;
  if (error && !r) return <Notice tone="error">{error}</Notice>;
  if (!r) return <Spinner />;

  const running = active(r.currentRun);
  const rollback = async () => {
    if (!(await confirm({ title: "Roll back?", body: `Visitors go back to ${r.previousImage ?? r.previousContainer}. It is started first if it was stopped.`, confirm: "Roll back" }))) return;
    try {
      await api.post(`/api/rollouts/${id}/rollback`);
      toast("Rolling back");
      setTab("overview");
      refresh();
    } catch (e) {
      toast((e as Error).message, "error");
    }
  };

  return (
    <>
      <PageHeader
        title={r.name}
        back={{ href: "/rollouts/", label: "Rollouts" }}
        intro={
          <>
            {r.url ? (
              <a href={r.url} target="_blank" rel="noreferrer" className="underline underline-offset-2">
                {r.url.replace(/^https?:\/\//, "")}
              </a>
            ) : (
              "No address yet"
            )}{" "}
            · {r.strategy === "CANARY" ? "Canary" : "Blue/green"}
            {r.description ? ` · ${r.description}` : ""}
          </>
        }
      >
        {managed && <ManagedBadge entry={managed.key} />}
        <Button disabled={running} onClick={() => setReleaseOpen(true)}>
          Release a version
        </Button>
        <Menu
          items={[
            !!r.previousContainer && !running && { label: `Roll back to ${r.previousImage ?? r.previousContainer}`, onClick: rollback },
            session?.admin &&
              !managed && {
              label: "Delete rollout",
              danger: true,
              onClick: async () => {
                if (
                  !(await confirm({
                    title: `Delete ${r.name}?`,
                    body: "The address keeps sending visitors to the current version, as an ordinary ingress route. The containers stay; remove the idle one on the Containers page if you no longer need it.",
                    confirm: "Delete",
                    danger: true,
                  }))
                )
                  return;
                try {
                  await api.del(`/api/rollouts/${id}`);
                  router.push("/rollouts/");
                } catch (e) {
                  toast((e as Error).message, "error");
                }
              },
            },
          ]}
        />
      </PageHeader>

      {!r.routeEnabled && (
        <div className="mb-6">
          <Notice tone="warn">The route is turned off on the Ingress page, so visitors get nothing.</Notice>
        </div>
      )}

      <Tabs<Tab>
        value={tab}
        onChange={setTab}
        tabs={[
          { value: "overview", label: "Overview" },
          { value: "releases", label: "Releases" },
          ...(session?.admin ? [{ value: "settings" as Tab, label: "Settings" }] : []),
        ]}
      />

      {tab === "overview" && (
        <div className="flex flex-col gap-6">
          <Traffic rollout={r} />
          {r.currentRun ? (
            <RunPanel key={r.currentRun.id} rolloutId={r.id} runId={r.currentRun.id} onChange={refresh} />
          ) : r.lastRun ? (
            <RunPanel key={r.lastRun.id} rolloutId={r.id} runId={r.lastRun.id} onChange={refresh} />
          ) : (
            <Empty title="Nothing released yet" action={<Button onClick={() => setReleaseOpen(true)}>Release a version</Button>}>
              {r.activeContainer
                ? `${r.activeContainer} serves visitors now. The first release starts next to it.`
                : "The first release starts the app, runs the tests and creates the address."}
            </Empty>
          )}
          <Sources rollout={r} />
        </div>
      )}

      {tab === "releases" && <Releases rollout={r} selected={selected} onSelect={setSelected} onChange={refresh} />}

      {tab === "settings" && managed && <ManagedNote what="This rollout" />}

      {tab === "settings" && !managed && (
        <RolloutForm
          key={r.id + r.routeId}
          initial={rolloutFormFrom(r, r.dockerHostId)}
          existing={r}
          busy={saveBusy}
          error={saveError}
          submitLabel="Save changes"
          onSubmit={async (v) => {
            setSaveBusy(true);
            setSaveError(null);
            try {
              await api.put(`/api/rollouts/${id}`, rolloutBody(v));
              toast("Saved. Changes apply to the next release.");
              refresh();
            } catch (e) {
              setSaveError((e as Error).message);
            } finally {
              setSaveBusy(false);
            }
          }}
        />
      )}

      <ReleaseDialog
        open={releaseOpen}
        rollout={r}
        onClose={() => setReleaseOpen(false)}
        onStarted={() => {
          setReleaseOpen(false);
          setTab("overview");
          refresh();
        }}
      />
    </>
  );
}

// --- traffic ---------------------------------------------------------------------------------

function slotColor(name: string, r: Rollout) {
  if (name === `${r.containerName}-blue`) return "bg-slot-blue";
  if (name === `${r.containerName}-green`) return "bg-slot-green";
  return "bg-slate";
}

function Traffic({ rollout: r }: { rollout: Rollout }) {
  const serving = r.slots.filter((s) => (s.traffic ?? 0) > 0);
  return (
    <Panel title="Who gets the visitors">
      {serving.length > 0 ? (
        <div className="flex h-3 rounded-full overflow-hidden bg-well mb-5" role="img" aria-label={serving.map((s) => `${s.name} ${s.traffic}%`).join(", ")}>
          {serving.map((s) => (
            <div key={s.name} className={`${slotColor(s.name, r)} transition-[width] duration-700`} style={{ width: `${s.traffic}%` }} />
          ))}
        </div>
      ) : (
        <p className="text-[14px] text-slate mb-4">No version has visitors yet.</p>
      )}
      <ul className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
        {r.slots.map((s) => (
          <SlotCard key={s.name} slot={s} rollout={r} />
        ))}
      </ul>
    </Panel>
  );
}

function SlotCard({ slot: s, rollout: r }: { slot: RolloutSlot; rollout: Rollout }) {
  const role = s.role === "active" ? "Current version" : s.role === "new" ? "New version" : s.role === "previous" ? "Previous version" : "Idle";
  return (
    <li className="rounded-xl border border-line p-4 min-w-0">
      <div className="flex items-center justify-between gap-2">
        <span className="flex items-center gap-2 min-w-0">
          <span className={`size-2.5 rounded-full shrink-0 ${slotColor(s.name, r)}`} aria-hidden />
          <span className="font-medium truncate">{s.name}</span>
        </span>
        <span className="font-display text-[1.1rem] tabular-nums">{s.traffic ? `${s.traffic}%` : ""}</span>
      </div>
      <div className="mt-2 flex flex-wrap items-center gap-1.5">
        <Badge tone={s.role === "active" ? "ok" : s.role === "new" ? "live" : "plain"}>{role}</Badge>
        <span className="text-[13px] text-slate">{s.exists ? (s.state === "running" ? "Running" : s.state === "exited" ? "Stopped" : s.state) : "Not created"}</span>
      </div>
      {s.image && <div className="text-[12.5px] text-slate mt-1.5 truncate font-mono">{s.image}</div>}
    </li>
  );
}

// --- runs ------------------------------------------------------------------------------------

function StepIcon({ status }: { status: RolloutStep["status"] }) {
  switch (status) {
    case "PASSED":
      return <Check size={15} className="text-ok" aria-label="Passed" />;
    case "FAILED":
      return <X size={15} className="text-danger" aria-label="Failed" />;
    case "RUNNING":
      return <Loader2 size={15} className="animate-spin text-marigold" aria-label="Running" />;
    case "WAITING":
      return <Pause size={15} className="text-caution" aria-label="Waiting" />;
    case "SKIPPED":
      return <Minus size={15} className="text-slate" aria-label="Skipped" />;
    default:
      return <Circle size={12} className="text-slate/60" aria-label="Not started" />;
  }
}

function RunPanel({ rolloutId, runId, onChange }: { rolloutId: string; runId: string; onChange: () => void }) {
  const [poll, setPoll] = useState(1500);
  const { data: run, refresh } = usePoll<RolloutRun>(`/api/rollouts/${rolloutId}/runs/${runId}`, poll);
  const toast = useToast();
  const confirm = useConfirm();
  const [busy, setBusy] = useState<string | null>(null);
  const isActive = active(run);
  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setPoll(isActive || !run ? 1500 : 0);
  }, [isActive, run]);
  if (!run) return <Spinner />;

  const act = async (key: string, path: string, body?: unknown, done?: string) => {
    setBusy(key);
    try {
      await api.post(`/api/rollouts/${rolloutId}/runs/${runId}/${path}`, body);
      if (done) toast(done);
      refresh();
      onChange();
    } catch (e) {
      toast((e as Error).message, "error");
    } finally {
      setBusy(null);
    }
  };
  const took = run.finishedAt && run.startedAt ? duration(run.finishedAt - run.startedAt) : "";
  const watching = run.status === "RUNNING" && run.trafficPercent > 0 && run.trafficPercent <= 100 && run.steps.some((s) => s.status === "RUNNING" && (s.kind === "CANARY" || s.kind === "WATCH"));

  return (
    <Panel
      title={
        <span className="flex flex-wrap items-center gap-2">
          <span>
            {run.kind === "ROLLBACK" ? "Roll back" : "Release"} #{run.number}
          </span>
          <RolloutBadge status={run.status} kind={run.kind} />
        </span>
      }
      action={
        isActive ? (
          <span className="flex gap-2">
            {watching && (
              <Button size="sm" tone="quiet" busy={busy === "promote"} onClick={() => act("promote", "promote", undefined, "Promoting")}>
                {run.steps.some((s) => s.kind === "CANARY") ? "Promote now" : "Finish now"}
              </Button>
            )}
            <Button
              size="sm"
              tone="danger"
              busy={busy === "abort"}
              onClick={async () => {
                if (!(await confirm({ title: "Stop this release?", body: run.trafficPercent > 0 ? `Visitors go back to ${run.stableContainer}.` : "Visitors keep using the current version.", confirm: "Stop", danger: true }))) return;
                act("abort", "abort", undefined, "Stopping");
              }}
            >
              {run.trafficPercent > 0 ? "Roll back" : "Stop"}
            </Button>
          </span>
        ) : (
          took && <span className="text-[13px] text-slate">took {took}</span>
        )
      }
    >
      {run.status === "WAITING_APPROVAL" && (
        <div className="mb-4 rounded-xl border border-caution/40 p-4 flex flex-wrap items-center justify-between gap-3">
          <div className="text-[14px]">
            <div className="font-medium">{run.approvalStep ?? "Approval"}: waiting for a decision</div>
            <div className="text-slate text-[13px]">The new version is tested and running, but has no visitors yet.</div>
          </div>
          <span className="flex gap-2">
            <Button tone="quiet" busy={busy === "reject"} onClick={() => act("reject", "approve", { approved: false }, "Rejected")}>
              Reject
            </Button>
            <Button busy={busy === "approve"} onClick={() => act("approve", "approve", { approved: true }, "Approved")}>
              Approve
            </Button>
          </span>
        </div>
      )}
      {!isActive && run.errorMessage && (
        <div className="mb-4">
          <Notice tone={run.status === "FAILED" ? "error" : "warn"}>{run.errorMessage}</Notice>
        </div>
      )}
      <KeyValues
        rows={[
          ["Image", <Copyable key="i" text={run.image} />],
          ...(run.targetContainer ? ([["Runs in", run.targetContainer]] as [string, ReactNode][]) : []),
          ...(run.stableContainer ? ([["Replacing", `${run.stableContainer}${run.stableImage ? ` (${run.stableImage})` : ""}`]] as [string, ReactNode][]) : []),
          ["Started by", `${triggerLabel(run)}${run.triggeredBy ? ` · ${run.triggeredBy}` : ""}`],
          ...(run.commitSha ? ([["Commit", run.commitSha.slice(0, 12)]] as [string, ReactNode][]) : []),
          ["When", dateTime(run.createdAt)],
          ...(isActive ? ([["Now", run.phase ?? ""]] as [string, ReactNode][]) : []),
        ]}
      />
      {run.steps.length > 0 && (
        <ol className="mt-5 flex flex-col divide-y divide-line rounded-xl border border-line">
          {run.steps.map((s, i) => (
            <li key={i} className="flex items-start gap-3 px-4 py-2.5">
              <span className="mt-0.5 w-4 grid place-items-center shrink-0">
                <StepIcon status={s.status} />
              </span>
              <span className="min-w-0 flex-1">
                <span className="flex items-baseline justify-between gap-3">
                  <span className={`text-[14px] ${s.status === "SKIPPED" || s.status === "PENDING" ? "text-slate" : ""}`}>{s.name}</span>
                  {s.finishedAt > 0 && s.startedAt > 0 && <span className="text-[12px] text-slate shrink-0">{duration(s.finishedAt - s.startedAt)}</span>}
                </span>
                {s.message && <span className="block text-[12.5px] text-slate break-words">{s.message}</span>}
              </span>
            </li>
          ))}
        </ol>
      )}
      {run.metrics.length > 0 && <Metrics samples={run.metrics} />}
      <div className="mt-5">
        <LogView text={run.logs ?? ""} height="h-[360px]" empty={isActive ? "Starting…" : "No output."} filename={`release-${run.number}.log`} />
      </div>
    </Panel>
  );
}

function triggerLabel(run: RolloutRun) {
  switch (run.triggerType) {
    case "GIT":
      return "Git push";
    case "PIPELINE":
      return "Pipeline";
    case "ROLLBACK":
      return "Roll back";
    default:
      return "By hand";
  }
}

function Metrics({ samples }: { samples: RolloutSample[] }) {
  const sum = (f: (s: RolloutSample) => number) => samples.reduce((a, s) => a + f(s), 0);
  const newReq = sum((s) => s.newRequests);
  const newErr = sum((s) => s.newErrors);
  const oldReq = sum((s) => s.oldRequests);
  const oldErr = sum((s) => s.oldErrors);
  const avg = (ms: (s: RolloutSample) => number, req: (s: RolloutSample) => number) => {
    const n = sum(req);
    return n ? Math.round(sum((s) => ms(s) * req(s)) / n) : 0;
  };
  const rate = (e: number, n: number) => (n ? `${((e * 100) / n).toFixed(1)}%` : "-");
  return (
    <div className="mt-5">
      <div className="grid gap-3 sm:grid-cols-2">
        <div className="rounded-xl bg-well/60 p-4">
          <div className="text-[13px] text-slate">New version</div>
          <div className="text-[14px] mt-1">
            {newReq} requests · {rate(newErr, newReq)} failed · {avg((s) => s.newAvgMs, (s) => s.newRequests)} ms avg
          </div>
        </div>
        <div className="rounded-xl bg-well/60 p-4">
          <div className="text-[13px] text-slate">Current version</div>
          <div className="text-[14px] mt-1">
            {oldReq} requests · {rate(oldErr, oldReq)} failed · {avg((s) => s.oldAvgMs, (s) => s.oldRequests)} ms avg
          </div>
        </div>
      </div>
      {samples.length > 1 && (
        <div className="grid gap-4 lg:grid-cols-2 mt-4">
          <LineChart title="New version: failed requests" points={samples.map((s) => ({ t: s.at, v: s.newRequests ? (s.newErrors * 100) / s.newRequests : 0 }))} format={(v) => `${v.toFixed(1)}%`} />
          <LineChart title="New version: requests per 10 s" points={samples.map((s) => ({ t: s.at, v: s.newRequests }))} format={(v) => String(Math.round(v))} />
        </div>
      )}
    </div>
  );
}

function Releases({ rollout, selected, onSelect, onChange }: { rollout: Rollout; selected: string | null; onSelect: (id: string) => void; onChange: () => void }) {
  const { data: runs, error } = usePoll<RolloutRun[]>(`/api/rollouts/${rollout.id}/runs`, 3000);
  if (error && !runs) return <Notice tone="error">{error}</Notice>;
  if (!runs) return <Spinner />;
  if (runs.length === 0) return <Empty title="No releases yet">Releases appear here with their tests, traffic and logs.</Empty>;
  const shown = selected ?? runs[0].id;
  return (
    <div className="grid gap-6 lg:grid-cols-[minmax(0,20rem)_1fr]">
      <Panel title="History" pad={false}>
        <ul className="divide-y divide-line border-t border-line">
          {runs.map((j) => (
            <li key={j.id}>
              <button onClick={() => onSelect(j.id)} className={`w-full text-left px-5 py-3 hover:bg-well/60 ${shown === j.id ? "bg-well" : ""}`}>
                <span className="flex items-center justify-between gap-2">
                  <span className="flex items-center gap-2">
                    <span className="text-[13px] font-medium">#{j.number}</span>
                    <RolloutBadge status={j.status} kind={j.kind} />
                  </span>
                  <span className="text-[12px] text-slate">{ago(j.createdAt)}</span>
                </span>
                <span className="block text-[12.5px] text-slate mt-1 truncate">
                  {triggerLabel(j)} · {j.image}
                </span>
              </button>
            </li>
          ))}
        </ul>
      </Panel>
      <RunPanel key={shown} rolloutId={rollout.id} runId={shown} onChange={onChange} />
    </div>
  );
}

function Sources({ rollout: r }: { rollout: Rollout }) {
  return (
    <Panel title="Where new versions come from">
      <ul className="flex flex-col gap-2 text-[14px]">
        <li>By hand: press Release a version and enter an image.</li>
        <li>
          From Git:{" "}
          {r.gitRepositories.length ? (
            r.gitRepositories.map((g, i) => (
              <span key={g.id}>
                {i > 0 && ", "}
                <Link href={`/git/view/?id=${g.id}`} className="underline underline-offset-2">
                  {g.name}
                </Link>
              </span>
            ))
          ) : (
            <span className="text-slate">in a Dockerfile repository&apos;s settings, choose this rollout under &quot;How to run it&quot;.</span>
          )}
        </li>
        <li>
          From a pipeline: <span className="text-slate">add a &quot;Release with a rollout&quot; step after the step that builds the image.</span>
        </li>
      </ul>
    </Panel>
  );
}

function ReleaseDialog({ open, rollout, onClose, onStarted }: { open: boolean; rollout: Rollout; onClose: () => void; onStarted: () => void }) {
  const [image, setImage] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const toast = useToast();
  const submit = async () => {
    setBusy(true);
    setError(null);
    try {
      const run = await api.post<RolloutRun>(`/api/rollouts/${rollout.id}/release`, { image: image.trim() });
      toast(`Release #${run.number} started`);
      setImage("");
      onStarted();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };
  return (
    <Modal
      open={open}
      onClose={onClose}
      title="Release a version"
      footer={
        <>
          <Button tone="quiet" onClick={onClose}>
            Cancel
          </Button>
          <Button busy={busy} disabled={!image.trim()} onClick={submit}>
            Release
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-4">
        <Field label="Image" hint={rollout.activeImage ? `Now serving: ${rollout.activeImage}` : "Pulled if it isn't on the host yet."}>
          <input
            className={`${inputClass} font-mono`}
            value={image}
            onChange={(e) => setImage(e.target.value)}
            onKeyDown={(e) => e.key === "Enter" && image.trim() && submit()}
            placeholder="ghcr.io/me/site:1.4.0"
            autoFocus
            spellCheck={false}
          />
        </Field>
        <p className="text-[13px] text-slate">
          It starts as {rollout.slots.find((s) => s.role !== "active" && (s.name.endsWith("-blue") || s.name.endsWith("-green")))?.name ?? "the idle slot"}, runs {rollout.tests.length || "no"} test
          {rollout.tests.length === 1 ? "" : "s"}, then{" "}
          {rollout.strategy === "CANARY" ? `gets ${rollout.canarySteps.replace(/,/g, "%, ")}% of visitors before everyone` : "gets all visitors at once"}.
        </p>
        {error && <Notice tone="error">{error}</Notice>}
      </div>
    </Modal>
  );
}
