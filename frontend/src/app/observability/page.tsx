"use client";

import Link from "next/link";
import { useMemo, useState, type ReactNode } from "react";
import { ExternalLink } from "lucide-react";
import { useHost, useSession } from "@/components/app";
import {
  Badge,
  Button,
  Checkbox,
  Copyable,
  Field,
  KeyValues,
  Notice,
  PageHeader,
  Panel,
  Segmented,
  Select,
  Spinner,
  StateBadge,
  Table,
  Toggle,
  copyText,
  inputClass,
  useConfirm,
  useToast,
} from "@/components/ui";
import { api, enc } from "@/lib/api";
import { usePoll } from "@/lib/hooks";
import { ALLOY, type Settings, type StackView } from "@/lib/observability";
import type { Container } from "@/lib/types";

const HOSTNAME = /^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$/;

export default function ObservabilityPage() {
  const { hostId } = useHost();
  const { session } = useSession();
  const base = hostId ? `/api/observability/hosts/${hostId}` : null;
  const { data, error, refresh } = usePoll<{ view: StackView | null }>(session?.admin ? base : null, 5000);

  if (!session?.admin) {
    return (
      <>
        <PageHeader title="Observability" />
        <Notice>Observability is managed by administrators, because Grafana shows every container&apos;s logs and metrics.</Notice>
      </>
    );
  }

  return (
    <>
      <PageHeader
        title="Observability"
        intro="Grafana with Prometheus, Mimir, Loki and Tempo, already connected to each other. Container logs, container and host metrics, and OpenTelemetry from your apps flow in by themselves."
      />
      {error && <Notice tone="error">{error}</Notice>}
      {!data && !error && <Spinner />}
      {data && base && !data.view && <Create base={base} onCreated={refresh} />}
      {data?.view && base && <Stack base={base} view={data.view} onChange={refresh} />}
    </>
  );
}

// --- settings form (create and change) -----------------------------------------------------------

function defaults(): Settings {
  return {
    mimir: true,
    loki: true,
    tempo: true,
    collectLogs: true,
    collectMetrics: true,
    otlp: true,
    metricsRetentionDays: 30,
    logsRetentionDays: 14,
    tracesRetentionDays: 7,
    access: "PUBLIC",
    hostname: "",
    port: 3000,
    dockerManagerUrl: "",
  };
}

function fromStack(v: StackView): Settings {
  const s = v.stack;
  return {
    mimir: s.mimirEnabled,
    loki: s.lokiEnabled,
    tempo: s.tempoEnabled,
    collectLogs: s.collectLogs,
    collectMetrics: s.collectMetrics,
    otlp: s.otlpEnabled,
    metricsRetentionDays: s.metricsRetentionDays,
    logsRetentionDays: s.logsRetentionDays,
    tracesRetentionDays: s.tracesRetentionDays,
    access: s.access,
    hostname: s.hostname ?? "",
    port: s.localPort ?? 3000,
    dockerManagerUrl: "",
  };
}

function problems(s: Settings): string | null {
  if (s.access === "PUBLIC" && !HOSTNAME.test(s.hostname ?? "")) return "Enter Grafana's full hostname, for example grafana.example.com";
  if (s.access === "LOCAL" && (!s.port || s.port < 1024 || s.port > 65535)) return "Choose a port between 1024 and 65535";
  for (const [label, days, max] of [
    ["Metrics", s.metricsRetentionDays, 3650],
    ["Logs", s.logsRetentionDays, 3650],
    ["Traces", s.tracesRetentionDays, 365],
  ] as const) {
    if (!Number.isInteger(days) || days < 1 || days > max) return `${label} retention must be between 1 and ${max} days`;
  }
  return null;
}

/** The request body: this page's address goes along so Grafana can send people back here to sign in. */
function body(s: Settings) {
  return {
    ...s,
    hostname: s.access === "PUBLIC" ? s.hostname : null,
    port: s.access === "LOCAL" ? s.port : null,
    collectLogs: s.loki && s.collectLogs,
    dockerManagerUrl: window.location.origin,
  };
}

