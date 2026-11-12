"use client";

import Link from "next/link";
import { Suspense, useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { Layers } from "lucide-react";
import { useRouter, useSearchParams } from "next/navigation";
import { useHost } from "@/components/app";
import { COMPOSE_PROJECT, COMPOSE_SERVICE, useContainerActions } from "@/components/containers";
import { ContainerForm } from "@/components/ContainerForm";
import { ManagedBadge, ManagedNote } from "@/components/managed";
import { LineChart } from "@/components/LineChart";
import {
  Badge,
  Button,
  Copyable,
  Empty,
  KeyValues,
  LinkButton,
  LogView,
  Notice,
  PageHeader,
  Panel,
  Search,
  Select,
  Spinner,
  StateBadge,
  Tabs,
  Toggle,
  inputClass,
  useConfirm,
  useToast,
} from "@/components/ui";
import { api } from "@/lib/api";
import { ago, bytes, dateTime, mem, percent, shortId } from "@/lib/format";
import { usePending, usePoll } from "@/lib/hooks";
import { useManaged } from "@/lib/managed";
import type { ContainerInspect, ContainerSpec, ContainerStats } from "@/lib/types";

type Tab = "overview" | "stats" | "logs" | "console" | "settings" | "history" | "inspect";
const TABS: Tab[] = ["overview", "stats", "logs", "console", "settings", "history", "inspect"];

export default function ContainerPage() {
  return (
    <Suspense>
      <ContainerView />
    </Suspense>
  );
}

function ContainerView() {
  const params = useSearchParams();
  const id = params.get("id");
  const { hostId } = useHost();
  const router = useRouter();
  const toast = useToast();
  const confirm = useConfirm();
  // Logs first: it's what people open a container for. ?tab= links straight to another tab.
  const [tab, setTab] = useState<Tab>(() => {
    const t = params.get("tab");
    return t && TABS.includes(t as Tab) ? (t as Tab) : "logs";
  });
  const { data: c, error, refresh } = usePoll<ContainerInspect>(hostId && id ? `/api/docker/hosts/${hostId}/containers/${id}` : null, 4000);
  const { act, remove } = useContainerActions(hostId, refresh);
  const [busy, setBusy] = useState<string | null>(null);
  const pending = usePending(c?.Id);
  const managed = useManaged();

  if (!hostId || !id) return null;
  if (error && !c) {
    return (
      <>
        <PageHeader title="Container" back={{ href: "/containers/", label: "Containers" }} />
        <Notice tone="error">{/not found|No such/i.test(error) ? "This container no longer exists. It may have been removed or replaced." : error}</Notice>
      </>
    );
  }
  if (!c) return <Spinner />;

  const name = c.Name.replace(/^\//, "");
  const project = c.Config.Labels?.[COMPOSE_PROJECT];
  const service = c.Config.Labels?.[COMPOSE_SERVICE];
  // Managed by the configuration file: its settings and removal go through the file.
  const entry = managed.find("containers", name, hostId)?.key;
  const s = c.State;
  const run = async (key: string, fn: () => Promise<unknown>) => {
    setBusy(key);
    try {
      await fn();
    } finally {
      setBusy(null);
    }
  };

  const updateImage = async () => {
    const ok = await confirm({
      title: `Update ${name}?`,
      body: `Pulls the newest ${c.Config.Image} and replaces the container with one using it. Settings and volumes stay the same; if the new one fails to start, the current one keeps running.`,
      confirm: "Pull and update",
    });
    if (!ok) return;
    await run("update", async () => {
      try {
        const r = await api.post<{ id: string }>(`/api/docker/hosts/${hostId}/containers/${c.Id}/update-image`);
        toast(`${name} updated`);
        router.replace(`/containers/view/?id=${r.id}`);
      } catch (e) {
        toast((e as Error).message, "error");
      }
    });
  };

  return (
    <>
      <PageHeader
        title={name}
        back={{ href: "/containers/", label: "Containers" }}
        intro={
          <span className="flex flex-wrap items-center gap-x-3 gap-y-1">
            <StateBadge
              state={s.Status}
              status={s.Health?.Status === "unhealthy" ? "(unhealthy)" : s.Status === "exited" ? `Exited (${s.ExitCode})` : undefined}
              pending={pending}
            />
            <span className="text-[14px]">{c.Config.Image}</span>
            {project && (
              <Link href={`/stacks/view/?name=${encodeURIComponent(project)}`}>
                <Badge>stack: {project}</Badge>
              </Link>
            )}
            {entry && <ManagedBadge entry={`containers.${entry}`} />}
          </span>
        }
      >
        {s.Running ? (
          <>
            <Button tone="quiet" busy={busy === "restart"} disabled={!!pending} onClick={() => run("restart", () => act(c.Id, name, "restart", "restarted"))}>
              {busy === "restart" ? "Restarting" : "Restart"}
            </Button>
            <Button tone="quiet" busy={busy === "stop"} disabled={!!pending} onClick={() => run("stop", () => act(c.Id, name, "stop", "stopped"))}>
              {busy === "stop" ? "Stopping" : "Stop"}
            </Button>
          </>
        ) : s.Paused ? (
          <Button busy={busy === "unpause"} disabled={!!pending} onClick={() => run("unpause", () => act(c.Id, name, "unpause", "resumed"))}>
            {busy === "unpause" ? "Resuming" : "Resume"}
          </Button>
        ) : (
          <Button busy={busy === "start"} disabled={!!pending} onClick={() => run("start", () => act(c.Id, name, "start", "started"))}>
            {busy === "start" ? "Starting" : "Start"}
          </Button>
        )}
        {!project && (
          <Button tone="quiet" busy={busy === "update"} onClick={updateImage}>
            Update image
          </Button>
        )}
        {project && (
          <LinkButton href={`/stacks/view/?name=${encodeURIComponent(project)}`} tone="quiet">
            <Layers size={15} aria-hidden /> Stack {project}
          </LinkButton>
        )}
        {!entry && (
          <Button
            tone="danger"
            onClick={async () => {
              await remove(c.Id, name, project);
              router.push("/containers/");
            }}
          >
            Remove
          </Button>
        )}
      </PageHeader>

      {s.OOMKilled && <div className="mb-6"><Notice tone="warn">The last run was stopped because it ran out of memory. Raise its memory limit or find what uses so much.</Notice></div>}
      {s.Error && <div className="mb-6"><Notice tone="error">{s.Error}</Notice></div>}

      <Tabs<Tab>
        value={tab}
        onChange={setTab}
        tabs={[
          { value: "overview", label: "Overview" },
          { value: "stats", label: "Usage" },
          { value: "logs", label: "Logs" },
          { value: "console", label: "Console" },
          { value: "settings", label: "Settings" },
          { value: "history", label: "History" },
          { value: "inspect", label: "Inspect" },
        ]}
      />
      {tab === "overview" && <Overview c={c} project={project} service={service} />}
      {tab === "stats" && (s.Running ? <Usage hostId={hostId} id={c.Id} /> : <Empty title="Not running">Usage is measured while the container runs.</Empty>)}
      {tab === "logs" && <Logs hostId={hostId} id={c.Id} name={name} />}
      {tab === "console" && (s.Running ? <Console hostId={hostId} id={c.Id} /> : <Empty title="Not running">Start the container to run commands in it.</Empty>)}
      {tab === "settings" &&
        (project ? (
          <Notice>
            This container is part of the stack <b>{project}</b>. Change it in the stack&apos;s compose file so the change survives the next deploy.{" "}
            <Link href={`/stacks/view/?name=${encodeURIComponent(project)}`} className="underline underline-offset-2">
              Open the stack
            </Link>
          </Notice>
        ) : entry ? (
          <ManagedNote what="This container" />
        ) : (
          <Settings hostId={hostId} id={c.Id} name={name} />
        ))}
      {tab === "history" && <History hostId={hostId} id={c.Id} name={name} />}
      {tab === "inspect" && <LogView text={JSON.stringify(c, null, 2)} height="h-[600px]" />}
    </>
  );
}

function Overview({ c, project, service }: { c: ContainerInspect; project?: string; service?: string }) {
  const ports = Object.entries(c.HostConfig.PortBindings ?? {}).flatMap(([cp, binds]) =>
    (binds ?? []).map((b) => `${b.HostIp && b.HostIp !== "0.0.0.0" ? b.HostIp + ":" : ""}${b.HostPort} → ${cp.replace("/tcp", "")}`),
  );
  const [showEnv, setShowEnv] = useState(false);
  const restart = c.HostConfig.RestartPolicy?.Name || "no";
  return (
    <div className="grid gap-6 lg:grid-cols-2">
      <Panel title="Details">
        <KeyValues
          rows={[
            ["ID", <Copyable key="id" text={c.Id} display={shortId(c.Id)} />],
            ["Image", <span key="i" className="font-mono text-[13px] break-all">{c.Config.Image}</span>],
            ["Created", `${dateTime(c.Created)} (${ago(c.Created)})`],
            [c.State.Running ? "Started" : "Stopped", c.State.Running ? ago(c.State.StartedAt) : ago(c.State.FinishedAt)],
            ["Restart policy", restart === "no" ? "Never" : restart],
            ["Restarts", String(c.RestartCount)],
            ["Command", <span key="c" className="font-mono text-[13px] break-all">{[c.Path, ...(c.Args ?? [])].join(" ")}</span>],
            ...(project
              ? ([
                  [
                    "Stack",
                    <Link key="s" href={`/stacks/view/?name=${encodeURIComponent(project)}`} className="underline underline-offset-2 hover:text-ink">
                      {project}
                      {service ? ` (service ${service})` : ""}
                    </Link>,
                  ],
                ] as [string, ReactNode][])
              : []),
            ...(c.State.Health ? ([["Health", `${c.State.Health.Status}${c.State.Health.FailingStreak ? `, failing ${c.State.Health.FailingStreak}×` : ""}`]] as [string, string][]) : []),
          ]}
        />
      </Panel>
      <Panel title="Network">
        <KeyValues
          rows={[
            ["Ports", ports.length ? ports.join(", ") : "None published"],
            ...Object.entries(c.NetworkSettings?.Networks ?? {}).map(([n, v]) => [n, v.IPAddress || "no address"] as [string, string]),
          ]}
        />
      </Panel>
      <Panel title="Storage">
        {(c.Mounts ?? []).length === 0 ? (
          <p className="text-[14px] text-slate">No volumes or mounts. Data written inside the container is lost when it is replaced.</p>
        ) : (
          <ul className="flex flex-col gap-2 text-[14px]">
            {(c.Mounts ?? []).map((m) => (
              <li key={m.Destination} className="min-w-0">
                <span className="font-mono text-[13px] break-all">{m.Destination}</span>
                <span className="block text-[12.5px] text-slate break-all">
                  {m.Type === "volume" ? `volume ${m.Name}` : `host folder ${m.Source}`}
                  {!m.RW && ", read-only"}
                </span>
              </li>
            ))}
          </ul>
        )}
      </Panel>
      <Panel title="Environment" action={<Toggle checked={showEnv} onChange={setShowEnv} label="Show values" />}>
        {(c.Config.Env ?? []).length === 0 ? (
          <p className="text-[14px] text-slate">None.</p>
        ) : (
          <ul className="flex flex-col gap-1 font-mono text-[12.5px]">
            {(c.Config.Env ?? []).map((e) => {
              const i = e.indexOf("=");
              const k = e.slice(0, i);
              return (
                <li key={e} className="break-all">
                  <span className="text-ink">{k}</span>
                  <span className="text-slate">={showEnv ? e.slice(i + 1) : "••••••"}</span>
                </li>
              );
            })}
          </ul>
        )}
      </Panel>
    </div>
  );
}

function Usage({ hostId, id }: { hostId: string; id: string }) {
  const { data } = usePoll<ContainerStats>(`/api/docker/hosts/${hostId}/containers/${id}/stats`, 3000);
  const [history, setHistory] = useState<ContainerStats[]>([]);
  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    if (data) setHistory((h) => [...h.slice(-59), data]);
  }, [data]);
  const net = useMemo(() => {
    // Rates between samples, so the chart shows traffic now rather than totals since start.
    return history.slice(1).map((s, i) => {
      const prev = history[i];
      const dt = Math.max(1, (s.timestamp - prev.timestamp) / 1000);
      return { t: s.timestamp, v: Math.max(0, (s.networkRxBytes + s.networkTxBytes - prev.networkRxBytes - prev.networkTxBytes) / dt) };
    });
  }, [history]);
  if (!data) return <Spinner label="Measuring" />;
  return (
    <>
      <div className="grid gap-3 grid-cols-2 lg:grid-cols-4 mb-6">
        <Panel>
          <div className="text-[13px] text-slate">CPU</div>
          <div className="font-display text-[1.5rem] tabular-nums">{percent(data.cpuPercent)}</div>
          <div className="text-[12.5px] text-slate">of one core ({data.onlineCpus} available)</div>
        </Panel>
        <Panel>
          <div className="text-[13px] text-slate">Memory</div>
          <div className="font-display text-[1.5rem] tabular-nums">{mem(data.memoryUsage)}</div>
          <div className="text-[12.5px] text-slate">limit {mem(data.memoryLimit)}</div>
        </Panel>
        <Panel>
          <div className="text-[13px] text-slate">Network</div>
          <div className="font-display text-[1.5rem] tabular-nums">{bytes(data.networkRxBytes)}</div>
          <div className="text-[12.5px] text-slate">received, {bytes(data.networkTxBytes)} sent</div>
        </Panel>
        <Panel>
          <div className="text-[13px] text-slate">Processes</div>
          <div className="font-display text-[1.5rem] tabular-nums">{data.pids}</div>
          <div className="text-[12.5px] text-slate">disk {bytes(data.blockReadBytes)} read</div>
        </Panel>
      </div>
      <div className="grid gap-6 lg:grid-cols-3">
        <Panel>
          <LineChart title="CPU (% of one core)" points={history.map((s) => ({ t: s.timestamp, v: s.cpuPercent }))} format={(v) => percent(v)} />
        </Panel>
        <Panel>
          <LineChart title="Memory" points={history.map((s) => ({ t: s.timestamp, v: s.memoryUsage }))} format={mem} max={data.memoryLimit && data.memoryLimit < 1e15 ? undefined : undefined} />
        </Panel>
        <Panel>
          <LineChart title="Network traffic" points={net} format={(v) => `${bytes(v)}/s`} />
        </Panel>
      </div>
      <p className="text-[12.5px] text-slate mt-3">Sampled every 3 seconds while this page is open. Memory excludes reclaimable file cache.</p>
    </>
  );
}

