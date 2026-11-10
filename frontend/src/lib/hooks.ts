"use client";

import { useCallback, useEffect, useMemo, useRef, useState, useSyncExternalStore } from "react";
import { api } from "./api";
import type { Job } from "./types";

/**
 * Fetch a JSON endpoint and refresh it every `interval` ms (0 = once). Polling pauses while the tab is hidden and
 * catches up as soon as it is visible again, so a dozen open tabs don't keep hammering Docker.
 */
export function usePoll<T>(path: string | null, interval = 0) {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(!!path);
  const [tick, setTick] = useState(0);
  const lastPath = useRef(path);

  useEffect(() => {
    if (!path) return;
    if (lastPath.current !== path) {
      lastPath.current = path;
      setData(null);
      setLoading(true);
    }
    let alive = true;
    let timer: ReturnType<typeof setTimeout> | undefined;
    const load = async () => {
      if (interval && typeof document !== "undefined" && document.hidden) {
        timer = setTimeout(load, 1000);
        return;
      }
      try {
        const d = await api.get<T>(path);
        if (alive) {
          setData(d);
          setError(null);
        }
      } catch (e) {
        if (alive) setError((e as Error).message);
      } finally {
        if (alive) setLoading(false);
      }
      if (alive && interval) timer = setTimeout(load, interval);
    };
    load();
    return () => {
      alive = false;
      if (timer) clearTimeout(timer);
    };
  }, [path, interval, tick]);

  const refresh = useCallback(() => setTick((t) => t + 1), []);
  return { data, error, loading, refresh, setData };
}

/** Runs an action and tracks whether it is busy and what went wrong. */
export function useAction() {
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const run = useCallback(async <T,>(key: string, fn: () => Promise<T>): Promise<T | undefined> => {
    setBusy(key);
    setError(null);
    try {
      return await fn();
    } catch (e) {
      setError((e as Error).message);
      return undefined;
    } finally {
      setBusy(null);
    }
  }, []);
  return { busy, error, setError, run };
}

/** Follows a background stack job, collecting its output, until it finishes. */
export function useStackJob(hostId: string | null, onDone?: (job: Job) => void) {
  const [job, setJob] = useState<Job | null>(null);
  const [lines, setLines] = useState<string[]>([]);
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const doneRef = useRef(onDone);
  useEffect(() => {
    doneRef.current = onDone;
  });

  useEffect(
    () => () => {
      if (timer.current) clearTimeout(timer.current);
    },
    [],
  );

  const follow = useCallback(
    (jobId: string) => {
      if (!hostId) return;
      if (timer.current) clearTimeout(timer.current);
      setLines([]);
      let next = 0;
      const tick = async () => {
        try {
          const j = await api.get<Job>(`/api/docker/hosts/${hostId}/stacks/jobs/${jobId}?from=${next}`);
          next = j.next;
          setJob(j);
          if (j.lines.length) setLines((l) => [...l, ...j.lines]);
          if (j.status !== "running") {
            doneRef.current?.(j);
            return;
          }
        } catch {
          return;
        }
        timer.current = setTimeout(tick, 800);
      };
      tick();
    },
    [hostId],
  );

  const running = job?.status === "running";
  return { job, lines, running, follow, clear: () => (setJob(null), setLines([])) };
}

/** localStorage-backed state, safe during static prerender. */
export function useStored<T>(key: string, initial: T) {
  const [value, setValue] = useState<T>(initial);
  useEffect(() => {
    try {
      const raw = localStorage.getItem(key);
      // eslint-disable-next-line react-hooks/set-state-in-effect
      if (raw) setValue(JSON.parse(raw));
    } catch {}
  }, [key]);
  const set = useCallback(
    (next: T | ((prev: T) => T)) =>
      setValue((prev) => {
        const v = typeof next === "function" ? (next as (p: T) => T)(prev) : next;
        try {
          localStorage.setItem(key, JSON.stringify(v));
        } catch {}
        return v;
      }),
    [key],
  );
  return [value, set] as const;
}

/**
 * Splits a list into pages. Going back to the first page whenever `resetKey` changes (a new search or filter)
 * keeps the view from landing on a page that no longer exists.
 */
export function usePaged<T>(items: T[], resetKey: string, initialSize = 25) {
  const [state, setState] = useState({ key: resetKey, page: 0 });
  const [size, setSizeState] = useStored("dm.pageSize", initialSize);
  const pages = Math.max(1, Math.ceil(items.length / size));
  const page = Math.min(state.key === resetKey ? state.page : 0, pages - 1);
  const slice = useMemo(() => items.slice(page * size, (page + 1) * size), [items, page, size]);
  const setPage = useCallback((p: number) => setState({ key: resetKey, page: p }), [resetKey]);
  const setSize = useCallback(
    (s: number) => {
      setSizeState(s);
      setState({ key: resetKey, page: 0 });
    },
    [resetKey, setSizeState],
  );
  return { slice, page, pages, size, total: items.length, setPage, setSize };
}

/** A set of selected IDs. Selections that disappear from `available` (deleted, filtered out) drop away. */
export function useSelection(available: string[]) {
  const [picked, setPicked] = useState<Set<string>>(() => new Set());
  const availableSet = useMemo(() => new Set(available), [available]);
  const selected = useMemo(() => [...picked].filter((id) => availableSet.has(id)), [picked, availableSet]);
  const isSelected = useCallback((id: string) => picked.has(id) && availableSet.has(id), [picked, availableSet]);
  const toggle = useCallback(
    (id: string, on?: boolean) =>
      setPicked((prev) => {
        const next = new Set(prev);
        if (on ?? !next.has(id)) next.add(id);
        else next.delete(id);
        return next;
      }),
    [],
  );
  const setMany = useCallback(
    (ids: string[], on: boolean) =>
      setPicked((prev) => {
        const next = new Set(prev);
        for (const id of ids) {
          if (on) next.add(id);
          else next.delete(id);
        }
        return next;
      }),
    [],
  );
  const clear = useCallback(() => setPicked(new Set()), []);
  return { selected, isSelected, toggle, setMany, clear };
}

/** Runs `fn` for every item, a few at a time, and reports which ones failed. */
export async function runBulk<T>(items: T[], fn: (item: T) => Promise<unknown>, concurrency = 4) {
  const failed: { item: T; message: string }[] = [];
  let next = 0;
  const worker = async () => {
    while (next < items.length) {
      const item = items[next++];
      try {
        await fn(item);
      } catch (e) {
        failed.push({ item, message: (e as Error).message });
      }
    }
  };
  await Promise.all(Array.from({ length: Math.min(concurrency, items.length) }, worker));
  return { done: items.length - failed.length, failed };
}

// --- actions under way --------------------------------------------------------------------------

const pending = new Map<string, string>();
const pendingListeners = new Set<() => void>();
let pendingVersion = 0;

/** Marks something (a container ID) as busy with a label such as "Stopping", or clears it with null. */
export function setPending(id: string, label: string | null) {
  if (label) pending.set(id, label);
  else pending.delete(id);
  pendingVersion++;
  pendingListeners.forEach((l) => l());
}

/** The label of the action under way for `id`, if any. Shared across pages. */
export function usePending(id: string | undefined) {
  useSyncExternalStore(
    (cb) => {
      pendingListeners.add(cb);
      return () => pendingListeners.delete(cb);
    },
    () => pendingVersion,
    () => 0,
  );
  return id ? pending.get(id) : undefined;
}
