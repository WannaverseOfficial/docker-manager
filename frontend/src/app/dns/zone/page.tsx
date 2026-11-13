"use client";

import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { Suspense, useState, type ReactNode } from "react";
import { useSession } from "@/components/app";
import { DelegationBadge, HealthBadge, ServerBanner } from "@/components/dns";
import { ManagedBadge, ManagedNote } from "@/components/managed";
import {
  Badge,
  Button,
  Checkbox,
  CodeEditor,
  Copyable,
  Empty,
  Field,
  KeyValues,
  LogView,
  Menu,
  Modal,
  Notice,
  PageHeader,
  Panel,
  Segmented,
  Select,
  Spinner,
  Table,
  Tabs,
  Toggle,
  inputClass,
  useConfirm,
  useToast,
} from "@/components/ui";
import { api, qs } from "@/lib/api";
import {
  ALGORITHMS,
  DIGEST_TYPES,
  RECORD_TYPES,
  VALUE_HINTS,
  displayName,
  lines,
  type DelegationResult,
  type DnsSettings,
  type FailoverRole,
  type HealthCheck,
  type HealthCheckView,
  type ImportResult,
  type LookupResult,
  type RecordSet,
  type ZoneDetail,
} from "@/lib/dns";
import { ago, dateTime, plural } from "@/lib/format";
import { usePoll } from "@/lib/hooks";
import { useManaged } from "@/lib/managed";

type Tab = "records" | "setup" | "settings" | "tools";

export default function ZonePage() {
  return (
    <Suspense>
      <Zone />
    </Suspense>
  );
}

function Zone() {
  const params = useSearchParams();
  const id = params.get("id") ?? "";
  const { session } = useSession();
  const [tab, setTab] = useState<Tab>(() => {
    const t = params.get("tab");
    return t === "setup" || t === "settings" || t === "tools" ? t : "records";
  });
  const admin = !!session?.admin;
  const { data, error, refresh } = usePoll<ZoneDetail>(id && admin ? `/api/dns/zones/${id}` : null, 10000);
  const { data: settings } = usePoll<DnsSettings>(admin ? "/api/dns/settings" : null, 30000);
  const { data: checks } = usePoll<HealthCheckView[]>(admin ? "/api/dns/health-checks" : null, 15000);
  const managedEntry = useManaged().find("dnsZones", id);
  const back = { href: "/dns/", label: "DNS" };

  if (!admin) {
    return (
      <>
        <PageHeader title="Zone" back={back} />
        <Notice>DNS is managed by administrators.</Notice>
      </>
    );
  }
  if (!id) {
    return (
      <>
        <PageHeader title="Zone" back={back} />
        <Notice tone="error">No zone was chosen.</Notice>
      </>
    );
  }

  const z = data?.zone;
  return (
    <>
      <PageHeader
        title={z?.name ?? "Zone"}
        back={back}
        intro={
          z && (
            <span className="inline-flex flex-wrap items-center gap-x-3 gap-y-1">
              <DelegationBadge status={z.delegationStatus} />
              {z.dnssecEnabled && <Badge tone="ok">DNSSEC</Badge>}
              {managedEntry && <ManagedBadge entry={managedEntry.key} />}
              <span className="tabular-nums">Serial {z.serial}</span>
              {z.comment && <span>{z.comment}</span>}
            </span>
          )
        }
      />
      {error && !data && <Notice tone="error">{error}</Notice>}
      {!data && !error && <Spinner />}
      {data && (
        <>
          <ServerBanner
            settings={
              settings ?? {
                enabled: data.dnsEnabled,
                server: {
                  running: true,
                  port: 0,
                  startedAt: null,
                  queries: 0,
                  rateLimited: 0,
                  error: null,
                },
              }
            }
            action={
              <Link href="/dns/?tab=settings" className="underline underline-offset-2">
                Open DNS settings
              </Link>
            }
          />
          {data.warnings.length > 0 && (
            <div className="mb-5">
              <Notice tone="warn" title={data.warnings.length === 1 ? "Something needs a look" : `${data.warnings.length} things need a look`}>
                <ul className="list-disc pl-5 flex flex-col gap-1">
                  {data.warnings.map((w, i) => (
                    <li key={i} className="break-words">
                      {w}
                    </li>
                  ))}
                </ul>
              </Notice>
            </div>
          )}
          <Tabs
            value={tab}
            onChange={setTab}
            tabs={[
              {
                value: "records",
                label: "Records",
                count: data.records.length,
              },
              { value: "setup", label: "Setup" },
              { value: "settings", label: "Settings" },
              { value: "tools", label: "Tools" },
            ]}
          />
          {tab === "records" && <Records detail={data} checks={checks?.map((c) => c.check) ?? []} onChange={refresh} />}
          {tab === "setup" && <Setup detail={data} onChange={refresh} />}
          {tab === "settings" && <ZoneSettings detail={data} managed={!!managedEntry} onChange={refresh} />}
          {tab === "tools" && <Tools detail={data} onChange={refresh} />}
        </>
      )}
    </>
  );
}

