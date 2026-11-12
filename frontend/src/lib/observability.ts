export interface ObservabilityStack {
  id: string;
  dockerHostId: string;
  createdAt: number;
  mimirEnabled: boolean;
  lokiEnabled: boolean;
  tempoEnabled: boolean;
  collectLogs: boolean;
  collectMetrics: boolean;
  otlpEnabled: boolean;
  metricsRetentionDays: number;
  logsRetentionDays: number;
  tracesRetentionDays: number;
  access: "PUBLIC" | "LOCAL";
  hostname: string | null;
  localPort: number | null;
  ingressRouteId: string | null;
  signInUrl: string | null;
  grafanaAdminUser: string;
  activity: string | null;
  lastResult: string | null;
  lastResultFailed: boolean;
}

export interface ComponentView {
  key: string;
  name: string;
  description: string;
  container: string;
  image: string;
  enabled: boolean;
  /** Docker's state, "missing" when it should run but doesn't exist, "absent" when turned off. */
  state: string;
  containerId: string | null;
  outdated: boolean;
}

export interface StackView {
  stack: ObservabilityStack;
  components: ComponentView[];
  grafanaUrl: string;
  certificate: { status: "PENDING" | "ACTIVE" | "EXPIRED" | "RENEWAL_PENDING" | "ERROR" | "MISSING"; message: string } | null;
  busy: boolean;
}

/** What the create and settings forms send. */
export interface Settings {
  mimir: boolean;
  loki: boolean;
  tempo: boolean;
  collectLogs: boolean;
  collectMetrics: boolean;
  otlp: boolean;
  metricsRetentionDays: number;
  logsRetentionDays: number;
  tracesRetentionDays: number;
  access: "PUBLIC" | "LOCAL";
  hostname: string | null;
  port: number | null;
  dockerManagerUrl: string;
}

export const ALLOY = "dm-obs-alloy";
