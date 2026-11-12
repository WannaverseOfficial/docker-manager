"use client";

import { api } from "@/lib/api";
import { containerName } from "@/lib/format";
import { setPending, usePending } from "@/lib/hooks";
import type { Container } from "@/lib/types";
import { Menu, useConfirm, useToast } from "./ui";

export const COMPOSE_PROJECT = "com.docker.compose.project";
export const COMPOSE_SERVICE = "com.docker.compose.service";

const ACTIVE: Record<string, string> = {
  start: "Starting",
  stop: "Stopping",
  restart: "Restarting",
  pause: "Pausing",
  unpause: "Resuming",
  remove: "Removing",
};

/** Start, stop, restart, pause and remove a container, with confirmation for anything destructive. */
export function useContainerActions(hostId: string | null, onChanged: () => void) {
  const confirm = useConfirm();
  const toast = useToast();

  const act = async (id: string, name: string, action: string, verb: string) => {
    // Stopping can take a while (Docker waits up to 10 seconds for the app to exit), so the container shows
    // "Stopping" everywhere until it is done.
    setPending(id, ACTIVE[action] ?? "Working");
    let ok = false;
    try {
      if (action === "remove") {
        await api.del(`/api/docker/hosts/${hostId}/containers/${id}?force=true`);
      } else {
        await api.post(`/api/docker/hosts/${hostId}/containers/${id}/${action}`);
      }
      ok = true;
      toast(`${name} ${verb}`);
      onChanged();
    } catch (e) {
      toast(`Couldn't ${action} ${name}: ${(e as Error).message}`, "error");
    } finally {
      // Give the refreshed list a moment to arrive so the badge goes straight to the new state.
      if (ok) setTimeout(() => setPending(id, null), 1500);
      else setPending(id, null);
    }
  };

  const remove = async (id: string, name: string, project?: string) => {
    const ok = await confirm({
      title: `Remove ${name}?`,
      body: (
        <>
          The container is stopped and deleted. Named volumes and their data are kept.
          {project && (
            <>
              {" "}
              It belongs to the stack <b className="text-ink">{project}</b>, which will recreate it on its next deploy.
            </>
          )}
        </>
      ),
      confirm: "Remove",
      danger: true,
    });
    if (ok) await act(id, name, "remove", "removed");
  };

  return { act, remove };
}

/** `managed`: the configuration file manages the container, so it can't be removed here. */
export function ContainerMenu({ c, hostId, onChanged, managed = false }: { c: Container; hostId: string; onChanged: () => void; managed?: boolean }) {
  const { act, remove } = useContainerActions(hostId, onChanged);
  const busy = !!usePending(c.Id);
  const name = containerName(c);
  const running = c.State === "running";
  const paused = c.State === "paused";
  return (
    <Menu
      label={busy ? "Busy" : undefined}
      disabled={busy}
      items={[
        !running && !paused && { label: "Start", onClick: () => act(c.Id, name, "start", "started") },
        running && { label: "Stop", onClick: () => act(c.Id, name, "stop", "stopped") },
        (running || paused) && { label: "Restart", onClick: () => act(c.Id, name, "restart", "restarted") },
        running && { label: "Pause", onClick: () => act(c.Id, name, "pause", "paused") },
        paused && { label: "Resume", onClick: () => act(c.Id, name, "unpause", "resumed") },
        !managed && { label: "Remove", danger: true, onClick: () => remove(c.Id, name, c.Labels?.[COMPOSE_PROJECT]) },
      ]}
    />
  );
}
