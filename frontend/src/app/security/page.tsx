"use client";

import Link from "next/link";
import { useMemo, useState } from "react";
import { ExternalLink } from "lucide-react";
import { useHost, useSession } from "@/components/app";
import {
  Badge,
  Button,
  Empty,
  Field,
  Modal,
  Notice,
  PageHeader,
  Panel,
  Segmented,
  Spinner,
  Stat,
  StateBadge,
  Table,
  Tabs,
  Toggle,
  copyText,
  inputClass,
  useToast,
} from "@/components/ui";
import { api } from "@/lib/api";
import { ago, dateTime, duration, plural } from "@/lib/format";
import { usePoll } from "@/lib/hooks";
import type { Container } from "@/lib/types";

type Kind = "TRIVY" | "SCOUT" | "BENCH";

interface Scan {
  id: string;
  dockerHostId: string;
  kind: Kind;
  target: string | null;
  status: "RUNNING" | "DONE" | "FAILED";
  startedAt: number;
  finishedAt: number | null;
  startedBy: string | null;
  summaryJson: string | null;
  findingsJson: string | null;
  error: string | null;
}

type Severity = "CRITICAL" | "HIGH" | "MEDIUM" | "LOW" | "UNKNOWN";
const SEVERITIES: Severity[] = ["CRITICAL", "HIGH", "MEDIUM", "LOW", "UNKNOWN"];

interface VulnSummary {
  CRITICAL: number;
  HIGH: number;
  MEDIUM: number;
  LOW: number;
  UNKNOWN: number;
  total: number;
  fixable: number;
  os?: string;
  osEol?: boolean;
}

interface Vuln {
  id: string;
  package?: string;
  installed?: string;
  fixed: string;
  severity: Severity;
  title: string;
  url: string;
  message?: string;
  target?: string;
}

interface BenchSummary {
  PASS?: number;
  WARN?: number;
  INFO?: number;
  NOTE?: number;
  score: number;
  checks: number;
  daemonJson: string | null;
}

interface BenchFinding {
  section: string;
  id: string;
  title: string;
  result: "PASS" | "WARN" | "INFO" | "NOTE";
  details: string;
  items: string[];
  remediation: string;
  impact: string;
  fix?: "MEMORY_LIMIT" | "NO_NEW_PRIVILEGES" | "PIDS_LIMIT";
  daemonSetting?: string;
}

const parse = <T,>(s: string | null | undefined): T | null => {
  if (!s) return null;
  try {
    return JSON.parse(s) as T;
  } catch {
    return null;
  }
};

const SCANNERS: Record<Kind, string> = { TRIVY: "Trivy", SCOUT: "Docker Scout", BENCH: "Docker Bench" };

export default function SecurityPage() {
  const { hostId } = useHost();
  const { session } = useSession();
  const [tab, setTab] = useState<"images" | "host">("images");
  const { data: scans, error, refresh } = usePoll<Scan[]>(hostId && session?.admin ? `/api/security/hosts/${hostId}/scans` : null, 4000);

  if (!session?.admin) {
    return (
      <>
        <PageHeader title="Security" />
        <Notice>Security scans are for administrators.</Notice>
      </>
    );
  }
  return (
    <>
      <PageHeader
        title="Security"
        intro="Find known vulnerabilities in your images with Trivy or Docker Scout, and check this host against the CIS Docker Benchmark with Docker Bench for Security. Fixes change nothing until you confirm them, and each one can be undone from History."
      />
      <Tabs
        value={tab}
        onChange={setTab}
        tabs={[
          { value: "images", label: "Images" },
          { value: "host", label: "Host check" },
        ]}
      />
      {error && <Notice tone="error">{error}</Notice>}
      {!scans && !error && <Spinner />}
      {scans && hostId && tab === "images" && <Images hostId={hostId} scans={scans.filter((s) => s.kind !== "BENCH")} onStarted={refresh} />}
      {scans && hostId && tab === "host" && <HostCheck hostId={hostId} scans={scans.filter((s) => s.kind === "BENCH")} onStarted={refresh} />}
    </>
  );
}

// --- images --------------------------------------------------------------------------------------