// --- records -------------------------------------------------------------------------------------

function Routing({ r }: { r: RecordSet }) {
  if (!r.routingPolicy) return <span className="text-[13px] text-slate">Simple</span>;
  return (
    <span className="inline-flex flex-col items-start gap-0.5">
      {r.routingPolicy === "WEIGHTED" ? (
        <Badge>Weighted, {r.weight ?? 1}</Badge>
      ) : (
        <Badge tone={r.failoverRole === "PRIMARY" ? "live" : "plain"}>Failover, {r.failoverRole === "PRIMARY" ? "primary" : "secondary"}</Badge>
      )}
      {r.setIdentifier && <span className="font-mono text-[12px] text-slate">{r.setIdentifier}</span>}
    </span>
  );
}

function Records({ detail, checks, onChange }: { detail: ZoneDetail; checks: HealthCheck[]; onChange: () => void }) {
  const zone = detail.zone;
  const { find } = useManaged();
  const confirm = useConfirm();
  const toast = useToast();
  const [editing, setEditing] = useState<RecordSet | "new" | null>(null);
  const checkById = new Map(checks.map((c) => [c.id, c]));

  return (
    <div className="flex flex-col gap-10">
      <section>
        <div className="flex flex-wrap items-center justify-between gap-3 mb-4">
          <p className="text-[14px] text-slate max-w-[70ch]">
            Record sets you added. Records with the same name and type are one set: several values are all answered together.
          </p>
          {detail.records.length > 0 && <Button onClick={() => setEditing("new")}>Add record</Button>}
        </div>
        {detail.records.length === 0 ? (
          <Empty title="No records yet" action={<Button onClick={() => setEditing("new")}>Add a record</Button>}>
            Ingress routes on {zone.name} get their records by themselves, shown below. Add the rest here, such as mail (MX) or verification (TXT) records.
          </Empty>
        ) : (
          <Table head={["Name", "Type", "TTL", "Values", "Routing", "Health check", ""]}>
            {detail.records.map((r) => {
              const managed = find("dnsZones.records", r.id);
              const check = r.healthCheckId ? checkById.get(r.healthCheckId) : undefined;
              return (
                <tr key={r.id}>
                  <td className="max-w-[18rem]">
                    <div className="flex flex-wrap items-center gap-2">
                      <span className="font-mono text-[13px] break-all">{r.fqdn}</span>
                      {managed && <ManagedBadge entry={managed.key} />}
                    </div>
                    {r.comment && <div className="text-[12.5px] text-slate">{r.comment}</div>}
                  </td>
                  <td>
                    <Badge>{r.type}</Badge>
                  </td>
                  <td className="tabular-nums text-[13px] whitespace-nowrap">{r.ttl == null ? <span className="text-slate">Default</span> : `${r.ttl} s`}</td>
                  <td className="max-w-[26rem]">
                    <ul className="font-mono text-[12.5px]">
                      {r.values.map((v, i) => (
                        <li key={i} className="break-all">
                          {v}
                        </li>
                      ))}
                    </ul>
                  </td>
                  <td>
                    <Routing r={r} />
                  </td>
                  <td className="text-[13px]">
                    {r.healthCheckId ? (
                      check ? (
                        <span className="inline-flex flex-wrap items-center gap-1.5">
                          {check.name}
                          <HealthBadge check={check} />
                        </span>
                      ) : (
                        <span className="text-slate">Loading</span>
                      )
                    ) : (
                      <span className="text-slate">None</span>
                    )}
                  </td>
                  <td className="text-right">
                    {!managed && (
                      <Menu
                        items={[
                          { label: "Edit", onClick: () => setEditing(r) },
                          {
                            label: "Delete",
                            danger: true,
                            onClick: async () => {
                              const ok = await confirm({
                                title: `Delete ${r.fqdn} ${r.type}?`,
                                body: `Resolvers stop getting ${r.values.length === 1 ? "this value" : "these values"} once their cached copy expires.`,
                                confirm: "Delete",
                                danger: true,
                              });
                              if (!ok) return;
                              try {
                                await api.del(`/api/dns/zones/${zone.id}/records/${r.id}`);
                                toast("Record deleted");
                                onChange();
                              } catch (e) {
                                toast((e as Error).message, "error");
                              }
                            },
                          },
                        ]}
                      />
                    )}
                  </td>
                </tr>
              );
            })}
          </Table>
        )}
      </section>

      <section>
        <h2 className="font-display text-[1.15rem]">Automatic records</h2>
        <p className="text-[14px] text-slate mt-0.5 mb-3 max-w-[75ch]">
          Docker Manager keeps these up to date: addresses for ingress routes on this domain, the zone&apos;s own nameservers, and Let&apos;s Encrypt checks. A
          record of yours with the same name and type replaces one.
          {!zone.autoRecords && " Records for ingress routes are turned off in this zone's settings."}
        </p>
        {detail.generated.length === 0 ? (
          <p className="text-[14px] text-slate">None right now.</p>
        ) : (
          <Table head={["Name", "Type", "Values", "From"]}>
            {detail.generated.map((g, i) => (
              <tr key={`${g.name} ${g.type} ${i}`} className={g.active ? "" : "opacity-60"}>
                <td className="font-mono text-[13px] break-all">{displayName(g.name, zone.name)}</td>
                <td>
                  <Badge>{g.type}</Badge>
                </td>
                <td className="max-w-[26rem]">
                  <ul className="font-mono text-[12.5px]">
                    {g.values.map((v, j) => (
                      <li key={j} className="break-all">
                        {v}
                      </li>
                    ))}
                  </ul>
                </td>
                <td className="text-[13px]">
                  {g.source}
                  {!g.active && <span className="block text-slate">Overridden by your record</span>}
                </td>
              </tr>
            ))}
          </Table>
        )}
      </section>

      {editing && (
        <RecordDialog detail={detail} record={editing === "new" ? null : editing} checks={checks} onClose={() => setEditing(null)} onSaved={onChange} />
      )}
    </div>
  );
}

