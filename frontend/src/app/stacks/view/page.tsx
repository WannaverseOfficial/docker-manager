"use client";

import Link from "next/link";
import { Suspense, useEffect, useState } from "react";
import { Wand2 } from "lucide-react";
import { useRouter, useSearchParams } from "next/navigation";
import { useHost, useSession } from "@/components/app";
import {
  Badge,
  Button,
  CodeEditor,
  Empty,
  KeyValues,
  LogView,
  Menu,
  Notice,
  PageHeader,
  Panel,
  Select,
  Spinner,
  StateBadge,
  Tabs,
  useConfirm,
  useToast,
} from "@/components/ui";
import { api, enc } from "@/lib/api";
import { ago, dateTime, shortId } from "@/lib/format";
import { usePoll, useStackJob } from "@/lib/hooks";
import type { StackDetail } from "@/lib/types";

type Tab = "containers" | "compose" | "logs" | "versions";

const ACTION_LABEL: Record<string, string> = {
  DEPLOY: "Deploying",
  PULL_AND_DEPLOY: "Pulling images and deploying",
  START: "Starting",
  STOP: "Stopping",
  RESTART: "Restarting",
  DOWN: "Taking down",
  REMOVE: "Removing",
  REMOVE_WITH_VOLUMES: "Removing with volumes",
};

export default function StackPage() {
  return (
    <Suspense>
      <StackView />
    </Suspense>
  );
}