function Logs({ hostId, id, name }: { hostId: string; id: string; name: string }) {
  const [tail, setTail] = useState("500");
  const [follow, setFollow] = useState(true);
  const [timestamps, setTimestamps] = useState(false);
  const [q, setQ] = useState("");
  const { data, error, loading } = usePoll<{ logs: string }>(`/api/docker/hosts/${hostId}/containers/${id}/logs?tail=${tail}&timestamps=${timestamps}`, follow ? 3000 : 0);
  const text = useMemo(() => {
    const raw = data?.logs ?? "";
    if (!q.trim()) return raw;
    const needle = q.toLowerCase();
    return raw
      .split("\n")
      .filter((l) => l.toLowerCase().includes(needle))
      .join("\n");
  }, [data, q]);
  return (
    <>
      <div className="flex flex-wrap items-center gap-3 mb-3">
        <Select value={tail} onChange={setTail} aria-label="Lines" className="w-36">
          <option value="100">Last 100 lines</option>
          <option value="500">Last 500 lines</option>
          <option value="2000">Last 2,000 lines</option>
          <option value="10000">Last 10,000 lines</option>
        </Select>
        <Toggle checked={follow} onChange={setFollow} label="Follow" />
        <Toggle checked={timestamps} onChange={setTimestamps} label="Timestamps" />
        <div className="ml-auto">
          <Search value={q} onChange={setQ} placeholder="Filter lines" />
        </div>
      </div>
      {error && <Notice tone="error">{error}</Notice>}
      {loading && !data ? <Spinner /> : <LogView text={text} height="h-[560px]" filename={`${name}.log`} empty={q ? "No lines match." : "This container hasn't written anything yet."} />}
    </>
  );
}

