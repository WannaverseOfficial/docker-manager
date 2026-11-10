"use client";

import { useEffect, useState } from "react";
import { useSession } from "@/components/app";
import { Badge, Button, Empty, Field, Notice, PageHeader, Panel, Select, Spinner, Tabs, Toggle, inputClass, useToast } from "@/components/ui";
import { api, qs } from "@/lib/api";
import { dateTime } from "@/lib/format";
import { usePoll } from "@/lib/hooks";

type Tab = "notifications" | "email" | "log";

export default function SettingsPage() {
  const { session } = useSession();
  const [tab, setTab] = useState<Tab>("notifications");
  return (
    <>
      <PageHeader title="Settings" art="setup" />
      <Tabs<Tab>
        value={tab}
        onChange={setTab}
        tabs={[
          { value: "notifications", label: "Your notifications" },
          ...(session?.admin
            ? [
                { value: "email" as Tab, label: "Email server" },
                { value: "log" as Tab, label: "Sent emails" },
              ]
            : []),
        ]}
      />
      {tab === "notifications" && <Notifications />}
      {tab === "email" && <EmailServer />}
      {tab === "log" && <EmailLog />}
    </>
  );
}

interface EventInfo {
  category: string;
  name: string;
  displayName: string;
}

function Notifications() {
  const { data: events } = usePoll<EventInfo[]>("/api/notifications/available-events");
  const { data: prefs, refresh } = usePoll<{ preferences: Record<string, boolean> }>("/api/notifications/preferences");
  const { data: smtp } = usePoll<{ configured: boolean; enabled: boolean }>("/api/admin/smtp/status");
  const toast = useToast();
  if (!events || !prefs) return <Spinner />;
  const byCategory = events.reduce<Record<string, EventInfo[]>>((m, e) => ((m[e.category] ??= []).push(e), m), {});
  const save = async (name: string, value: boolean) => {
    try {
      await api.put("/api/notifications/preferences", { ...prefs.preferences, [name]: value });
      refresh();
    } catch (e) {
      toast((e as Error).message, "error");
    }
  };
  return (
    <div className="flex flex-col gap-6 max-w-2xl">
      {smtp && !smtp.configured && <Notice tone="warn">No email server is set up yet, so no emails are sent. An administrator can add one under Email server.</Notice>}
      {Object.entries(byCategory).map(([cat, list]) => (
        <Panel key={cat} title={cat[0].toUpperCase() + cat.slice(1)}>
          <div className="flex flex-col gap-4">
            {list.map((e) => (
              <Toggle key={e.name} checked={prefs.preferences[e.name] ?? true} onChange={(v) => save(e.name, v)} label={e.displayName} />
            ))}
          </div>
        </Panel>
      ))}
    </div>
  );
}

interface Smtp {
  host: string;
  port: number;
  username: string | null;
  hasPassword: boolean;
  fromAddress: string;
  fromName: string | null;
  securityType: "NONE" | "STARTTLS" | "SSL_TLS";
  enabled: boolean;
}

