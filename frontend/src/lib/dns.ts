// Types and helpers for the DNS pages (Docker Manager's own authoritative DNS server).

export interface DnsServerStatus {
  running: boolean;
  port: number;
  startedAt: number | null;
  queries: number;
  rateLimited: number;
  error: string | null;
}

export interface DnsSettings {
  enabled: boolean;
  nameservers: string[];
  publicIpv4: string | null;
  publicIpv6: string | null;
  /** The address used when publicIpv4 is empty: the public address of the host Docker Manager runs on. */
  effectiveIpv4: string | null;
  effectiveIpv6: string | null;
  hostmasterEmail: string | null;
  transferAllowed: string[];
  rateLimit: number;
  server: DnsServerStatus;
}

export type DelegationStatus = "OK" | "PARTIAL" | "NO" | "UNKNOWN";

export interface ZoneSummary {
  id: string;
  name: string;
  comment: string | null;
  records: number;
  dnssec: boolean;
  serial: number;
  delegationStatus: DelegationStatus | string | null;
  delegationCheckedAt: number | null;
  warnings: number;
}

export interface DnsZone {
  id: string;
  name: string;
  comment: string | null;
  defaultTtl: number;
  negativeTtl: number;
  nameserversJson: string | null;
  autoRecords: boolean;
  dnssecEnabled: boolean;
  dnssecKeyCreatedAt: number | null;
  serial: number;
  delegationStatus: DelegationStatus | string | null;
  delegationDetail: string | null;
  delegationCheckedAt: number | null;
  createdAt: number;
  updatedAt: number;
}

export type RoutingPolicy = "WEIGHTED" | "FAILOVER";
export type FailoverRole = "PRIMARY" | "SECONDARY";

export interface RecordSet {
  id: string;
  /** Relative to the zone, "@" for the zone itself. */
  name: string;
  fqdn: string;
  type: string;
  ttl: number | null;
  values: string[];
  routingPolicy: RoutingPolicy | null;
  setIdentifier: string | null;
  weight: number | null;
  failoverRole: FailoverRole | null;
  healthCheckId: string | null;
  comment: string | null;
}

export interface GeneratedRecord {
  name: string;
  type: string;
  values: string[];
  kind: "AUTO" | "NAMESERVER" | "CHALLENGE";
  source: string;
  /** False when a record of your own with the same name and type replaces it. */
  active: boolean;
}

export interface Glue {
  name: string;
  inZone: boolean;
  ipv4: string | null;
  ipv6: string | null;
}

export interface DsInfo {
  keyTag: number;
  algorithm: number;
  digestType: number;
  digest: string;
  record: string;
}

export interface ZoneDetail {
  zone: DnsZone;
  nameservers: string[];
  ownNameservers: boolean;
  glue: Glue[];
  records: RecordSet[];
  generated: GeneratedRecord[];
  warnings: string[];
  ds: DsInfo | null;
  dnsEnabled: boolean;
}

export interface DelegationResult {
  status: DelegationStatus | string;
  detail: string | null;
  registryNameservers: string[];
  registryDs: string[];
}

export interface HealthCheck {
  id: string;
  name: string;
  type: "HTTP" | "HTTPS" | "TCP";
  address: string;
  port: number;
  path: string;
  hostHeader: string | null;
  intervalSeconds: number;
  timeoutSeconds: number;
  failureThreshold: number;
  notify: boolean;
  enabled: boolean;
  status: "HEALTHY" | "UNHEALTHY" | "UNKNOWN";
  lastResult: string | null;
  lastCheckedAt: number | null;
  lastChangedAt: number | null;
}

export interface HealthCheckView {
  check: HealthCheck;
  usedBy: string[];
}

export interface LookupResult {
  rcode: string;
  authoritative: boolean;
  answer: string[];
  authority: string[];
  additional: string[];
}

export interface ImportResult {
  added: number;
  merged: number;
  skipped: string[];
}

/** The record types that can be added by hand, as the API accepts them. */
export const RECORD_TYPES = ["A", "AAAA", "CNAME", "MX", "TXT", "SRV", "CAA", "NS", "PTR", "DS"] as const;

/** What the values of each record type look like. */
export const VALUE_HINTS: Record<string, { hint: string; placeholder: string }> = {
  A: { hint: "IPv4 addresses, one per line.", placeholder: "203.0.113.10" },
  AAAA: { hint: "IPv6 addresses, one per line.", placeholder: "2001:db8::10" },
  CNAME: { hint: "One hostname this name is an alias of.", placeholder: "app.example.net" },
  MX: { hint: "A priority and a mail server, one per line. Lower numbers are tried first.", placeholder: "10 mail.example.com" },
  TXT: { hint: "Text, one value per line. Long text is split for you; quotes are optional.", placeholder: "v=spf1 include:_spf.example.com ~all" },
  SRV: { hint: "Priority, weight, port and target, one per line.", placeholder: "10 5 5060 sip.example.com" },
  CAA: { hint: "Flags, tag and value in quotes, one per line.", placeholder: '0 issue "letsencrypt.org"' },
  NS: { hint: "Nameservers that answer for this name instead (a delegated subdomain), one per line.", placeholder: "ns1.other-dns.example" },
  PTR: { hint: "The hostname this address points back to.", placeholder: "host.example.com" },
  DS: { hint: "Key tag, algorithm, digest type and digest, as the delegated zone's DNS host shows them.", placeholder: "12345 13 2 1F2E3D..." },
};

export const ALGORITHMS: Record<number, string> = {
  8: "RSA/SHA-256",
  13: "ECDSA P-256 with SHA-256",
  14: "ECDSA P-384 with SHA-384",
  15: "Ed25519",
};

export const DIGEST_TYPES: Record<number, string> = {
  1: "SHA-1",
  2: "SHA-256",
  4: "SHA-384",
};

/** Splits a text box into its non-empty lines. */
export function lines(text: string): string[] {
  return text
    .split(/\r?\n/)
    .map((l) => l.trim())
    .filter(Boolean);
}

/** The record's name as people read it: the zone name for "@", otherwise the full name. */
export function displayName(name: string, zone: string) {
  return name === "@" ? zone : `${name}.${zone}`;
}
