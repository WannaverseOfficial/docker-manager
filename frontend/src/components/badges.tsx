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