function SeverityBadges({ s }: { s: VulnSummary }) {
  if (!s.total) return <Badge tone="ok">No known vulnerabilities</Badge>;
  return (
    <span className="inline-flex flex-wrap gap-1">
      {s.CRITICAL > 0 && <Badge tone="danger">{s.CRITICAL} critical</Badge>}
      {s.HIGH > 0 && <Badge tone="danger">{s.HIGH} high</Badge>}
      {s.MEDIUM > 0 && <Badge tone="warn">{s.MEDIUM} medium</Badge>}
      {s.LOW + s.UNKNOWN > 0 && <Badge>{s.LOW + s.UNKNOWN} low</Badge>}
    </span>
  );
}

function Images({ hostId, scans, onStarted }: { hostId: string; scans: Scan[]; onStarted: () => void }) {
  const toast = useToast();
  const { data: status } = usePoll<{ scout: boolean }>("/api/security/status");
  const { data: containers } = usePoll<Container[]>(`/api/docker/hosts/${hostId}/containers?all=true`);
  const { data: images } = usePoll<{ RepoTags?: string[] | null }[]>(`/api/docker/hosts/${hostId}/images`);
  const [image, setImage] = useState("");
  const [kind, setKind] = useState<"TRIVY" | "SCOUT">("TRIVY");
  const [busy, setBusy] = useState<string | null>(null);
  const [open, setOpen] = useState<string | null>(null);

  const tags = useMemo(
    () => Array.from(new Set((images ?? []).flatMap((i) => i.RepoTags ?? []).filter((t) => t && !t.startsWith("<none>")))).sort(),
    [images],
  );
  const running = useMemo(
    () => Array.from(new Set((containers ?? []).filter((c) => c.State === "running" && !c.Labels?.["com.wannaverse.helper"]).map((c) => c.Image))).sort(),
    [containers],
  );
  // The newest scan of each image and scanner.
  const latest = useMemo(() => {
    const seen = new Set<string>();
    return scans.filter((s) => {
      const k = `${s.kind}:${s.target}`;
      if (seen.has(k)) return false;
      seen.add(k);
      return true;
    });
  }, [scans]);

  const start = async (target: string) => {
    await api.post(`/api/security/hosts/${hostId}/scans`, { kind, image: target });
  };

  return (
    <div className="flex flex-col gap-6">
      <Panel title="Scan an image">
        <div className="flex flex-wrap items-end gap-3">
          <div className="flex-1 min-w-[240px]">
            <Field label="Image">
              <input className={`${inputClass} font-mono text-[13px]`} list="scan-images" value={image} onChange={(e) => setImage(e.target.value)} placeholder="nginx:1.27" />
              <datalist id="scan-images">
                {tags.map((t) => (
                  <option key={t} value={t} />
                ))}
              </datalist>
            </Field>
          </div>
          <div>
            <div className="text-[13px] font-medium mb-1.5">Scanner</div>
            <Segmented
              label="Scanner"
              value={kind}
              onChange={setKind}
              options={[
                { value: "TRIVY", label: "Trivy" },
                { value: "SCOUT", label: "Docker Scout" },
              ]}
            />
          </div>
          <Button
            busy={busy === "one"}
            disabled={!image.trim() || (kind === "SCOUT" && !status?.scout) || !!busy}
            onClick={async () => {
              setBusy("one");
              try {
                await start(image.trim());
                toast(`Scanning ${image.trim()}`);
                onStarted();
              } catch (e) {
                toast((e as Error).message, "error");
              } finally {
                setBusy(null);
              }
            }}
          >
            Scan
          </Button>
          <Button
            tone="quiet"
            busy={busy === "all"}
            disabled={!running.length || (kind === "SCOUT" && !status?.scout) || !!busy}
            onClick={async () => {
              setBusy("all");
              let started = 0;
              for (const r of running) {
                try {
                  await start(r);
                  started++;
                } catch (e) {
                  toast(`${r}: ${(e as Error).message}`, "error");
                }
              }
              setBusy(null);
              toast(`Scanning ${plural(started, "image")} (two at a time)`);
              onStarted();
            }}
          >
            Scan all running ({running.length})
          </Button>
        </div>
        <p className="text-[13px] text-slate mt-3">
          {kind === "TRIVY"
            ? "Trivy runs in a short-lived container. Its vulnerability database (about 80 MB) is downloaded on the first scan and kept in the dm-trivy-cache volume."
            : status?.scout
              ? "Docker Scout signs in with the Docker Hub login saved under Registries."
              : "Docker Scout needs a Docker Hub sign-in. Add Docker Hub under Registries (a personal access token works) to use it."}
        </p>
      </Panel>

      {latest.length === 0 && <Empty title="No scans yet">Scan an image above, or scan every image your running containers use.</Empty>}
      {latest.length > 0 && (
        <Table head={["Image", "Scanner", "Result", "Fixable", "Scanned", ""]}>
          {latest.map((s) => {
            const sum = parse<VulnSummary>(s.summaryJson);
            const users = (containers ?? []).filter((c) => c.Image === s.target);
            return (
              <tr key={s.id}>
                <td className="max-w-[320px]">
                  <div className="font-mono text-[13px] truncate">{s.target}</div>
                  {users.length > 0 && <div className="text-[12.5px] text-slate truncate">Used by {users.map((c) => c.Names[0]?.replace(/^\//, "")).join(", ")}</div>}
                </td>
                <td className="whitespace-nowrap">{SCANNERS[s.kind]}</td>
                <td>
                  {s.status === "RUNNING" && <StateBadge state="running" pending="Scanning" />}
                  {s.status === "FAILED" && <span className="text-danger text-[13px] break-words">{s.error ?? "Failed"}</span>}
                  {s.status === "DONE" && sum && <SeverityBadges s={sum} />}
                </td>
                <td className="tabular-nums">{s.status === "DONE" && sum ? `${sum.fixable} of ${sum.total}` : ""}</td>
                <td className="whitespace-nowrap text-[13px] text-slate">{ago(s.startedAt)}</td>
                <td className="text-right">
                  {s.status === "DONE" && (
                    <Button tone="quiet" size="sm" onClick={() => setOpen(s.id)}>
                      Report
                    </Button>
                  )}
                </td>
              </tr>
            );
          })}
        </Table>
      )}
      {open && <VulnReport id={open} containers={containers ?? []} onClose={() => setOpen(null)} />}
    </div>
  );
}

function VulnReport({ id, containers, onClose }: { id: string; containers: Container[]; onClose: () => void }) {
  const { data: scan, error } = usePoll<Scan>(`/api/security/scans/${id}`);
  const [severity, setSeverity] = useState<"ALL" | Severity>("ALL");
  const [fixableOnly, setFixableOnly] = useState(false);
  const findings = useMemo(() => parse<Vuln[]>(scan?.findingsJson) ?? [], [scan]);
  const sum = parse<VulnSummary>(scan?.summaryJson);
  const shown = findings.filter((f) => (severity === "ALL" || f.severity === severity) && (!fixableOnly || f.fixed));
  const users = containers.filter((c) => c.Image === scan?.target);

  return (
    <Modal open wide onClose={onClose} title={scan ? `${scan.target}` : "Report"}>
      {error && <Notice tone="error">{error}</Notice>}
      {!scan && !error && <Spinner />}
      {scan && sum && (
        <div className="flex flex-col gap-4 text-[14px]">
          <div className="text-[13px] text-slate">
            {SCANNERS[scan.kind]}, {dateTime(scan.startedAt)}
            {scan.finishedAt ? ` (took ${duration(scan.finishedAt - scan.startedAt)})` : ""}
            {sum.os ? `. Base: ${sum.os}` : ""}
          </div>
          {sum.osEol && (
            <Notice tone="warn" title="The base system is no longer supported">
              It gets no more security updates. Rebuild the image on a supported base image.
            </Notice>
          )}
          {sum.total > 0 && (
            <Notice title="How to fix">
              {sum.fixable > 0 ? (
                <>
                  {plural(sum.fixable, "vulnerability", "vulnerabilities")} of {sum.total} have a fixed version. Pull a newer tag of the image (or rebuild yours on an updated base) and redeploy
                  {users.length > 0 && (
                    <>
                      {" "}
                      {users.map((c, i) => (
                        <span key={c.Id}>
                          {i > 0 && ", "}
                          <Link className="underline underline-offset-2" href={`/containers/view/?id=${c.Id}`}>
                            {c.Names[0]?.replace(/^\//, "")}
                          </Link>
                        </span>
                      ))}
                    </>
                  )}
                  , then scan again.
                </>
              ) : (
                "None of these have a fixed version yet. Check whether the image has a newer release, or a slimmer variant (alpine, distroless) with fewer packages."
              )}
            </Notice>
          )}
          <div className="flex flex-wrap items-center justify-between gap-3">
            <Segmented
              label="Severity"
              value={severity}
              onChange={setSeverity}
              options={[{ value: "ALL" as const, label: `All ${sum.total}` }, ...SEVERITIES.filter((s) => sum[s] > 0).map((s) => ({ value: s, label: `${s[0]}${s.slice(1).toLowerCase()} ${sum[s]}` }))]}
            />
            <Toggle checked={fixableOnly} onChange={setFixableOnly} label="Only fixable" />
          </div>
          {shown.length === 0 && <p className="text-slate">Nothing to show.</p>}
          {shown.length > 0 && (
            <div className="table-scroll rounded-2xl border border-line max-h-[50dvh] overflow-auto">
              <table className="w-full text-[13px] border-collapse">
                <thead className="sticky top-0 bg-paper">
                  <tr className="text-left text-slate border-b border-line">
                    <th className="font-medium px-3 py-2">Vulnerability</th>
                    <th className="font-medium px-3 py-2">Package</th>
                    <th className="font-medium px-3 py-2">Fixed in</th>
                  </tr>
                </thead>
                <tbody>
                  {shown.slice(0, 500).map((f, i) => (
                    <tr key={`${f.id}-${f.package}-${i}`} className="border-b border-line last:border-0 align-top">
                      <td className="px-3 py-2 max-w-[360px]">
                        <div className="flex flex-wrap items-center gap-2">
                          <Badge tone={f.severity === "CRITICAL" || f.severity === "HIGH" ? "danger" : f.severity === "MEDIUM" ? "warn" : "plain"}>{f.severity.toLowerCase()}</Badge>
                          {f.url ? (
                            <a className="font-mono underline underline-offset-2 inline-flex items-center gap-1" href={f.url} target="_blank" rel="noreferrer noopener">
                              {f.id} <ExternalLink size={12} aria-hidden />
                            </a>
                          ) : (
                            <span className="font-mono">{f.id}</span>
                          )}
                        </div>
                        {(f.title || f.message) && <div className="text-slate mt-1 break-words">{f.title || f.message}</div>}
                      </td>
                      <td className="px-3 py-2 font-mono break-all">
                        {f.package}
                        {f.installed && <div className="text-slate">{f.installed}</div>}
                      </td>
                      <td className="px-3 py-2 font-mono break-all">{f.fixed || <span className="text-slate font-sans">Not yet</span>}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
              {shown.length > 500 && <p className="px-3 py-2 text-[13px] text-slate">Showing the first 500.</p>}
            </div>
          )}
        </div>
      )}
    </Modal>
  );
}

// --- host check ----------------------------------------------------------------------------------

function HostCheck({ hostId, scans, onStarted }: { hostId: string; scans: Scan[]; onStarted: () => void }) {
  const toast = useToast();
  const last = scans.find((s) => s.status !== "RUNNING") ?? null;
  const runningScan = scans.find((s) => s.status === "RUNNING");
  const { data: scan } = usePoll<Scan>(last ? `/api/security/scans/${last.id}` : null);
  const [warningsOnly, setWarningsOnly] = useState(true);
  const [fixing, setFixing] = useState<{ finding: BenchFinding; container: string } | null>(null);
  const [busy, setBusy] = useState(false);
  const sum = parse<BenchSummary>(scan?.summaryJson);
  const findings = useMemo(() => parse<BenchFinding[]>(scan?.findingsJson) ?? [], [scan]);
  const sections = useMemo(() => {
    const map = new Map<string, BenchFinding[]>();
    for (const f of findings) {
      if (warningsOnly && f.result !== "WARN") continue;
      map.set(f.section, [...(map.get(f.section) ?? []), f]);
    }
    return Array.from(map.entries());
  }, [findings, warningsOnly]);

  const run = async () => {
    setBusy(true);
    try {
      await api.post(`/api/security/hosts/${hostId}/scans`, { kind: "BENCH" });
      toast("Docker Bench is running. It takes a minute or two.");
      onStarted();
    } catch (e) {
      toast((e as Error).message, "error");
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="flex flex-col gap-6">
      <div className="flex flex-wrap items-center gap-3">
        <Button busy={busy || !!runningScan} onClick={run}>
          {runningScan ? "Checking the host" : last ? "Check again" : "Check this host"}
        </Button>
        <span className="text-[13px] text-slate">
          Runs Docker Bench for Security (the CIS Docker Benchmark) in a temporary container with read-only access to the host. Nothing is changed.
        </span>
      </div>
      {last?.status === "FAILED" && <Notice tone="error" title="The last check failed">{last.error}</Notice>}
      {!last && !runningScan && <Empty title="Not checked yet">The check looks at the host, the Docker daemon&apos;s settings and every running container.</Empty>}
      {sum && scan?.status === "DONE" && (
        <>
          <div className="grid gap-3 grid-cols-2 sm:grid-cols-4">
            <Stat label="Warnings" value={sum.WARN ?? 0} />
            <Stat label="Passed" value={sum.PASS ?? 0} />
            <Stat label="Notes" value={(sum.INFO ?? 0) + (sum.NOTE ?? 0)} />
            <Stat label="Checked" value={ago(scan.startedAt)} sub={dateTime(scan.startedAt)} />
          </div>
          {sum.daemonJson && <DaemonJson json={sum.daemonJson} />}
          <div className="flex flex-wrap items-center justify-between gap-3">
            <h2 className="font-display text-[1.15rem]">Findings</h2>
            <Toggle checked={warningsOnly} onChange={setWarningsOnly} label="Warnings only" />
          </div>
          {sections.length === 0 && <p className="text-slate text-[14px]">No warnings. Nicely done.</p>}
          {sections.map(([section, list]) => (
            <Panel key={section} title={section}>
              <ul className="divide-y divide-line -my-2">
                {list.map((f) => (
                  <BenchRow key={f.id} f={f} onFix={(container) => setFixing({ finding: f, container })} />
                ))}
              </ul>
            </Panel>
          ))}
        </>
      )}
      {fixing && <FixDialog hostId={hostId} finding={fixing.finding} container={fixing.container} onClose={() => setFixing(null)} />}
    </div>
  );
}

function BenchRow({ f, onFix }: { f: BenchFinding; onFix: (container: string) => void }) {
  const [open, setOpen] = useState(f.result === "WARN" && !!f.fix);
  const items = f.items.filter((i) => i && !i.startsWith("("));
  const tone = f.result === "WARN" ? "warn" : f.result === "PASS" ? "ok" : "plain";
  return (
    <li className="py-3">
      <button className="w-full text-left flex items-start gap-3" onClick={() => setOpen(!open)} aria-expanded={open}>
        <span className="shrink-0 font-mono text-[12.5px] text-slate w-12 pt-0.5">{f.id}</span>
        <span className="flex-1 min-w-0 text-[14px]">{f.title.replace(/ \((Automated|Manual)\)$/, "")}</span>
        <Badge tone={tone}>{f.result.toLowerCase()}</Badge>
      </button>
      {open && (
        <div className="mt-2 ml-15 pl-0 sm:pl-[60px] text-[13.5px] flex flex-col gap-2">
          {f.remediation && <p className="text-slate">{f.remediation}</p>}
          {f.impact && f.impact !== "None." && <p className="text-slate">Impact: {f.impact}</p>}
          {f.daemonSetting && (
            <p>
              Daemon setting: <span className="font-mono">{f.daemonSetting}</span> (see the suggested daemon.json above)
            </p>
          )}
          {items.length > 0 && (
            <ul className="flex flex-col gap-1.5">
              {items.map((c) => (
                <li key={c} className="flex flex-wrap items-center justify-between gap-2 rounded-lg bg-well px-3 py-1.5">
                  <span className="font-mono text-[12.5px] break-all">{c}</span>
                  {f.fix && (
                    <Button size="sm" tone="quiet" onClick={() => onFix(c)}>
                      Fix
                    </Button>
                  )}
                </li>
              ))}
            </ul>
          )}
          {!items.length && f.details && <p className="text-slate break-words">{f.details}</p>}
        </div>
      )}
    </li>
  );
}

function DaemonJson({ json }: { json: string }) {
  const toast = useToast();
  return (
    <Panel title="Suggested daemon settings">
      <p className="text-[13px] text-slate mb-3">
        These Docker daemon settings address host-level warnings. Merge them into <span className="font-mono">/etc/docker/daemon.json</span> on the server and restart Docker. They can&apos;t be
        changed from here because a mistake there stops Docker (and Docker Manager) from starting. Test with <span className="font-mono">dockerd --validate</span> first. Note that{" "}
        <span className="font-mono">&quot;icc&quot;: false</span> blocks traffic between containers on the default bridge network; containers on their own networks (stacks, databases, ingress) are not affected.
      </p>
      <pre className="code rounded-xl bg-well border border-line p-4 overflow-x-auto text-[12.5px]">{json}</pre>
      <Button tone="quiet" size="sm" className="mt-3" onClick={async () => toast((await copyText(json)) ? "Copied" : "Couldn't copy")}>
        Copy
      </Button>
    </Panel>
  );
}

const FIXES: Record<NonNullable<BenchFinding["fix"]>, { title: string; change: (v: string) => string; why: string; input?: { label: string; hint: string; initial: string } }> = {
  NO_NEW_PRIVILEGES: {
    title: "Block privilege escalation",
    change: () => "Adds the security option no-new-privileges:true.",
    why: "Processes in the container can no longer gain extra privileges, for example through setuid programs.",
  },
  PIDS_LIMIT: {
    title: "Limit the number of processes",
    change: (v) => `Sets a process limit of ${v || "1024"}.`,
    why: "A fork bomb or runaway process in the container can't exhaust the host's process table.",
    input: { label: "Maximum processes", hint: "1024 is plenty for most apps. Databases with many connections may need more.", initial: "1024" },
  },
  MEMORY_LIMIT: {
    title: "Limit memory",
    change: (v) => `Sets a memory limit of ${v || "?"} MB.`,
    why: "The container can't use up all of the host's memory. If it goes over the limit, it is restarted instead of the host running out.",
    input: { label: "Memory limit (MB)", hint: "Look at the container's current usage first and leave headroom.", initial: "512" },
  },
};

function FixDialog({ hostId, finding, container, onClose }: { hostId: string; finding: BenchFinding; container: string; onClose: () => void }) {
  const toast = useToast();
  const fix = FIXES[finding.fix!];
  const [typedValue, setValue] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const { data: stats } = usePoll<{ memoryUsage?: number }>(finding.fix === "MEMORY_LIMIT" ? `/api/docker/hosts/${hostId}/containers/${encodeURIComponent(container)}/stats` : null);
  // Suggest twice the current memory use, rounded up to 64 MB, when it is known.
  const used = stats?.memoryUsage;
  const suggested = finding.fix === "MEMORY_LIMIT" && used && used > 0 ? String(Math.max(128, Math.ceil((used * 2) / (64 * 1024 * 1024)) * 64)) : null;
  const value = typedValue ?? suggested ?? fix.input?.initial ?? "";
  return (
    <Modal
      open
      onClose={onClose}
      title={`${fix.title}: ${container}`}
      footer={
        <>
          <Button tone="quiet" onClick={onClose}>
            Cancel
          </Button>
          <Button
            busy={busy}
            disabled={!!fix.input && !/^\d+$/.test(value)}
            onClick={async () => {
              setBusy(true);
              setError(null);
              try {
                await api.post(`/api/security/hosts/${hostId}/fix`, { container, fix: finding.fix, value: fix.input ? Number(value) : null });
                toast(`${container} was recreated with the change. Run the check again to confirm.`);
                onClose();
              } catch (e) {
                setError((e as Error).message);
                setBusy(false);
              }
            }}
          >
            Apply
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-4 text-[14px]">
        <p>{fix.why}</p>
        {fix.input && (
          <Field label={fix.input.label} hint={fix.input.hint}>
            <input className={inputClass} inputMode="numeric" value={value} onChange={(e) => setValue(e.target.value.replace(/\D/g, ""))} />
          </Field>
        )}
        <div className="rounded-xl bg-well px-4 py-3">
          <div className="font-medium mb-1">What changes</div>
          <p className="text-slate">
            {fix.change(value)} Docker can&apos;t change this on a running container, so {container} is recreated with the same image, volumes, networks and settings. It is down for a few seconds;
            if the new container doesn&apos;t start, the original is put back.
          </p>
        </div>
        <p className="text-[13px] text-slate">
          Recorded in{" "}
          <Link href="/history/" className="underline underline-offset-2">
            History
          </Link>
          , where you can undo it. Containers from a stack are changed in their compose file instead (you&apos;ll get the line to add).
        </p>
        {error && <Notice tone="error">{error}</Notice>}
      </div>
    </Modal>
  );
}
