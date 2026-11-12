"use client";

import { useState } from "react";
import { ArrowDown, ArrowUp, X } from "lucide-react";
import { useHost } from "./app";
import { Button, Combobox, Field, IconButton, ListEditor, Notice, Panel, Segmented, Select, Toggle, inputClass } from "./ui";
import { usePoll } from "@/lib/hooks";
import type { ContainerSpec, IngressRoute, Pipeline, Rollout, RolloutTest, RolloutTestKind } from "@/lib/types";

export interface RolloutFormValue {
  name: string;
  description: string;
  dockerHostId: string;
  strategy: Rollout["strategy"];
  containerName: string;
  containerPort: string;
  spec: Partial<ContainerSpec>;
  addressMode: "new" | "existing";
  routeId: string;
  hostname: string;
  pathPrefix: string;
  https: boolean;
  previewHostname: string;
  healthPath: string;
  startTimeoutSeconds: string;
  tests: RolloutTest[];
  canarySteps: string;
  stepSeconds: string;
  stickySessions: boolean;
  autoRollback: boolean;
  maxErrorRate: string;
  maxAvgResponseMs: string;
  minRequests: string;
  bakeSeconds: string;
  keepPreviousRunning: boolean;
}

export function rolloutFormFrom(r: Rollout | null, hostId: string): RolloutFormValue {
  return {
    name: r?.name ?? "",
    description: r?.description ?? "",
    dockerHostId: r?.dockerHostId ?? hostId,
    strategy: r?.strategy ?? "BLUE_GREEN",
    containerName: r?.containerName ?? "",
    containerPort: r ? String(r.containerPort) : "",
    spec: r?.containerSpec ?? { env: [], volumes: [], networks: [], restartPolicy: "unless-stopped" },
    addressMode: "new",
    routeId: "",
    hostname: r?.hostname ?? "",
    pathPrefix: r?.pathPrefix ?? "/",
    https: r ? r.tlsMode === "LETS_ENCRYPT" : true,
    previewHostname: r?.previewHostname ?? "",
    healthPath: r?.healthPath ?? "/",
    startTimeoutSeconds: String(r?.startTimeoutSeconds ?? 180),
    tests: r?.tests ?? [{ kind: "HTTP", path: "/", expectStatus: "" }],
    canarySteps: r?.canarySteps ?? "10, 25, 50",
    stepSeconds: String(r?.stepSeconds ?? 120),
    stickySessions: r?.stickySessions ?? true,
    autoRollback: r?.autoRollback ?? true,
    maxErrorRate: String(r?.maxErrorRate ?? 5),
    maxAvgResponseMs: r?.maxAvgResponseMs ? String(r.maxAvgResponseMs) : "",
    minRequests: String(r?.minRequests ?? 20),
    bakeSeconds: String(r?.bakeSeconds ?? 120),
    keepPreviousRunning: r?.keepPreviousRunning ?? false,
  };
}

const num = (s: string) => (s.trim() === "" ? undefined : Number(s));

/** The request body for create/update. */
export function rolloutBody(v: RolloutFormValue) {
  return {
    name: v.name,
    description: v.description || null,
    dockerHostId: v.dockerHostId,
    strategy: v.strategy,
    containerName: v.containerName || null,
    containerPort: Number(v.containerPort) || 0,
    containerSpec: {
      ...v.spec,
      env: (v.spec.env ?? []).filter((e) => e.trim()),
      volumes: (v.spec.volumes ?? []).filter((e) => e.trim()),
      networks: (v.spec.networks ?? []).filter((e) => e.trim()),
    },
    routeId: v.addressMode === "existing" ? v.routeId || null : null,
    hostname: v.addressMode === "new" ? v.hostname : null,
    pathPrefix: v.pathPrefix,
    tlsMode: v.https ? "LETS_ENCRYPT" : "NONE",
    previewHostname: v.previewHostname || null,
    healthPath: v.healthPath,
    startTimeoutSeconds: num(v.startTimeoutSeconds),
    tests: v.tests,
    canarySteps: v.canarySteps,
    stepSeconds: num(v.stepSeconds),
    stickySessions: v.stickySessions,
    autoRollback: v.autoRollback,
    maxErrorRate: num(v.maxErrorRate),
    maxAvgResponseMs: num(v.maxAvgResponseMs) ?? 0,
    minRequests: num(v.minRequests),
    bakeSeconds: num(v.bakeSeconds),
    keepPreviousRunning: v.keepPreviousRunning,
  };
}

