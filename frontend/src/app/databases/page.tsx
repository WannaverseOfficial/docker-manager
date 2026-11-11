"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { Database } from "lucide-react";
import { useHost, useSession } from "@/components/app";
import { ReachPicker, validPort, type Reach } from "@/components/databases";
import { Badge, Button, Empty, Field, Modal, Notice, PageHeader, Select, Spinner, StateBadge, inputClass } from "@/components/ui";
import { api } from "@/lib/api";
import { usePoll } from "@/lib/hooks";
import { backupSchedule, type ServerView } from "@/lib/databases";

export default function DatabasesPage() {
  const { hostId } = useHost();
  const { session } = useSession();
  const { data, error, refresh } = usePoll<ServerView[]>(hostId && session?.admin ? `/api/databases/hosts/${hostId}/servers` : null, 10000);
  const [creating, setCreating] = useState(false);

  if (!session?.admin) {
    return (
      <>
        <PageHeader title="Databases" />
        <Notice>Databases are managed by administrators.</Notice>
      </>
    );
  }

  return (
    <>
      <PageHeader
        title="Databases"
        intro="PostgreSQL servers with secure defaults. Keep your apps' databases in one place, share a server between containers, and back everything up on a schedule."
      >
        {data && data.length > 0 && <Button onClick={() => setCreating(true)}>New server</Button>}
      </PageHeader>
      {error && <Notice tone="error">{error}</Notice>}
      {!data && !error && <Spinner />}
      {data && data.length === 0 && (
        <Empty title="No database servers yet" action={<Button onClick={() => setCreating(true)}>Create a PostgreSQL server</Button>}>
          One server can hold the databases of many apps, each with its own user who can only see its own data. You can import databases from the PostgreSQL containers you already run.
        </Empty>
      )}
      {data && data.length > 0 && (
        <ul className="grid gap-3 sm:grid-cols-2">
          {data.map((v) => (
            <li key={v.server.id}>
              <Link
                href={`/databases/view/?id=${v.server.id}`}
                className="block h-full rounded-2xl border border-line bg-paper p-5 hover:border-slate/60 transition-colors"
              >
                <div className="flex items-start gap-3">
                  <span className="size-10 shrink-0 grid place-items-center rounded-xl bg-well text-slate">
                    <Database size={19} aria-hidden />
                  </span>
                  <div className="min-w-0 flex-1">
                    <div className="flex flex-wrap items-center gap-2">
                      <span className="font-medium truncate">{v.server.name}</span>
                      {v.version && <Badge>PostgreSQL {v.version}</Badge>}
                    </div>
                    <div className="font-mono text-[12.5px] text-slate truncate mt-0.5">{v.server.containerName}:5432</div>
                  </div>
                  <StateBadge state={v.state} status={v.health === "unhealthy" ? "(unhealthy)" : undefined} />
                </div>
                <div className="mt-4 flex flex-wrap gap-x-5 gap-y-1 text-[13px] text-slate">
                  <span>{backupSchedule(v.server)}</span>
                  <span>{v.server.publishedPort ? `Port ${v.server.publishedPort} on ${v.server.publishedAddress === "0.0.0.0" ? "the network" : "this machine"}` : "Containers only"}</span>
                  {v.server.s3Enabled && <span>Copies to S3</span>}
                </div>
                {v.server.activity && <div className="mt-3 text-[13px] text-ink">{v.server.activity}...</div>}
              </Link>
            </li>
          ))}
        </ul>
      )}
      {creating && hostId && <CreateServer hostId={hostId} onClose={() => setCreating(false)} onCreated={refresh} />}
    </>
  );
}

function CreateServer({ hostId, onClose, onCreated }: { hostId: string; onClose: () => void; onCreated: () => void }) {
  const router = useRouter();
  const { data: versions } = usePoll<string[]>("/api/databases/versions");
  const [name, setName] = useState("main");
  const [version, setVersion] = useState("");
  const [reach, setReach] = useState<Reach>("containers");
  const [port, setPort] = useState("5432");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const valid = /^[a-z][a-z0-9-]{1,30}[a-z0-9]$/.test(name) && (reach === "containers" || validPort(port));

  const create = async () => {
    setBusy(true);
    setError(null);
    try {
      const s = await api.post<{ id: string }>(`/api/databases/hosts/${hostId}/servers`, {
        name,
        version: version || versions?.[0],
        port: reach === "containers" ? null : Number(port),
        address: reach === "network" ? "0.0.0.0" : "127.0.0.1",
      });
      onCreated();
      router.push(`/databases/view/?id=${s.id}`);
    } catch (e) {
      setError((e as Error).message);
      setBusy(false);
    }
  };

  return (
    <Modal
      open
      onClose={onClose}
      title="New PostgreSQL server"
      footer={
        <>
          <Button tone="quiet" onClick={onClose}>
            Cancel
          </Button>
          <Button busy={busy} disabled={!valid} onClick={create}>
            {busy ? "Starting PostgreSQL" : "Create server"}
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-4">
        <Field label="Name" hint={`The container will be dm-pg-${name || "name"}, and apps reach it at that name.`} error={name && !valid ? "Lowercase letters, digits and dashes, 3 to 32 characters" : null}>
          <input className={inputClass} value={name} onChange={(e) => setName(e.target.value.toLowerCase())} autoFocus />
        </Field>
        <Field label="Version">
          <Select value={version || versions?.[0] || ""} onChange={setVersion}>
            {(versions ?? []).map((v, i) => (
              <option key={v} value={v}>
                PostgreSQL {v}
                {i === 0 ? " (newest)" : ""}
              </option>
            ))}
          </Select>
        </Field>
        <ReachPicker reach={reach} setReach={setReach} port={port} setPort={setPort} />
        <div className="rounded-xl bg-well px-4 py-3 text-[13px] text-slate">
          <div className="text-ink font-medium mb-1">Secure by default</div>
          A random 32-character administrator password, SCRAM-SHA-256 for every login, each database closed to everyone but the users you allow, and daily backups that keep the last 7.
        </div>
        {error && <Notice tone="error">{error}</Notice>}
      </div>
    </Modal>
  );
}
