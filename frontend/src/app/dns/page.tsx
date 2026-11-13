"use client";

import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { Suspense, useState, type ChangeEvent, type ReactNode } from "react";
import { useSession } from "@/components/app";
import { DelegationBadge, HealthBadge, ServerBanner, healthTarget } from "@/components/dns";
import { ManagedBadge, ManagedNote } from "@/components/managed";
import {
  Badge,
  Button,
  Copyable,
  Empty,
  Field,
  KeyValues,
  Menu,
  Modal,
  Notice,
  PageHeader,
  Panel,
  Segmented,
  Spinner,
  Table,
  Tabs,
  Toggle,
  inputClass,
  useConfirm,
  useToast,
} from "@/components/ui";
import { api } from "@/lib/api";
import { lines, type DnsSettings, type HealthCheck, type HealthCheckView, type ZoneSummary } from "@/lib/dns";
import { ago, dateTime, plural } from "@/lib/format";
import { usePoll } from "@/lib/hooks";
import { useManaged } from "@/lib/managed";

type Tab = "zones" | "health" | "settings";

export default function DnsPage() {
  return (
    <Suspense>
      <Dns />
    </Suspense>
  );
}

function Dns() {
  const params = useSearchParams();
  const { session } = useSession();
  const [tab, setTab] = useState<Tab>(() => {
    const t = params.get("tab");
    return t === "health" || t === "settings" ? t : "zones";
  });
  const { data: settings, error, refresh } = usePoll<DnsSettings>(session?.admin ? "/api/dns/settings" : null, 10000);
  const locked = useManaged().sectionManaged("dns");

  if (!session?.admin) {
    return (
      <>
        <PageHeader title="DNS" />
        <Notice>DNS is managed by administrators.</Notice>
      </>
    );
  }

  return (
    <>
      <PageHeader
        title="DNS"
        intro="Host your domains' DNS right here. Point a domain's nameservers at this server and manage its records. Ingress routes get their records by themselves."
      >
        {locked && <ManagedBadge />}
      </PageHeader>
      {error && !settings && <Notice tone="error">{error}</Notice>}
      {!settings && !error && <Spinner />}
      {settings && (
        <>
          <Tabs
            value={tab}
            onChange={setTab}
            tabs={[
              { value: "zones", label: "Zones" },
              { value: "health", label: "Health checks" },
              { value: "settings", label: "Settings" },
            ]}
          />
          {tab === "zones" && <Zones settings={settings} onSettings={() => setTab("settings")} />}
          {tab === "health" && <HealthChecks />}
          {tab === "settings" && <SettingsTab settings={settings} locked={locked} onSaved={refresh} />}
        </>
      )}
    </>
  );
}

// --- zones ---------------------------------------------------------------------------------------