function EmailServer() {
  const { session } = useSession();
  const [form, setForm] = useState<Smtp & { password: string }>({
    host: "",
    port: 587,
    username: "",
    hasPassword: false,
    password: "",
    fromAddress: "",
    fromName: "Docker Manager",
    securityType: "STARTTLS",
    enabled: true,
  });
  const [loaded, setLoaded] = useState(false);
  const [busy, setBusy] = useState<"save" | "test" | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [testTo, setTestTo] = useState("");
  const toast = useToast();

  useEffect(() => {
    api
      .get<Smtp | undefined>("/api/admin/smtp")
      .then((s) => s && setForm((f) => ({ ...f, ...s, username: s.username ?? "", fromName: s.fromName ?? "", password: "" })))
      .catch(() => {})
      .finally(() => setLoaded(true));
  }, []);

  if (!loaded) return <Spinner />;
  const set = <K extends keyof typeof form>(k: K, v: (typeof form)[K]) => setForm((f) => ({ ...f, [k]: v }));

  return (
    <div className="grid gap-6 lg:grid-cols-[1fr_minmax(0,20rem)]">
      <Panel title="Outgoing email (SMTP)">
        <form
          className="flex flex-col gap-4"
          onSubmit={async (e) => {
            e.preventDefault();
            setBusy("save");
            setError(null);
            try {
              await api.put("/api/admin/smtp", { ...form, password: form.password || undefined });
              toast("Email server saved");
              set("hasPassword", form.hasPassword || !!form.password);
              set("password", "");
            } catch (err) {
              setError((err as Error).message);
            } finally {
              setBusy(null);
            }
          }}
        >
          <div className="grid gap-4 sm:grid-cols-[1fr_8rem]">
            <Field label="Server">
              <input className={inputClass} value={form.host} onChange={(e) => set("host", e.target.value)} placeholder="smtp.example.com" required />
            </Field>
            <Field label="Port">
              <input className={inputClass} inputMode="numeric" value={form.port} onChange={(e) => set("port", Number(e.target.value.replace(/\D/g, "")) || 0)} />
            </Field>
          </div>
          <Field label="Security">
            <Select
              value={form.securityType}
              onChange={(v) => {
                set("securityType", v as Smtp["securityType"]);
                set("port", v === "SSL_TLS" ? 465 : v === "STARTTLS" ? 587 : 25);
              }}
            >
              <option value="STARTTLS">STARTTLS (usually port 587)</option>
              <option value="SSL_TLS">TLS (usually port 465)</option>
              <option value="NONE">None (not recommended)</option>
            </Select>
          </Field>
          <div className="grid gap-4 sm:grid-cols-2">
            <Field label="Username" optional>
              <input className={inputClass} value={form.username ?? ""} onChange={(e) => set("username", e.target.value)} autoComplete="off" />
            </Field>
            <Field label="Password" optional hint={form.hasPassword ? "Saved. Leave empty to keep it." : "Stored encrypted."}>
              <input className={inputClass} type="password" value={form.password} onChange={(e) => set("password", e.target.value)} autoComplete="new-password" />
            </Field>
          </div>
          <div className="grid gap-4 sm:grid-cols-2">
            <Field label="From address">
              <input className={inputClass} type="email" value={form.fromAddress} onChange={(e) => set("fromAddress", e.target.value)} placeholder="docker@example.com" required />
            </Field>
            <Field label="From name" optional>
              <input className={inputClass} value={form.fromName ?? ""} onChange={(e) => set("fromName", e.target.value)} />
            </Field>
          </div>
          <Toggle checked={form.enabled} onChange={(v) => set("enabled", v)} label="Send emails" />
          {error && <Notice tone="error">{error}</Notice>}
          <div>
            <Button type="submit" busy={busy === "save"}>
              Save
            </Button>
          </div>
        </form>
      </Panel>
      <Panel title="Send a test">
        <div className="flex flex-col gap-3">
          <Field label="To">
            <input className={inputClass} type="email" value={testTo} onChange={(e) => setTestTo(e.target.value)} placeholder={session?.username ? "you@example.com" : ""} />
          </Field>
          <div>
            <Button
              tone="quiet"
              busy={busy === "test"}
              disabled={!testTo}
              onClick={async () => {
                setBusy("test");
                try {
                  const r = await api.post<{ success: boolean; message?: string; error?: string }>("/api/admin/smtp/test", { recipientEmail: testTo });
                  toast(r.success ? "Test email sent" : `Sending failed: ${r.error ?? r.message ?? ""}`, r.success ? "ok" : "error");
                } catch (e) {
                  toast((e as Error).message, "error");
                } finally {
                  setBusy(null);
                }
              }}
            >
              Send test email
            </Button>
          </div>
          <p className="text-[12.5px] text-slate">Save first; the test uses the saved settings.</p>
        </div>
      </Panel>
    </div>
  );
}

interface EmailLogEntry {
  id: string;
  recipientEmail: string;
  subject: string;
  eventType: string;
  status: string;
  errorMessage: string | null;
  createdAt: number;
}

function EmailLog() {
  const [page, setPage] = useState(0);
  const { data } = usePoll<{ content: EmailLogEntry[]; totalPages: number }>(`/api/admin/email-logs${qs({ page, size: 30 })}`, 30000);
  if (!data) return <Spinner />;
  if (!data.content.length) return <Empty title="No emails sent yet" />;
  return (
    <>
      <ul className="rounded-2xl border border-line bg-paper divide-y divide-line">
        {data.content.map((e) => (
          <li key={e.id} className="px-4 py-3">
            <div className="flex flex-wrap items-center justify-between gap-2">
              <span className="text-[14px] truncate">{e.subject}</span>
              <Badge tone={e.status === "SENT" ? "ok" : e.status === "FAILED" ? "danger" : "plain"}>{e.status.toLowerCase()}</Badge>
            </div>
            <div className="text-[12.5px] text-slate">
              To {e.recipientEmail} · {dateTime(e.createdAt)}
            </div>
            {e.errorMessage && <div className="text-[13px] text-danger mt-0.5 break-words">{e.errorMessage}</div>}
          </li>
        ))}
      </ul>
      {data.totalPages > 1 && (
        <div className="flex justify-between mt-4">
          <Button tone="quiet" size="sm" disabled={page === 0} onClick={() => setPage(page - 1)}>
            Newer
          </Button>
          <Button tone="quiet" size="sm" disabled={page + 1 >= data.totalPages} onClick={() => setPage(page + 1)}>
            Older
          </Button>
        </div>
      )}
    </>
  );
}