function StackView() {
  const name = useSearchParams().get("name") ?? "";
  const { hostId } = useHost();
  const { session } = useSession();
  const router = useRouter();
  const toast = useToast();
  const confirm = useConfirm();
  const [tab, setTab] = useState<Tab>("containers");
  const base = hostId ? `/api/docker/hosts/${hostId}/stacks/${enc(name)}` : null;
  const { data: stack, error, refresh } = usePoll<StackDetail>(base, 5000);
  const job = useStackJob(hostId, (j) => {
    refresh();
    if (j.status === "success") {
      if (j.action === "REMOVE" || j.action === "REMOVE_WITH_VOLUMES") {
        toast(`${name} removed`);
        router.push("/stacks/");
      } else toast(`${name}: ${ACTION_LABEL[j.action]?.toLowerCase() ?? j.action} finished`);
    } else toast(`${name}: ${ACTION_LABEL[j.action]?.toLowerCase() ?? j.action} failed. See the output.`, "error");
  });

  // Pick up an action that is still running (for example after a reload).
  const [resumed, setResumed] = useState(false);
  useEffect(() => {
    if (stack?.runningJobId && !resumed && !job.job) {
      // eslint-disable-next-line react-hooks/set-state-in-effect
      setResumed(true);
      job.follow(stack.runningJobId);
    }
  }, [stack, resumed, job]);

  if (!hostId) return null;
  if (error && !stack)
    return (
      <>
        <PageHeader title={name} back={{ href: "/stacks/", label: "Stacks" }} />
        <Notice tone="error">{error}</Notice>
      </>
    );
  if (!stack) return <Spinner />;

  const s = stack.summary;
  const git = s.source === "GIT";

  const action = async (a: string) => {
    if (a === "REMOVE" || a === "REMOVE_WITH_VOLUMES" || a === "DOWN") {
      const ok = await confirm({
        title: a === "DOWN" ? `Take ${name} down?` : `Remove ${name}?`,
        body:
          a === "REMOVE_WITH_VOLUMES"
            ? "Stops and deletes the containers and networks, and deletes the stack's volumes with all their data. This can't be undone."
            : a === "REMOVE"
              ? "Stops and deletes the containers and networks and forgets the stack. Volumes and their data are kept."
              : "Stops and deletes the containers and networks. The compose file is kept, so you can deploy again.",
        confirm: a === "DOWN" ? "Take down" : "Remove",
        danger: true,
      });
      if (!ok) return;
    }
    try {
      const r = await api.post<{ jobId: string }>(`${base}/actions/${a.toLowerCase()}`);
      job.follow(r.jobId);
    } catch (e) {
      toast((e as Error).message, "error");
    }
  };

  return (
    <>
      <PageHeader
        title={name}
        back={{ href: "/stacks/", label: "Stacks" }}
        intro={
          <span className="flex flex-wrap items-center gap-x-3 gap-y-1">
            <span>
              {s.total === 0 ? "Not running" : `${s.running} of ${s.total} containers running`}
              {s.lastDeployedAt ? `, deployed ${ago(s.lastDeployedAt)}` : ""}
            </span>
            {git && s.gitRepositoryId && (
              <Link href={`/git/view/?id=${s.gitRepositoryId}`}>
                <Badge>from git</Badge>
              </Link>
            )}
            {!s.managed && <Badge tone="warn">not managed here</Badge>}
          </span>
        }
      >
        {s.managed && !git && (
          <Button busy={job.running && job.job?.action === "DEPLOY"} disabled={job.running} onClick={() => action("DEPLOY")}>
            Deploy
          </Button>
        )}
        {git && s.gitRepositoryId && (
          <Link href={`/git/view/?id=${s.gitRepositoryId}`} className="inline-flex items-center rounded-full px-4 h-9 text-[14px] font-medium bg-accent text-accent-ink hover:opacity-85">
            Deploy from git
          </Link>
        )}
        {s.total > 0 && s.running < s.total && (
          <Button tone="quiet" disabled={job.running} onClick={() => action("START")}>
            Start
          </Button>
        )}
        {s.running > 0 && (
          <Button tone="quiet" disabled={job.running} onClick={() => action("STOP")}>
            Stop
          </Button>
        )}
        <Menu
          items={[
            s.managed && !git && { label: "Pull newest images and redeploy", onClick: () => action("PULL_AND_DEPLOY"), disabled: job.running },
            s.running > 0 && { label: "Restart", onClick: () => action("RESTART"), disabled: job.running },
            s.total > 0 && { label: "Take down", onClick: () => action("DOWN"), disabled: job.running },
            s.managed && session?.admin && { label: "Remove stack", danger: true, onClick: () => action("REMOVE"), disabled: job.running },
            s.managed && session?.admin && { label: "Remove stack and its volumes", danger: true, onClick: () => action("REMOVE_WITH_VOLUMES"), disabled: job.running },
          ]}
        />
      </PageHeader>

      {s.lastError && !job.job && (
        <div className="mb-6">
          <Notice tone="error" title="The last action failed">
            <span className="log log-wrap block">{s.lastError}</span>
          </Notice>
        </div>
      )}

      {job.job && (
        <Panel
          className="mb-6"
          title={
            <span className="flex items-center gap-2">
              {job.running && <span className="size-2 rounded-full bg-marigold animate-pulse" aria-hidden />}
              {ACTION_LABEL[job.job.action] ?? job.job.action}
              {!job.running && <span className={job.job.status === "success" ? "text-ok" : "text-danger"}>{job.job.status === "success" ? "finished" : "failed"}</span>}
            </span>
          }
          action={
            !job.running && (
              <Button tone="ghost" size="sm" onClick={job.clear}>
                Hide
              </Button>
            )
          }
        >
          <LogView lines={job.lines} height="h-64" />
        </Panel>
      )}

      {!s.managed && <ExternalStack name={name} hostId={hostId} stack={stack} onImported={refresh} admin={!!session?.admin} />}

      <Tabs<Tab>
        value={tab}
        onChange={setTab}
        tabs={[
          { value: "containers", label: "Containers", count: stack.containers.length },
          { value: "compose", label: "Compose file" },
          { value: "logs", label: "Logs" },
          { value: "versions", label: "Versions", count: stack.versions.length },
        ]}
      />
      {tab === "containers" &&
        (stack.containers.length === 0 ? (
          <Empty title="No containers">{s.managed ? "Deploy the stack to start its services." : "Its containers have been removed."}</Empty>
        ) : (
          <ul className="rounded-2xl border border-line bg-paper divide-y divide-line">
            {stack.containers.map((c) => (
              <li key={c.id}>
                <Link href={`/containers/view/?id=${c.id}`} className="grid gap-x-6 gap-y-1 sm:grid-cols-[minmax(0,1.3fr)_minmax(0,1fr)_8rem] items-center px-4 py-3 group">
                  <span className="min-w-0">
                    <span className="block truncate font-medium group-hover:underline underline-offset-2">{c.service ?? c.name}</span>
                    <span className="block text-[13px] text-slate truncate">{c.image}</span>
                  </span>
                  <span className="text-[13px] text-slate truncate">{c.ports.length ? c.ports.join(", ") : "No published ports"}</span>
                  <span className="flex sm:flex-col gap-x-3">
                    <StateBadge state={c.state} status={c.status} />
                    <span className="text-[12px] text-slate truncate">{c.status}</span>
                  </span>
                </Link>
              </li>
            ))}
          </ul>
        ))}
      {tab === "compose" && <ComposeTab stack={stack} base={base!} busy={job.running} onDeploy={(id) => job.follow(id)} onSaved={refresh} hostId={hostId} admin={!!session?.admin} />}
      {tab === "logs" && <StackLogs base={base!} />}
      {tab === "versions" && <Versions stack={stack} base={base!} onRollback={(id) => job.follow(id)} admin={!!session?.admin} />}
    </>
  );
}