type Policy = "SIMPLE" | "WEIGHTED" | "FAILOVER";

function RecordDialog({
  detail,
  record,
  checks,
  onClose,
  onSaved,
}: {
  detail: ZoneDetail;
  record: RecordSet | null;
  checks: HealthCheck[];
  onClose: () => void;
  onSaved: () => void;
}) {
  const zone = detail.zone;
  const toast = useToast();
  const [name, setName] = useState(record ? (record.name === "@" ? "@" : record.name) : "");
  const [type, setType] = useState(record?.type ?? "A");
  const [ttl, setTtl] = useState(record?.ttl == null ? "" : String(record.ttl));
  const [values, setValues] = useState(record?.values.join("\n") ?? "");
  const [policy, setPolicy] = useState<Policy>(record?.routingPolicy ?? "SIMPLE");
  const [setId, setSetId] = useState(record?.setIdentifier ?? "");
  const [weight, setWeight] = useState(String(record?.weight ?? 1));
  const [role, setRole] = useState<FailoverRole>(record?.failoverRole ?? "PRIMARY");
  const [checkId, setCheckId] = useState(record?.healthCheckId ?? "");
  const [comment, setComment] = useState(record?.comment ?? "");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const hint = VALUE_HINTS[type];
  const simpleOnly = type === "NS" || type === "DS";
  const effectivePolicy: Policy = simpleOnly ? "SIMPLE" : policy;
  const shown = name.trim() === "" || name.trim() === "@" ? zone.name : `${name.trim().replace(/\.$/, "")}.${zone.name}`;
  const weightNum = Number(weight);
  const problem =
    lines(values).length === 0
      ? "Add at least one value"
      : effectivePolicy !== "SIMPLE" && !setId.trim()
        ? "Give this record set an identifier"
        : effectivePolicy === "WEIGHTED" && (weight === "" || weightNum < 0 || weightNum > 255)
          ? "Weight: 0 to 255"
          : effectivePolicy === "FAILOVER" && role === "PRIMARY" && !checkId
            ? "The primary of a failover needs a health check"
            : null;

  const save = async () => {
    setBusy(true);
    setError(null);
    const body = {
      name: name.trim() || "@",
      type,
      ttl: ttl.trim() === "" ? null : Number(ttl),
      values: lines(values),
      routingPolicy: effectivePolicy,
      setIdentifier: effectivePolicy === "SIMPLE" ? undefined : setId.trim(),
      weight: effectivePolicy === "WEIGHTED" ? weightNum : undefined,
      failoverRole: effectivePolicy === "FAILOVER" ? role : undefined,
      healthCheckId: effectivePolicy === "SIMPLE" ? undefined : checkId || undefined,
      comment: comment.trim(),
    };
    try {
      if (record) await api.put(`/api/dns/zones/${zone.id}/records/${record.id}`, body);
      else await api.post(`/api/dns/zones/${zone.id}/records`, body);
      toast(record ? "Record saved" : "Record added");
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
      wide
      onClose={onClose}
      title={record ? `Edit ${record.fqdn} ${record.type}` : "Add a record"}
      footer={
        <>
          {problem && <span className="text-[13px] text-slate mr-auto self-center">{problem}</span>}
          <Button tone="quiet" onClick={onClose}>
            Cancel
          </Button>
          <Button busy={busy} disabled={!!problem} onClick={save}>
            {record ? "Save" : "Add record"}
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-4">
        <div className="grid gap-4 sm:grid-cols-[minmax(0,1fr)_9rem_8rem]">
          <Field label="Name" hint={<span className="break-all">{shown}</span>}>
            <div className="flex items-center rounded-xl border border-line bg-paper focus-within:border-slate min-w-0">
              <input
                className="flex-1 min-w-0 bg-transparent px-3.5 py-2.5 text-[13px] font-mono focus:outline-none placeholder:text-slate/70"
                value={name}
                onChange={(e) => setName(e.target.value)}
                placeholder="www"
                autoFocus={!record}
                autoCapitalize="off"
                spellCheck={false}
              />
              {name.trim() !== "@" && <span className="pr-3.5 text-[13px] font-mono text-slate truncate max-w-[50%]">.{zone.name}</span>}
            </div>
          </Field>
          <Field label="Type">
            <Select value={type} onChange={setType}>
              {RECORD_TYPES.map((t) => (
                <option key={t} value={t}>
                  {t}
                </option>
              ))}
            </Select>
          </Field>
          <Field label="TTL" optional>
            <input
              className={inputClass}
              inputMode="numeric"
              value={ttl}
              onChange={(e) => setTtl(e.target.value.replace(/\D/g, "").slice(0, 7))}
              placeholder={`${zone.defaultTtl}`}
            />
          </Field>
        </div>
        <p className="text-[13px] text-slate -mt-2">
          Use @ for {zone.name} itself and * for a wildcard. TTL is how many seconds resolvers may keep the answer; empty uses the zone&apos;s default (
          {zone.defaultTtl} s).
        </p>
        <Field label="Values" hint={hint?.hint}>
          <textarea
            className={`${inputClass} h-28 py-2 font-mono text-[13px]`}
            value={values}
            onChange={(e) => setValues(e.target.value)}
            placeholder={hint?.placeholder}
            spellCheck={false}
          />
        </Field>

        <div>
          <div className="text-[13px] font-medium mb-1.5">Routing</div>
          {simpleOnly ? (
            <p className="text-[13px] text-slate">{type} records always use simple routing.</p>
          ) : (
            <>
              <Segmented<Policy>
                label="Routing"
                value={policy}
                onChange={setPolicy}
                options={[
                  { value: "SIMPLE", label: "Simple" },
                  { value: "WEIGHTED", label: "Weighted" },
                  { value: "FAILOVER", label: "Failover" },
                ]}
              />
              <p className="text-[13px] text-slate mt-1.5">
                {policy === "SIMPLE"
                  ? "Every value is answered."
                  : policy === "WEIGHTED"
                    ? "Add several record sets with the same name and type. Each is answered in proportion to its weight, for example 9 and 1 to send a tenth of visitors to a new server."
                    : "Add a primary and a secondary record set with the same name and type. The secondary is answered while the primary's health check fails."}
              </p>
            </>
          )}
        </div>
        {effectivePolicy !== "SIMPLE" && (
          <div className="grid gap-4 sm:grid-cols-2">
            <Field label="Set identifier" hint="Tells record sets with the same name and type apart.">
              <input className={inputClass} value={setId} onChange={(e) => setSetId(e.target.value)} placeholder="server-a" spellCheck={false} />
            </Field>
            {effectivePolicy === "WEIGHTED" ? (
              <Field label="Weight" hint="0 to 255. 0 is only answered when every other set is down.">
                <input className={inputClass} inputMode="numeric" value={weight} onChange={(e) => setWeight(e.target.value.replace(/\D/g, "").slice(0, 3))} />
              </Field>
            ) : (
              <div>
                <div className="text-[13px] font-medium mb-1.5">Role</div>
                <Segmented<FailoverRole>
                  label="Role"
                  value={role}
                  onChange={setRole}
                  options={[
                    { value: "PRIMARY", label: "Primary" },
                    { value: "SECONDARY", label: "Secondary" },
                  ]}
                />
              </div>
            )}
          </div>
        )}
        {effectivePolicy !== "SIMPLE" && (
          <Field
            label="Health check"
            optional={!(effectivePolicy === "FAILOVER" && role === "PRIMARY")}
            hint={
              checks.length === 0 ? (
                <>
                  No health checks yet. Add one on the{" "}
                  <Link href="/dns/?tab=health" className="underline underline-offset-2">
                    Health checks
                  </Link>{" "}
                  tab.
                </>
              ) : (
                "While it fails, this record set isn't answered (unless every set is down)."
              )
            }
          >
            <Select value={checkId} onChange={setCheckId}>
              <option value="">None</option>
              {checks.map((c) => (
                <option key={c.id} value={c.id}>
                  {c.name}
                </option>
              ))}
            </Select>
          </Field>
        )}
        <Field label="Comment" optional>
          <input className={inputClass} value={comment} onChange={(e) => setComment(e.target.value)} />
        </Field>
        {error && <Notice tone="error">{error}</Notice>}
      </div>
    </Modal>
  );
}

// --- setup at the registrar ------------------------------------------------------------------------

function Step({ n, title, children, action }: { n: number; title: string; children: ReactNode; action?: ReactNode }) {
  return (
    <Panel
      title={
        <span className="inline-flex items-center gap-2.5">
          <span className="size-6 shrink-0 grid place-items-center rounded-full bg-well text-[13px] tabular-nums">{n}</span>
          {title}
        </span>
      }
      action={action}
    >
      {children}
    </Panel>
  );
}

function Setup({ detail, onChange }: { detail: ZoneDetail; onChange: () => void }) {
  const zone = detail.zone;
  const toast = useToast();
  const [checking, setChecking] = useState(false);
  const [result, setResult] = useState<DelegationResult | null>(null);
  const glue = detail.glue.filter((g) => g.inZone);
  const ds = detail.ds;

  const check = async () => {
    setChecking(true);
    try {
      setResult(await api.post<DelegationResult>(`/api/dns/zones/${zone.id}/check-delegation`));
      onChange();
    } catch (e) {
      toast((e as Error).message, "error");
    } finally {
      setChecking(false);
    }
  };

  let n = 1;
  return (
    <div className="flex flex-col gap-5">
      <p className="text-[14px] text-slate max-w-[75ch]">
        To make this server answer for {zone.name}, change its settings at your registrar (the company you bought the domain from). Changes there can take up to
        two days to reach everyone.
      </p>

      <Step n={n++} title="Set the nameservers">
        <p className="text-[14px] text-slate mb-3">Replace the domain&apos;s nameservers at your registrar with these:</p>
        <ul className="flex flex-col gap-1.5">
          {detail.nameservers.map((ns) => (
            <li key={ns} className="rounded-xl bg-well border border-line px-3 py-2 min-w-0">
              <Copyable text={ns} />
            </li>
          ))}
        </ul>
        <p className="text-[13px] text-slate mt-3">
          {detail.ownNameservers ? "This zone sets its own nameservers (Settings tab)." : "From the DNS settings. A zone can set its own in its Settings tab."}{" "}
          Registrars usually ask for at least two. With only this server, give it two names (such as ns1 and ns2) that both point at its address, or add a
          secondary DNS service and allow it to copy zones under zone transfers in the{" "}
          <Link href="/dns/?tab=settings" className="underline underline-offset-2">
            DNS settings
          </Link>
          .
        </p>
      </Step>

      {glue.length > 0 && (
        <Step n={n++} title="Register the nameservers' addresses">
          <p className="text-[14px] text-slate mb-3">
            {glue.length === 1 ? "This nameserver is" : "These nameservers are"} inside {zone.name} itself, so the registry also needs{" "}
            {glue.length === 1 ? "its address" : "their addresses"}. At your registrar, look for &quot;glue records&quot;, &quot;child nameservers&quot; or
            &quot;register nameservers&quot;.
          </p>
          <Table head={["Nameserver", "IPv4", "IPv6"]}>
            {glue.map((g) => (
              <tr key={g.name}>
                <td className="font-mono text-[13px] break-all">{g.name}</td>
                <td>{g.ipv4 ? <Copyable text={g.ipv4} /> : <span className="text-[13px] text-slate">None</span>}</td>
                <td>{g.ipv6 ? <Copyable text={g.ipv6} /> : <span className="text-[13px] text-slate">None</span>}</td>
              </tr>
            ))}
          </Table>
          {glue.some((g) => !g.ipv4 && !g.ipv6) && (
            <div className="mt-3">
              <Notice tone="warn">
                This server&apos;s public address isn&apos;t known yet. Set it in the{" "}
                <Link href="/dns/?tab=settings" className="underline underline-offset-2">
                  DNS settings
                </Link>
                .
              </Notice>
            </div>
          )}
        </Step>
      )}

      <Step
        n={n++}
        title="Check the delegation"
        action={
          <Button tone="quiet" size="sm" busy={checking} onClick={check}>
            Check now
          </Button>
        }
      >
        <KeyValues
          rows={[
            ["Status", <DelegationBadge key="s" status={result?.status ?? zone.delegationStatus} />],
            [
              "Details",
              result?.detail ?? zone.delegationDetail ?? (
                <span key="d" className="text-slate">
                  Not checked yet
                </span>
              ),
            ],
            [
              "Checked",
              zone.delegationCheckedAt ? (
                <span key="c" title={dateTime(zone.delegationCheckedAt)}>
                  {ago(zone.delegationCheckedAt)}
                </span>
              ) : (
                <span key="c" className="text-slate">
                  Never
                </span>
              ),
            ],
            ...(result
              ? ([
                  [
                    "The registry lists",
                    result.registryNameservers.length ? (
                      <ul key="n" className="font-mono text-[13px]">
                        {result.registryNameservers.map((x) => (
                          <li key={x} className="break-all">
                            {x}
                          </li>
                        ))}
                      </ul>
                    ) : (
                      <span key="n" className="text-slate">
                        No nameservers
                      </span>
                    ),
                  ],
                  [
                    "DS at the registry",
                    result.registryDs.length ? (
                      <ul key="ds" className="font-mono text-[12.5px]">
                        {result.registryDs.map((x) => (
                          <li key={x} className="break-all">
                            {x}
                          </li>
                        ))}
                      </ul>
                    ) : (
                      <span key="ds" className="text-slate">
                        None
                      </span>
                    ),
                  ],
                ] as [string, ReactNode][])
              : []),
          ]}
        />
        <p className="text-[13px] text-slate mt-4">
          Asks the registry for {zone.name} which nameservers it sends visitors to. Docker Manager also checks this by itself now and then.
        </p>
      </Step>

      <Step n={n++} title="DNSSEC">
        {!zone.dnssecEnabled && (
          <p className="text-[14px] text-slate">
            DNSSEC is off. With it on, resolvers can check that answers really come from you. Turn it on in the Settings tab once the nameservers above work.
          </p>
        )}
        {zone.dnssecEnabled && !ds && <Notice tone="warn">DNSSEC is on, but the DS record isn&apos;t ready yet. Refresh in a moment.</Notice>}
        {ds && (
          <>
            <p className="text-[14px] text-slate mb-3">
              Answers are signed. Add this DS record at your registrar, usually under &quot;DNSSEC&quot;, after the nameservers work. Some registrars ask for
              the parts, others for the whole line.
            </p>
            <KeyValues
              rows={[
                ["Key tag", <Copyable key="k" text={String(ds.keyTag)} />],
                [
                  "Algorithm",
                  <span key="a" className="inline-flex flex-wrap items-center gap-2">
                    <Copyable text={String(ds.algorithm)} />
                    <span className="text-[13px] text-slate">{ALGORITHMS[ds.algorithm] ?? ""}</span>
                  </span>,
                ],
                [
                  "Digest type",
                  <span key="t" className="inline-flex flex-wrap items-center gap-2">
                    <Copyable text={String(ds.digestType)} />
                    <span className="text-[13px] text-slate">{DIGEST_TYPES[ds.digestType] ?? ""}</span>
                  </span>,
                ],
                ["Digest", <CopyBlock key="d" text={ds.digest} />],
                ["Whole record", <CopyBlock key="r" text={ds.record} />],
              ]}
            />
          </>
        )}
      </Step>
    </div>
  );
}

/** Long text that wraps instead of being cut, with a copy button. */
function CopyBlock({ text }: { text: string }) {
  return (
    <span className="flex items-start gap-2 min-w-0">
      <span className="font-mono text-[12.5px] break-all min-w-0">{text}</span>
      <Copyable text={text} display={<span className="sr-only">Copy</span>} className="shrink-0 mt-0.5" />
    </span>
  );
}

// --- zone settings -------------------------------------------------------------------------------

function ZoneSettings({ detail, managed, onChange }: { detail: ZoneDetail; managed: boolean; onChange: () => void }) {
  const zone = detail.zone;
  const toast = useToast();
  const confirm = useConfirm();
  const [comment, setComment] = useState(zone.comment ?? "");
  const [defaultTtl, setDefaultTtl] = useState(String(zone.defaultTtl));
  const [negativeTtl, setNegativeTtl] = useState(String(zone.negativeTtl));
  const [nameservers, setNameservers] = useState(detail.ownNameservers ? detail.nameservers.join("\n") : "");
  const [autoRecords, setAutoRecords] = useState(zone.autoRecords);
  const [dnssec, setDnssec] = useState(zone.dnssecEnabled);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [deleting, setDeleting] = useState(false);
  const turningOff = zone.dnssecEnabled && !dnssec;

  const save = async () => {
    if (turningOff) {
      const ok = await confirm({
        title: "Turn off DNSSEC?",
        body: "Remove the DS record at your registrar first and wait two days, or the domain stops resolving for most people.",
        confirm: "Turn off DNSSEC",
        danger: true,
      });
      if (!ok) return;
    }
    setBusy(true);
    setError(null);
    try {
      await api.put(`/api/dns/zones/${zone.id}`, {
        comment: comment.trim(),
        defaultTtl: Number(defaultTtl),
        negativeTtl: Number(negativeTtl),
        nameservers: lines(nameservers),
        autoRecords,
        dnssec,
      });
      toast("Zone saved");
      onChange();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  const defaults = detail.ownNameservers ? "" : detail.nameservers.join("\n");
  return (
    <div className="flex flex-col gap-5">
      <Panel title="Settings">
        <form
          className="flex flex-col gap-5"
          onSubmit={(e) => {
            e.preventDefault();
            save();
          }}
        >
          {managed && <ManagedNote what="This zone" />}
          <fieldset disabled={managed} className="contents">
            <Field label="Comment" optional>
              <input className={inputClass} value={comment} onChange={(e) => setComment(e.target.value)} />
            </Field>
            <div className="grid gap-4 sm:grid-cols-2">
              <Field label="Default TTL" hint="Seconds resolvers keep an answer, for records without their own TTL.">
                <div className="flex items-center gap-2 w-44">
                  <input
                    className={inputClass}
                    inputMode="numeric"
                    value={defaultTtl}
                    onChange={(e) => setDefaultTtl(e.target.value.replace(/\D/g, "").slice(0, 7))}
                  />
                  <span className="text-[14px] text-slate">seconds</span>
                </div>
              </Field>
              <Field label="Negative answer TTL" hint="Seconds resolvers remember that a name doesn't exist. 0 to 86400.">
                <div className="flex items-center gap-2 w-44">
                  <input
                    className={inputClass}
                    inputMode="numeric"
                    value={negativeTtl}
                    onChange={(e) => setNegativeTtl(e.target.value.replace(/\D/g, "").slice(0, 5))}
                  />
                  <span className="text-[14px] text-slate">seconds</span>
                </div>
              </Field>
            </div>
            <Field label="Nameservers" optional hint="One per line. Empty uses the nameservers from the DNS settings.">
              <textarea
                className={`${inputClass} h-24 py-2 font-mono text-[13px]`}
                value={nameservers}
                onChange={(e) => setNameservers(e.target.value)}
                placeholder={defaults || "ns1.example.com"}
                spellCheck={false}
              />
            </Field>
            <Toggle
              checked={autoRecords}
              onChange={setAutoRecords}
              disabled={managed}
              label="Automatic records for ingress routes"
              hint={`Ingress routes and rollouts on ${zone.name} get A and AAAA records pointing at their Docker host's public address.`}
            />
            <Toggle
              checked={dnssec}
              onChange={setDnssec}
              disabled={managed}
              label="Sign with DNSSEC"
              hint={
                zone.dnssecEnabled
                  ? "Answers are signed. The DS record is on the Setup tab."
                  : "After turning it on, give your registrar the DS record shown on the Setup tab."
              }
            />
            {turningOff && (
              <Notice tone="error" title="Careful">
                Remove the DS record at your registrar first and wait two days, or the domain stops resolving for most people.
              </Notice>
            )}
            {error && <Notice tone="error">{error}</Notice>}
            {!managed && (
              <div>
                <Button type="submit" busy={busy}>
                  Save
                </Button>
              </div>
            )}
          </fieldset>
        </form>
      </Panel>
      {!managed && (
        <Panel title="Delete zone">
          <div className="flex flex-wrap items-center justify-between gap-3">
            <p className="text-[14px] text-slate max-w-[65ch]">
              Deletes the zone and all its records. If the registrar still points at this server, {zone.name} stops resolving.
            </p>
            <Button tone="danger" size="sm" onClick={() => setDeleting(true)}>
              Delete zone
            </Button>
          </div>
        </Panel>
      )}
      {deleting && <DeleteZone detail={detail} onClose={() => setDeleting(false)} />}
    </div>
  );
}

function DeleteZone({ detail, onClose }: { detail: ZoneDetail; onClose: () => void }) {
  const zone = detail.zone;
  const router = useRouter();
  const toast = useToast();
  const [typed, setTyped] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  return (
    <Modal
      open
      onClose={onClose}
      title={`Delete ${zone.name}?`}
      footer={
        <>
          <Button tone="quiet" onClick={onClose}>
            Cancel
          </Button>
          <Button
            tone="danger"
            busy={busy}
            disabled={typed.trim().toLowerCase() !== zone.name}
            onClick={async () => {
              setBusy(true);
              setError(null);
              try {
                await api.del(`/api/dns/zones/${zone.id}`);
                toast(`Deleted ${zone.name}`);
                router.push("/dns/");
              } catch (e) {
                setError((e as Error).message);
                setBusy(false);
              }
            }}
          >
            Delete zone
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-4 text-[14px]">
        <p className="text-slate">
          {plural(detail.records.length, "record set")} {detail.records.length === 1 ? "is" : "are"} deleted with it. Export the zone file first (Tools tab) if
          you might need them.
        </p>
        <Field label={`Type ${zone.name} to confirm`}>
          <input
            className={`${inputClass} font-mono text-[13px]`}
            value={typed}
            onChange={(e) => setTyped(e.target.value)}
            autoCapitalize="off"
            spellCheck={false}
          />
        </Field>
        {error && <Notice tone="error">{error}</Notice>}
      </div>
    </Modal>
  );
}

// --- tools ---------------------------------------------------------------------------------------

const LOOKUP_TYPES = ["A", "AAAA", "CNAME", "MX", "TXT", "SRV", "CAA", "NS", "PTR", "DS", "SOA", "DNSKEY"];

function Tools({ detail, onChange }: { detail: ZoneDetail; onChange: () => void }) {
  return (
    <div className="flex flex-col gap-5">
      <Lookup zone={detail.zone.name} />
      <div className="grid gap-5 lg:grid-cols-2">
        <ExportZone detail={detail} />
        <ImportZone detail={detail} onChange={onChange} />
      </div>
    </div>
  );
}

function Lookup({ zone }: { zone: string }) {
  const [name, setName] = useState(zone);
  const [type, setType] = useState("A");
  const [dnssec, setDnssec] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [result, setResult] = useState<LookupResult | null>(null);

  const ask = async () => {
    setBusy(true);
    setError(null);
    try {
      setResult(await api.get<LookupResult>(`/api/dns/lookup${qs({ name: name.trim(), type, dnssec })}`));
    } catch (e) {
      setError((e as Error).message);
      setResult(null);
    } finally {
      setBusy(false);
    }
  };

  const section = (title: string, rows: string[]) =>
    rows.length > 0 && (
      <div>
        <div className="text-[13px] font-medium mb-1.5">{title}</div>
        <pre className="code rounded-xl bg-well border border-line p-3 overflow-x-auto text-[12.5px]">{rows.join("\n")}</pre>
      </div>
    );

  return (
    <Panel title="Look up">
      <p className="text-[14px] text-slate mb-4">Asks this server the way a resolver would, so you see exactly what the internet gets.</p>
      <form
        className="flex flex-wrap items-end gap-3"
        onSubmit={(e) => {
          e.preventDefault();
          if (name.trim()) ask();
        }}
      >
        <div className="flex-1 min-w-[14rem]">
          <Field label="Name">
            <input
              className={`${inputClass} font-mono text-[13px]`}
              value={name}
              onChange={(e) => setName(e.target.value)}
              autoCapitalize="off"
              spellCheck={false}
            />
          </Field>
        </div>
        <div className="w-32">
          <Field label="Type">
            <Select value={type} onChange={setType}>
              {LOOKUP_TYPES.map((t) => (
                <option key={t} value={t}>
                  {t}
                </option>
              ))}
            </Select>
          </Field>
        </div>
        <span className="inline-flex items-center gap-2 h-[42px]">
          <Checkbox label="Ask for DNSSEC signatures" checked={dnssec} onChange={setDnssec} />
          <span className="text-[14px] cursor-pointer" onClick={() => setDnssec(!dnssec)}>
            DNSSEC
          </span>
        </span>
        <Button type="submit" busy={busy} disabled={!name.trim()} className="mb-[3px]">
          Look up
        </Button>
      </form>
      {error && (
        <div className="mt-4">
          <Notice tone="error">{error}</Notice>
        </div>
      )}
      {result && (
        <div className="mt-5 flex flex-col gap-4">
          <div className="flex flex-wrap items-center gap-2 text-[14px]">
            <Badge tone={result.rcode === "NOERROR" ? "ok" : result.rcode === "NXDOMAIN" ? "warn" : "danger"}>{result.rcode}</Badge>
            <span className="text-slate">{result.authoritative ? "Authoritative answer" : "Not authoritative (this server doesn't host that name)"}</span>
          </div>
          {result.answer.length === 0 && <p className="text-[14px] text-slate">No records of that type.</p>}
          {section("Answer", result.answer)}
          {section("Authority", result.authority)}
          {section("Additional", result.additional)}
        </div>
      )}
    </Panel>
  );
}

function ExportZone({ detail }: { detail: ZoneDetail }) {
  const [text, setText] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  return (
    <Panel
      title="Export"
      action={
        <Button
          tone="quiet"
          size="sm"
          busy={busy}
          onClick={async () => {
            setBusy(true);
            setError(null);
            try {
              setText(await api.get<string>(`/api/dns/zones/${detail.zone.id}/export`));
            } catch (e) {
              setError((e as Error).message);
            } finally {
              setBusy(false);
            }
          }}
        >
          {text == null ? "Show zone file" : "Refresh"}
        </Button>
      }
    >
      <p className="text-[14px] text-slate mb-3">The zone in BIND format, which other DNS hosts can import. Copy or download it.</p>
      {error && <Notice tone="error">{error}</Notice>}
      {text != null && <LogView text={text} height="h-[320px]" filename={`${detail.zone.name}.zone`} empty="The zone is empty." />}
    </Panel>
  );
}

function ImportZone({ detail, onChange }: { detail: ZoneDetail; onChange: () => void }) {
  const [text, setText] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [result, setResult] = useState<ImportResult | null>(null);
  return (
    <Panel title="Import">
      <p className="text-[14px] text-slate mb-3">
        Paste a zone file exported from Route 53, Cloudflare or another DNS host. SOA, the zone&apos;s own NS and DNSSEC records are skipped; values join a
        simple record set with the same name and type.
      </p>
      <CodeEditor label="Zone file" value={text} onChange={setText} rows={10} placeholder={`$ORIGIN ${detail.zone.name}.\nwww 300 IN A 203.0.113.10`} />
      {error && (
        <div className="mt-3">
          <Notice tone="error">{error}</Notice>
        </div>
      )}
      {result && (
        <div className="mt-3">
          <Notice tone="ok" title="Imported">
            {plural(result.added, "record set")} added, {plural(result.merged, "record set")} merged into existing ones.
            {result.skipped.length > 0 && (
              <>
                <span className="block mt-2">Skipped:</span>
                <ul className="mt-1 font-mono text-[12.5px] text-slate">
                  {result.skipped.map((s, i) => (
                    <li key={i} className="break-all">
                      {s}
                    </li>
                  ))}
                </ul>
              </>
            )}
          </Notice>
        </div>
      )}
      <div className="mt-3">
        <Button
          busy={busy}
          disabled={!text.trim()}
          onClick={async () => {
            setBusy(true);
            setError(null);
            setResult(null);
            try {
              setResult(await api.post<ImportResult>(`/api/dns/zones/${detail.zone.id}/import`, { text }));
              setText("");
              onChange();
            } catch (e) {
              setError((e as Error).message);
            } finally {
              setBusy(false);
            }
          }}
        >
          Import
        </Button>
      </div>
    </Panel>
  );
}
