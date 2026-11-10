// Thin client for the Docker Manager API. Same origin in production, NEXT_PUBLIC_API_BASE in development.
//
// Sessions: the short-lived access token is kept in memory only (never in storage, so page scripts injected by an
// attacker can't find it later). The refresh token is an HttpOnly cookie the browser sends to /api/auth only. When the
// access token expires, the next request refreshes it once and retries.

export const API_BASE = (process.env.NEXT_PUBLIC_API_BASE ?? "").replace(/\/$/, "");

export class ApiError extends Error {
  status: number;
  code?: string;
  constructor(message: string, status: number, code?: string) {
    super(message);
    this.status = status;
    this.code = code;
  }
}

export interface Session {
  accessToken: string;
  userId: string;
  username: string;
  admin: boolean;
  mustChangePassword: boolean;
}

let session: Session | null = null;
let refreshing: Promise<Session | null> | null = null;
const listeners = new Set<(s: Session | null) => void>();

export function getSession() {
  return session;
}

export function onSessionChange(cb: (s: Session | null) => void) {
  listeners.add(cb);
  return () => {
    listeners.delete(cb);
  };
}

function setSession(s: Session | null) {
  session = s;
  listeners.forEach((l) => l(s));
}

async function readError(r: Response): Promise<ApiError> {
  let message = r.status === 0 ? "Can't reach Docker Manager" : `${r.status} ${r.statusText}`;
  let code: string | undefined;
  try {
    const body = await r.json();
    message = body.message || body.error || message;
    code = body.code;
  } catch {}
  if (r.status === 502 && message.startsWith("502")) message = "Docker didn't answer";
  return new ApiError(message, r.status, code);
}

/** Exchanges the refresh cookie for a new access token. Concurrent callers share one request. */
export function refreshSession(): Promise<Session | null> {
  if (!refreshing) {
    const call = () =>
      fetch(API_BASE + "/api/auth/refresh", { method: "POST", credentials: "include" });
    refreshing = call()
      .then(async (r) => {
        // 409: another tab rotated the cookie a moment ago. The new cookie is already set.
        if (r.status === 409) {
          await new Promise((res) => setTimeout(res, 400));
          r = await call();
        }
        if (!r.ok || r.status === 204) {
          setSession(null);
          return null;
        }
        const s = (await r.json()) as Session;
        setSession(s);
        return s;
      })
      .catch(() => null)
      .finally(() => {
        refreshing = null;
      });
  }
  return refreshing;
}

export async function login(username: string, password: string) {
  const r = await fetch(API_BASE + "/api/auth/login", {
    method: "POST",
    credentials: "include",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ username, password }),
  });
  if (!r.ok) throw await readError(r);
  const s = (await r.json()) as Session;
  setSession(s);
  return s;
}

export async function logout() {
  try {
    await fetch(API_BASE + "/api/auth/logout", { method: "POST", credentials: "include" });
  } finally {
    setSession(null);
  }
}

export async function changePassword(currentPassword: string, newPassword: string) {
  const s = await request<Session>("POST", "/api/auth/change-password", { currentPassword, newPassword });
  setSession(s);
  return s;
}

/** Sent when the server says the password must be changed first; the shell listens for it. */
export const PASSWORD_REQUIRED_EVENT = "dm-password-required";

async function request<T>(method: string, path: string, body?: unknown, retried = false): Promise<T> {
  if (!session && !retried) await refreshSession();
  const headers: Record<string, string> = {};
  if (session) headers.Authorization = `Bearer ${session.accessToken}`;
  if (body !== undefined) headers["Content-Type"] = "application/json";
  let r: Response;
  try {
    r = await fetch(API_BASE + path, {
      method,
      headers,
      credentials: "include",
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  } catch {
    throw new ApiError("Can't reach Docker Manager. Check that it is running.", 0);
  }
  if (r.status === 401 && !retried) {
    const s = await refreshSession();
    if (s) return request<T>(method, path, body, true);
  }
  if (!r.ok) {
    const err = await readError(r);
    if (err.code === "PASSWORD_CHANGE_REQUIRED" && typeof window !== "undefined") {
      window.dispatchEvent(new Event(PASSWORD_REQUIRED_EVENT));
    }
    if (r.status === 401) setSession(null);
    throw err;
  }
  if (r.status === 204) return undefined as T;
  const type = r.headers.get("content-type") ?? "";
  return (type.includes("json") ? r.json() : r.text()) as Promise<T>;
}

/** A signed-in request with any body (such as an upload), returning the raw response. */
async function authed(method: string, path: string, body?: BodyInit, retried = false): Promise<Response> {
  if (!session && !retried) await refreshSession();
  const headers: Record<string, string> = {};
  if (session) headers.Authorization = `Bearer ${session.accessToken}`;
  let r: Response;
  try {
    r = await fetch(API_BASE + path, { method, headers, credentials: "include", body });
  } catch {
    throw new ApiError("Can't reach Docker Manager. Check that it is running.", 0);
  }
  if (r.status === 401 && !retried) {
    const s = await refreshSession();
    if (s) return authed(method, path, body, true);
  }
  if (!r.ok) throw await readError(r);
  return r;
}

/** Downloads a file the API sends as an attachment and hands it to the browser to save. */
async function download(path: string, fallbackName = "download") {
  const r = await authed("GET", path);
  const disposition = r.headers.get("content-disposition") ?? "";
  const star = /filename\*=UTF-8''([^;]+)/i.exec(disposition);
  const plain = /filename="?([^";]+)"?/i.exec(disposition);
  const name = star ? decodeURIComponent(star[1]) : plain ? plain[1] : fallbackName;
  const url = URL.createObjectURL(await r.blob());
  const a = document.createElement("a");
  a.href = url;
  a.download = name;
  a.click();
  setTimeout(() => URL.revokeObjectURL(url), 2000);
}

export const api = {
  get: <T>(path: string) => request<T>("GET", path),
  post: <T>(path: string, body?: unknown) => request<T>("POST", path, body),
  put: <T>(path: string, body?: unknown) => request<T>("PUT", path, body),
  patch: <T>(path: string, body?: unknown) => request<T>("PATCH", path, body),
  del: <T>(path: string) => request<T>("DELETE", path),
  upload: async <T>(path: string, form: FormData) => (await (await authed("POST", path, form)).json()) as T,
  download,
};

/** Builds a query string, leaving out empty values. */
export function qs(params: Record<string, string | number | boolean | undefined | null>) {
  const p = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) {
    if (v !== undefined && v !== null && v !== "") p.set(k, String(v));
  }
  const s = p.toString();
  return s ? `?${s}` : "";
}

export const enc = encodeURIComponent;
