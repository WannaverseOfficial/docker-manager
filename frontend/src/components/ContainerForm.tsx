"use client";

import { useState } from "react";
import { X } from "lucide-react";
import { useSession } from "./app";
import { Button, Field, IconButton, ListEditor, Notice, Panel, Select, Toggle, inputClass } from "./ui";
import { usePoll } from "@/lib/hooks";
import type { ContainerSpec, Network, PortMapping, Template } from "@/lib/types";

export function emptySpec(): ContainerSpec {
  return {
    image: "",
    name: "",
    command: [],
    entrypoint: [],
    env: [],
    ports: [],
    volumes: [],
    networks: [],
    restartPolicy: "unless-stopped",
    labels: {},
    user: "",
    workingDir: "",
    privileged: false,
    capAdd: [],
    memoryLimitMb: 0,
    cpus: 0,
    start: true,
  };
}

/** Templates store defaults as comma-separated lists: "8080:80,53:53/udp", "A=1,B=2", "data:/data". */
export function specFromTemplate(t: Template): ContainerSpec {
  const split = (s: string | null) => (s ? s.split(",").map((x) => x.trim()).filter(Boolean) : []);
  const ports: PortMapping[] = split(t.defaultPorts).map((p) => {
    const [mapping, proto] = p.split("/");
    const [host, container] = mapping.includes(":") ? mapping.split(":") : [mapping, mapping];
    return { hostPort: Number(host) || null, containerPort: Number(container), protocol: proto === "udp" ? "udp" : "tcp" };
  });
  return {
    ...emptySpec(),
    image: t.imageName ?? "",
    name: t.name.toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-|-$/g, ""),
    ports,
    env: split(t.defaultEnv),
    volumes: split(t.defaultVolumes),
    user: t.defaultUser ?? "",
    networks: t.defaultNetwork ? [t.defaultNetwork] : [],
  };
}

/** Splits a command line like a shell would for simple cases: spaces separate, quotes group. */
export function splitCommand(line: string): string[] {
  const out: string[] = [];
  let cur = "";
  let quote: string | null = null;
  let any = false;
  for (const ch of line) {
    if (quote) {
      if (ch === quote) quote = null;
      else cur += ch;
    } else if (ch === '"' || ch === "'") {
      quote = ch;
      any = true;
    } else if (/\s/.test(ch)) {
      if (cur || any) out.push(cur);
      cur = "";
      any = false;
    } else cur += ch;
  }
  if (cur || any) out.push(cur);
  return out;
}