function SettingsForm({ value, onChange, ingressOn }: { value: Settings; onChange: (s: Settings) => void; ingressOn: boolean | undefined }) {
  const set = <K extends keyof Settings>(k: K, v: Settings[K]) => onChange({ ...value, [k]: v });
  const days = (k: "metricsRetentionDays" | "logsRetentionDays" | "tracesRetentionDays", label: string, enabled: boolean) => (
    <Field label={label}>
      <div className="flex items-center gap-2 w-36">
        <input
          className={inputClass}
          inputMode="numeric"
          disabled={!enabled}
          value={Number.isFinite(value[k]) ? String(value[k]) : ""}
          onChange={(e) => set(k, e.target.value === "" ? NaN : Number(e.target.value.replace(/\D/g, "")))}
        />
        <span className="text-[14px] text-slate">days</span>
      </div>
    </Field>
  );

  return (
    <div className="flex flex-col gap-6">
      <section>
        <h3 className="text-[13px] font-medium mb-3">Reach Grafana</h3>
        <Segmented
          label="Reach Grafana"
          value={value.access}
          onChange={(v) => set("access", v)}
          options={[
            { value: "PUBLIC", label: "On a hostname, over HTTPS" },
            { value: "LOCAL", label: "On a port on this server" },
          ]}
        />
        <div className="mt-3 max-w-md">
          {value.access === "PUBLIC" ? (
            <Field
              label="Hostname"
              hint="Point its DNS at this server first. Ingress gets a Let's Encrypt certificate, and administrators sign in with their Docker Manager account."
              error={value.hostname && !HOSTNAME.test(value.hostname) ? "A full hostname, for example grafana.example.com" : null}
            >
              <input
                className={`${inputClass} font-mono text-[13px]`}
                value={value.hostname ?? ""}
                onChange={(e) => set("hostname", e.target.value.trim().toLowerCase())}
                placeholder="grafana.example.com"
              />
            </Field>
          ) : (
            <Field label="Port" hint="Published on 127.0.0.1 only. Sign in with Grafana's own administrator password, shown here once it runs.">
              <input
                className={inputClass}
                inputMode="numeric"
                value={value.port ?? ""}
                onChange={(e) => set("port", e.target.value === "" ? null : Number(e.target.value.replace(/\D/g, "")))}
              />
            </Field>
          )}
          {value.access === "PUBLIC" && ingressOn === false && (
            <div className="mt-3">
              <Notice tone="warn">
                Ingress is off for this host. <Link href="/ingress/" className="underline underline-offset-2">Turn it on</Link> first, or reach Grafana on a port.
              </Notice>
            </div>
          )}
        </div>
      </section>

      <section>
        <h3 className="text-[13px] font-medium mb-3">Components</h3>
        <div className="grid gap-3 sm:grid-cols-2">
          <Toggle checked disabled onChange={() => {}} label="Grafana and Prometheus" hint="Always on: dashboards, and metrics scraped every 15 seconds." />
          <Toggle checked={value.mimir} onChange={(v) => set("mimir", v)} label="Mimir" hint="Prometheus writes everything into Mimir, which keeps the long history. Without it, Prometheus keeps it." />
          <Toggle checked={value.loki} onChange={(v) => set("loki", v)} label="Loki" hint="Logs." />
          <Toggle checked={value.tempo} onChange={(v) => set("tempo", v)} label="Tempo" hint="Traces, with service graphs and span metrics." />
        </div>
      </section>

      <section>
        <h3 className="text-[13px] font-medium mb-3">Collect</h3>
        <div className="grid gap-3 sm:grid-cols-2">
          <Toggle
            checked={value.loki && value.collectLogs}
            disabled={!value.loki}
            onChange={(v) => set("collectLogs", v)}
            label="Every container's logs"
            hint={value.loki ? "Grafana Alloy reads them from Docker and labels them with the container and Compose service." : "Needs Loki."}
          />
          <Toggle checked={value.collectMetrics} onChange={(v) => set("collectMetrics", v)} label="Container and host metrics" hint="cAdvisor for each container's CPU, memory, network and disk; node-exporter for the host." />
          <Toggle
            checked={value.otlp}
            onChange={(v) => set("otlp", v)}
            label="OpenTelemetry from your apps"
            hint="Apps you connect send traces, metrics and logs to Grafana Alloy, which passes them on."
          />
        </div>
      </section>

      <section>
        <h3 className="text-[13px] font-medium mb-3">Keep data for</h3>
        <div className="flex flex-wrap gap-4">
          {days("metricsRetentionDays", "Metrics", true)}
          {days("logsRetentionDays", "Logs", value.loki)}
          {days("tracesRetentionDays", "Traces", value.tempo)}
        </div>
      </section>
    </div>
  );
}