type Detected = {
  composeContent: string;
  envContent: string | null;
  composePath: string;
  projectDir: string | null;
  envSource: "file" | "rebuilt" | "none";
  rebuiltVariables: string[];
  missingVariables: string[];
  services: string[];
  warnings: string[];
};

function ExternalStack({ name, hostId, stack, onImported, admin }: { name: string; hostId: string; stack: StackDetail; onImported: () => void; admin: boolean }) {
  const [open, setOpen] = useState(false);
  const [compose, setCompose] = useState(stack.composeContent ?? "");
  const [env, setEnv] = useState("");
  const [found, setFound] = useState<Detected | null>(null);
  const [detecting, setDetecting] = useState(false);
  const [detectError, setDetectError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const toast = useToast();

  const detect = async () => {
    setDetecting(true);
    setDetectError(null);
    try {
      const d = await api.get<Detected>(`/api/docker/hosts/${hostId}/stacks/${enc(name)}/detect`);
      setFound(d);
      setCompose(d.composeContent);
      setEnv(d.envContent ?? "");
      setOpen(true);
    } catch (e) {
      setDetectError((e as Error).message);
      setOpen(true);
    } finally {
      setDetecting(false);
    }
  };

  return (
    <div className="mb-6">
      <Notice title="Started outside Docker Manager">
        This compose project was started from a file on {stack.summary.workingDir ? <code className="font-mono text-[13px] break-all">{stack.summary.workingDir}</code> : "the host"}.
        You can start, stop and take it down here.{" "}
        {admin ? "To edit and redeploy it, import its compose file." : "An administrator can import its compose file to manage it fully."}
        {admin && !open && (
          <span className="flex flex-wrap gap-2 mt-3">
            <Button size="sm" busy={detecting} onClick={detect}>
              <Wand2 size={14} aria-hidden /> Find the compose file
            </Button>
            <Button size="sm" tone="quiet" onClick={() => setOpen(true)}>
              Paste it instead
            </Button>
          </span>
        )}
      </Notice>
      {open && (
        <Panel className="mt-4" title="Import compose file">
          <div className="flex flex-col gap-4">
            {detectError && <Notice tone="warn">{detectError}</Notice>}
            {found && (
              <Notice tone="ok" title={`Found ${found.composePath}`}>
                {found.services.length ? `Services: ${found.services.join(", ")}. ` : ""}
                {found.envSource === "file" && "Its .env file was found too. "}
                {found.envSource === "rebuilt" &&
                  `There was no .env file, so ${found.rebuiltVariables.length ? `${found.rebuiltVariables.join(", ")} ${found.rebuiltVariables.length === 1 ? "was" : "were"} filled in from the running containers` : "one was started for you"}. `}
                {found.projectDir && (
                  <>
                    Relative paths like <code className="font-mono text-[13px]">./data</code> keep pointing at <code className="font-mono text-[13px] break-all">{found.projectDir}</code>.{" "}
                  </>
                )}
                Importing doesn&apos;t restart anything.
              </Notice>
            )}
            {found && found.missingVariables.length > 0 && (
              <Notice tone="warn" title="Some settings are missing">
                {found.missingVariables.join(", ")} {found.missingVariables.length === 1 ? "isn't" : "aren't"} in any container. Fill {found.missingVariables.length === 1 ? "it" : "them"} in below before deploying.
              </Notice>
            )}
            {found?.warnings.map((w) => (
              <Notice key={w} tone="warn">
                {w}
              </Notice>
            ))}
            <CodeEditor label="Compose file" value={compose} onChange={setCompose} rows={16} />
            <CodeEditor label=".env file" value={env} onChange={setEnv} rows={found?.envContent ? 8 : 4} placeholder=".env contents, if the project uses one" />
            {error && <Notice tone="error">{error}</Notice>}
            <div className="flex flex-wrap gap-2">
              <Button
                busy={busy}
                disabled={!compose.trim()}
                onClick={async () => {
                  setBusy(true);
                  setError(null);
                  try {
                    await api.post(`/api/docker/hosts/${hostId}/stacks/${enc(name)}/import`, {
                      composeContent: compose,
                      envContent: env,
                      projectDir: found?.projectDir ?? null,
                    });
                    toast(`${name} is now managed here`);
                    setOpen(false);
                    onImported();
                  } catch (e) {
                    setError((e as Error).message);
                  } finally {
                    setBusy(false);
                  }
                }}
              >
                Import
              </Button>
              {!found && (
                <Button tone="quiet" busy={detecting} onClick={detect}>
                  <Wand2 size={14} aria-hidden /> Find it for me
                </Button>
              )}
              <Button tone="ghost" onClick={() => setOpen(false)}>
                Cancel
              </Button>
            </div>
          </div>
        </Panel>
      )}
    </div>
  );
}

function ComposeTab({
  stack,
  base,
  hostId,
  busy,
  onDeploy,
  onSaved,
  admin,
}: {
  stack: StackDetail;
  base: string;
  hostId: string;
  busy: boolean;
  onDeploy: (jobId: string) => void;
  onSaved: () => void;
  admin: boolean;
}) {
  const toast = useToast();
  const readOnly = stack.summary.source === "GIT" || !stack.summary.managed || !admin;
  const [compose, setCompose] = useState(stack.composeContent ?? "");
  const [env, setEnv] = useState(stack.envContent ?? "");
  const [saving, setSaving] = useState<null | "save" | "deploy" | "check">(null);
  const [error, setError] = useState<string | null>(null);
  const [ok, setOk] = useState<string | null>(null);
  const dirty = compose !== (stack.composeContent ?? "") || env !== (stack.envContent ?? "");

  const save = async (deploy: boolean) => {
    setSaving(deploy ? "deploy" : "save");
    setError(null);
    setOk(null);
    try {
      const r = await api.put<{ jobId?: string }>(base, { composeContent: compose, envContent: env, deploy });
      onSaved();
      if (r.jobId) onDeploy(r.jobId);
      else toast("Saved. Deploy to apply the changes.");
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setSaving(null);
    }
  };

  return (
    <div className="flex flex-col gap-6">
      {stack.summary.source === "GIT" && <Notice>This file comes from the git repository. Change it there and deploy the repository.</Notice>}
      {!admin && stack.summary.managed && stack.summary.source !== "GIT" && (
        <Notice>Compose files can give containers access to the host, so only administrators edit them. You can deploy, start and stop the stack.</Notice>
      )}
      {!stack.summary.managed && <Notice>This project&apos;s compose file is on the host, outside Docker Manager, so it isn&apos;t shown here.</Notice>}
      {stack.path && (
        <p className="text-[13px] text-slate">
          Stored in <code className="font-mono">{stack.path}</code>. Relative paths in the file (like ./data) are relative to that folder.
        </p>
      )}
      {(stack.summary.managed || !!stack.composeContent) && (
        <Panel title="docker-compose.yml" pad>
          <CodeEditor label="Compose file" value={compose} onChange={readOnly ? undefined : (v) => (setCompose(v), setOk(null))} readOnly={readOnly} rows={22} />
        </Panel>
      )}
      {!readOnly && (
        <Panel title=".env">
          <CodeEditor label=".env file" value={env} onChange={setEnv} rows={5} />
        </Panel>
      )}
      {ok && <Notice tone="ok">{ok}</Notice>}
      {error && <Notice tone="error">{error}</Notice>}
      {!readOnly && (
        <div className="flex flex-wrap gap-2">
          <Button onClick={() => save(true)} busy={saving === "deploy"} disabled={busy || !!saving || !compose.trim()}>
            {dirty ? "Save and deploy" : "Redeploy"}
          </Button>
          <Button tone="quiet" onClick={() => save(false)} busy={saving === "save"} disabled={busy || !!saving || !dirty}>
            Save only
          </Button>
          <Button
            tone="ghost"
            busy={saving === "check"}
            disabled={!!saving}
            onClick={async () => {
              setSaving("check");
              setError(null);
              setOk(null);
              try {
                const r = await api.post<{ services: string[] }>(`/api/docker/hosts/${hostId}/stacks/validate`, { composeContent: compose });
                setOk(`Looks good: ${r.services.join(", ")}.`);
              } catch (e) {
                setError((e as Error).message);
              } finally {
                setSaving(null);
              }
            }}
          >
            Check file
          </Button>
          {dirty && (
            <Button tone="ghost" onClick={() => (setCompose(stack.composeContent ?? ""), setEnv(stack.envContent ?? ""))}>
              Discard changes
            </Button>
          )}
        </div>
      )}
    </div>
  );
}

function StackLogs({ base }: { base: string }) {
  const [tail, setTail] = useState("300");
  const { data, error, loading } = usePoll<{ logs: string }>(`${base}/logs?tail=${tail}`, 5000);
  return (
    <>
      <div className="mb-3">
        <Select value={tail} onChange={setTail} aria-label="Lines" className="w-44">
          <option value="100">Last 100 lines each</option>
          <option value="300">Last 300 lines each</option>
          <option value="2000">Last 2,000 lines each</option>
        </Select>
      </div>
      {error && <Notice tone="error">{error}</Notice>}
      {loading && !data ? <Spinner /> : <LogView text={data?.logs} height="h-[560px]" />}
    </>
  );
}

function Versions({ stack, base, onRollback, admin }: { stack: StackDetail; base: string; onRollback: (jobId: string) => void; admin: boolean }) {
  const confirm = useConfirm();
  const toast = useToast();
  if (!stack.versions.length) return <Empty title="No versions yet">Each deploy is saved here, so you can go back to an earlier compose file.</Empty>;
  const canRollback = admin && stack.summary.managed && stack.summary.source !== "GIT";
  return (
    <>
      <ul className="rounded-2xl border border-line bg-paper divide-y divide-line">
        {stack.versions.map((v) => (
          <li key={v.version} className="flex flex-wrap items-center justify-between gap-3 px-4 py-3">
            <div>
              <div className="text-[14px] flex items-center gap-2">
                Version {v.version}
                {v.status === "ACTIVE" && <Badge tone="live">current</Badge>}
                {v.status === "FAILED" && <Badge tone="danger">failed</Badge>}
              </div>
              <div className="text-[12.5px] text-slate">
                {dateTime(v.createdAt)}
                {v.username && ` by ${v.username}`}
                {v.commitSha && ` · commit ${shortId(v.commitSha).slice(0, 7)}`}
              </div>
            </div>
            {canRollback && v.status !== "ACTIVE" && (
              <Button
                tone="quiet"
                size="sm"
                onClick={async () => {
                  const ok = await confirm({
                    title: `Go back to version ${v.version}?`,
                    body: "Its compose file and .env replace the current ones, and the stack is redeployed.",
                    confirm: "Roll back",
                  });
                  if (!ok) return;
                  try {
                    const r = await api.post<{ jobId: string }>(`${base}/rollback/${v.version}`);
                    onRollback(r.jobId);
                  } catch (e) {
                    toast((e as Error).message, "error");
                  }
                }}
              >
                Roll back
              </Button>
            )}
          </li>
        ))}
      </ul>
      <div className="mt-4">
        <KeyValues rows={[["Kept", "The newest 50 versions of each stack."]]} />
      </div>
    </>
  );
}
