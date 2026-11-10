"use client";

import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from "react";
import { usePathname, useRouter } from "next/navigation";
import { api, getSession, onSessionChange, PASSWORD_REQUIRED_EVENT, refreshSession, type Session } from "@/lib/api";
import type { Host } from "@/lib/types";

// --- session ---------------------------------------------------------------------------------

const SessionContext = createContext<{ session: Session | null; ready: boolean }>({ session: null, ready: false });

export function useSession() {
  return useContext(SessionContext);
}

const PUBLIC = ["/login"];

export function SessionProvider({ children }: { children: ReactNode }) {
  const [session, setSession] = useState<Session | null>(getSession());
  const [ready, setReady] = useState(false);
  const path = usePathname();
  const router = useRouter();

  useEffect(() => onSessionChange(setSession), []);

  useEffect(() => {
    let alive = true;
    (async () => {
      const s = getSession() ?? (await refreshSession());
      if (!alive) return;
      setSession(s);
      setReady(true);
    })();
    return () => {
      alive = false;
    };
  }, []);

  // Send people to sign in, or to change a temporary password, before anything else.
  useEffect(() => {
    if (!ready) return;
    const isPublic = PUBLIC.some((p) => path.startsWith(p));
    if (!session && !isPublic) {
      const next = path && path !== "/" ? `?next=${encodeURIComponent(path + window.location.search)}` : "";
      router.replace(`/login/${next}`);
    } else if (session?.mustChangePassword && !path.startsWith("/account")) {
      router.replace("/account/?first=1");
    }
  }, [ready, session, path, router]);

  useEffect(() => {
    const onRequired = () => router.replace("/account/?first=1");
    window.addEventListener(PASSWORD_REQUIRED_EVENT, onRequired);
    return () => window.removeEventListener(PASSWORD_REQUIRED_EVENT, onRequired);
  }, [router]);

  return <SessionContext.Provider value={{ session, ready }}>{children}</SessionContext.Provider>;
}

// --- hosts -----------------------------------------------------------------------------------

interface HostState {
  hosts: Host[];
  host: Host | null;
  hostId: string | null;
  loaded: boolean;
  select: (id: string) => void;
  reload: () => void;
}

const HostContext = createContext<HostState>({
  hosts: [],
  host: null,
  hostId: null,
  loaded: false,
  select: () => {},
  reload: () => {},
});

/** The Docker host the pages work on, chosen in the sidebar and remembered per browser. */
export function useHost() {
  return useContext(HostContext);
}

const HOST_KEY = "dm.host";

export function HostProvider({ children }: { children: ReactNode }) {
  const { session } = useSession();
  const [hosts, setHosts] = useState<Host[]>([]);
  const [selected, setSelected] = useState<string | null>(null);
  const [loaded, setLoaded] = useState(false);
  const [tick, setTick] = useState(0);

  useEffect(() => {
    try {
      // eslint-disable-next-line react-hooks/set-state-in-effect
      setSelected(localStorage.getItem(HOST_KEY));
    } catch {}
  }, []);

  useEffect(() => {
    if (!session || session.mustChangePassword) return;
    let alive = true;
    api
      .get<Host[]>("/api/docker/hosts")
      .then((h) => {
        if (!alive) return;
        setHosts(h);
        setLoaded(true);
      })
      .catch(() => alive && setLoaded(true));
    return () => {
      alive = false;
    };
  }, [session, tick]);

  const select = useCallback((id: string) => {
    setSelected(id);
    try {
      localStorage.setItem(HOST_KEY, id);
    } catch {}
  }, []);

  const value = useMemo(() => {
    const host = hosts.find((h) => h.id === selected) ?? hosts[0] ?? null;
    return { hosts, host, hostId: host?.id ?? null, loaded, select, reload: () => setTick((t) => t + 1) };
  }, [hosts, selected, loaded, select]);

  return <HostContext.Provider value={value}>{children}</HostContext.Provider>;
}
