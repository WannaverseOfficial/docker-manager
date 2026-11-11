// Types and helpers for the Databases pages (PostgreSQL servers run by Docker Manager).

export interface DatabaseServer {
  id: string;
  dockerHostId: string;
  name: string;
  image: string;
  containerName: string;
  volumeName: string;
  networkName: string;
  superuser: string;
  publishedPort: number | null;
  publishedAddress: string | null;
  createdAt: number;
  backupIntervalHours: number;
  backupKeep: number;
  lastAutoBackupAt: number;
  s3Enabled: boolean;
  s3Endpoint: string | null;
  s3Region: string | null;
  s3Bucket: string | null;
  s3Prefix: string | null;
  s3AccessKey: string | null;
  hasS3Secret: boolean;
  activity: string | null;
  lastResult: string | null;
  lastResultFailed: boolean;
}

export interface ServerView {
  server: DatabaseServer;
  state: string;
  health: string | null;
  version: string | null;
  containerId: string | null;
}

export interface DatabaseInfo {
  name: string;
  owner: string;
  sizeBytes: number;
  connections: number;
  access: { role: string; level: "READ" | "WRITE" | "PUBLIC" }[];
}

export interface Role {
  name: string;
  login: boolean;
  superuser: boolean;
  createDb: boolean;
  owns: string[];
}

export interface Backup {
  id: string;
  serverId: string;
  scope: "SERVER" | "DATABASE";
  databaseName: string | null;
  format: "SQL_GZ" | "CUSTOM";
  origin: "MANUAL" | "SCHEDULED" | "BEFORE_RESTORE" | "UPLOAD" | "IMPORT";
  note: string | null;
  status: "RUNNING" | "DONE" | "FAILED";
  sizeBytes: number;
  createdAt: number;
  finishedAt: number;
  createdBy: string | null;
  error: string | null;
  s3Key: string | null;
  s3Status: "DONE" | "FAILED" | null;
  s3Error: string | null;
}

export interface ResultSet {
  columns: string[];
  rows: (string | null)[][];
  rowCount: number;
  rowsCut: boolean;
}

export interface QueryResult {
  results: ResultSet[];
  messages: string[];
  error: string | null;
  outputCut: boolean;
  millis: number;
}

export const INTERVALS: { hours: number; label: string }[] = [
  { hours: 0, label: "Off" },
  { hours: 6, label: "Every 6 hours" },
  { hours: 12, label: "Every 12 hours" },
  { hours: 24, label: "Daily" },
  { hours: 48, label: "Every 2 days" },
  { hours: 168, label: "Weekly" },
];

export function backupSchedule(s: Pick<DatabaseServer, "backupIntervalHours" | "backupKeep">) {
  if (!s.backupIntervalHours) return "No automatic backups";
  const known = INTERVALS.find((i) => i.hours === s.backupIntervalHours);
  const every = known ? known.label : `Every ${s.backupIntervalHours} hours`;
  return `${every} backups, keeps ${s.backupKeep}`;
}

export const ORIGINS: Record<Backup["origin"], string> = {
  MANUAL: "Manual",
  SCHEDULED: "Automatic",
  BEFORE_RESTORE: "Before a restore",
  UPLOAD: "Uploaded",
  IMPORT: "Imported",
};

/** A valid PostgreSQL name as Docker Manager creates them (lowercase, digits, underscores). */
export const IDENT = /^[a-z_][a-z0-9_]{0,62}$/;

/** Turns a result set into CSV text (RFC 4180 quoting). */
export function toCsv(r: ResultSet) {
  const cell = (v: string | null) => (v == null ? "" : /[",\r\n]/.test(v) ? `"${v.replace(/"/g, '""')}"` : v);
  return [r.columns.map(cell).join(","), ...r.rows.map((row) => row.map(cell).join(","))].join("\r\n") + "\r\n";
}
