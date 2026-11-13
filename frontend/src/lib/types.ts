// Shapes returned by the API. Docker's own objects keep Docker's capitalised keys; Docker Manager's are camelCase.

export interface Host {
  id: string;
  name: string | null;
  dockerHostUrl: string;
  /** tcp:// with TLS certificates */
  tls?: boolean;
  hasSshKey?: boolean;
  /** Where the internet reaches this host. DNS records for its ingress routes point here. */
  publicIpv4?: string | null;
  publicIpv6?: string | null;
}

// --- Docker objects ---------------------------------------------------------------------------

export interface DockerPort {
  IP?: string;
  PrivatePort: number;
  PublicPort?: number;
  Type: string;
}

export interface DockerMount {
  Type: string;
  Name?: string;
  Source: string;
  Destination: string;
  RW: boolean;
}

export interface Container {
  Id: string;
  Names: string[];
  Image: string;
  ImageID: string;
  Command: string;
  Created: number;
  State: string;
  Status: string;
  Ports: DockerPort[];
  Labels: Record<string, string>;
  Mounts?: DockerMount[];
  NetworkSettings?: { Networks?: Record<string, { IPAddress?: string; NetworkID?: string }> };
}

export interface ContainerInspect {
  Id: string;
  Name: string;
  Created: string;
  Path: string;
  Args: string[];
  Image: string;
  RestartCount: number;
  State: {
    Status: string;
    Running: boolean;
    Paused: boolean;
    Restarting: boolean;
    OOMKilled: boolean;
    ExitCode: number;
    Error: string;
    StartedAt: string;
    FinishedAt: string;
    Health?: { Status: string; FailingStreak: number; Log?: { Start: string; End: string; ExitCode: number; Output: string }[] };
  };
  Config: {
    Hostname: string;
    Image: string;
    Env?: string[];
    Cmd?: string[];
    Entrypoint?: string[];
    WorkingDir?: string;
    User?: string;
    Labels?: Record<string, string>;
    ExposedPorts?: Record<string, unknown>;
  };
  HostConfig: {
    NetworkMode?: string;
    RestartPolicy?: { Name: string; MaximumRetryCount: number };
    Privileged?: boolean;
    Memory?: number;
    NanoCpus?: number;
    PortBindings?: Record<string, { HostIp: string; HostPort: string }[] | null>;
  };
  Mounts?: DockerMount[];
  NetworkSettings?: { Networks?: Record<string, { IPAddress?: string; Gateway?: string; MacAddress?: string; Aliases?: string[] }> };
}

export interface Image {
  Id: string;
  RepoTags: string[] | null;
  RepoDigests: string[] | null;
  Created: number;
  Size: number;
  Containers: number;
  Labels: Record<string, string> | null;
}

export interface Volume {
  Name: string;
  Driver: string;
  Mountpoint: string;
  Labels: Record<string, string> | null;
  CreatedAt?: string;
  Scope?: string;
}

export interface Network {
  Id: string;
  Name: string;
  Driver: string;
  Scope: string;
  Internal: boolean;
  Created: string;
  IPAM?: { Config?: { Subnet?: string; Gateway?: string }[] };
  Containers?: Record<string, { Name: string; IPv4Address: string }> | null;
  Labels?: Record<string, string> | null;
}

// --- Docker Manager ---------------------------------------------------------------------------

export interface Overview {
  host: {
    name: string;
    dockerVersion: string;
    operatingSystem: string;
    kernelVersion: string;
    architecture: string;
    cpus: number;
    memoryBytes: number;
    storageDriver: string;
    dockerRootDir: string;
  };
  containers: { total: number; running: number; paused: number; stopped: number; restarting: number; unhealthy: number };
  images: number;
  imagesBytes: number;
  danglingImages: number;
  volumes: number;
  unusedVolumes: number;
  networks: number;
  stacks: number;
  recentContainers: { id: string; name: string; image: string; state: string; status: string; created: number }[];
}

export interface ContainerStats {
  containerId: string;
  containerName: string;
  cpuPercent: number;
  onlineCpus: number;
  memoryUsage: number;
  memoryLimit: number;
  memoryPercent: number;
  networkRxBytes: number;
  networkTxBytes: number;
  blockReadBytes: number;
  blockWriteBytes: number;
  pids: number;
  timestamp: number;
}

export interface PortMapping {
  hostIp?: string;
  hostPort?: number | null;
  containerPort: number;
  protocol: "tcp" | "udp";
}