function Zones({ settings, onSettings }: { settings: DnsSettings; onSettings: () => void }) {
  const { data, error, refresh } = usePoll<ZoneSummary[]>("/api/dns/zones", 15000);
  const { isManaged } = useManaged();
  const [adding, setAdding] = useState(false);

  return (
    <>
      <ServerBanner
        settings={settings}
        action={
          <button className="underline underline-offset-2" onClick={onSettings}>
            Open Settings
          </button>
        }
      />
      <div className="flex flex-wrap items-center justify-between gap-3 mb-4">
        <p className="text-[14px] text-slate max-w-[70ch]">A zone holds the records of one domain, like a hosted zone in Route 53.</p>
        {data && data.length > 0 && <Button onClick={() => setAdding(true)}>Add zone</Button>}
      </div>
      {error && <Notice tone="error">{error}</Notice>}
      {!data && !error && <Spinner />}
      {data && data.length === 0 && (
        <Empty title="No zones yet" action={<Button onClick={() => setAdding(true)}>Add a zone</Button>}>
          Add a domain you own, such as example.com. Then set its nameservers at your registrar, and this server answers for it.
        </Empty>
      )}
      {data && data.length > 0 && (
        <Table head={["Zone", "Records", "DNSSEC", "Delegation", "Warnings", "Serial"]}>
          {data.map((z) => (
            <tr key={z.id}>
              <td>
                <div className="flex flex-wrap items-center gap-2">
                  <Link href={`/dns/zone/?id=${z.id}`} className="font-medium hover:underline underline-offset-2">
                    {z.name}
                  </Link>
                  {isManaged("dnsZones", z.id) && <ManagedBadge />}
                </div>
                {z.comment && <div className="text-[12.5px] text-slate">{z.comment}</div>}
              </td>
              <td className="tabular-nums">{z.records}</td>
              <td>{z.dnssec ? <Badge tone="ok">On</Badge> : <span className="text-[13px] text-slate">Off</span>}</td>
              <td>
                <DelegationBadge status={z.delegationStatus} />
                {z.delegationCheckedAt && <div className="text-[12px] text-slate mt-0.5">Checked {ago(z.delegationCheckedAt)}</div>}
              </td>
              <td>{z.warnings > 0 ? <Badge tone="warn">{plural(z.warnings, "warning")}</Badge> : <span className="text-[13px] text-slate">None</span>}</td>
              <td className="font-mono text-[13px] tabular-nums">{z.serial}</td>
            </tr>
          ))}
        </Table>
      )}
      {adding && <AddZone onClose={() => setAdding(false)} onAdded={refresh} />}
    </>
  );
}

function AddZone({ onClose, onAdded }: { onClose: () => void; onAdded: () => void }) {
  const router = useRouter();
  const [name, setName] = useState("");
  const [comment, setComment] = useState("");
  const [dnssec, setDnssec] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const clean = name.trim().toLowerCase().replace(/\.$/, "");
  const valid = /^([a-z0-9]([a-z0-9-]*[a-z0-9])?\.)+[a-z0-9]([a-z0-9-]*[a-z0-9])?$/.test(clean);

  const add = async () => {
    setBusy(true);
    setError(null);
    try {
      const z = await api.post<{ id: string }>("/api/dns/zones", { name: clean, comment: comment.trim() || undefined, dnssec });
      onAdded();
      router.push(`/dns/zone/?id=${z.id}&tab=setup`);
    } catch (e) {
      setError((e as Error).message);
      setBusy(false);
    }
  };

  return (
    <Modal
      open
      onClose={onClose}
      title="Add a zone"
      footer={
        <>
          <Button tone="quiet" onClick={onClose}>
            Cancel
          </Button>
          <Button busy={busy} disabled={!valid} onClick={add}>
            Add zone
          </Button>
        </>
      }
    >
      <form
        className="flex flex-col gap-4"
        onSubmit={(e) => {
          e.preventDefault();
          if (valid && !busy) add();
        }}
      >
        <Field label="Domain" hint="The domain you registered, without www." error={name.trim() && !valid ? "A domain name, such as example.com" : null}>
          <input
            className={`${inputClass} font-mono text-[13px]`}
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder="example.com"
            autoFocus
            autoCapitalize="off"
            spellCheck={false}
          />
        </Field>
        <Field label="Comment" optional>
          <input className={inputClass} value={comment} onChange={(e) => setComment(e.target.value)} />
        </Field>
        <Toggle
          checked={dnssec}
          onChange={setDnssec}
          label="Sign with DNSSEC"
          hint="Lets resolvers check that answers really come from you. You give your registrar a DS record afterwards. You can turn it on later too."
        />
        {error && <Notice tone="error">{error}</Notice>}
        <button type="submit" hidden />
      </form>
    </Modal>
  );
}

// --- health checks -------------------------------------------------------------------------------

