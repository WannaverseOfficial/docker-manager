import type { ReactNode } from "react";
import { Badge, Notice } from "./ui";
import type { DnsSettings, HealthCheck } from "@/lib/dns";

const DELEGATION: Record<string, [string, "plain" | "ok" | "warn" | "danger"]> = {
  OK: ["Delegated", "ok"],
  PARTIAL: ["Partly delegated", "warn"],
  NO: ["Not delegated", "danger"],
  UNKNOWN: ["Unknown", "plain"],
};

/** Whether the registry sends the domain's visitors to this server. */
export function DelegationBadge({ status }: { status: string | null | undefined }) {
  if (!status) return <Badge>Not checked yet</Badge>;
  const [label, tone] = DELEGATION[status] ?? [status, "plain"];
  return <Badge tone={tone}>{label}</Badge>;
}

const HEALTH: Record<HealthCheck["status"], [string, "plain" | "ok" | "danger"]> = {
  HEALTHY: ["Healthy", "ok"],
  UNHEALTHY: ["Unhealthy", "danger"],
  UNKNOWN: ["Unknown", "plain"],
};

export function HealthBadge({ check }: { check: Pick<HealthCheck, "status" | "enabled"> }) {
  if (!check.enabled) return <Badge>Paused</Badge>;
  const [label, tone] = HEALTH[check.status] ?? [check.status, "plain"];
  return <Badge tone={tone}>{label}</Badge>;
}

/** What a health check connects to, as a short line: https://203.0.113.10:443/health */
export function healthTarget(c: Pick<HealthCheck, "type" | "address" | "port" | "path">) {
  const host = c.address.includes(":") ? `[${c.address}]` : c.address;
  if (c.type === "TCP") return `${host}:${c.port}`;
  const defaultPort = c.type === "HTTPS" ? 443 : 80;
  return `${c.type.toLowerCase()}://${host}${c.port === defaultPort ? "" : `:${c.port}`}${c.path || "/"}`;
}

/** Says when the server isn't answering, so zones and records don't seem to do nothing. */
export function ServerBanner({ settings, action }: { settings: Pick<DnsSettings, "enabled" | "server">; action: ReactNode }) {
  if (!settings.enabled) {
    return (
      <div className="mb-5">
        <Notice tone="warn" title="The DNS server is off">
          Zones and records are kept, but nobody gets answers until you turn the server on in Settings. {action}
        </Notice>
      </div>
    );
  }
  if (!settings.server.running || settings.server.error) {
    return (
      <div className="mb-5">
        <Notice tone="error" title={settings.server.running ? "The DNS server has a problem" : "The DNS server isn't running"}>
          {settings.server.error && <span className="block mb-1 break-words">{settings.server.error}</span>}
          Check its port and settings. {action}
        </Notice>
      </div>
    );
  }
  return null;
}
