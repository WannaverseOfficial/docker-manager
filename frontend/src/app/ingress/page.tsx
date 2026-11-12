"use client";

import Link from "next/link";
import { useMemo, useState } from "react";
import { Plus, X } from "lucide-react";
import { useHost, useSession } from "@/components/app";
import {
  Badge,
  Button,
  Combobox,
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
import { api } from "@/lib/api";
import { ago, containerName, dateTime } from "@/lib/format";
import { usePoll } from "@/lib/hooks";
import { useManaged } from "@/lib/managed";
import { ManagedBadge, ManagedNote } from "@/components/managed";
import type { Certificate, Container, ContainerInspect, IngressConfig, IngressRoute, NginxStatus } from "@/lib/types";

type Tab = "routes" | "certificates" | "config" | "activity";

export default function IngressPage() {
  const { hostId } = useHost();
  const { data: config, error, refresh } = usePoll<IngressConfig>(hostId ? `/api/ingress/hosts/${hostId}/config` : null, 10000);
  if (!hostId) return null;
  return (
    <>
      <PageHeader
        title="Ingress"
        art="ingress"
        intro="Publish containers on your own domain names, with HTTPS certificates from Let's Encrypt that renew by themselves."
      />
      {error && !config && <Notice tone="error">{error}</Notice>}
      {!config && !error && <Spinner />}
      {config && (config.status === "ENABLED" ? <Enabled hostId={hostId} config={config} onChange={refresh} /> : <Enable hostId={hostId} config={config} onChange={refresh} />)}
    </>
  );
}

function Enable({ hostId, config, onChange }: { hostId: string; config: IngressConfig; onChange: () => void }) {
  const [httpPort, setHttpPort] = useState(String(config.httpPort || 80));
  const [httpsPort, setHttpsPort] = useState(String(config.httpsPort || 443));
  const [email, setEmail] = useState(config.acmeEmail ?? "");
  const [staging, setStaging] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(config.status === "ERROR" ? config.lastError : null);
  const toast = useToast();
  const managed = useManaged().onHost("ingress", hostId);
  if (managed) {
    return (
      <Panel title="Ingress is off">
        <div className="flex flex-col gap-2">
          <ManagedNote what="Ingress on this host" />
          <p className="text-[14px] text-slate">The configuration file turns it off. Set enabled: true there to turn it on.</p>
        </div>
      </Panel>
    );
  }
  return (
    <div className="grid gap-6 lg:grid-cols-[1fr_minmax(0,22rem)]">
      <Panel title="Turn on ingress">
        <form
          className="flex flex-col gap-4"
          onSubmit={async (e) => {
            e.preventDefault();
            setBusy(true);
            setError(null);
            try {
              const r = await api.post<{ success: boolean; error: string | null }>(`/api/ingress/hosts/${hostId}/enable`, {
                httpPort: Number(httpPort),
                httpsPort: Number(httpsPort),
                acmeEmail: email.trim() || null,
                useStaging: staging,
              });
              if (!r.success) throw new Error(r.error ?? "Could not turn ingress on");
              toast("Ingress is on");
              onChange();
            } catch (err) {
              setError((err as Error).message);
            } finally {
              setBusy(false);
            }
          }}
        >
          <p className="text-[14px] text-slate">
            Starts an nginx proxy container on this host that receives web traffic and sends each hostname to the container you choose.
          </p>
          <div className="grid gap-4 sm:grid-cols-2">
            <Field label="HTTP port" hint="Let's Encrypt connects on port 80, so use 80 for certificates.">
              <input className={inputClass} inputMode="numeric" value={httpPort} onChange={(e) => setHttpPort(e.target.value.replace(/\D/g, ""))} />
            </Field>
            <Field label="HTTPS port">
              <input className={inputClass} inputMode="numeric" value={httpsPort} onChange={(e) => setHttpsPort(e.target.value.replace(/\D/g, ""))} />
            </Field>
          </div>
          <Field label="Email for Let's Encrypt" optional hint="Let's Encrypt writes to it if a certificate is about to expire.">
            <input className={inputClass} type="email" value={email} onChange={(e) => setEmail(e.target.value)} placeholder="you@example.com" />
          </Field>
          <Toggle checked={staging} onChange={setStaging} label="Use Let's Encrypt's test environment" hint="For trying things out: no rate limits, but browsers don't trust the certificates." />
          {error && <Notice tone="error">{error}</Notice>}
          <div>
            <Button type="submit" busy={busy}>
              Turn on
            </Button>
          </div>
        </form>
      </Panel>
      <Panel title="Before you start">
        <ul className="text-[14px] text-slate flex flex-col gap-2 list-disc pl-5">
          <li>Point each domain&apos;s DNS (an A or AAAA record) at this server.</li>
          <li>Open ports 80 and 443 in the firewall or router.</li>
          <li>Stop anything else on this host that already uses those ports.</li>
        </ul>
      </Panel>
    </div>
  );
}

function Enabled({ hostId, config, onChange }: { hostId: string; config: IngressConfig; onChange: () => void }) {
  const [tab, setTab] = useState<Tab>("routes");
  const { data: status } = usePoll<NginxStatus>(`/api/ingress/hosts/${hostId}/nginx/status`, 10000);
  const { data: routes, refresh: refreshRoutes } = usePoll<IngressRoute[]>(`/api/ingress/hosts/${hostId}/routes`, 15000);
  const { data: certs, refresh: refreshCerts } = usePoll<Certificate[]>(`/api/ingress/hosts/${hostId}/certificates`, 8000);
  const [editing, setEditing] = useState<IngressRoute | "new" | null>(null);
  const [settings, setSettings] = useState(false);
  const toast = useToast();
  const confirm = useConfirm();
  const managedConfig = useManaged().onHost("ingress", hostId);

  const reapply = async () => {
    try {
      await api.post(`/api/ingress/hosts/${hostId}/nginx/reload`);
      toast("Configuration applied");
      onChange();
    } catch (e) {
      toast((e as Error).message, "error");
    }
  };

  return (
    <>
      <div className="grid gap-3 sm:grid-cols-3 mb-6">
        <Panel>
          <div className="text-[13px] text-slate">Proxy</div>
          <div className="flex items-center gap-2 mt-1">
            <span className={`size-2 rounded-full ${status?.containerStatus === "running" ? "bg-marigold" : "bg-danger"}`} aria-hidden />
            <span className="font-display text-[1.2rem]">{status?.containerStatus === "running" ? "Running" : status?.containerStatus ?? "…"}</span>
          </div>
          <div className="text-[12.5px] text-slate mt-1">
            Ports {config.httpPort} and {config.httpsPort}
            {managedConfig ? (
              <span className="ml-2 align-middle">
                <ManagedBadge entry={`ingress`} />
              </span>
            ) : (
              <button className="ml-2 underline underline-offset-2 hover:text-ink" onClick={() => setSettings(true)}>
                Change
              </button>
            )}
          </div>
        </Panel>
        <Panel>
          <div className="text-[13px] text-slate">Routes</div>
          <div className="font-display text-[1.5rem]">{status?.activeRoutes ?? "…"}</div>
          <div className="text-[12.5px] text-slate">active</div>
        </Panel>
        <Panel>
          <div className="text-[13px] text-slate">Certificates</div>
          <div className="font-display text-[1.5rem]">{status?.activeCertificates ?? "…"}</div>
          <div className="text-[12.5px] text-slate">{config.staging ? "Let's Encrypt test environment" : "in use"}</div>
        </Panel>
      </div>
      {(status?.lastError || config.lastError) && (
        <div className="mb-6">
          <Notice tone="error" title="The proxy reported a problem">
            {status?.lastError || config.lastError} <button className="underline underline-offset-2 ml-1" onClick={reapply}>Try again</button>
          </Notice>
        </div>
      )}
      <Tabs<Tab>
        value={tab}
        onChange={setTab}
        tabs={[
          { value: "routes", label: "Routes", count: routes?.length },
          { value: "certificates", label: "Certificates", count: certs?.length },
          { value: "config", label: "nginx config" },
          { value: "activity", label: "Activity" },
        ]}
      />
      {tab === "routes" && (
        <>
          <div className="flex justify-between items-center gap-3 mb-4">
            <p className="text-[14px] text-slate">Each route sends one hostname (and optional path) to a container, an address, or another website.</p>
            <div className="flex items-center gap-2">
              {!managedConfig && (
                <Button tone="quiet" onClick={() => setSettings(true)}>
                  Settings
                </Button>
              )}
              <Button onClick={() => setEditing("new")}>Add route</Button>
              <Menu
                items={[
                  { label: "Re-apply configuration", onClick: reapply },
                  !managedConfig && {
                    label: "Turn ingress off",
                    danger: true,
                    onClick: async () => {
                      const ok = await confirm({
                        title: "Turn ingress off?",
                        body: "The proxy container is removed and every route stops answering. Routes and certificates are kept, so turning it back on restores them.",
                        confirm: "Turn off",
                        danger: true,
                      });
                      if (!ok) return;
                      try {
                        await api.post(`/api/ingress/hosts/${hostId}/disable`);
                        onChange();
                      } catch (e) {
                        toast((e as Error).message, "error");
                      }
                    },
                  },
                ]}
              />
            </div>
          </div>
          <Routes routes={routes} certs={certs ?? []} onEdit={setEditing} onChange={() => (refreshRoutes(), refreshCerts())} />
        </>
      )}
      {tab === "certificates" && <Certificates certs={certs} onChange={refreshCerts} />}
      {tab === "config" && <NginxConfig hostId={hostId} />}
      {tab === "activity" && <IngressActivity hostId={hostId} />}
      {editing && (
        <RouteDialog
          hostId={hostId}
          route={editing === "new" ? null : editing}
          onClose={() => setEditing(null)}
          onSaved={() => {
            setEditing(null);
            refreshRoutes();
            refreshCerts();
            onChange();
          }}
        />
      )}
      {settings && <SettingsDialog hostId={hostId} config={config} onClose={() => setSettings(false)} onSaved={() => (setSettings(false), onChange())} />}
    </>
  );
}

function Routes({ routes, certs, onEdit, onChange }: { routes: IngressRoute[] | null; certs: Certificate[]; onEdit: (r: IngressRoute) => void; onChange: () => void }) {
  const toast = useToast();
  const confirm = useConfirm();
  const { find } = useManaged();
  if (!routes) return <Spinner />;
  if (!routes.length) return <Empty title="No routes yet">Add one to publish a container on a hostname such as app.example.com.</Empty>;
  const certFor = (h: string) => certs.find((c) => c.hostname === h);
  return (
    <ul className="rounded-2xl border border-line bg-paper divide-y divide-line">
      {routes.map((r) => {
        const cert = certFor(r.hostname);
        const https = r.tlsMode !== "NONE" && cert?.status === "ACTIVE";
        const url = `${https ? "https" : "http"}://${r.hostname}${r.pathPrefix === "/" ? "" : r.pathPrefix}`;
        const managed = find("ingress.routes", r.id);
        return (
          <li key={r.id} className={`flex items-center gap-3 px-4 py-3 ${r.enabled ? "" : "opacity-60"}`}>
            <div className="flex-1 min-w-0 grid gap-x-6 gap-y-1 sm:grid-cols-[minmax(0,1.4fr)_minmax(0,1fr)_minmax(0,1fr)] items-center">
              <div className="min-w-0">
                <a href={url} target="_blank" rel="noreferrer" className="block truncate font-medium hover:underline underline-offset-2">
                  {r.hostname}
                  {r.pathPrefix !== "/" && <span className="text-slate">{r.pathPrefix}</span>}
                </a>
                <div className="text-[12.5px] text-slate flex gap-2">
                  {!r.enabled && <span>paused</span>}
                  {r.authEnabled && <span>sign-in required</span>}
                  {r.forceHttpsRedirect && <span>HTTPS only</span>}
                  {r.rolloutId && (
                    <Link href={`/rollouts/view/?id=${r.rolloutId}`} className="underline underline-offset-2">
                      managed by a rollout
                    </Link>
                  )}
                  {managed && <ManagedBadge entry={managed.key && `ingress.${managed.key.replace("/", ".routes.")}`} />}
                </div>
              </div>
              <div className="text-[13px] truncate" title={routeTarget(r)}>
                {r.targetType === "REDIRECT" ? (
                  <>
                    <span className="text-slate">redirects to </span>
                    {r.redirectKeepPath ? (r.redirectUrl ?? "").replace(/\/+$/, "") : r.redirectUrl}
                    {r.redirectKeepPath && <span className="text-slate">/…</span>}
                  </>
                ) : (
                  <>→ {routeTarget(r)}</>
                )}
              </div>
              <div className="text-[13px]">
                {r.tlsMode === "NONE" ? (
                  <span className="text-slate">HTTP only</span>
                ) : cert?.status === "ACTIVE" ? (
                  <Badge tone="ok">HTTPS</Badge>
                ) : cert?.status === "ERROR" ? (
                  <Badge tone="danger">Certificate failed</Badge>
                ) : (
                  <Badge tone="live">Getting certificate</Badge>
                )}
              </div>
            </div>
            {!managed && (
            <Menu
              items={[
                { label: "Edit", onClick: () => onEdit(r) },
                {
                  label: r.enabled ? "Pause" : "Resume",
                  onClick: async () => {
                    try {
                      await api.put(`/api/ingress/routes/${r.id}`, { enabled: !r.enabled });
                      onChange();
                    } catch (e) {
                      toast((e as Error).message, "error");
                    }
                  },
                },
                !r.rolloutId && {
                  label: "Delete",
                  danger: true,
                  onClick: async () => {
                    if (!(await confirm({ title: `Delete the route for ${r.hostname}${r.pathPrefix === "/" ? "" : r.pathPrefix}?`, confirm: "Delete", danger: true }))) return;
                    try {
                      await api.del(`/api/ingress/routes/${r.id}?disconnectFromNetwork=true`);
                      onChange();
                    } catch (e) {
                      toast((e as Error).message, "error");
                    }
                  },
                },
              ]}
            />
            )}
          </li>
        );
      })}
    </ul>
  );
}

type SignInUser = { key: string; username: string; password: string; existing: boolean };

type Kind = IngressRoute["targetType"];

const OTHER = "other";

/** Where a route sends visitors, as shown in the list. */
function routeTarget(r: IngressRoute) {
  if (r.targetType === "REDIRECT") return r.redirectUrl ?? "";
  if (r.targetType === "ADDRESS") return `${r.targetHost}:${r.targetPort}`;
  if (r.canaryContainerName && r.canaryWeight > 0) {
    return r.canaryWeight >= 100
      ? `${r.canaryContainerName}:${r.targetPort}`
      : `${r.targetContainerName} (${100 - r.canaryWeight}%) + ${r.canaryContainerName} (${r.canaryWeight}%) :${r.targetPort}`;
  }
  return `${r.targetContainerName}:${r.targetPort}`;
}

function RouteDialog({ hostId, route, onClose, onSaved }: { hostId: string; route: IngressRoute | null; onClose: () => void; onSaved: () => void }) {
  const { session } = useSession();
  const { data: containers } = usePoll<Container[]>(`/api/docker/hosts/${hostId}/containers?all=true`);
  const [kind, setKind] = useState<Kind>(route?.targetType ?? "CONTAINER");
  const [hostname, setHostname] = useState(route?.hostname ?? "");
  const [path, setPath] = useState(route?.pathPrefix ?? "/");
  const [container, setContainer] = useState(route?.targetType === "CONTAINER" || !route?.targetType ? route?.targetContainerName ?? "" : "");
  const [address, setAddress] = useState(route?.targetHost ?? "");
  const [addressPort, setAddressPort] = useState(route?.targetType === "ADDRESS" ? String(route.targetPort) : "");
  const [redirectUrl, setRedirectUrl] = useState(route?.redirectUrl ?? "");
  const [keepPath, setKeepPath] = useState(route?.redirectKeepPath ?? false);
  const [permanent, setPermanent] = useState(route?.redirectPermanent ?? false);
  const [port, setPort] = useState(route && route.targetType === "CONTAINER" ? String(route.targetPort) : "");
  const [customPort, setCustomPort] = useState(false);
  const [tls, setTls] = useState<IngressRoute["tlsMode"]>(route?.tlsMode ?? "LETS_ENCRYPT");
  const [redirect, setRedirect] = useState(route?.forceHttpsRedirect ?? true);
  const [backendHttps, setBackendHttps] = useState(route?.protocol === "HTTPS");
  const [auth, setAuth] = useState(route?.authEnabled ?? false);
  const [users, setUsers] = useState<SignInUser[]>(() => {
    const names = route?.authUsernames?.length ? route.authUsernames : route?.authUsername ? [route.authUsername] : [];
    return names.length
      ? names.map((n, i) => ({ key: `u${i}`, username: n, password: "", existing: true }))
      : [{ key: "u0", username: "", password: "", existing: false }];
  });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const candidates = useMemo(
    () => (containers ?? []).filter((c) => !containerName(c).startsWith("managed-ingress-")).sort((a, b) => containerName(a).localeCompare(containerName(b))),
    [containers],
  );
  const containerOptions = useMemo(() => candidates.map((c) => ({ value: containerName(c), label: containerName(c), hint: c.State !== "running" ? "stopped" : undefined })), [candidates]);
  const selected = kind === "CONTAINER" ? candidates.find((c) => containerName(c) === container) : undefined;
  // The ports the image declares (EXPOSE), which stopped containers don't list, plus any it has running.
  const { data: inspect } = usePoll<ContainerInspect>(selected ? `/api/docker/hosts/${hostId}/containers/${selected.Id}` : null);
  const ports = useMemo(() => {
    const found = new Set<number>();
    for (const p of selected?.Ports ?? []) if (p.Type === "tcp" && p.PrivatePort) found.add(p.PrivatePort);
    if (inspect && inspect.Id === selected?.Id) {
      for (const key of Object.keys(inspect.Config.ExposedPorts ?? {})) {
        const [n, proto] = key.split("/");
        if ((proto ?? "tcp") === "tcp" && Number(n)) found.add(Number(n));
      }
    }
    return [...found].sort((a, b) => a - b);
  }, [selected, inspect]);
  const loadingPorts = !!selected && (!inspect || inspect.Id !== selected.Id);
  // Free text only when the container declares no port, or the user asks for another one.
  const typing = customPort || (!loadingPorts && !!selected && ports.length === 0) || (!!port && ports.length > 0 && !ports.includes(Number(port)));
  const portValue = typing ? OTHER : port;

  const pickContainer = (v: string) => {
    setContainer(v);
    setCustomPort(false);
    setPort("");
  };

  const updateUser = (key: string, patch: Partial<SignInUser>) => setUsers((list) => list.map((u) => (u.key === key ? { ...u, ...patch } : u)));
  const usersValid =
    users.length > 0 &&
    users.every((u) => u.username.trim() && (u.existing ? u.password.length === 0 || u.password.length >= 8 : u.password.length >= 8)) &&
    new Set(users.map((u) => u.username.trim())).size === users.length;

  // Pick the first known port once they are loaded, so a fresh route needs no typing at all.
  const effectivePort = port || (!typing && ports.length ? String(ports[0]) : "");

  const addressPortNumber = Number(addressPort);
  const targetReady =
    kind === "CONTAINER"
      ? !!container && !!effectivePort
      : kind === "ADDRESS"
        ? !!address.trim() && addressPortNumber >= 1 && addressPortNumber <= 65535
        : !!redirectUrl.trim();
  // Address routes can reach any machine the server can, so only administrators get them.
  const kinds: { value: Kind; label: string; hint?: string }[] = [
    { value: "CONTAINER", label: "A container" },
    ...(session?.admin || route?.targetType === "ADDRESS" ? [{ value: "ADDRESS" as Kind, label: "An address", hint: "A host and port you type in" }] : []),
    { value: "REDIRECT", label: "Another website", hint: "Redirect visitors to a web address" },
  ];

  const save = async () => {
    setBusy(true);
    setError(null);
    const signIn = auth && kind !== "REDIRECT";
    const body = {
      targetType: kind,
      containerName: kind === "CONTAINER" ? container : null,
      targetHost: kind === "ADDRESS" ? address.trim() : null,
      redirectUrl: kind === "REDIRECT" ? redirectUrl.trim() : null,
      redirectPermanent: kind === "REDIRECT" && permanent,
      redirectKeepPath: kind === "REDIRECT" && keepPath,
      hostname: hostname.trim().toLowerCase(),
      pathPrefix: path.trim() || "/",
      targetPort: kind === "CONTAINER" ? Number(effectivePort) : kind === "ADDRESS" ? Number(addressPort) : 0,
      protocol: backendHttps && kind !== "REDIRECT" ? "HTTPS" : "HTTP",
      tlsMode: tls,
      forceHttpsRedirect: tls !== "NONE" && redirect,
      authEnabled: signIn,
      authType: signIn ? "BASIC" : null,
      authUsers: signIn ? users.map((u) => ({ username: u.username.trim(), password: u.password })) : null,
    };
    try {
      if (route) await api.put(`/api/ingress/routes/${route.id}`, { route: body });
      else await api.post(`/api/ingress/hosts/${hostId}/routes`, body);
      onSaved();
    } catch (e) {
      setError((e as Error).message);
      setBusy(false);
    }
  };

  return (
    <Modal
      open
      wide
      onClose={onClose}
      title={route ? `Edit ${route.hostname}` : "Add a route"}
      footer={
        <>
          <Button tone="quiet" onClick={onClose}>
            Cancel
          </Button>
          <Button onClick={save} busy={busy} disabled={!hostname || !targetReady || (auth && kind !== "REDIRECT" && !usersValid)}>
            {route ? "Save" : "Add route"}
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-4">
        {route?.rolloutId ? (
          <Notice>
            A rollout decides which container gets this route&apos;s visitors (now {routeTarget(route)}). Change the hostname, HTTPS and sign-in here; release new versions on the{" "}
            <Link href={`/rollouts/view/?id=${route.rolloutId}`} className="underline underline-offset-2">
              rollout&apos;s page
            </Link>
            .
          </Notice>
        ) : (
          <div>
            <div className="text-[13px] font-medium mb-1.5">Send visitors to</div>
            <Segmented<Kind> label="Send visitors to" value={kind} onChange={setKind} options={kinds} />
          </div>
        )}
        <div className="grid gap-4 sm:grid-cols-[1fr_10rem]">
          <Field label="Hostname" hint="Its DNS record must point at this server.">
            <input className={inputClass} value={hostname} onChange={(e) => setHostname(e.target.value)} placeholder="app.example.com" autoCapitalize="off" spellCheck={false} />
          </Field>
          <Field label="Path" hint="/ for the whole site">
            <input className={inputClass} value={path} onChange={(e) => setPath(e.target.value)} placeholder="/" spellCheck={false} />
          </Field>
        </div>
        {kind === "CONTAINER" && !route?.rolloutId && (
          <div className="grid gap-4 sm:grid-cols-[1fr_12rem] items-start">
            <Field label="Container" hint={`Type to search${candidates.length ? ` ${candidates.length} containers` : ""}. Routes follow the container's name, so redeploys keep working.`}>
              <Combobox
                value={container}
                onChange={pickContainer}
                options={containerOptions}
                placeholder={containers ? "Choose or search for a container" : "Loading containers"}
                empty="No container has that name"
              />
            </Field>
            <Field
              label="Port"
              hint={
                !selected
                  ? "Choose a container first"
                  : loadingPorts
                    ? "Looking up its ports"
                    : ports.length === 0
                      ? "It doesn't declare a port. Enter the one the app listens on."
                      : typing
                        ? "The port the app listens on inside the container"
                        : `From the image${ports.length > 1 ? `, ${ports.length} ports` : ""}`
              }
            >
              {!selected || loadingPorts || (ports.length > 0 && !typing) ? (
                <Select
                  value={!selected || loadingPorts ? "" : portValue || String(ports[0] ?? "")}
                  disabled={!selected || loadingPorts}
                  onChange={(v) => {
                    if (v === OTHER) {
                      setCustomPort(true);
                      setPort("");
                    } else setPort(v);
                  }}
                >
                  {(!selected || loadingPorts) && <option value="">{loadingPorts ? "Loading" : "Port"}</option>}
                  {ports.map((p) => (
                    <option key={p} value={String(p)}>
                      {p}
                      {PORT_NAMES[p] ? ` (${PORT_NAMES[p]})` : ""}
                    </option>
                  ))}
                  {selected && !loadingPorts && <option value={OTHER}>Another port</option>}
                </Select>
              ) : (
                <div className="flex gap-2">
                  <input className={inputClass} inputMode="numeric" value={port} onChange={(e) => setPort(e.target.value.replace(/\D/g, "").slice(0, 5))} placeholder="8080" autoFocus={customPort} />
                  {ports.length > 0 && (
                    <Button
                      tone="ghost"
                      size="sm"
                      className="h-10"
                      onClick={() => {
                        setCustomPort(false);
                        setPort(String(ports[0]));
                      }}
                    >
                      List
                    </Button>
                  )}
                </div>
              )}
            </Field>
          </div>
        )}
        {kind === "ADDRESS" && (
          <div className="grid gap-4 sm:grid-cols-[1fr_12rem]">
            <Field label="Address" hint="An IP address or hostname the proxy can reach, such as another machine on your network.">
              <input className={inputClass} value={address} onChange={(e) => setAddress(e.target.value)} placeholder="192.168.1.20" autoCapitalize="off" spellCheck={false} />
            </Field>
            <Field label="Port">
              <input className={inputClass} inputMode="numeric" value={addressPort} onChange={(e) => setAddressPort(e.target.value.replace(/\D/g, "").slice(0, 5))} placeholder="8080" />
            </Field>
          </div>
        )}
        {kind === "REDIRECT" && (
          <>
            <Field label="Redirect to" hint="Visitors to this hostname are sent to this address.">
              <input className={inputClass} value={redirectUrl} onChange={(e) => setRedirectUrl(e.target.value)} placeholder="https://example.org" autoCapitalize="off" spellCheck={false} />
            </Field>
            <Toggle
              checked={keepPath}
              onChange={setKeepPath}
              label="Keep the rest of the address"
              hint={`${hostname.trim() || "this hostname"}/page?x=1 goes to ${(redirectUrl.trim() || "the new address").replace(/\/+$/, "")}/page?x=1. Off: every page goes to the address itself.`}
            />
            <Toggle
              checked={permanent}
              onChange={setPermanent}
              label="Permanent redirect (301)"
              hint="Browsers and search engines remember it, so a later change reaches visitors slowly. Off sends a temporary redirect (302)."
            />
          </>
        )}
        <Field label="HTTPS">
          <Select value={tls} onChange={(v) => setTls(v as IngressRoute["tlsMode"])}>
            <option value="LETS_ENCRYPT">Free certificate from Let&apos;s Encrypt</option>
            <option value="CUSTOM_CERT">Upload my own certificate</option>
            <option value="NONE">No HTTPS</option>
          </Select>
        </Field>
        {tls !== "NONE" && <Toggle checked={redirect} onChange={setRedirect} label="Send visitors to HTTPS" hint="Starts once the certificate is ready; until then the site answers over HTTP." />}
        {kind !== "REDIRECT" && (
          <Toggle
            checked={auth}
            onChange={setAuth}
            label="Require sign-in"
            hint="Visitors see a sign-in page and stay signed in for 7 days. Scripts and API clients can send the same username and password as an Authorization: Basic header instead."
          />
        )}
        {auth && kind !== "REDIRECT" && (
          <div className="rounded-xl border border-line p-3 sm:p-4 flex flex-col gap-3">
            <div className="text-[13px] text-slate">People who can sign in</div>
            {users.map((u) => (
              <div key={u.key} className="grid gap-2 sm:grid-cols-[1fr_1fr_auto] items-start">
                <input
                  className={inputClass}
                  aria-label="Username"
                  placeholder="Username"
                  value={u.username}
                  readOnly={u.existing}
                  onChange={(e) => updateUser(u.key, { username: e.target.value })}
                  autoComplete="off"
                  autoCapitalize="off"
                  spellCheck={false}
                />
                <input
                  className={inputClass}
                  aria-label={`Password for ${u.username || "new user"}`}
                  type="password"
                  placeholder={u.existing ? "Unchanged" : "Password, 8+ characters"}
                  value={u.password}
                  onChange={(e) => updateUser(u.key, { password: e.target.value })}
                  autoComplete="new-password"
                />
                <IconButton label={`Remove ${u.username || "this user"}`} className="mt-1" disabled={users.length === 1} onClick={() => setUsers((list) => list.filter((x) => x.key !== u.key))}>
                  <X size={16} />
                </IconButton>
              </div>
            ))}
            <div>
              <Button tone="ghost" size="sm" onClick={() => setUsers((list) => [...list, { key: `u${Date.now()}`, username: "", password: "", existing: false }])}>
                <Plus size={14} aria-hidden /> Add a person
              </Button>
            </div>
            {route?.authEnabled && <p className="text-[12.5px] text-slate">Changing someone&apos;s password signs everyone out of this site.</p>}
          </div>
        )}
        {kind !== "REDIRECT" && (
          <Toggle checked={backendHttps} onChange={setBackendHttps} label={kind === "ADDRESS" ? "The address speaks HTTPS" : "The container itself speaks HTTPS"} hint="Rare. Leave off for most apps." />
        )}
        {error && <Notice tone="error">{error}</Notice>}
      </div>
    </Modal>
  );
}

/** Familiar names for common ports, shown next to the number. */
const PORT_NAMES: Record<number, string> = {
  80: "HTTP",
  443: "HTTPS",
  3000: "web app",
  5000: "web app",
  8000: "web app",
  8080: "HTTP",
  8443: "HTTPS",
  9000: "web app",
  5432: "PostgreSQL",
  3306: "MySQL",
  6379: "Redis",
  27017: "MongoDB",
};

function Certificates({ certs, onChange }: { certs: Certificate[] | null; onChange: () => void }) {
  const toast = useToast();
  const confirm = useConfirm();
  const [uploading, setUploading] = useState<Certificate | null>(null);
  const { find } = useManaged();
  if (!certs) return <Spinner />;
  if (!certs.length) return <Empty title="No certificates">Routes with HTTPS get one automatically.</Empty>;
  const call = async (path: string, msg: string, body?: unknown) => {
    try {
      if (body !== undefined) await api.patch(path, body);
      else await api.post(path);
      toast(msg);
      onChange();
    } catch (e) {
      toast((e as Error).message, "error");
    }
  };
  return (
    <>
      <ul className="rounded-2xl border border-line bg-paper divide-y divide-line">
        {certs.map((c) => {
          const managed = find("ingress.certificates", c.id);
          return (
          <li key={c.id} className="flex items-start gap-3 px-4 py-3">
            <div className="flex-1 min-w-0">
              <div className="flex flex-wrap items-center gap-2">
                <span className="font-medium">{c.hostname}</span>
                {c.status === "ACTIVE" ? (
                  <Badge tone={c.daysUntilExpiry < 14 ? "warn" : "ok"}>valid for {c.daysUntilExpiry} days</Badge>
                ) : c.status === "ERROR" ? (
                  <Badge tone="danger">failed</Badge>
                ) : (
                  <Badge tone="live">{c.status === "RENEWAL_PENDING" ? "renewing" : "pending"}</Badge>
                )}
                <Badge>{c.source === "LETS_ENCRYPT" ? "Let's Encrypt" : "uploaded"}</Badge>
                {managed && <ManagedBadge entry={managed.key && `ingress.${managed.key.replace("/", ".certificates.")}`} />}
              </div>
              {c.statusMessage && <p className={`text-[13px] mt-1 break-words ${c.status === "ERROR" ? "text-danger" : "text-slate"}`}>{c.statusMessage}</p>}
              {c.expiresAt && <p className="text-[12.5px] text-slate mt-0.5">Expires {dateTime(c.expiresAt)}</p>}
            </div>
            {!managed && (
            <Menu
              items={[
                c.source === "LETS_ENCRYPT" && c.status === "ERROR" && { label: "Try again", onClick: () => call(`/api/ingress/certificates/${c.id}/retry`, "Requesting a certificate") },
                c.source === "LETS_ENCRYPT" && c.status === "ACTIVE" && { label: "Renew now", onClick: () => call(`/api/ingress/certificates/${c.id}/renew`, "Renewing") },
                c.source === "LETS_ENCRYPT" && !managed && {
                  label: c.autoRenew ? "Turn off automatic renewal" : "Turn on automatic renewal",
                  onClick: () => call(`/api/ingress/certificates/${c.id}/auto-renew`, "Saved", { autoRenew: !c.autoRenew }),
                },
                c.source !== "LETS_ENCRYPT" && !managed && { label: "Upload certificate", onClick: () => setUploading(c) },
                !managed && {
                  label: "Delete",
                  danger: true,
                  onClick: async () => {
                    if (!(await confirm({ title: `Delete the certificate for ${c.hostname}?`, body: "The site falls back to HTTP until a new certificate is in place.", confirm: "Delete", danger: true }))) return;
                    try {
                      await api.del(`/api/ingress/certificates/${c.id}`);
                      onChange();
                    } catch (e) {
                      toast((e as Error).message, "error");
                    }
                  },
                },
              ]}
            />
            )}
          </li>
          );
        })}
      </ul>
      {uploading && <UploadDialog cert={uploading} onClose={() => setUploading(null)} onSaved={() => (setUploading(null), onChange())} />}
    </>
  );
}

function UploadDialog({ cert, onClose, onSaved }: { cert: Certificate; onClose: () => void; onSaved: () => void }) {
  const [pem, setPem] = useState("");
  const [key, setKey] = useState("");
  const [chain, setChain] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  return (
    <Modal
      open
      wide
      onClose={onClose}
      title={`Certificate for ${cert.hostname}`}
      footer={
        <>
          <Button tone="quiet" onClick={onClose}>
            Cancel
          </Button>
          <Button
            busy={busy}
            disabled={!pem.includes("BEGIN CERTIFICATE") || !key.includes("PRIVATE KEY")}
            onClick={async () => {
              setBusy(true);
              setError(null);
              try {
                await api.post(`/api/ingress/certificates/${cert.id}/upload`, { certificatePem: pem, privateKeyPem: key, chainPem: chain || null });
                onSaved();
              } catch (e) {
                setError((e as Error).message);
                setBusy(false);
              }
            }}
          >
            Upload
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-4">
        <Field label="Certificate (PEM)">
          <textarea className={`${inputClass} code`} rows={6} value={pem} onChange={(e) => setPem(e.target.value)} placeholder="-----BEGIN CERTIFICATE-----" />
        </Field>
        <Field label="Private key (PEM)" hint="Stored encrypted. It must belong to the certificate.">
          <textarea className={`${inputClass} code`} rows={5} value={key} onChange={(e) => setKey(e.target.value)} placeholder="-----BEGIN PRIVATE KEY-----" />
        </Field>
        <Field label="Intermediate certificates" optional>
          <textarea className={`${inputClass} code`} rows={4} value={chain} onChange={(e) => setChain(e.target.value)} />
        </Field>
        {error && <Notice tone="error">{error}</Notice>}
      </div>
    </Modal>
  );
}

function SettingsDialog({ hostId, config, onClose, onSaved }: { hostId: string; config: IngressConfig; onClose: () => void; onSaved: () => void }) {
  const [httpPort, setHttpPort] = useState(String(config.httpPort || 80));
  const [httpsPort, setHttpsPort] = useState(String(config.httpsPort || 443));
  const [email, setEmail] = useState(config.acmeEmail ?? "");
  const [staging, setStaging] = useState(config.staging);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const http = Number(httpPort);
  const https = Number(httpsPort);
  const portsChanged = http !== config.httpPort || https !== config.httpsPort;
  const portsValid = http >= 1 && http <= 65535 && https >= 1 && https <= 65535 && http !== https;
  return (
    <Modal
      open
      onClose={onClose}
      title="Ingress settings"
      footer={
        <>
          <Button tone="quiet" onClick={onClose}>
            Cancel
          </Button>
          <Button
            busy={busy}
            disabled={!portsValid}
            onClick={async () => {
              setBusy(true);
              setError(null);
              try {
                await api.patch(`/api/ingress/hosts/${hostId}/settings`, { acmeEmail: email, useStaging: staging, httpPort: http, httpsPort: https });
                onSaved();
              } catch (e) {
                setError((e as Error).message);
                setBusy(false);
              }
            }}
          >
            {busy && portsChanged ? "Moving the proxy" : "Save"}
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-4">
        <div className="text-[13px] font-medium">Ports on this host</div>
        <div className="grid grid-cols-2 gap-4 -mt-2">
          <Field label="HTTP">
            <input className={inputClass} inputMode="numeric" value={httpPort} onChange={(e) => setHttpPort(e.target.value.replace(/\D/g, ""))} />
          </Field>
          <Field label="HTTPS">
            <input className={inputClass} inputMode="numeric" value={httpsPort} onChange={(e) => setHttpsPort(e.target.value.replace(/\D/g, ""))} />
          </Field>
        </div>
        {!portsValid && <Notice tone="error">Use two different ports from 1 to 65535.</Notice>}
        {portsValid && portsChanged && (
          <Notice tone="warn">
            The proxy is restarted on the new ports, so routes are down for a few seconds. If a port is already in use, the proxy stays on the current ones.
            {http !== 80 && " Let's Encrypt checks domains on port 80: forward port 80 to the new HTTP port (on your router, for example), or certificates can't be issued or renewed."}
          </Notice>
        )}
        <div className="text-[13px] font-medium pt-2 border-t border-line">Let&apos;s Encrypt</div>
        <Field label="Email" optional hint="Let's Encrypt writes here about certificates that are about to expire.">
          <input className={inputClass} type="email" value={email} onChange={(e) => setEmail(e.target.value)} />
        </Field>
        <Toggle checked={staging} onChange={setStaging} label="Use the test environment" hint="Certificates requested from now on come from Let's Encrypt's staging service." />
        {error && <Notice tone="error">{error}</Notice>}
      </div>
    </Modal>
  );
}

function NginxConfig({ hostId }: { hostId: string }) {
  const { data } = usePoll<{ config: string | null }>(`/api/ingress/hosts/${hostId}/nginx/config`);
  return (
    <>
      <p className="text-[14px] text-slate mb-3">Generated from your routes and tested with nginx before it is applied. It is rewritten on every change, so edit routes instead.</p>
      <LogView text={data?.config ?? ""} height="h-[600px]" empty="Not generated yet." />
    </>
  );
}

function IngressActivity({ hostId }: { hostId: string }) {
  const { data } = usePoll<{ id: string; action: string; details: string; success: boolean; errorMessage: string | null; username: string | null; timestamp: number }[]>(
    `/api/ingress/hosts/${hostId}/audit`,
    15000,
  );
  if (!data) return <Spinner />;
  if (!data.length) return <Empty title="No activity yet" />;
  return (
    <ul className="rounded-2xl border border-line bg-paper divide-y divide-line">
      {data.slice(0, 100).map((a) => (
        <li key={a.id} className="px-4 py-3">
          <div className="flex flex-wrap justify-between gap-2 text-[14px]">
            <span className={a.success ? "" : "text-danger"}>{a.details}</span>
            <span className="text-[12.5px] text-slate">
              {ago(a.timestamp)}
              {a.username && ` · ${a.username}`}
            </span>
          </div>
          {a.errorMessage && <p className="text-[13px] text-danger mt-0.5 break-words">{a.errorMessage}</p>}
        </li>
      ))}
    </ul>
  );
}