export interface ContainerSpec {
  image: string;
  name?: string;
  command: string[];
  entrypoint: string[];
  env: string[];
  ports: PortMapping[];
  volumes: string[];
  networks: string[];
  restartPolicy: "no" | "always" | "unless-stopped" | "on-failure";
  labels: Record<string, string>;
  user?: string | null;
  workingDir?: string | null;
  privileged: boolean;
  capAdd: string[];
  memoryLimitMb: number;
  cpus: number;
  start: boolean;
}

export interface StackSummary {
  name: string;
  managed: boolean;
  source: "UI" | "TEMPLATE" | "GIT" | "IMPORTED" | "EXTERNAL";
  gitRepositoryId: string | null;
  templateName: string | null;
  running: number;
  total: number;
  services: string[];
  lastDeployedAt: number | null;
  updatedAt: number;
  lastError: string | null;
  configFiles: string | null;
  workingDir: string | null;
  /** Docker Manager's own compose project. */
  dockerManager?: boolean;
}

export interface StackDetail {
  summary: StackSummary;
  composeContent: string | null;
  envContent: string | null;
  path: string | null;
  containers: { id: string; name: string; service: string | null; image: string; state: string; status: string; ports: string[] }[];
  versions: { version: number; status: string; createdAt: number; username: string | null; commitSha: string | null }[];
  runningJobId: string | null;
}

export interface Job {
  id: string;
  stack: string;
  action: string;
  status: "running" | "success" | "failed" | "unknown";
  startedAt: number;
  finishedAt: number | null;
  from: number;
  next: number;
  lines: string[];
}

export interface Template {
  id: string;
  name: string;
  description: string;
  category: string;
  logo: string | null;
  type: "CONTAINER" | "COMPOSE";
  system: boolean;
  imageName: string | null;
  defaultPorts: string | null;
  defaultEnv: string | null;
  defaultVolumes: string | null;
  defaultUser: string | null;
  defaultNetwork: string | null;
  composeContent: string | null;
  platform: string | null;
  documentation: string | null;
}

export interface CleanupOptions {
  containers: boolean;
  images: boolean;
  allImages: boolean;
  volumes: boolean;
  networks: boolean;
  buildCache: boolean;
}

export interface CleanupPreview {
  items: { kind: string; id: string; name: string; size: number }[];
  reclaimableBytes: number;
}

export interface CleanupResult {
  containersRemoved: number;
  imagesRemoved: number;
  volumesRemoved: number;
  networksRemoved: number;
  bytesReclaimed: number;
  errors: string[];
}

export interface GitRepo {
  id: string;
  name: string;
  repositoryUrl: string;
  branch: string;
  authType: "NONE" | "PAT" | "SSH_KEY";
  tokenUsername: string | null;
  hasToken: boolean;
  hasSshKey: boolean;
  deploymentType: "DOCKERFILE" | "DOCKER_COMPOSE";
  dockerfilePath: string | null;
  composePath: string | null;
  imageName: string | null;
  runContainer: boolean;
  /** Release new images through this rollout instead of replacing the container. */
  rolloutId: string | null;
  containerSpec: Partial<ContainerSpec> | null;
  dockerHostId: string;
  dockerHostName: string | null;
  pollingEnabled: boolean;
  pollingIntervalSeconds: number;
  webhookEnabled: boolean;
  webhookUrl: string | null;
  lastCommitSha: string | null;
  lastDeployedAt: number;
  createdAt: number;
  driftStatus: "SYNCED" | "BEHIND" | "UNKNOWN" | "ERROR";
  latestRemoteCommitSha: string | null;
  lastDriftCheckAt: number | null;
}

export interface DeploymentJob {
  id: string;
  gitRepositoryId: string;
  gitRepositoryName: string;
  status: "PENDING" | "RUNNING" | "SUCCESS" | "FAILED" | "CANCELLED";
  triggerType: "MANUAL" | "POLLING" | "WEBHOOK";
  commitSha: string | null;
  logs: string | null;
  errorMessage: string | null;
  createdAt: number;
  startedAt: number;
  completedAt: number;
}

export interface IngressConfig {
  id: string | null;
  dockerHostId: string;
  status: "DISABLED" | "ENABLING" | "ENABLED" | "DISABLING" | "ERROR";
  nginxContainerName: string | null;
  ingressNetworkName: string | null;
  httpPort: number;
  httpsPort: number;
  acmeEmail: string | null;
  enabledAt: number | null;
  enabledByUsername: string | null;
  lastError: string | null;
  staging: boolean;
}

export interface NginxStatus {
  containerStatus: string;
  containerId: string | null;
  containerName: string | null;
  activeRoutes: number;
  activeCertificates: number;
  lastError: string | null;
}