function useIngressOn(hostId: string | null) {
  const { data } = usePoll<{ status?: string } | null>(hostId ? `/api/ingress/hosts/${hostId}/status` : null);
  return data === undefined ? undefined : data?.status === "ENABLED";
}

function Create({ base, onCreated }: { base: string; onCreated: () => void }) {
  const { hostId } = useHost();
  const ingressOn = useIngressOn(hostId);
  const [settings, setSettings] = useState<Settings>(defaults);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const problem = problems(settings);

  return (
    <div className="grid gap-5 lg:grid-cols-[minmax(0,1.4fr)_minmax(0,1fr)]">
      <Panel title="Start an observability stack">
        <SettingsForm value={settings} onChange={setSettings} ingressOn={ingressOn} />
        {error && (
          <div className="mt-5">
            <Notice tone="error">{error}</Notice>
          </div>
        )}
        <div className="mt-6 pt-5 border-t border-line flex flex-wrap items-center gap-3">
          <Button
            busy={busy}
            disabled={!!problem}
            onClick={async () => {
              setBusy(true);
              setError(null);
              try {
                await api.post(base, body(settings));
                onCreated();
              } catch (e) {
                setError((e as Error).message);
                setBusy(false);
              }
            }}
          >
            Start the stack
          </Button>
          {problem && <span className="text-[13px] text-slate">{problem}</span>}
        </div>
      </Panel>
      <Panel title="Good to know">
        <ul className="list-disc pl-5 flex flex-col gap-2 text-[14px] text-slate">
          <li>Everything runs in containers named dm-obs-*, on their own network. Only Grafana is reachable from outside.</li>
          <li>
            With a hostname, Docker Manager administrators sign in to Grafana with their own account. Other users can&apos;t: Grafana shows every container&apos;s logs and metrics.
          </li>
          <li>The first start downloads the images, about 4 GB on disk once unpacked, which can take a few minutes.</li>
          <li>Data lives in dm-obs-* volumes on this host. Turning a component off keeps its data until you remove the stack.</li>
          <li>Single-server mode: fine for one host&apos;s logs, metrics and traces, not for a large fleet.</li>
        </ul>
      </Panel>
    </div>
  );
}

// --- a running stack -------------------------------------------------------------------------

function Stack({ base, view, onChange }: { base: string; view: StackView; onChange: () => void }) {
  const s = view.stack;
  const toast = useToast();
  const confirm = useConfirm();
  const [repairing, setRepairing] = useState(false);
  const outdated = view.components.filter((c) => c.enabled && (c.outdated || c.state !== "running"));
  const working = view.busy || !!s.activity;

  return (
    <div className="flex flex-col gap-5">
      {working && (
        <Notice title={s.activity ? `${s.activity}...` : "Working..."}>This page updates by itself. Downloading the images can take a few minutes the first time.</Notice>
      )}
      {!working && s.lastResultFailed && s.lastResult && (
        <Notice tone="error" title="The last change didn't finish">
          {s.lastResult}
        </Notice>
      )}
      {!working && outdated.length > 0 && (
        <Notice tone="warn" title={`${outdated.length === 1 ? "A component needs" : `${outdated.length} components need`} attention`}>
          <span className="block mb-2">
            {outdated.map((c) => c.name).join(", ")} {outdated.length === 1 ? "is" : "are"} stopped, missing or running older settings.
          </span>
          <Button
            size="sm"
            busy={repairing}
            onClick={async () => {
              setRepairing(true);
              try {
                await api.post(`${base}/repair`);
                onChange();
              } catch (e) {
                toast((e as Error).message, "error");
              } finally {
                setRepairing(false);
              }
            }}
          >
            Repair
          </Button>
        </Notice>
      )}

      <div className="grid gap-5 lg:grid-cols-[minmax(0,1fr)_minmax(0,1fr)]">
        <GrafanaPanel base={base} view={view} />
        <section className="min-w-0">
          <h2 className="font-display text-[1.05rem] mb-3">Components</h2>
          <Table head={["Component", "State", ""]}>
            {view.components
              .filter((c) => c.enabled || c.state !== "absent")
              .map((c) => (
                <tr key={c.key}>
                  <td>
                    <div className="font-medium">{c.name}</div>
                    <div className="text-[12.5px] text-slate">{c.description}</div>
                  </td>
                  <td>
                    <span className="inline-flex flex-wrap items-center gap-2">
                      {c.state === "missing" ? <Badge tone="danger">Missing</Badge> : <StateBadge state={c.state} />}
                      {!c.enabled && <Badge>Turned off</Badge>}
                      {c.outdated && c.state !== "missing" && <Badge tone="warn">Older settings</Badge>}
                    </span>
                  </td>
                  <td className="text-right">
                    {c.containerId && (
                      <Link href={`/containers/view/?id=${c.containerId}`} className="text-[13px] text-slate hover:text-ink underline-offset-2 hover:underline whitespace-nowrap">
                        Container
                      </Link>
                    )}
                  </td>
                </tr>
              ))}
          </Table>
        </section>
      </div>

      {s.otlpEnabled && <Telemetry base={base} view={view} />}

      <SettingsPanel base={base} view={view} onChange={onChange} disabled={working} />

      <Panel title="Remove">
        <p className="text-[14px] text-slate mb-4">
          Stops and removes the dm-obs-* containers, Grafana&apos;s ingress route and the stack&apos;s networks. You choose whether the collected data is kept.
        </p>
        <RemoveButton base={base} onRemoved={onChange} disabled={working} confirm={confirm} toast={toast} />
      </Panel>
    </div>
  );
}

