import { Badge } from "./ui";

const JOB: Record<string, [string, "plain" | "live" | "ok" | "danger"]> = {
  PENDING: ["Waiting", "plain"],
  RUNNING: ["Running", "live"],
  SUCCESS: ["Succeeded", "ok"],
  FAILED: ["Failed", "danger"],
  CANCELLED: ["Cancelled", "plain"],
};

/** Status of a git deployment or a pipeline run. */
export function JobBadge({ status }: { status: string }) {
  const [label, tone] = JOB[status] ?? [status, "plain"];
  return <Badge tone={tone}>{label}</Badge>;
}

const ROLLOUT: Record<string, [string, "plain" | "live" | "ok" | "danger" | "warn"]> = {
  PENDING: ["Waiting", "plain"],
  RUNNING: ["Releasing", "live"],
  WAITING_APPROVAL: ["Needs approval", "warn"],
  SUCCEEDED: ["Released", "ok"],
  FAILED: ["Failed", "danger"],
  ROLLED_BACK: ["Rolled back", "warn"],
  CANCELLED: ["Stopped", "plain"],
};

/** Status of a rollout release. */
export function RolloutBadge({ status, kind }: { status: string; kind?: string }) {
  const [label, tone] = ROLLOUT[status] ?? [status, "plain"];
  if (kind === "ROLLBACK" && status === "SUCCEEDED") return <Badge tone="ok">Switched back</Badge>;
  if (kind === "ROLLBACK" && status === "RUNNING") return <Badge tone="live">Switching back</Badge>;
  return <Badge tone={tone}>{label}</Badge>;
}