function HealthChecks() {
  const { data, error, refresh } = usePoll<HealthCheckView[]>("/api/dns/health-checks", 10000);
  const { find } = useManaged();
  const confirm = useConfirm();
  const toast = useToast();
  const [editing, setEditing] = useState<HealthCheck | "new" | null>(null);
  const [running, setRunning] = useState<string | null>(null);

  const runNow = async (c: HealthCheck) => {
    setRunning(c.id);
    try {
      const r = await api.post<{ ok: boolean; detail: string }>(`/api/dns/health-checks/${c.id}/run`);
      toast(`${c.name}: ${r.detail || (r.ok ? "healthy" : "failed")}`, r.ok ? "ok" : "error");
      refresh();
    } catch (e) {
      toast((e as Error).message, "error");
    } finally {
      setRunning(null);
    }
  };

  return (
    <>
      <div className="flex flex-wrap items-center justify-between gap-3 mb-4">
        <p className="text-[14px] text-slate max-w-[70ch]">
          Health checks watch a server. Record sets with a health check stop being answered while it fails, so visitors go to the servers that work.
        </p>
        {data && data.length > 0 && <Button onClick={() => setEditing("new")}>Add health check</Button>}
      </div>
      {error && <Notice tone="error">{error}</Notice>}
      {!data && !error && <Spinner />}
      {data && data.length === 0 && (
        <Empty title="No health checks yet" action={<Button onClick={() => setEditing("new")}>Add a health check</Button>}>
          Use them with weighted or failover records: a failover record set needs one on its primary.
        </Empty>
      )}
      {data && data.length > 0 && (
        <Table head={["Health check", "Status", "Last result", "Last checked", "Used by", ""]}>
          {data.map(({ check: c, usedBy }) => {
            const managed = find("dnsHealthChecks", c.id);
            return (
              <tr key={c.id}>
                <td>
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="font-medium">{c.name}</span>
                    {managed && <ManagedBadge entry={managed.key} />}
                  </div>
                  <div className="font-mono text-[12.5px] text-slate break-all">{healthTarget(c)}</div>
                </td>
                <td>
                  <HealthBadge check={c} />
                </td>
                <td className="text-[13px] max-w-[22rem]">
                  <span className="break-words">{c.lastResult ?? <span className="text-slate">Not checked yet</span>}</span>
                  {c.lastChangedAt && <span className="block text-slate">Since {ago(c.lastChangedAt)}</span>}
                </td>
                <td className="text-[13px] text-slate whitespace-nowrap" title={dateTime(c.lastCheckedAt)}>
                  {ago(c.lastCheckedAt)}
                </td>
                <td className="text-[13px]">
                  {usedBy.length === 0 ? (
                    <span className="text-slate">Nothing</span>
                  ) : (
                    <ul>
                      {usedBy.map((u) => (
                        <li key={u} className="font-mono text-[12.5px] break-all">
                          {u}
                        </li>
                      ))}
                    </ul>
                  )}
                </td>
                <td className="text-right">
                  <div className="inline-flex items-center gap-1">
                    <Button tone="quiet" size="sm" busy={running === c.id} onClick={() => runNow(c)}>
                      Check now
                    </Button>
                    {!managed && (
                      <Menu
                        items={[
                          { label: "Edit", onClick: () => setEditing(c) },
                          {
                            label: "Delete",
                            danger: true,
                            onClick: async () => {
                              const ok = await confirm({
                                title: `Delete ${c.name}?`,
                                body: usedBy.length
                                  ? `${plural(usedBy.length, "record set")} still use it. Remove it from them first.`
                                  : "Nothing uses it, so no answers change.",
                                confirm: "Delete",
                                danger: true,
                              });
                              if (!ok) return;
                              try {
                                await api.del(`/api/dns/health-checks/${c.id}`);
                                toast(`Deleted ${c.name}`);
                                refresh();
                              } catch (e) {
                                toast((e as Error).message, "error");
                              }
                            },
                          },
                        ]}
                      />
                    )}
                  </div>
                </td>
              </tr>
            );
          })}
        </Table>
      )}
      {editing && <HealthCheckDialog check={editing === "new" ? null : editing} onClose={() => setEditing(null)} onSaved={refresh} />}
    </>
  );
}

function num(v: string) {
  return v.trim() === "" ? undefined : Number(v);
}