export interface IngressRoute {
  id: string;
  hostname: string;
  pathPrefix: string;
  targetContainerId: string;
  targetContainerName: string;
  targetPort: number;
  protocol: "HTTP" | "HTTPS";
  tlsMode: "NONE" | "LETS_ENCRYPT" | "CUSTOM_CERT" | "BRING_YOUR_OWN";
  authEnabled: boolean;
  enabled: boolean;
  certificateId: string | null;
  createdAt: number;
  createdByUsername: string | null;
  forceHttpsRedirect: boolean;
  authUsername: string | null;
  authUsernames?: string[];
  /** CONTAINER, ADDRESS (a host and port typed in) or REDIRECT. */
  targetType: "CONTAINER" | "ADDRESS" | "REDIRECT";
  targetHost: string | null;
  redirectUrl: string | null;
  redirectPermanent: boolean;
  redirectKeepPath: boolean;
  /** Set when a rollout decides where this route's visitors go. */
  rolloutId: string | null;
  canaryContainerName: string | null;
  canaryWeight: number;
}

export interface Certificate {
  id: string;
  hostname: string;
  source: "LETS_ENCRYPT" | "UPLOADED" | "EXTERNAL";
  status: string;
  statusMessage: string | null;
  issuer: string | null;
  issuedAt: number | null;
  expiresAt: number | null;
  daysUntilExpiry: number;
  renewalNeeded: boolean;
  autoRenew: boolean;
  lastRenewalError: string | null;
}

export interface Operation {
  id: string;
  dockerHostId: string;
  operationType: string;
  status: string;
  resourceId: string | null;
  resourceName: string | null;
  username: string | null;
  errorMessage: string | null;
  createdAt: number;
  completedAt: number;
  durationMs: number | null;
  rollbackAvailable: boolean;
}

export interface Page<T> {
  content: T[];
  page: number;
  size: number;
  totalPages: number;
  totalElements?: number;
}

export interface HealthEvent {
  id: string;
  hostId: string;
  containerId: string;
  containerName: string;
  eventType: "CRASH" | "RESTART_LOOP" | "HEALTH_UNHEALTHY" | "OOM_KILLED";
  exitCode: number | null;
  restartCount: number | null;
  errorMessage: string | null;
  notificationSent: boolean;
  detectedAt: number;
  resolvedAt: number | null;
  active: boolean;
}

export interface HealthSummary {
  activeIssues: number;
  totalEvents24h: number;
  totalEvents7d: number;
  eventsByType: Record<string, number>;
  eventsByType24h: Record<string, number>;
}

export interface AuditEntry {
  id: string;
  timestamp: number;
  userId: string | null;
  username: string | null;
  action: string;
  resourceType: string;
  resourceId: string | null;
  ipAddress: string | null;
  details: string | null;
  success: boolean;
  errorMessage: string | null;
}

export interface User {
  id: string;
  username: string;
  email: string | null;
  admin: boolean;
  enabled: boolean;
  mustChangePassword: boolean;
  createdAt: string;
  lastLoginAt: string | null;
  groupIds: string[];
  groupNames: string[];
}

export interface Group {
  id: string;
  name: string;
  description: string | null;
  memberCount: number;
  memberIds: string[];
}

export interface Permission {
  id: string;
  resource: string;
  action: string;
  scopeHostId: string | null;
  scopeResourceId: string | null;
  source?: string;
}

export interface Registry {
  id: string;
  name: string;
  url: string;
  registryType: "DOCKER_HUB" | "PRIVATE" | "AWS_ECR" | "GCR" | "ACR";
  hasCredentials: boolean;
  isDefault: boolean;
  enabled: boolean;
  awsRegion: string | null;
  gcpProjectId: string | null;
  azureClientId: string | null;
  azureTenantId: string | null;
}

export interface Policy {
  id: string;
  name: string;
  description: string | null;
  policyType: "ALLOW" | "DENY";
  enabled: boolean;
  priority: number;
  ruleCount: number;
  rules?: { id: string; pattern: string; description: string | null }[];
}

export interface Pipeline {
  id: string;
  name: string;
  description: string | null;
  gitRepositoryId: string | null;
  gitRepositoryName: string | null;
  dockerHostId: string | null;
  dockerHostName: string | null;
  enabled: boolean;
  webhookEnabled: boolean;
  pollingEnabled: boolean;
  pollingIntervalSeconds: number;
  branchFilter: string | null;
  webhookSecret: string | null;
  stages: PipelineStage[];
  totalExecutions: number;
  successfulExecutions: number;
  failedExecutions: number;
  lastExecutionAt: number | null;
  lastExecutionStatus: string | null;
}