const TEST_KINDS: { value: RolloutTestKind; label: string; hint: string }[] = [
  { value: "HTTP", label: "HTTP check", hint: "Request a page of the new version and check the answer." },
  { value: "COMMAND", label: "Command", hint: "Run a script in any image. TARGET_URL points at the new version; exit code 0 passes." },
  { value: "PIPELINE", label: "Pipeline", hint: "Run one of your pipelines with TARGET_URL set; it must succeed." },
  { value: "APPROVAL", label: "Approval", hint: "Wait until someone presses Approve. Add a preview address so they can look first." },
];

function blankTest(kind: RolloutTestKind): RolloutTest {
  switch (kind) {
    case "COMMAND":
      return { kind, image: "curlimages/curl:latest", script: 'curl -fsS "$TARGET_URL/" > /dev/null', timeoutSeconds: 600 };
    case "PIPELINE":
      return { kind, pipelineId: "", timeoutSeconds: 3600 };
    case "APPROVAL":
      return { kind, timeoutSeconds: 0 };
    default:
      return { kind, path: "/", expectStatus: "" };
  }
}

export function RolloutForm({
  initial,
  existing,
  busy,
  error,
  submitLabel,
  onSubmit,
}: {
  initial: RolloutFormValue;
  existing?: Rollout | null;
  busy: boolean;
  error: string | null;
  submitLabel: string;
  onSubmit: (v: RolloutFormValue) => void;
}) {
  const { hosts } = useHost();
  const [v, setV] = useState(initial);
  const set = <K extends keyof RolloutFormValue>(k: K, val: RolloutFormValue[K]) => setV((x) => ({ ...x, [k]: val }));
  const setSpec = <K extends keyof ContainerSpec>(k: K, val: ContainerSpec[K]) => setV((x) => ({ ...x, spec: { ...x.spec, [k]: val } }));
  const setTest = (i: number, patch: Partial<RolloutTest>) => setV((x) => ({ ...x, tests: x.tests.map((t, j) => (j === i ? { ...t, ...patch } : t)) }));
  const moveTest = (i: number, d: number) =>
    setV((x) => {
      const tests = [...x.tests];
      const [t] = tests.splice(i, 1);
      tests.splice(i + d, 0, t);
      return { ...x, tests };
    });
  const { data: routes } = usePoll<IngressRoute[]>(!existing && v.dockerHostId ? `/api/ingress/hosts/${v.dockerHostId}/routes` : null);
  const { data: pipelines } = usePoll<Pipeline[]>("/api/pipelines");
  const hostPipelines = (pipelines ?? []).filter((p) => p.dockerHostId === v.dockerHostId);
  const takeable = (routes ?? []).filter((r) => r.targetType === "CONTAINER" && !r.rolloutId);
  const hasRoute = !!existing?.routeId;
  const canary = v.strategy === "CANARY";
  const base = v.containerName.trim() || v.name.trim().toLowerCase().replace(/[^a-z0-9_.-]+/g, "-").replace(/^-+|-+$/g, "") || "app";

  return (
    <form
      onSubmit={(e) => {
        e.preventDefault();
        onSubmit(v);
      }}
      className="flex flex-col gap-6"
    >
      <Panel title="Rollout">
        <div className="flex flex-col gap-4">
          <div className="grid gap-4 sm:grid-cols-2">
            <Field label="Name">
              <input className={inputClass} value={v.name} onChange={(e) => set("name", e.target.value)} required placeholder="Website" />
            </Field>
            <Field label="Docker host">
              <Select value={v.dockerHostId} onChange={(x) => set("dockerHostId", x)} disabled={!!existing}>
                {hosts.map((h) => (
                  <option key={h.id} value={h.id}>
                    {h.name || h.dockerHostUrl}
                  </option>
                ))}
              </Select>
            </Field>
          </div>
          <Field label="Description" optional>
            <input className={inputClass} value={v.description} onChange={(e) => set("description", e.target.value)} />
          </Field>
          <div>
            <div className="text-[13px] font-medium mb-1.5">How new versions reach visitors</div>
            <Segmented<Rollout["strategy"]>
              label="Strategy"
              value={v.strategy}
              onChange={(x) => set("strategy", x)}
              options={[
                { value: "BLUE_GREEN", label: "Blue/green" },
                { value: "CANARY", label: "Canary" },
              ]}
            />
            <p className="text-[13px] text-slate mt-2">
              {canary
                ? "The new version gets a small share of visitors first, then more at each step while its error rate and response time are watched. If it misbehaves, everyone goes back to the current version."
                : "The new version starts next to the current one and is tested. Then all visitors switch at once, and it is watched for a while with the old version still running, so switching back is instant."}
            </p>
          </div>
        </div>
      </Panel>

      <Panel title="Address">
        <div className="flex flex-col gap-4">
          {hasRoute ? (
            <p className="text-[14px]">
              Visitors use <b className="font-medium">{existing?.url}</b>. Change the address, HTTPS or passwords on the Ingress page; the rollout keeps deciding which version gets the traffic.
            </p>
          ) : (
            <>
              {!existing && (
                <Segmented<"new" | "existing">
                  label="Address"
                  value={v.addressMode}
                  onChange={(x) => set("addressMode", x)}
                  options={[
                    { value: "new", label: "New address" },
                    { value: "existing", label: "Take over an existing route" },
                  ]}
                />
              )}
              {v.addressMode === "existing" && !existing ? (
                <Field
                  label="Route"
                  hint="Its container becomes the current version. The first release starts next to it, and the old container is stopped (not removed) once the new one serves everyone."
                >
                  <Combobox
                    value={v.routeId}
                    onChange={(id) => {
                      const r = takeable.find((x) => x.id === id);
                      setV((x) => ({ ...x, routeId: id, containerPort: x.containerPort || String(r?.targetPort ?? "") }));
                    }}
                    options={takeable.map((r) => ({ value: r.id, label: `${r.hostname}${r.pathPrefix === "/" ? "" : r.pathPrefix}`, hint: `${r.targetContainerName}:${r.targetPort}` }))}
                    placeholder={routes ? "Search routes" : "Loading routes"}
                    empty="No container routes on this host"
                  />
                </Field>
              ) : (
                <div className="grid gap-4 sm:grid-cols-[2fr_1fr]">
                  <Field label="Hostname" hint="Point its DNS at this server. The route is created at the first release, once a tested version exists.">
                    <input className={inputClass} value={v.hostname} onChange={(e) => set("hostname", e.target.value)} placeholder="www.example.com" autoCapitalize="off" spellCheck={false} />
                  </Field>
                  <Field label="Path">
                    <input className={inputClass} value={v.pathPrefix} onChange={(e) => set("pathPrefix", e.target.value)} placeholder="/" spellCheck={false} />
                  </Field>
                  <div className="sm:col-span-2">
                    <Toggle checked={v.https} onChange={(x) => set("https", x)} label="HTTPS with a free Let's Encrypt certificate" />
                  </div>
                </div>
              )}
            </>
          )}
          <Field
            label="Preview address"
            optional
            hint="Always shows the version being released, so people can try it before approving. After a release it shows the current version."
          >
            <input className={inputClass} value={v.previewHostname} onChange={(e) => set("previewHostname", e.target.value)} placeholder="next.example.com" autoCapitalize="off" spellCheck={false} />
          </Field>
        </div>
      </Panel>

      <Panel title="Container">
        <div className="flex flex-col gap-4">
          <div className="grid gap-4 sm:grid-cols-3">
            <Field label="Container name" optional hint={`Runs as ${base}-blue and ${base}-green.`}>
              <input className={inputClass} value={v.containerName} onChange={(e) => set("containerName", e.target.value)} placeholder={base} spellCheck={false} />
            </Field>
            <Field label="Port the app listens on">
              <input className={inputClass} inputMode="numeric" value={v.containerPort} onChange={(e) => set("containerPort", e.target.value.replace(/\D/g, ""))} placeholder="8080" required />
            </Field>
            <Field label="Restart">
              <Select value={v.spec.restartPolicy ?? "unless-stopped"} onChange={(x) => setSpec("restartPolicy", x as ContainerSpec["restartPolicy"])}>
                <option value="unless-stopped">Always, unless I stopped it</option>
                <option value="always">Always</option>
                <option value="on-failure">Only after a crash</option>
                <option value="no">Never</option>
              </Select>
            </Field>
          </div>
          <div className="grid gap-4 lg:grid-cols-2">
            <div>
              <div className="text-[13px] font-medium mb-1.5">Environment</div>
              <ListEditor items={v.spec.env ?? []} onChange={(x) => setSpec("env", x)} placeholder="NAME=value" addLabel="Add variable" />
            </div>
            <div>
              <div className="text-[13px] font-medium mb-1.5">Volumes</div>
              <ListEditor items={v.spec.volumes ?? []} onChange={(x) => setSpec("volumes", x)} placeholder="uploads:/app/uploads" addLabel="Add volume" />
              <p className="text-[12.5px] text-slate mt-1.5">Both versions mount the same volumes while they run side by side.</p>
            </div>
          </div>
          <div>
            <div className="text-[13px] font-medium mb-1.5">Other networks</div>
            <ListEditor items={v.spec.networks ?? []} onChange={(x) => setSpec("networks", x)} placeholder="database-net" addLabel="Add network" />
            <p className="text-[12.5px] text-slate mt-1.5">The ingress network is always joined. Add others to reach a database, for example. No ports are published on the server: visitors come in through the proxy.</p>
          </div>
          <div className="grid gap-4 sm:grid-cols-2">
            <Field label="Health path" hint="Must answer 200-399 before the new version counts as up, and keeps being checked while it has visitors.">
              <input className={inputClass} value={v.healthPath} onChange={(e) => set("healthPath", e.target.value)} placeholder="/health" spellCheck={false} />
            </Field>
            <Field label="Time to start (seconds)">
              <input className={inputClass} inputMode="numeric" value={v.startTimeoutSeconds} onChange={(e) => set("startTimeoutSeconds", e.target.value.replace(/\D/g, ""))} />
            </Field>
          </div>
        </div>
      </Panel>

      <Panel title="Tests before visitors see it">
        <div className="flex flex-col gap-4">
          <p className="text-[13px] text-slate -mt-2">Run in order against the new version while visitors still use the current one. If one fails, the new version is stopped and nothing changes for visitors.</p>
          {v.tests.length === 0 && <p className="text-[14px] text-slate">No tests: only the health path is checked.</p>}
          {v.tests.map((t, i) => (
            <div key={i} className="rounded-xl border border-line p-4">
              <div className="grid gap-3 sm:grid-cols-[1fr_1fr_auto] items-end">
                <Field label="Step name" optional>
                  <input className={inputClass} value={t.name ?? ""} onChange={(e) => setTest(i, { name: e.target.value })} placeholder={TEST_KINDS.find((k) => k.value === t.kind)?.label} />
                </Field>
                <Field label="Kind" hint={TEST_KINDS.find((k) => k.value === t.kind)?.hint}>
                  <Select value={t.kind} onChange={(x) => setTest(i, { ...blankTest(x as RolloutTestKind), name: t.name })}>
                    {TEST_KINDS.map((k) => (
                      <option key={k.value} value={k.value}>
                        {k.label}
                      </option>
                    ))}
                  </Select>
                </Field>
                <div className="flex mb-1.5">
                  <IconButton type="button" label="Move up" disabled={i === 0} onClick={() => moveTest(i, -1)}>
                    <ArrowUp size={15} />
                  </IconButton>
                  <IconButton type="button" label="Move down" disabled={i === v.tests.length - 1} onClick={() => moveTest(i, 1)}>
                    <ArrowDown size={15} />
                  </IconButton>
                  <IconButton type="button" label="Remove step" onClick={() => set("tests", v.tests.filter((_, j) => j !== i))}>
                    <X size={16} />
                  </IconButton>
                </div>
              </div>
              <div className="grid gap-3 sm:grid-cols-2 mt-3">
                {t.kind === "HTTP" && (
                  <>
                    <Field label="Path">
                      <input className={inputClass} value={t.path ?? ""} onChange={(e) => setTest(i, { path: e.target.value })} placeholder="/" spellCheck={false} />
                    </Field>
                    <Field label="Expected status" optional hint="e.g. 200, 2xx or 200-299. Redirects are followed. Default 200-399.">
                      <input className={inputClass} value={t.expectStatus ?? ""} onChange={(e) => setTest(i, { expectStatus: e.target.value })} placeholder="200-399" />
                    </Field>
                    <Field label="Page contains" optional>
                      <input className={inputClass} value={t.bodyContains ?? ""} onChange={(e) => setTest(i, { bodyContains: e.target.value })} placeholder="Welcome" />
                    </Field>
                    <Field label="Tries" optional hint="A few seconds apart. Default 3.">
                      <input className={inputClass} inputMode="numeric" value={t.retries ?? ""} onChange={(e) => setTest(i, { retries: num(e.target.value.replace(/\D/g, "")) ?? null })} />
                    </Field>
                  </>
                )}
                {t.kind === "COMMAND" && (
                  <>
                    <Field label="Image">
                      <input className={inputClass} value={t.image ?? ""} onChange={(e) => setTest(i, { image: e.target.value })} placeholder="curlimages/curl:latest" spellCheck={false} />
                    </Field>
                    <Field label="Time limit (seconds)">
                      <input className={inputClass} inputMode="numeric" value={t.timeoutSeconds ?? ""} onChange={(e) => setTest(i, { timeoutSeconds: num(e.target.value.replace(/\D/g, "")) ?? null })} />
                    </Field>
                    <div className="sm:col-span-2">
                      <Field label="Script" hint="Runs with /bin/sh. Available: TARGET_URL, TARGET_HOST, TARGET_PORT, PUBLIC_HOSTNAME, RELEASE_IMAGE, RELEASE_NUMBER, ROLLOUT_NAME.">
                        <textarea className={`${inputClass} code`} rows={4} value={t.script ?? ""} onChange={(e) => setTest(i, { script: e.target.value })} spellCheck={false} />
                      </Field>
                    </div>
                  </>
                )}
                {t.kind === "PIPELINE" && (
                  <>
                    <Field label="Pipeline" hint="Its steps get TARGET_URL and join the network the new version is on.">
                      <Select value={t.pipelineId ?? ""} onChange={(x) => setTest(i, { pipelineId: x })}>
                        <option value="">Choose a pipeline</option>
                        {hostPipelines.map((p) => (
                          <option key={p.id} value={p.id}>
                            {p.name}
                          </option>
                        ))}
                      </Select>
                    </Field>
                    <Field label="Time limit (seconds)">
                      <input className={inputClass} inputMode="numeric" value={t.timeoutSeconds ?? ""} onChange={(e) => setTest(i, { timeoutSeconds: num(e.target.value.replace(/\D/g, "")) ?? null })} />
                    </Field>
                  </>
                )}
                {t.kind === "APPROVAL" && (
                  <Field label="Give up after (seconds)" optional hint="Empty or 0 waits until someone decides.">
                    <input className={inputClass} inputMode="numeric" value={t.timeoutSeconds || ""} onChange={(e) => setTest(i, { timeoutSeconds: num(e.target.value.replace(/\D/g, "")) ?? 0 })} />
                  </Field>
                )}
              </div>
            </div>
          ))}
          <div className="flex flex-wrap gap-2">
            {TEST_KINDS.map((k) => (
              <Button key={k.value} type="button" size="sm" tone="quiet" onClick={() => set("tests", [...v.tests, blankTest(k.value)])}>
                Add {k.label.toLowerCase()}
              </Button>
            ))}
          </div>
        </div>
      </Panel>

      <Panel title={canary ? "Canary steps" : "After the switch"}>
        <div className="flex flex-col gap-4">
          {canary ? (
            <>
              <div className="grid gap-4 sm:grid-cols-2">
                <Field label="Share of visitors at each step (%)" hint="Then everyone. For example 10, 25, 50.">
                  <input className={inputClass} value={v.canarySteps} onChange={(e) => set("canarySteps", e.target.value)} placeholder="10, 25, 50" />
                </Field>
                <Field label="Length of each step (seconds)">
                  <input className={inputClass} inputMode="numeric" value={v.stepSeconds} onChange={(e) => set("stepSeconds", e.target.value.replace(/\D/g, ""))} />
                </Field>
              </div>
              <Toggle
                checked={v.stickySessions}
                onChange={(x) => set("stickySessions", x)}
                label="Keep each visitor on one version"
                hint="Chosen by address and browser, so a visitor doesn't flip between versions. Testers can set the cookie dm_variant=canary (or stable) to pick one."
              />
            </>
          ) : (
            <Field label="Watch the new version for (seconds)" hint="The old version keeps running meanwhile. 0 skips watching.">
              <input className={`${inputClass} sm:max-w-xs`} inputMode="numeric" value={v.bakeSeconds} onChange={(e) => set("bakeSeconds", e.target.value.replace(/\D/g, ""))} />
            </Field>
          )}
        </div>
      </Panel>

      <Panel title="Automatic rollback">
        <div className="flex flex-col gap-4">
          <Toggle
            checked={v.autoRollback}
            onChange={(x) => set("autoRollback", x)}
            label="Send visitors back to the current version when the new one misbehaves"
            hint="While the new version has visitors. When off, problems are logged and the release carries on (a crashed container still stops it)."
          />
          <div className="grid gap-4 sm:grid-cols-3">
            <Field label="Most failed requests (%)" hint="Answers with status 500 or more.">
              <input className={inputClass} inputMode="decimal" value={v.maxErrorRate} onChange={(e) => set("maxErrorRate", e.target.value.replace(/[^\d.]/g, ""))} />
            </Field>
            <Field label="Slowest average answer (ms)" optional hint="Empty for no limit.">
              <input className={inputClass} inputMode="numeric" value={v.maxAvgResponseMs} onChange={(e) => set("maxAvgResponseMs", e.target.value.replace(/\D/g, ""))} />
            </Field>
            <Field label="Requests before judging" hint="Fewer requests aren't enough to tell.">
              <input className={inputClass} inputMode="numeric" value={v.minRequests} onChange={(e) => set("minRequests", e.target.value.replace(/\D/g, ""))} />
            </Field>
          </div>
          <Toggle
            checked={v.keepPreviousRunning}
            onChange={(x) => set("keepPreviousRunning", x)}
            label="Keep the previous version running after a release"
            hint="Rolling back is then instant, at the cost of its memory. Otherwise it is stopped and started again on roll back."
          />
        </div>
      </Panel>

      {error && <Notice tone="error">{error}</Notice>}
      <div>
        <Button type="submit" busy={busy} disabled={!v.name || !v.containerPort || (!hasRoute && (v.addressMode === "existing" ? !v.routeId : !v.hostname))}>
          {submitLabel}
        </Button>
      </div>
    </form>
  );
}