export function joinCommand(args: string[]): string {
  return args.map((a) => (/[\s"']/.test(a) || a === "" ? `"${a.replace(/"/g, '\\"')}"` : a)).join(" ");
}

const NAME_RE = /^[a-zA-Z0-9][a-zA-Z0-9_.-]*$/;

export function ContainerForm({
  initial,
  hostId,
  submitLabel,
  busy,
  error,
  onSubmit,
  editing = false,
  imageLocked = false,
}: {
  initial: ContainerSpec;
  hostId: string;
  submitLabel: string;
  busy: boolean;
  error: string | null;
  onSubmit: (spec: ContainerSpec, opts: { pull: boolean }) => void;
  editing?: boolean;
  imageLocked?: boolean;
}) {
  const { session } = useSession();
  const [spec, setSpec] = useState<ContainerSpec>(initial);
  const [command, setCommand] = useState(joinCommand(initial.command ?? []));
  const [advanced, setAdvanced] = useState(
    !!(initial.user || initial.workingDir || initial.memoryLimitMb || initial.cpus || initial.privileged || initial.capAdd?.length || initial.command?.length),
  );
  const [pull, setPull] = useState(false);
  const { data: networks } = usePoll<Network[]>(`/api/docker/hosts/${hostId}/networks`);

  const set = <K extends keyof ContainerSpec>(k: K, v: ContainerSpec[K]) => setSpec((s) => ({ ...s, [k]: v }));
  const nameError = spec.name && !NAME_RE.test(spec.name) ? "Use letters, digits, '_', '.' and '-', starting with a letter or digit." : null;
  const hostPathMounts = spec.volumes.filter((v) => /^[/.~]/.test(v.split(":")[0]));
  const portClash = (() => {
    const seen = new Set<string>();
    for (const p of spec.ports) {
      if (!p.hostPort) continue;
      const k = `${p.hostPort}/${p.protocol}`;
      if (seen.has(k)) return `Host port ${p.hostPort}/${p.protocol} is used twice.`;
      seen.add(k);
    }
    return null;
  })();

  const submit = (e: React.FormEvent) => {
    e.preventDefault();
    onSubmit(
      {
        ...spec,
        name: spec.name?.trim() || undefined,
        image: spec.image.trim(),
        command: splitCommand(command),
        env: spec.env.map((x) => x.trim()).filter(Boolean),
        volumes: spec.volumes.map((x) => x.trim()).filter(Boolean),
        ports: spec.ports.filter((p) => p.containerPort > 0),
        user: spec.user?.trim() || null,
        workingDir: spec.workingDir?.trim() || null,
      },
      { pull },
    );
  };

  const userNetworks = (networks ?? []).filter((n) => !["host", "none"].includes(n.Name) || session?.admin);

  return (
    <form onSubmit={submit} className="flex flex-col gap-6">
      <Panel title="Image and name">
        <div className="grid gap-4 sm:grid-cols-2">
          <Field label="Image" hint="From Docker Hub or any registry, for example nginx:1.29-alpine or ghcr.io/owner/app:tag">
            <input
              className={inputClass}
              value={spec.image}
              onChange={(e) => set("image", e.target.value)}
              placeholder="nginx:latest"
              required
              readOnly={imageLocked}
              autoCapitalize="off"
              spellCheck={false}
            />
          </Field>
          <Field label="Name" optional={!editing} error={nameError} hint={editing ? undefined : "Docker picks a random one if empty."}>
            <input className={inputClass} value={spec.name ?? ""} onChange={(e) => set("name", e.target.value)} placeholder="my-app" autoCapitalize="off" spellCheck={false} />
          </Field>
        </div>
        {editing && (
          <div className="mt-4">
            <Toggle checked={pull} onChange={setPull} label="Pull the newest version of the image first" hint="Otherwise the image already on this host is used." />
          </div>
        )}
      </Panel>

      <Panel title="Ports" action={<span className="text-[12.5px] text-slate">Publish a container port on the host</span>}>
        <div className="flex flex-col gap-2">
          {spec.ports.length === 0 && <p className="text-[14px] text-slate">No ports published. The container is reachable from other containers on its network only.</p>}
          {spec.ports.map((p, i) => (
            <div key={i} className="grid grid-cols-[1fr_auto_1fr_6.5rem_auto] items-center gap-2">
              <input
                className={inputClass}
                inputMode="numeric"
                aria-label="Host port"
                placeholder="Host port"
                value={p.hostPort ?? ""}
                onChange={(e) => set("ports", spec.ports.map((x, j) => (j === i ? { ...x, hostPort: e.target.value ? Number(e.target.value.replace(/\D/g, "")) : null } : x)))}
              />
              <span className="text-slate" aria-hidden>
                →
              </span>
              <input
                className={inputClass}
                inputMode="numeric"
                aria-label="Container port"
                placeholder="Container port"
                value={p.containerPort || ""}
                onChange={(e) => set("ports", spec.ports.map((x, j) => (j === i ? { ...x, containerPort: Number(e.target.value.replace(/\D/g, "")) } : x)))}
                required
              />
              <Select
                aria-label="Protocol"
                value={p.protocol}
                onChange={(v) => set("ports", spec.ports.map((x, j) => (j === i ? { ...x, protocol: v as "tcp" | "udp" } : x)))}
              >
                <option value="tcp">TCP</option>
                <option value="udp">UDP</option>
              </Select>
              <IconButton type="button" label="Remove port" onClick={() => set("ports", spec.ports.filter((_, j) => j !== i))}>
                <X size={16} />
              </IconButton>
            </div>
          ))}
          {portClash && <p className="text-[13px] text-danger">{portClash}</p>}
          <div>
            <Button type="button" tone="quiet" size="sm" onClick={() => set("ports", [...spec.ports, { hostPort: null, containerPort: 0, protocol: "tcp" }])}>
              Add port
            </Button>
          </div>
        </div>
      </Panel>

      <div className="grid gap-6 lg:grid-cols-2">
        <Panel title="Environment variables">
          <ListEditor items={spec.env} onChange={(v) => set("env", v)} placeholder="NAME=value" addLabel="Add variable" />
        </Panel>
        <Panel title="Volumes">
          <ListEditor items={spec.volumes} onChange={(v) => set("volumes", v)} placeholder="volume-name:/path/in/container" addLabel="Add volume" />
          <p className="text-[12.5px] text-slate mt-3">
            A name (data:/var/lib/app) uses a Docker volume that keeps its data when the container is replaced.
            {session?.admin ? " A path (/srv/app:/data) mounts a folder from the host; add :ro for read-only." : " Only administrators can mount host folders."}
          </p>
          {hostPathMounts.length > 0 && !session?.admin && <p className="text-[13px] text-danger mt-2">Host folders can&apos;t be mounted with your account.</p>}
        </Panel>
      </div>

      <Panel title="Running">
        <div className="grid gap-4 sm:grid-cols-2">
          <Field label="Restart" hint="What Docker does when the container stops or the host reboots.">
            <Select value={spec.restartPolicy} onChange={(v) => set("restartPolicy", v as ContainerSpec["restartPolicy"])}>
              <option value="unless-stopped">Always, unless I stopped it</option>
              <option value="always">Always</option>
              <option value="on-failure">Only after a crash</option>
              <option value="no">Never</option>
            </Select>
          </Field>
          <Field label="Network" hint="Containers on the same network reach each other by name.">
            <Select value={spec.networks[0] ?? ""} onChange={(v) => set("networks", v ? [v, ...spec.networks.slice(1).filter((n) => n !== v)] : spec.networks.slice(1))}>
              <option value="">Default (bridge)</option>
              {userNetworks
                .filter((n) => n.Name !== "bridge")
                .map((n) => (
                  <option key={n.Id} value={n.Name}>
                    {n.Name}
                  </option>
                ))}
            </Select>
          </Field>
        </div>
        {spec.networks.length > 1 && (
          <p className="text-[13px] text-slate mt-3">Also joins: {spec.networks.slice(1).join(", ")}</p>
        )}
        {!editing && (
          <div className="mt-4">
            <Toggle checked={spec.start} onChange={(v) => set("start", v)} label="Start it right away" />
          </div>
        )}
      </Panel>

      <div>
        <button type="button" onClick={() => setAdvanced(!advanced)} className="text-[14px] text-slate hover:text-ink" aria-expanded={advanced}>
          {advanced ? "Hide" : "Show"} advanced settings
        </button>
      </div>
      {advanced && (
        <Panel title="Advanced">
          <div className="grid gap-4 sm:grid-cols-2">
            <Field label="Command" optional hint="Replaces the image's default command, e.g. server /data">
              <input className={`${inputClass} font-mono text-[13px]`} value={command} onChange={(e) => setCommand(e.target.value)} spellCheck={false} />
            </Field>
            <Field label="Run as user" optional hint="A user name or UID[:GID], e.g. 1000:1000">
              <input className={inputClass} value={spec.user ?? ""} onChange={(e) => set("user", e.target.value)} spellCheck={false} />
            </Field>
            <Field label="Memory limit (MiB)" optional hint="0 for no limit">
              <input className={inputClass} inputMode="numeric" value={spec.memoryLimitMb || ""} onChange={(e) => set("memoryLimitMb", Number(e.target.value.replace(/\D/g, "")) || 0)} />
            </Field>
            <Field label="CPU limit (cores)" optional hint="e.g. 1.5; 0 for no limit">
              <input className={inputClass} inputMode="decimal" value={spec.cpus || ""} onChange={(e) => set("cpus", Number(e.target.value) || 0)} />
            </Field>
            <Field label="Working directory" optional>
              <input className={inputClass} value={spec.workingDir ?? ""} onChange={(e) => set("workingDir", e.target.value)} spellCheck={false} />
            </Field>
            {session?.admin && (
              <Field label="Extra capabilities" optional hint="Comma separated, e.g. NET_ADMIN">
                <input
                  className={inputClass}
                  value={spec.capAdd.join(", ")}
                  onChange={(e) => set("capAdd", e.target.value.split(",").map((s) => s.trim().toUpperCase()).filter(Boolean))}
                  spellCheck={false}
                />
              </Field>
            )}
          </div>
          {session?.admin && (
            <div className="mt-4">
              <Toggle checked={spec.privileged} onChange={(v) => set("privileged", v)} label="Privileged" hint="Gives the container full access to the host. Only for software that needs it." />
            </div>
          )}
        </Panel>
      )}

      {editing && (
        <Notice>
          Saving replaces the container with a new one using these settings. Volumes keep their data. If the new container fails to start, the current one is kept
          running.
        </Notice>
      )}
      {error && <Notice tone="error">{error}</Notice>}
      <div className="flex gap-2">
        <Button type="submit" busy={busy} disabled={!spec.image.trim() || !!nameError || !!portClash}>
          {submitLabel}
        </Button>
      </div>
    </form>
  );
}
