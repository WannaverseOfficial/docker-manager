import type { Container, Host } from "./types";

/** Decimal units, as Docker itself reports sizes. */
export function bytes(n: number | null | undefined, digits = 1): string {
  if (!n || n < 0) return "0 B";
  const units = ["B", "KB", "MB", "GB", "TB"];
  let i = 0;
  let v = n;
  while (v >= 1000 && i < units.length - 1) {
    v /= 1000;
    i++;
  }
  return `${v.toFixed(i >= 2 ? digits : 0)} ${units[i]}`;
}

/** Memory in binary units (what `docker stats` shows as MiB/GiB). */
export function mem(n: number | null | undefined): string {
  if (!n || n < 0) return "0 MiB";
  const mib = n / 1024 ** 2;
  if (mib < 1024) return `${mib < 10 ? mib.toFixed(1) : Math.round(mib)} MiB`;
  return `${(mib / 1024).toFixed(1)} GiB`;
}

export function percent(n: number | null | undefined, digits = 1): string {
  if (n == null || Number.isNaN(n)) return "0%";
  return `${n.toFixed(n >= 10 ? 0 : digits)}%`;
}

/** Relative time for epoch milliseconds (or seconds, when the value is clearly seconds). */
export function ago(ts: number | string | null | undefined): string {
  if (ts == null || ts === 0 || ts === "") return "never";
  let ms = typeof ts === "string" ? Date.parse(ts) : ts;
  if (!Number.isFinite(ms)) return "unknown";
  if (ms < 1e12) ms *= 1000;
  const s = (Date.now() - ms) / 1000;
  if (s < 0) return "just now";
  if (s < 45) return "just now";
  if (s < 3600) return `${Math.max(1, Math.round(s / 60))} min ago`;
  if (s < 86400) return `${Math.round(s / 3600)} h ago`;
  if (s < 86400 * 30) return `${Math.round(s / 86400)} d ago`;
  return new Date(ms).toLocaleDateString();
}

export function dateTime(ts: number | string | null | undefined): string {
  if (ts == null || ts === 0 || ts === "") return "";
  let ms = typeof ts === "string" ? Date.parse(ts) : ts;
  if (ms < 1e12) ms *= 1000;
  return new Date(ms).toLocaleString();
}

export function duration(ms: number | null | undefined): string {
  if (!ms || ms < 0) return "";
  const s = ms / 1000;
  if (s < 60) return `${s < 10 ? s.toFixed(1) : Math.round(s)} s`;
  const m = Math.floor(s / 60);
  if (m < 60) return `${m} min ${Math.round(s % 60)} s`;
  return `${Math.floor(m / 60)} h ${m % 60} min`;
}

export function shortId(id: string | null | undefined): string {
  if (!id) return "";
  return id.replace(/^sha256:/, "").slice(0, 12);
}

export function containerName(c: Pick<Container, "Names" | "Id">): string {
  return c.Names?.[0]?.replace(/^\//, "") || shortId(c.Id);
}

export function hostLabel(h: Host | null | undefined): string {
  if (!h) return "";
  if (h.name) return h.name;
  return h.dockerHostUrl.replace(/^unix:\/\//, "").replace(/^tcp:\/\//, "").replace(/^ssh:\/\//, "");
}

/** "nginx:1.27-alpine" -> { repo: "nginx", tag: "1.27-alpine" } */
export function splitImage(ref: string): { repo: string; tag: string } {
  const at = ref.indexOf("@");
  if (at >= 0) return { repo: ref.slice(0, at), tag: ref.slice(at + 1, at + 20) };
  const slash = ref.lastIndexOf("/");
  const colon = ref.lastIndexOf(":");
  if (colon > slash) return { repo: ref.slice(0, colon), tag: ref.slice(colon + 1) };
  return { repo: ref, tag: "latest" };
}

/** Published ports as "8080 → 80/tcp", unique and sorted. */
export function portList(ports: Container["Ports"]): string[] {
  const seen = new Set<string>();
  const out: string[] = [];
  for (const p of [...(ports ?? [])].sort((a, b) => (a.PublicPort ?? 0) - (b.PublicPort ?? 0))) {
    if (!p.PublicPort) continue;
    const s = `${p.PublicPort} → ${p.PrivatePort}${p.Type === "tcp" ? "" : "/" + p.Type}`;
    if (!seen.has(s)) {
      seen.add(s);
      out.push(s);
    }
  }
  return out;
}

export function plural(n: number, word: string, many = word + "s") {
  return `${n} ${n === 1 ? word : many}`;
}

export function titleCase(s: string): string {
  return s
    .toLowerCase()
    .replace(/_/g, " ")
    .replace(/^\w/, (c) => c.toUpperCase());
}