function GrafanaPanel({ base, view }: { base: string; view: StackView }) {
  const s = view.stack;
  const toast = useToast();
  const [password, setPassword] = useState<string | null>(null);
  const sso = s.access === "PUBLIC" && !!s.signInUrl && !!s.ingressRouteId;
  const running = view.components.find((c) => c.key === "grafana")?.state === "running";
  const cert = view.certificate;
  const rows: [string, ReactNode][] = [
    ["Address", <Copyable key="u" text={view.grafanaUrl} />],
    ["Sign in", sso ? "With your Docker Manager account (administrators)" : "With Grafana's administrator password"],
  ];
  if (cert) rows.push(["HTTPS", <Https key="h" cert={cert} />]);
  rows.push([
    "Grafana administrator",
    <span key="a" className="inline-flex flex-wrap items-center gap-2">
      <span className="font-mono text-[13px]">{s.grafanaAdminUser}</span>
      {password ? (
        <Copyable text={password} />
      ) : (
        <Button
          size="sm"
          tone="quiet"
          onClick={async () => {
            try {
              setPassword((await api.get<{ password: string }>(`${base}/password`)).password);
            } catch (e) {
              toast((e as Error).message, "error");
            }
          }}
        >
          Show password
        </Button>
      )}
    </span>,
  ]);

  return (
    <Panel
      title="Grafana"
      action={
        running &&
        (sso ? (
          <a href={`/observability/grafana/?host=${enc(s.dockerHostId)}`} target="_blank" rel="noopener" className="inline-flex items-center gap-1.5 h-8 px-3 rounded-full bg-accent text-accent-ink text-[13px] font-medium">
            Open Grafana <ExternalLink size={14} aria-hidden />
          </a>
        ) : (
          <a href={view.grafanaUrl} target="_blank" rel="noopener noreferrer" className="inline-flex items-center gap-1.5 h-8 px-3 rounded-full bg-accent text-accent-ink text-[13px] font-medium">
            Open Grafana <ExternalLink size={14} aria-hidden />
          </a>
        ))
      }
    >
      <KeyValues rows={rows} />
      <p className="text-[13px] text-slate mt-4">
        {s.access === "LOCAL"
          ? "Grafana listens on 127.0.0.1, so open it on this server or through an SSH tunnel (ssh -L " + s.localPort + ":localhost:" + s.localPort + " this-server)."
          : "The administrator password is for when Docker Manager isn't reachable: open /login?local on Grafana's address."}
      </p>
      <p className="text-[13px] text-slate mt-2">Dashboards are in the Docker Manager folder: Containers, Host and Container logs. Explore works with every data source.</p>
    </Panel>
  );
}

function Https({ cert }: { cert: NonNullable<StackView["certificate"]> }) {
  if (cert.status === "ACTIVE" || cert.status === "RENEWAL_PENDING") return <Badge tone="ok">Certificate active</Badge>;
  if (cert.status === "PENDING")
    return (
      <span className="text-[13px]">
        <Badge tone="warn">Waiting for the certificate</Badge>
        {cert.message ? (
          <span className="block text-danger mt-1 break-words">Let&apos;s Encrypt said: {cert.message}</span>
        ) : (
          <span className="block text-slate mt-1">Grafana works over plain HTTP until Let&apos;s Encrypt has issued it.</span>
        )}
      </span>
    );
  return (
    <span className="text-[13px]">
      <Badge tone="danger">{cert.status === "MISSING" ? "Route missing" : cert.status === "EXPIRED" ? "Certificate expired" : "Certificate failed"}</Badge>
      {cert.message && <span className="block text-danger mt-1 break-words">{cert.message}</span>}
      <span className="block text-slate mt-1">Check that the hostname&apos;s DNS points at this server and ports 80 and 443 are open, then retry from Ingress.</span>
    </span>
  );
}