function HealthCheckDialog({ check, onClose, onSaved }: { check: HealthCheck | null; onClose: () => void; onSaved: () => void }) {
  const toast = useToast();
  const [name, setName] = useState(check?.name ?? "");
  const [type, setType] = useState<HealthCheck["type"]>(check?.type ?? "HTTP");
  const [address, setAddress] = useState(check?.address ?? "");
  const [port, setPort] = useState(check ? String(check.port) : "");
  const [path, setPath] = useState(check?.path ?? "/");
  const [hostHeader, setHostHeader] = useState(check?.hostHeader ?? "");
  const [every, setEvery] = useState(String(check?.intervalSeconds ?? 30));
  const [wait, setWait] = useState(String(check?.timeoutSeconds ?? 5));
  const [threshold, setThreshold] = useState(String(check?.failureThreshold ?? 3));
  const [notify, setNotify] = useState(check?.notify ?? true);
  const [enabled, setEnabled] = useState(check?.enabled ?? true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const http = type !== "TCP";
  const valid = name.trim() && address.trim() && (type !== "TCP" || port.trim());
  const digits = (set: (v: string) => void, max = 5) => (e: ChangeEvent<HTMLInputElement>) => set(e.target.value.replace(/\D/g, "").slice(0, max));

  const save = async () => {
    setBusy(true);
    setError(null);
    const body = {
      name: name.trim(),
      type,
      address: address.trim(),
      port: num(port),
      path: http ? path.trim() || "/" : undefined,
      hostHeader: http ? hostHeader.trim() : undefined,
      intervalSeconds: num(every),
      timeoutSeconds: num(wait),
      failureThreshold: num(threshold),
      notify,
      enabled,
    };
    try {
      if (check) await api.put(`/api/dns/health-checks/${check.id}`, body);
      else await api.post("/api/dns/health-checks", body);
      toast(check ? "Health check saved" : "Health check added");
      onSaved();
      onClose();
    } catch (e) {
      setError((e as Error).message);
      setBusy(false);
    }
  };

  return (
    <Modal
      open
      onClose={onClose}
      title={check ? `Edit ${check.name}` : "Add a health check"}
      footer={
        <>
          <Button tone="quiet" onClick={onClose}>
            Cancel
          </Button>
          <Button busy={busy} disabled={!valid} onClick={save}>
            {check ? "Save" : "Add health check"}
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-4">
        <Field label="Name">
          <input className={inputClass} value={name} onChange={(e) => setName(e.target.value)} placeholder="web-server-a" autoFocus />
        </Field>
        <div>
          <div className="text-[13px] font-medium mb-1.5">Check with</div>
          <Segmented<HealthCheck["type"]>
            label="Check with"
            value={type}
            onChange={setType}
            options={[
              { value: "HTTP", label: "HTTP" },
              { value: "HTTPS", label: "HTTPS" },
              { value: "TCP", label: "TCP" },
            ]}
          />
          <p className="text-[13px] text-slate mt-1.5">
            {http ? "Healthy when the server answers with a 2xx or 3xx status." : "Healthy when the port accepts a connection."}
          </p>
        </div>
        <div className="grid gap-4 sm:grid-cols-[1fr_7rem]">
          <Field label="Address" hint="An IP address or hostname.">
            <input
              className={`${inputClass} font-mono text-[13px]`}
              value={address}
              onChange={(e) => setAddress(e.target.value)}
              placeholder="203.0.113.10"
              autoCapitalize="off"
              spellCheck={false}
            />
          </Field>
          <Field label="Port" optional={http}>
            <input className={inputClass} inputMode="numeric" value={port} onChange={digits(setPort)} placeholder={type === "HTTPS" ? "443" : type === "HTTP" ? "80" : ""} />
          </Field>
        </div>
        {http && (
          <div className="grid gap-4 sm:grid-cols-2">
            <Field label="Path">
              <input className={`${inputClass} font-mono text-[13px]`} value={path} onChange={(e) => setPath(e.target.value)} placeholder="/" spellCheck={false} />
            </Field>
            <Field label="Host name to ask for" optional hint="Sent as the Host header, for servers that host several sites.">
              <input
                className={`${inputClass} font-mono text-[13px]`}
                value={hostHeader}
                onChange={(e) => setHostHeader(e.target.value)}
                placeholder="app.example.com"
                autoCapitalize="off"
                spellCheck={false}
              />
            </Field>
          </div>
        )}
        <div className="grid gap-4 sm:grid-cols-3">
          <Field label="Every" hint="Seconds, 10 to 3600">
            <input className={inputClass} inputMode="numeric" value={every} onChange={digits(setEvery, 4)} />
          </Field>
          <Field label="Timeout" hint="Seconds, 1 to 30">
            <input className={inputClass} inputMode="numeric" value={wait} onChange={digits(setWait, 2)} />
          </Field>
          <Field label="Failures in a row" hint="Before it counts as unhealthy">
            <input className={inputClass} inputMode="numeric" value={threshold} onChange={digits(setThreshold, 2)} />
          </Field>
        </div>
        <Toggle checked={notify} onChange={setNotify} label="Email administrators when it changes" hint="When it becomes unhealthy, and when it recovers." />
        <Toggle checked={enabled} onChange={setEnabled} label="Check regularly" hint="While paused, record sets that use it are answered as if it were healthy." />
        {error && <Notice tone="error">{error}</Notice>}
      </div>
    </Modal>
  );
}

// --- settings ------------------------------------------------------------------------------------

function SettingsTab({ settings, locked, onSaved }: { settings: DnsSettings; locked: boolean; onSaved: () => void }) {
  const toast = useToast();
  const [enabled, setEnabled] = useState(settings.enabled);
  const [nameservers, setNameservers] = useState(settings.nameservers.join("\n"));
  const [ipv4, setIpv4] = useState(settings.publicIpv4 ?? "");
  const [ipv6, setIpv6] = useState(settings.publicIpv6 ?? "");
  const [email, setEmail] = useState(settings.hostmasterEmail ?? "");
  const [transfer, setTransfer] = useState(settings.transferAllowed.join("\n"));
  const [rateLimit, setRateLimit] = useState(String(settings.rateLimit));
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const s = settings.server;
  const publish = `-p 53:${s.port}/udp -p 53:${s.port}/tcp`;

  const save = async () => {
    setBusy(true);
    setError(null);
    try {
      await api.put("/api/dns/settings", {
        enabled,
        nameservers: lines(nameservers),
        publicIpv4: ipv4.trim(),
        publicIpv6: ipv6.trim(),
        hostmasterEmail: email.trim(),
        transferAllowed: lines(transfer),
        rateLimit: rateLimit.trim() === "" ? 0 : Number(rateLimit),
      });
      toast("DNS settings saved");
      onSaved();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="grid gap-5 lg:grid-cols-[minmax(0,1fr)_minmax(0,22rem)]">
      <Panel title="Settings">
        <form
          className="flex flex-col gap-5"
          onSubmit={(e) => {
            e.preventDefault();
            save();
          }}
        >
          {locked && <ManagedNote what="The DNS server's settings" />}
          <fieldset disabled={locked} className="contents">
            <Toggle
              checked={enabled}
              onChange={setEnabled}
              disabled={locked}
              label="Answer DNS queries"
              hint="Turns the DNS server on. Zones and records are kept while it's off."
            />
            <Field
              label="Nameservers"
              optional
              hint="One per line, such as ns1.example.com. Every zone uses these unless it sets its own. Empty means ns1 and ns2 inside each zone."
            >
              <textarea
                className={`${inputClass} h-24 py-2 font-mono text-[13px]`}
                value={nameservers}
                onChange={(e) => setNameservers(e.target.value)}
                placeholder={"ns1.example.com\nns2.example.com"}
                spellCheck={false}
              />
            </Field>
            <div className="grid gap-4 sm:grid-cols-2">
              <Field label="This server's public IPv4" optional hint="Empty means the public address of the host Docker Manager runs on.">
                <input
                  className={`${inputClass} font-mono text-[13px]`}
                  value={ipv4}
                  onChange={(e) => setIpv4(e.target.value)}
                  placeholder={settings.effectiveIpv4 ?? "203.0.113.10"}
                  spellCheck={false}
                />
              </Field>
              <Field label="This server's public IPv6" optional hint="Empty means the public address of the host Docker Manager runs on.">
                <input
                  className={`${inputClass} font-mono text-[13px]`}
                  value={ipv6}
                  onChange={(e) => setIpv6(e.target.value)}
                  placeholder={settings.effectiveIpv6 ?? "2001:db8::10"}
                  spellCheck={false}
                />
              </Field>
            </div>
            <Field label="Contact email" optional hint="Published in each zone's SOA record, for people who need to reach whoever runs the domain.">
              <input className={inputClass} type="email" value={email} onChange={(e) => setEmail(e.target.value)} placeholder="hostmaster@example.com" />
            </Field>
            <Field
              label="Zone transfers allowed to"
              optional
              hint="IP addresses or networks (CIDR), one per line: secondary nameservers allowed to copy zones (AXFR). Single addresses are also notified of changes."
            >
              <textarea
                className={`${inputClass} h-24 py-2 font-mono text-[13px]`}
                value={transfer}
                onChange={(e) => setTransfer(e.target.value)}
                placeholder={"198.51.100.53\n2001:db8:53::/48"}
                spellCheck={false}
              />
            </Field>
            <Field label="Rate limit" hint="Answers per second for each address asking. 0 turns it off. Slows down floods that abuse DNS servers to attack others.">
              <div className="flex items-center gap-2 w-56">
                <input className={inputClass} inputMode="numeric" value={rateLimit} onChange={(e) => setRateLimit(e.target.value.replace(/\D/g, "").slice(0, 6))} />
                <span className="text-[14px] text-slate whitespace-nowrap">per second</span>
              </div>
            </Field>
            {error && <Notice tone="error">{error}</Notice>}
            {!locked && (
              <div>
                <Button type="submit" busy={busy}>
                  Save
                </Button>
              </div>
            )}
          </fieldset>
        </form>
      </Panel>
      <div className="flex flex-col gap-5 min-w-0">
        <Panel title="Server">
          <KeyValues
            rows={[
              ["State", s.running ? <Badge key="st" tone="ok">Running</Badge> : <Badge key="st" tone={settings.enabled ? "danger" : "plain"}>{settings.enabled ? "Not running" : "Off"}</Badge>],
              ["Port", <span key="p" className="tabular-nums">{s.port}, UDP and TCP</span>],
              ...(s.startedAt ? ([["Since", <span key="s" title={dateTime(s.startedAt)}>{ago(s.startedAt)}</span>]] as [string, ReactNode][]) : []),
              ["Queries answered", <span key="q" className="tabular-nums">{s.queries.toLocaleString()}</span>],
              ["Rate limited", <span key="r" className="tabular-nums">{s.rateLimited.toLocaleString()}</span>],
            ]}
          />
          {s.error && (
            <div className="mt-4">
              <Notice tone="error">{s.error}</Notice>
            </div>
          )}
        </Panel>
        <Panel title="Open port 53">
          <p className="text-[14px] text-slate">
            DNS uses port 53. Docker Manager listens on port {s.port} inside its container, so publish it as port 53 for both UDP and TCP:
          </p>
          <div className="mt-3 rounded-xl bg-well border border-line px-3 py-2.5 min-w-0">
            <Copyable text={publish} />
          </div>
          <p className="text-[13px] text-slate mt-3">Also allow port 53 (UDP and TCP) in the firewall or router. To listen on another port inside the container, set the DNS_PORT environment variable.</p>
        </Panel>
      </div>
    </div>
  );
}