function Console({ hostId, id }: { hostId: string; id: string }) {
  const [cmd, setCmd] = useState("");
  const [out, setOut] = useState<string[]>([]);
  const [busy, setBusy] = useState(false);
  const [history, setHistory] = useState<string[]>([]);
  const [pos, setPos] = useState(-1);
  const input = useRef<HTMLInputElement>(null);

  const runCmd = async (e: React.FormEvent) => {
    e.preventDefault();
    const line = cmd.trim();
    if (!line) return;
    setBusy(true);
    setHistory((h) => [line, ...h.filter((x) => x !== line)].slice(0, 50));
    setPos(-1);
    const args = line.match(/(?:[^\s"']+|"[^"]*"|'[^']*')+/g)?.map((a) => a.replace(/^["']|["']$/g, "")) ?? [];
    try {
      const r = await api.post<{ output: string }>(`/api/docker/hosts/${hostId}/containers/${id}/exec`, { command: args });
      setOut((o) => [...o, `$ ${line}`, r.output.replace(/\n$/, "")]);
    } catch (err) {
      setOut((o) => [...o, `$ ${line}`, `Error: ${(err as Error).message}`]);
    } finally {
      setBusy(false);
      setCmd("");
      input.current?.focus();
    }
  };

  return (
    <>
      <p className="text-[14px] text-slate mb-3">
        Runs one command at a time inside the container, without a shell, so pipes and <code className="font-mono">&amp;&amp;</code> are not available. Every
        command is recorded in the activity log.
      </p>
      <LogView lines={out} height="h-[420px]" empty="Output appears here." wrap />
      <form onSubmit={runCmd} className="flex gap-2 mt-3">
        <input
          ref={input}
          className={`${inputClass} font-mono text-[13px]`}
          value={cmd}
          onChange={(e) => setCmd(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === "ArrowUp" && history.length) {
              e.preventDefault();
              const p = Math.min(history.length - 1, pos + 1);
              setPos(p);
              setCmd(history[p]);
            } else if (e.key === "ArrowDown") {
              e.preventDefault();
              const p = pos - 1;
              setPos(Math.max(-1, p));
              setCmd(p >= 0 ? history[p] : "");
            }
          }}
          placeholder="ls -la /app"
          aria-label="Command"
          spellCheck={false}
          autoCapitalize="off"
        />
        <Button type="submit" busy={busy} disabled={!cmd.trim()}>
          Run
        </Button>
      </form>
    </>
  );
}

function Settings({ hostId, id, name }: { hostId: string; id: string; name: string }) {
  const router = useRouter();
  const toast = useToast();
  const { data: spec, error } = usePoll<ContainerSpec>(`/api/docker/hosts/${hostId}/containers/${id}/spec`);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  if (error) return <Notice tone="error">{error}</Notice>;
  if (!spec) return <Spinner />;
  return (
    <ContainerForm
      key={id}
      initial={spec}
      hostId={hostId}
      editing
      submitLabel="Save and recreate"
      busy={busy}
      error={err}
      onSubmit={async (next, { pull }) => {
        setBusy(true);
        setErr(null);
        try {
          const r = await api.put<{ id: string }>(`/api/docker/hosts/${hostId}/containers/${id}?pull=${pull}`, next);
          toast(`${next.name || name} recreated with the new settings`);
          router.replace(`/containers/view/?id=${r.id}`);
        } catch (e) {
          setErr((e as Error).message);
        } finally {
          setBusy(false);
        }
      }}
    />
  );
}

interface RollbackPoint {
  operationId: string;
  operationType: string;
  resourceName: string;
  timestamp: number;
  username: string | null;
  canRollback: boolean;
  reason: string | null;
}

const OP_LABEL: Record<string, string> = {
  CONTAINER_CREATE: "Created",
  CONTAINER_UPDATE: "Settings changed",
  CONTAINER_START: "Started",
  CONTAINER_STOP: "Stopped",
  CONTAINER_RESTART: "Restarted",
  CONTAINER_DELETE: "Removed",
};

function History({ hostId, id, name }: { hostId: string; id: string; name: string }) {
  const { data, error, refresh } = usePoll<RollbackPoint[]>(`/api/rollback/hosts/${hostId}/points/CONTAINER/${id}`);
  const confirm = useConfirm();
  const toast = useToast();
  const router = useRouter();
  if (error) return <Notice tone="error">{error}</Notice>;
  if (!data) return <Spinner />;
  if (!data.length) return <Empty title="No history yet">Changes made through Docker Manager appear here and can be undone.</Empty>;
  return (
    <ul className="rounded-2xl border border-line bg-paper divide-y divide-line">
      {data.map((p) => (
        <li key={p.operationId} className="flex flex-wrap items-center justify-between gap-3 px-4 py-3">
          <div>
            <div className="text-[14px]">{OP_LABEL[p.operationType] ?? p.operationType}</div>
            <div className="text-[12.5px] text-slate">
              {dateTime(p.timestamp)}
              {p.username && ` by ${p.username}`}
            </div>
          </div>
          {p.canRollback ? (
            <Button
              tone="quiet"
              size="sm"
              onClick={async () => {
                const ok = await confirm({ title: `Undo this change to ${name}?`, body: "The container is put back the way it was before this change.", confirm: "Undo" });
                if (!ok) return;
                try {
                  const r = await api.post<{ success: boolean; message: string; newResourceId?: string }>(`/api/rollback/operation/${p.operationId}`);
                  if (!r.success) throw new Error(r.message);
                  toast("Change undone");
                  if (r.newResourceId) router.replace(`/containers/view/?id=${r.newResourceId}`);
                  else refresh();
                } catch (e) {
                  toast((e as Error).message, "error");
                }
              }}
            >
              Undo
            </Button>
          ) : (
            p.reason && <span className="text-[12.5px] text-slate">{p.reason}</span>
          )}
        </li>
      ))}
    </ul>
  );
}
