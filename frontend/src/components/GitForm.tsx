"use client";

import { useState } from "react";
import { X } from "lucide-react";
import { useHost } from "./app";
import { Button, Field, IconButton, ListEditor, Notice, Panel, Segmented, Select, Toggle, inputClass } from "./ui";
import { usePoll } from "@/lib/hooks";
import type { ContainerSpec, GitRepo, PortMapping, Rollout } from "@/lib/types";

export interface GitFormValue {
  name: string;
  repositoryUrl: string;
  branch: string;
  authType: GitRepo["authType"];
  token: string;
  tokenUsername: string;
  sshKey: string;
  deploymentType: GitRepo["deploymentType"];
  dockerfilePath: string;
  composePath: string;
  imageName: string;
  runContainer: boolean;
  rolloutId: string;
  containerSpec: Partial<ContainerSpec>;
  dockerHostId: string;
  pollingEnabled: boolean;
  pollingIntervalSeconds: number;
  webhookEnabled: boolean;
}

export function gitFormFrom(repo: GitRepo | null, hostId: string): GitFormValue {
  return {
    name: repo?.name ?? "",
    repositoryUrl: repo?.repositoryUrl ?? "",
    branch: repo?.branch ?? "main",
    authType: repo?.authType ?? "NONE",
    token: "",
    tokenUsername: repo?.tokenUsername ?? "",
    sshKey: "",
    deploymentType: repo?.deploymentType ?? "DOCKER_COMPOSE",
    dockerfilePath: repo?.dockerfilePath ?? "",
    composePath: repo?.composePath ?? "",
    imageName: repo?.imageName ?? "",
    runContainer: repo?.runContainer ?? true,
    rolloutId: repo?.rolloutId ?? "",
    containerSpec: repo?.containerSpec ?? { ports: [], env: [], volumes: [], restartPolicy: "unless-stopped" },
    dockerHostId: repo?.dockerHostId ?? hostId,
    pollingEnabled: repo?.pollingEnabled ?? false,
    pollingIntervalSeconds: repo?.pollingIntervalSeconds || 300,
    webhookEnabled: repo?.webhookEnabled ?? true,
  };
}