export interface PipelineStage {
  id?: string;
  name: string;
  orderIndex?: number;
  executionMode: "SEQUENTIAL" | "PARALLEL";
  dependsOn: string[];
  stopOnFailure: boolean;
  steps: PipelineStep[];
}

export type StepType = "SHELL" | "JAR" | "DOCKERFILE" | "DOCKER_COMPOSE" | "CUSTOM_IMAGE" | "ROLLOUT";

export interface PipelineStep {
  id?: string;
  name: string;
  stepType: StepType;
  configuration: string;
  workingDirectory?: string | null;
  timeoutSeconds: number;
  continueOnFailure: boolean;
  environmentVariables?: string | null;
}

export interface PipelineRun {
  id: string;
  pipelineId: string;
  pipelineName: string;
  buildNumber: number;
  status: "PENDING" | "RUNNING" | "SUCCESS" | "FAILED" | "CANCELLED";
  triggerType: string;
  triggerCommit: string | null;
  triggerBranch: string | null;
  triggeredBy: string | null;
  logs: string | null;
  errorMessage: string | null;
  createdAt: number;
  startedAt: number;
  finishedAt: number;
  durationMs: number;
  stageExecutions?: {
    id: string;
    stageName: string;
    status: string;
    durationMs: number;
    stepExecutions: { id: string; stepName: string; status: string; exitCode: number; durationMs: number; errorMessage: string | null }[];
  }[];
}

// --- rollouts ----------------------------------------------------------------------------------

export type RolloutTestKind = "HTTP" | "COMMAND" | "PIPELINE" | "APPROVAL";

export interface RolloutTest {
  name?: string | null;
  kind: RolloutTestKind;
  path?: string | null;
  expectStatus?: string | null;
  bodyContains?: string | null;
  retries?: number | null;
  image?: string | null;
  script?: string | null;
  pipelineId?: string | null;
  timeoutSeconds?: number | null;
}

export interface RolloutSlot {
  name: string;
  role: "active" | "previous" | "new" | "idle";
  exists: boolean;
  state: string | null;
  image: string | null;
  traffic: number | null;
}

export type RolloutRunStatus = "PENDING" | "RUNNING" | "WAITING_APPROVAL" | "SUCCEEDED" | "FAILED" | "ROLLED_BACK" | "CANCELLED";

export interface RolloutStep {
  name: string;
  kind: string;
  status: "PENDING" | "RUNNING" | "WAITING" | "PASSED" | "FAILED" | "SKIPPED";
  message: string | null;
  startedAt: number;
  finishedAt: number;
}

export interface RolloutSample {
  at: number;
  percent: number;
  newRequests: number;
  newErrors: number;
  newAvgMs: number;
  oldRequests: number;
  oldErrors: number;
  oldAvgMs: number;
  healthy: boolean;
}

export interface RolloutRun {
  id: string;
  number: number;
  kind: "RELEASE" | "ROLLBACK";
  image: string;
  targetContainer: string | null;
  stableContainer: string | null;
  stableImage: string | null;
  status: RolloutRunStatus;
  phase: string | null;
  trafficPercent: number;
  triggerType: "MANUAL" | "GIT" | "PIPELINE" | "ROLLBACK";
  triggeredBy: string | null;
  commitSha: string | null;
  steps: RolloutStep[];
  metrics: RolloutSample[];
  logs: string | null;
  errorMessage: string | null;
  approvalStep: string | null;
  approvedBy: string | null;
  createdAt: number;
  startedAt: number;
  finishedAt: number;
}

export interface Rollout {
  id: string;
  name: string;
  description: string | null;
  dockerHostId: string;
  dockerHostName: string | null;
  strategy: "BLUE_GREEN" | "CANARY";
  containerName: string;
  containerSpec: Partial<ContainerSpec> | null;
  containerPort: number;
  routeId: string | null;
  hostname: string | null;
  pathPrefix: string | null;
  tlsMode: string | null;
  url: string | null;
  routeEnabled: boolean;
  previewHostname: string | null;
  healthPath: string;
  startTimeoutSeconds: number;
  tests: RolloutTest[];
  canarySteps: string;
  stepSeconds: number;
  stickySessions: boolean;
  autoRollback: boolean;
  maxErrorRate: number;
  maxAvgResponseMs: number;
  minRequests: number;
  bakeSeconds: number;
  keepPreviousRunning: boolean;
  activeContainer: string | null;
  activeImage: string | null;
  previousContainer: string | null;
  previousImage: string | null;
  slots: RolloutSlot[];
  currentRun: RolloutRun | null;
  lastRun: RolloutRun | null;
  gitRepositories: { id: string; name: string }[];
  createdAt: number;
  createdBy: string | null;
}
