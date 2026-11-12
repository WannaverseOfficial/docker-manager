"use client";

// What the configuration file manages. Pages use it to mark those items and hide the buttons that would change them
// (the API refuses such changes anyway, with code MANAGED_BY_CONFIG). One shared request, refreshed every 30 seconds.

import { useEffect, useSyncExternalStore } from "react";
import { api } from "./api";

export type ManagedItem = {
  section: string;
  /** Administrators only. */
  key?: string;
  targetId: string;
  hostId: string | null;
  /** Administrators only. */
  label?: string | null;
};

let items: ManagedItem[] = [];
let loadedAt = 0;
let inflight: Promise<void> | null = null;
const listeners = new Set<() => void>();

function load(force = false) {
  if (inflight || (!force && Date.now() - loadedAt < 30000)) return;
  inflight = api
    .get<ManagedItem[]>("/api/config/managed")
    .then((d) => {
      items = d ?? [];
      loadedAt = Date.now();
      listeners.forEach((l) => l());
    })
    .catch(() => {})
    .finally(() => {
      inflight = null;
    });
}

/** Fetch again now, for example after applying a configuration. */
export function refreshManaged() {
  load(true);
}

function subscribe(cb: () => void) {
  listeners.add(cb);
  return () => {
    listeners.delete(cb);
  };
}

export function useManaged() {
  const list = useSyncExternalStore(
    subscribe,
    () => items,
    () => items,
  );
  useEffect(() => {
    load();
    const t = setInterval(() => load(), 30000);
    return () => clearInterval(t);
  }, []);
  /** The record for a resource, if the file manages it. Leave hostId out to match on any host. */
  const find = (section: string, targetId: string | null | undefined, hostId?: string | null) =>
    targetId ? list.find((i) => i.section === section && i.targetId === targetId && (hostId == null || i.hostId === hostId)) : undefined;
  return {
    items: list,
    find,
    isManaged: (section: string, targetId: string | null | undefined, hostId?: string | null) => !!find(section, targetId, hostId),
    /** Whether the section manages something on this host (per-host sections: ingress, observability...). */
    onHost: (section: string, hostId: string | null | undefined) => !!hostId && list.some((i) => i.section === section && i.hostId === hostId),
    /** Whether the section has any managed entry (for singletons such as smtp). */
    sectionManaged: (section: string) => list.some((i) => i.section === section),
  };
}