export function GitForm({
  initial,
  existing,
  busy,
  error,
  submitLabel,
  onSubmit,
}: {
  initial: GitFormValue;
  existing?: GitRepo | null;
  busy: boolean;
  error: string | null;
  submitLabel: string;
  onSubmit: (v: GitFormValue) => void;
}) {
  const { hosts } = useHost();
  const [v, setV] = useState(initial);
  const set = <K extends keyof GitFormValue>(k: K, val: GitFormValue[K]) => setV((x) => ({ ...x, [k]: val }));
  const spec = v.containerSpec;
  const setSpec = <K extends keyof ContainerSpec>(k: K, val: ContainerSpec[K]) => setV((x) => ({ ...x, containerSpec: { ...x.containerSpec, [k]: val } }));
  const ports = (spec.ports ?? []) as PortMapping[];
  const ssh = /^(ssh:\/\/|[\w.-]+@[\w.-]+:)/.test(v.repositoryUrl.trim());
  const { data: allRollouts } = usePoll<Rollout[]>(v.deploymentType === "DOCKERFILE" ? "/api/rollouts" : null);
  const rollouts = (allRollouts ?? []).filter((r) => r.dockerHostId === v.dockerHostId);
  const runMode: "replace" | "rollout" | "build" = !v.runContainer ? "build" : v.rolloutId ? "rollout" : "replace";
  const [wantsRollout, setWantsRollout] = useState(runMode === "rollout");
  const mode = runMode === "build" ? "build" : wantsRollout ? "rollout" : "replace";

  return (
    <form
      onSubmit={(e) => {
        e.preventDefault();
        onSubmit(v);
      }}
      className="flex flex-col gap-6"
    >
      <Panel title="Repository">
        <div className="grid gap-4 sm:grid-cols-2">
          <Field label="Name">
            <input className={inputClass} value={v.name} onChange={(e) => set("name", e.target.value)} required placeholder="My app" />
          </Field>
          <Field label="Docker host" hint="Where it is deployed.">
            <Select value={v.dockerHostId} onChange={(x) => set("dockerHostId", x)} disabled={!!existing}>
              {hosts.map((h) => (
                <option key={h.id} value={h.id}>
                  {h.name || h.dockerHostUrl}
                </option>
              ))}
            </Select>
          </Field>
          <Field label="Repository URL" hint="https://github.com/owner/repo.git or git@github.com:owner/repo.git">
            <input className={inputClass} value={v.repositoryUrl} onChange={(e) => set("repositoryUrl", e.target.value)} required autoCapitalize="off" spellCheck={false} />
          </Field>
          <Field label="Branch">
            <input className={inputClass} value={v.branch} onChange={(e) => set("branch", e.target.value)} required autoCapitalize="off" spellCheck={false} />
          </Field>
        </div>
      </Panel>

      <Panel title="Access">
        <div className="flex flex-col gap-4">
          <Segmented<GitRepo["authType"]>
            label="Access"
            value={v.authType}
            onChange={(x) => set("authType", x)}
            options={[
              { value: "NONE", label: "Public" },
              { value: "PAT", label: "Access token" },
              { value: "SSH_KEY", label: "SSH key" },
            ]}
          />
          {v.authType === "PAT" && (
            <div className="grid gap-4 sm:grid-cols-2">
              <Field label="Access token" hint={existing?.hasToken ? "Saved. Leave empty to keep it." : "A personal access token with read access to the repository."}>
                <input className={inputClass} type="password" value={v.token} onChange={(e) => set("token", e.target.value)} autoComplete="off" />
              </Field>
              <Field label="Username" optional hint="GitHub: your username. GitLab and most others accept anything; oauth2 is used when empty. Bitbucket repository tokens: x-token-auth.">
                <input className={inputClass} value={v.tokenUsername} onChange={(e) => set("tokenUsername", e.target.value)} placeholder="oauth2" autoCapitalize="off" />
              </Field>
            </div>
          )}
          {v.authType === "SSH_KEY" && (
            <Field
              label="Private key"
              hint={
                existing?.hasSshKey
                  ? "Saved. Paste a new key to replace it."
                  : "An unencrypted key (BEGIN OPENSSH PRIVATE KEY). Add its public key to the repository as a read-only deploy key. The server's host key is trusted the first time and checked after that."
              }
            >
              <textarea className={`${inputClass} code`} rows={5} value={v.sshKey} onChange={(e) => set("sshKey", e.target.value)} spellCheck={false} />
            </Field>
          )}
          {v.authType !== "SSH_KEY" && ssh && <Notice tone="warn">This is an SSH address. Choose SSH key, or use the repository&apos;s https:// address.</Notice>}
        </div>
      </Panel>

      <Panel title="How to deploy it">
        <div className="flex flex-col gap-4">
          <Segmented<GitRepo["deploymentType"]>
            label="Deployment type"
            value={v.deploymentType}
            onChange={(x) => set("deploymentType", x)}
            options={[
              { value: "DOCKER_COMPOSE", label: "Compose file" },
              { value: "DOCKERFILE", label: "Dockerfile" },
            ]}
          />
          {v.deploymentType === "DOCKER_COMPOSE" ? (
            <>
              <Field label="Compose file" optional hint="Path in the repository. Found automatically when empty (compose.yaml, docker-compose.yml…).">
                <input className={inputClass} value={v.composePath} onChange={(e) => set("composePath", e.target.value)} placeholder="docker-compose.yml" spellCheck={false} />
              </Field>
              <p className="text-[13px] text-slate">Deployed as a stack named after the repository; it appears on the Stacks page.</p>
            </>
          ) : (
            <>
              <div className="grid gap-4 sm:grid-cols-2">
                <Field label="Dockerfile" optional hint="Path in the repository. The build context is the repository root.">
                  <input className={inputClass} value={v.dockerfilePath} onChange={(e) => set("dockerfilePath", e.target.value)} placeholder="Dockerfile" spellCheck={false} />
                </Field>
                <Field label="Image name" optional hint="Tagged :latest and with the commit.">
                  <input className={inputClass} value={v.imageName} onChange={(e) => set("imageName", e.target.value)} placeholder="my-app" spellCheck={false} />
                </Field>
              </div>
              <div>
                <div className="text-[13px] font-medium mb-1.5">How to run it</div>
                <Segmented<"replace" | "rollout" | "build">
                  label="How to run it"
                  value={mode}
                  onChange={(x) => {
                    setWantsRollout(x === "rollout");
                    setV((s) => ({ ...s, runContainer: x !== "build", rolloutId: x === "rollout" ? s.rolloutId : "" }));
                  }}
                  options={[
                    { value: "replace", label: "Replace the container" },
                    { value: "rollout", label: "Release with a rollout" },
                    { value: "build", label: "Only build the image" },
                  ]}
                />
                <p className="text-[13px] text-slate mt-2">
                  {mode === "replace"
                    ? "The previous container is replaced; if the new one fails to start, it keeps running."
                    : mode === "rollout"
                      ? "Each new image goes through a rollout: it starts next to the current version, is tested, and gets visitors with blue/green or canary. The rollout's container settings are used."
                      : "The image is built and tagged; nothing is started."}
                </p>
              </div>
              {mode === "rollout" && (
                <Field label="Rollout" hint={rollouts.length === 0 && allRollouts ? "No rollouts on this host yet. Create one on the Rollouts page first." : undefined}>
                  <Select value={v.rolloutId} onChange={(x) => set("rolloutId", x)}>
                    <option value="">Choose a rollout</option>
                    {rollouts.map((r) => (
                      <option key={r.id} value={r.id}>
                        {r.name}
                        {r.hostname ? ` (${r.hostname})` : ""}
                      </option>
                    ))}
                  </Select>
                </Field>
              )}
              {mode === "replace" && (
                <div className="flex flex-col gap-4 rounded-xl bg-well/50 p-4">
                  <div className="grid gap-4 sm:grid-cols-2">
                    <Field label="Container name" optional>
                      <input className={inputClass} value={spec.name ?? ""} onChange={(e) => setSpec("name", e.target.value)} placeholder="Repository name" spellCheck={false} />
                    </Field>
                    <Field label="Restart">
                      <Select value={spec.restartPolicy ?? "unless-stopped"} onChange={(x) => setSpec("restartPolicy", x as ContainerSpec["restartPolicy"])}>
                        <option value="unless-stopped">Always, unless I stopped it</option>
                        <option value="always">Always</option>
                        <option value="on-failure">Only after a crash</option>
                        <option value="no">Never</option>
                      </Select>
                    </Field>
                  </div>
                  <div>
                    <div className="text-[13px] font-medium mb-1.5">Ports</div>
                    <div className="flex flex-col gap-2">
                      {ports.map((p, i) => (
                        <div key={i} className="grid grid-cols-[1fr_auto_1fr_6rem_auto] gap-2 items-center">
                          <input className={inputClass} aria-label="Host port" placeholder="Host" inputMode="numeric" value={p.hostPort ?? ""} onChange={(e) => setSpec("ports", ports.map((x, j) => (j === i ? { ...x, hostPort: Number(e.target.value) || null } : x)))} />
                          <span className="text-slate">→</span>
                          <input className={inputClass} aria-label="Container port" placeholder="Container" inputMode="numeric" value={p.containerPort || ""} onChange={(e) => setSpec("ports", ports.map((x, j) => (j === i ? { ...x, containerPort: Number(e.target.value) || 0 } : x)))} />
                          <Select aria-label="Protocol" value={p.protocol} onChange={(x) => setSpec("ports", ports.map((y, j) => (j === i ? { ...y, protocol: x as "tcp" | "udp" } : y)))}>
                            <option value="tcp">TCP</option>
                            <option value="udp">UDP</option>
                          </Select>
                          <IconButton type="button" label="Remove" onClick={() => setSpec("ports", ports.filter((_, j) => j !== i))}>
                            <X size={16} />
                          </IconButton>
                        </div>
                      ))}
                      <div>
                        <Button type="button" size="sm" tone="quiet" onClick={() => setSpec("ports", [...ports, { hostPort: null, containerPort: 0, protocol: "tcp" }])}>
                          Add port
                        </Button>
                      </div>
                    </div>
                  </div>
                  <div className="grid gap-4 lg:grid-cols-2">
                    <div>
                      <div className="text-[13px] font-medium mb-1.5">Environment</div>
                      <ListEditor items={spec.env ?? []} onChange={(x) => setSpec("env", x)} placeholder="NAME=value" addLabel="Add variable" />
                    </div>
                    <div>
                      <div className="text-[13px] font-medium mb-1.5">Volumes</div>
                      <ListEditor items={spec.volumes ?? []} onChange={(x) => setSpec("volumes", x)} placeholder="data:/app/data" addLabel="Add volume" />
                    </div>
                  </div>
                </div>
              )}
            </>
          )}
        </div>
      </Panel>

      <Panel title="Automatic deploys">
        <div className="flex flex-col gap-4">
          <Toggle checked={v.webhookEnabled} onChange={(x) => set("webhookEnabled", x)} label="Deploy on push (webhook)" hint="Add the webhook URL, shown after saving, to the repository. Works with GitHub, GitLab, Gitea and Forgejo." />
          <Toggle checked={v.pollingEnabled} onChange={(x) => set("pollingEnabled", x)} label="Check for new commits regularly" hint="For servers that can't receive webhooks." />
          {v.pollingEnabled && (
            <Field label="Check every">
              <Select value={String(v.pollingIntervalSeconds)} onChange={(x) => set("pollingIntervalSeconds", Number(x))} className="sm:max-w-xs">
                <option value="60">Minute</option>
                <option value="300">5 minutes</option>
                <option value="900">15 minutes</option>
                <option value="3600">Hour</option>
              </Select>
            </Field>
          )}
        </div>
      </Panel>

      {error && <Notice tone="error">{error}</Notice>}
      <div>
        <Button type="submit" busy={busy} disabled={!v.name || !v.repositoryUrl}>
          {submitLabel}
        </Button>
      </div>
    </form>
  );
}

/** The request body for create/update; secrets are only sent when typed. */
export function gitBody(v: GitFormValue) {
  const spec = v.containerSpec;
  return {
    ...v,
    token: v.token || undefined,
    sshKey: v.sshKey || undefined,
    rolloutId: v.deploymentType === "DOCKERFILE" && v.runContainer ? v.rolloutId : "",
    containerSpec: v.deploymentType === "DOCKERFILE" && v.runContainer
      ? {
          ...spec,
          ports: (spec.ports ?? []).filter((p) => p.containerPort > 0),
          env: (spec.env ?? []).filter((e) => e.trim()),
          volumes: (spec.volumes ?? []).filter((e) => e.trim()),
        }
      : undefined,
  };
}
