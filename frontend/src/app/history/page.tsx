"use client";

import Link from "next/link";
import { useState } from "react";
import { useHost } from "@/components/app";
import { Badge, Button, Empty, Notice, PageHeader, Pagination, Select, Spinner, useConfirm, useToast } from "@/components/ui";
import { api, qs } from "@/lib/api";
import { dateTime, duration } from "@/lib/format";
import { usePoll, useStored } from "@/lib/hooks";
import type { Operation, Page } from "@/lib/types";

const LABEL: Record<string, string> = {
  CONTAINER_CREATE: "Created container",
  CONTAINER_UPDATE: "Changed container",
  CONTAINER_START: "Started container",
  CONTAINER_STOP: "Stopped container",
  CONTAINER_RESTART: "Restarted container",
  CONTAINER_DELETE: "Removed container",
  COMPOSE_UP: "Deployed stack",
  COMPOSE_DOWN: "Took stack down",
};

export default function HistoryPage() {
  const { hostId } = useHost();
  const toast = useToast();
  const confirm = useConfirm();
  const [type, setType] = useState("");
  const [page, setPage] = useState(0);
  const [size, setSize] = useStored("dm.pageSize", 25);
  const { data, error, refresh } = usePoll<Page<Operation>>(
    hostId ? `/api/deployments/hosts/${hostId}/operations${qs({ type, page, size })}` : null,
    15000,
  );

  const undo = async (op: Operation) => {
    const ok = await confirm({
      title: `Undo “${LABEL[op.operationType] ?? op.operationType}” for ${op.resourceName}?`,
      body:
        op.operationType === "CONTAINER_DELETE"
          ? "The container is recreated from its saved settings. Data in named volumes is still there; data that only lived inside the container is not."
          : op.operationType === "CONTAINER_CREATE"
            ? "The container is removed."
            : "The container goes back to how it was before.",
      confirm: "Undo",
    });
    if (!ok) return;
    try {
      const r = await api.post<{ success: boolean; message: string }>(`/api/rollback/operation/${op.id}`);
      if (!r.success) throw new Error(r.message);
      toast("Undone");
      refresh();
    } catch (e) {
      toast((e as Error).message, "error");
    }
  };

  if (!hostId) return null;
  return (
    <>
      <PageHeader title="History" intro="Every change made to containers through Docker Manager, with the state before and after, so you can undo it." />
      <div className="flex justify-between items-center gap-3 mb-4">
        <Select value={type} onChange={(v) => (setType(v), setPage(0))} aria-label="Type" className="w-56">
          <option value="">All changes</option>
          {Object.entries(LABEL).map(([k, v]) => (
            <option key={k} value={k}>
              {v}
            </option>
          ))}
        </Select>
        <Link href="/stacks/" className="text-[14px] text-slate hover:text-ink">
          Stack versions are on each stack
        </Link>
      </div>
      {error && <Notice tone="error">{error}</Notice>}
      {!data && !error && <Spinner />}
      {data && data.content.length === 0 && <Empty title="Nothing yet">Changes you make appear here.</Empty>}
      {data && data.content.length > 0 && (
        <>
          <ul className="rounded-2xl border border-line bg-paper divide-y divide-line">
            {data.content.map((op) => (
              <li key={op.id} className="flex flex-wrap items-center gap-3 px-4 py-3">
                <div className="flex-1 min-w-0">
                  <div className="text-[14px] flex flex-wrap items-center gap-2">
                    <span>{LABEL[op.operationType] ?? op.operationType}</span>
                    {op.resourceId && op.operationType !== "CONTAINER_DELETE" ? (
                      <Link href={`/containers/view/?id=${op.resourceId}`} className="font-medium hover:underline underline-offset-2 truncate">
                        {op.resourceName}
                      </Link>
                    ) : (
                      <span className="font-medium truncate">{op.resourceName}</span>
                    )}
                    {op.status === "FAILED" && <Badge tone="danger">failed</Badge>}
                    {op.status === "ROLLED_BACK" && <Badge>undone</Badge>}
                  </div>
                  <div className="text-[12.5px] text-slate">
                    {dateTime(op.createdAt)}
                    {op.username && ` by ${op.username}`}
                    {op.durationMs ? ` · ${duration(op.durationMs)}` : ""}
                  </div>
                  {op.errorMessage && <div className="text-[13px] text-danger mt-0.5 break-words">{op.errorMessage}</div>}
                </div>
                {op.rollbackAvailable && op.status === "SUCCESS" && (
                  <Button tone="quiet" size="sm" onClick={() => undo(op)}>
                    Undo
                  </Button>
                )}
              </li>
            ))}
          </ul>
          <Pagination
            page={page}
            pages={data.totalPages}
            total={data.totalElements ?? data.content.length}
            size={size}
            onPage={setPage}
            onSize={(n) => (setSize(n), setPage(0))}
            noun="change"
          />
        </>
      )}
    </>
  );
}