function Telemetry({ base, view }: { base: string; view: StackView }) {
  const { hostId } = useHost();
  const toast = useToast();
  const { data, error, refresh } = usePoll<{ id: string; name: string }[]>(`${base}/members`, 10000);
  const { data: containers } = usePoll<Container[]>(hostId ? `/api/docker/hosts/${hostId}/containers?all=true` : null);
  const [pick, setPick] = useState("");
  const [selected, setSelected] = useState<string[]>([]);
  const memberIds = useMemo(() => new Set((data ?? []).map((m) => m.id)), [data]);
  const candidates = (containers ?? [])
    .map((c) => ({ id: c.Id, name: c.Names?.[0]?.replace(/^\//, "") ?? c.Id }))
    .filter((c) => !memberIds.has(c.id) && !c.name.startsWith("dm-obs-"))
    .map((c) => c.name)
    .sort();
  const alloyRunning = view.components.find((c) => c.key === "alloy")?.state === "running";
  const snippet = `services:
  app:
    environment:
      OTEL_EXPORTER_OTLP_ENDPOINT: http://${ALLOY}:4318
      OTEL_SERVICE_NAME: my-app
    networks: [default, dm-obs-otlp]

networks:
  dm-obs-otlp:
    external: true`;

  return (
    <div className="grid gap-5 lg:grid-cols-[minmax(0,1fr)_minmax(0,1fr)]">
      <Panel title="Apps sending OpenTelemetry">
        <KeyValues
          rows={[
            ["OTLP over HTTP", <Copyable key="h" text={`http://${ALLOY}:4318`} />],
            ["OTLP over gRPC", <Copyable key="g" text={`${ALLOY}:4317`} />],
            [
              "Goes to",
              [view.stack.tempoEnabled && "traces to Tempo", view.stack.lokiEnabled && "logs to Loki", `metrics to ${view.stack.mimirEnabled ? "Mimir" : "Prometheus"}`].filter(Boolean).join(", "),
            ],
          ]}
        />
        <p className="text-[13px] text-slate mt-4 mb-4">
          Containers connected here reach Grafana Alloy on the dm-obs-otlp network, and nothing else of the stack.
          {!alloyRunning && " Grafana Alloy isn't running right now."}
        </p>
        {error && <Notice tone="error">{error}</Notice>}
        {data && data.length === 0 && <p className="text-[14px] text-slate">No containers connected yet.</p>}
        {data && data.length > 0 && (
          <ul className="divide-y divide-line mb-4">
            {data.map((m) => (
              <li key={m.id} className="flex items-center gap-3 py-2">
                <Checkbox label={`Select ${m.name}`} checked={selected.includes(m.id)} onChange={(v) => setSelected((s) => (v ? [...s, m.id] : s.filter((x) => x !== m.id)))} />
                <Link className="flex-1 truncate hover:underline underline-offset-2" href={`/containers/view/?id=${m.id}`}>
                  {m.name}
                </Link>
              </li>
            ))}
          </ul>
        )}
        {selected.length > 0 && (
          <Button
            tone="quiet"
            size="sm"
            className="mb-4"
            onClick={async () => {
              for (const id of selected) {
                try {
                  await api.del(`${base}/members/${enc(id)}`);
                } catch (e) {
                  toast((e as Error).message, "error");
                }
              }
              setSelected([]);
              refresh();
            }}
          >
            Disconnect selected
          </Button>
        )}
        <div className="flex flex-wrap items-end gap-2 pt-4 border-t border-line">
          <Field label="Connect a container">
            <Select value={pick} onChange={setPick} className="w-64 max-w-full">
              <option value="">Choose</option>
              {candidates.map((n) => (
                <option key={n} value={n}>
                  {n}
                </option>
              ))}
            </Select>
          </Field>
          <Button
            disabled={!pick}
            onClick={async () => {
              try {
                await api.post(`${base}/members`, { container: pick });
                toast(`${pick} is connected`);
                setPick("");
                refresh();
              } catch (e) {
                toast((e as Error).message, "error");
              }
            }}
          >
            Connect
          </Button>
        </div>
      </Panel>
      <Panel title="In a compose file">
        <p className="text-[13px] text-slate mb-3">
          Containers recreated by docker compose lose networks added by hand. Declare the network in the stack instead, and point the OpenTelemetry SDK at Alloy:
        </p>
        <pre className="code rounded-xl bg-well border border-line p-4 overflow-x-auto text-[12.5px]">{snippet}</pre>
        <Button tone="quiet" size="sm" className="mt-3" onClick={async () => toast((await copyText(snippet)) ? "Copied" : "Couldn't copy", "ok")}>
          Copy
        </Button>
      </Panel>
    </div>
  );
}

function SettingsPanel({ base, view, onChange, disabled }: { base: string; view: StackView; onChange: () => void; disabled: boolean }) {
  const { hostId } = useHost();
  const ingressOn = useIngressOn(hostId);
  const toast = useToast();
  const confirm = useConfirm();
  const [settings, setSettings] = useState<Settings>(() => fromStack(view));
  const [busy, setBusy] = useState(false);
  const original = fromStack(view);
  const changed = JSON.stringify({ ...settings, dockerManagerUrl: "" }) !== JSON.stringify(original);
  const problem = problems(settings);
  const s = view.stack;
  const dropping = [
    s.mimirEnabled && !settings.mimir && "Mimir",
    s.lokiEnabled && !settings.loki && "Loki",
    s.tempoEnabled && !settings.tempo && "Tempo",
  ].filter(Boolean) as string[];

  return (
    <Panel title="Settings">
      <SettingsForm value={settings} onChange={setSettings} ingressOn={ingressOn} />
      <div className="mt-6 pt-5 border-t border-line flex flex-wrap items-center gap-3">
        <Button
          busy={busy}
          disabled={disabled || !changed || !!problem}
          onClick={async () => {
            if (dropping.length > 0) {
              const ok = await confirm({
                title: `Turn off ${dropping.join(" and ")}?`,
                body: "The containers are removed. Their data stays in the dm-obs-* volumes until you remove the stack, so turning them back on picks up where they left off.",
                confirm: "Turn off",
              });
              if (!ok) return;
            }
            setBusy(true);
            try {
              await api.put(base, body(settings));
              toast("Applying the changes");
              onChange();
            } catch (e) {
              toast((e as Error).message, "error");
            } finally {
              setBusy(false);
            }
          }}
        >
          Apply changes
        </Button>
        {changed && (
          <Button tone="ghost" onClick={() => setSettings(original)}>
            Undo
          </Button>
        )}
        {changed && problem && <span className="text-[13px] text-slate">{problem}</span>}
        {!changed && <span className="text-[13px] text-slate">Only the containers whose settings change are recreated. Data is kept.</span>}
      </div>
    </Panel>
  );
}

function RemoveButton({
  base,
  onRemoved,
  disabled,
  confirm,
  toast,
}: {
  base: string;
  onRemoved: () => void;
  disabled: boolean;
  confirm: ReturnType<typeof useConfirm>;
  toast: ReturnType<typeof useToast>;
}) {
  const [deleteData, setDeleteData] = useState(false);
  const [busy, setBusy] = useState(false);
  return (
    <div className="flex flex-wrap items-center gap-4">
      <span className="inline-flex items-center gap-2.5">
        <Checkbox label="Also delete the collected data" checked={deleteData} onChange={setDeleteData} />
        <span className="text-[14px] cursor-pointer" onClick={() => setDeleteData(!deleteData)}>
          Also delete the collected data and the dashboards you made in Grafana
        </span>
      </span>
      <Button
        tone="danger"
        busy={busy}
        disabled={disabled}
        onClick={async () => {
          const ok = await confirm({
            title: "Remove the observability stack?",
            body: deleteData
              ? "The containers, networks, Grafana's route and every dm-obs-* volume are deleted. This can't be undone."
              : "The containers, networks and Grafana's route are removed. The dm-obs-* volumes stay, and a new stack picks them up again.",
            confirm: "Remove",
            danger: true,
          });
          if (!ok) return;
          setBusy(true);
          try {
            await api.del(`${base}?deleteData=${deleteData}`);
            toast("The stack was removed");
            onRemoved();
          } catch (e) {
            toast((e as Error).message, "error");
            setBusy(false);
          }
        }}
      >
        Remove stack
      </Button>
    </div>
  );
}
