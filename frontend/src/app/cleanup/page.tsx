"use client";

import { useEffect, useMemo, useState } from "react";
import { useHost } from "@/components/app";
import { Button, Notice, PageHeader, Panel, Spinner, Toggle, useConfirm, useToast } from "@/components/ui";
import { api } from "@/lib/api";
import { bytes, plural } from "@/lib/format";
import type { CleanupOptions, CleanupPreview, CleanupResult } from "@/lib/types";

const KIND_LABEL: Record<string, [string, string]> = {
  container: ["stopped container", "stopped containers"],
  image: ["image", "images"],
  volume: ["volume", "volumes"],
  network: ["network", "networks"],
  "build-cache": ["build cache", "build cache"],
};

export default function CleanupPage() {
  const { hostId } = useHost();
  const toast = useToast();
  const confirm = useConfirm();
  const [opts, setOpts] = useState<CleanupOptions>({ containers: true, images: true, allImages: false, volumes: false, networks: true, buildCache: true });
  const [preview, setPreview] = useState<CleanupPreview | null>(null);
  const [loading, setLoading] = useState(false);
  const [running, setRunning] = useState(false);
  const [result, setResult] = useState<CleanupResult | null>(null);
  const [error, setError] = useState<string | null>(null);

  const set = (k: keyof CleanupOptions, v: boolean) => setOpts((o) => ({ ...o, [k]: v }));

  useEffect(() => {
    if (!hostId) return;
    let alive = true;
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setLoading(true);
    setError(null);
    api
      .post<CleanupPreview>(`/api/docker/hosts/${hostId}/cleanup/preview`, opts)
      .then((p) => alive && setPreview(p))
      .catch((e) => alive && setError((e as Error).message))
      .finally(() => alive && setLoading(false));
    return () => {
      alive = false;
    };
  }, [hostId, opts, result]);

  const groups = useMemo(() => {
    const g = new Map<string, CleanupPreview["items"]>();
    for (const i of preview?.items ?? []) g.set(i.kind, [...(g.get(i.kind) ?? []), i]);
    return g;
  }, [preview]);

  if (!hostId) return null;

  const run = async () => {
    const count = preview?.items.filter((i) => i.kind !== "build-cache").length ?? 0;
    const ok = await confirm({
      title: "Remove these unused items?",
      body: (
        <>
          {plural(count, "item")} will be deleted{opts.volumes ? ", including volumes and the data in them" : ""}. This can&apos;t be undone.
        </>
      ),
      confirm: "Clean up",
      danger: true,
    });
    if (!ok) return;
    setRunning(true);
    setError(null);
    try {
      const r = await api.post<CleanupResult>(`/api/docker/hosts/${hostId}/cleanup`, opts);
      setResult(r);
      toast(`Freed ${bytes(r.bytesReclaimed)}`);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setRunning(false);
    }
  };

  const nothing = preview && preview.items.filter((i) => i.kind !== "build-cache").length === 0;

  return (
    <>
      <PageHeader title="Clean up" art="cleanup" intro="Free disk space by removing what no container uses. You see exactly what goes before anything is deleted." />
      <div className="grid gap-6 lg:grid-cols-[minmax(0,22rem)_1fr]">
        <Panel title="What to remove">
          <div className="flex flex-col gap-4">
            <Toggle checked={opts.containers} onChange={(v) => set("containers", v)} label="Stopped containers" hint="Exited and never-started containers. Their anonymous volumes go too." />
            <Toggle checked={opts.images} onChange={(v) => set("images", v)} label="Unused images" hint="Images no container uses." />
            {opts.images && (
              <div className="pl-12">
                <Toggle
                  checked={opts.allImages}
                  onChange={(v) => set("allImages", v)}
                  label="Include tagged images"
                  hint="Off: only untagged leftovers from builds and pulls. On: every unused image, like docker image prune -a."
                />
              </div>
            )}
            <Toggle
              checked={opts.volumes}
              onChange={(v) => set("volumes", v)}
              label="Unused volumes"
              hint="Volumes no container uses, including named ones. Their data is deleted."
            />
            <Toggle checked={opts.networks} onChange={(v) => set("networks", v)} label="Unused networks" hint="Networks you created that have no containers." />
            <Toggle checked={opts.buildCache} onChange={(v) => set("buildCache", v)} label="Build cache" hint="Cached layers from image builds." />
          </div>
        </Panel>

        <div className="flex flex-col gap-4 min-w-0">
          {result && (
            <Notice tone={result.errors.length ? "warn" : "ok"} title={`Freed ${bytes(result.bytesReclaimed)}`}>
              Removed {plural(result.containersRemoved, "container")}, {plural(result.imagesRemoved, "image")}, {plural(result.volumesRemoved, "volume")} and{" "}
              {plural(result.networksRemoved, "network")}.
              {result.errors.length > 0 && (
                <ul className="mt-2 text-[13px] text-slate list-disc pl-5">
                  {result.errors.slice(0, 8).map((e) => (
                    <li key={e}>{e}</li>
                  ))}
                </ul>
              )}
            </Notice>
          )}
          {error && <Notice tone="error">{error}</Notice>}
          <Panel
            title="Will be removed"
            action={
              preview && (
                <span className="text-[13px] text-slate tabular-nums">
                  {preview.reclaimableBytes > 0 ? `At least ${bytes(preview.reclaimableBytes)}` : ""}
                </span>
              )
            }
          >
            {loading && !preview ? (
              <Spinner label="Checking" />
            ) : nothing ? (
              <p className="text-[14px] text-slate">Nothing to remove with these choices. This host is tidy.</p>
            ) : (
              <div className={`flex flex-col gap-5 ${loading ? "opacity-60" : ""}`}>
                {[...groups.entries()].map(([kind, items]) => (
                  <div key={kind}>
                    <div className="text-[14px] font-medium mb-1.5">
                      {kind === "build-cache" ? "Build cache" : `${items.length} ${KIND_LABEL[kind]?.[items.length === 1 ? 0 : 1] ?? kind}`}
                    </div>
                    {kind !== "build-cache" && (
                      <ul className="text-[13px] text-slate grid sm:grid-cols-2 gap-x-6 gap-y-0.5">
                        {items.slice(0, 40).map((i) => (
                          <li key={i.id} className="truncate flex justify-between gap-3">
                            <span className="truncate font-mono">{/^[0-9a-f]{64}$/.test(i.name) ? i.name.slice(0, 12) + "…" : i.name}</span>
                            {i.size > 0 && <span className="tabular-nums shrink-0">{bytes(i.size)}</span>}
                          </li>
                        ))}
                        {items.length > 40 && <li>and {items.length - 40} more</li>}
                      </ul>
                    )}
                  </div>
                ))}
              </div>
            )}
          </Panel>
          <div>
            <Button tone="danger" onClick={run} busy={running} disabled={!preview || !!nothing || loading}>
              Clean up
            </Button>
          </div>
        </div>
      </div>
    </>
  );
}
