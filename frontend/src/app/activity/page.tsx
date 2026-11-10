"use client";

import { useState } from "react";
import { Badge, Empty, Notice, PageHeader, Pagination, Search, Select, Spinner } from "@/components/ui";
import { qs } from "@/lib/api";
import { dateTime, titleCase } from "@/lib/format";
import { usePoll, useStored } from "@/lib/hooks";
import type { AuditEntry } from "@/lib/types";

export default function ActivityPage() {
  const [page, setPage] = useState(0);
  const [size, setSize] = useStored("dm.pageSize", 25);
  const [action, setAction] = useState("");
  const [resourceType, setResourceType] = useState("");
  const [search, setSearch] = useState("");
  const { data, error } = usePoll<{ logs?: AuditEntry[]; content?: AuditEntry[]; totalPages: number; totalElements?: number }>(
    `/api/audit${qs({ page, size, action, resourceType, search })}`,
    20000,
  );
  const { data: actions } = usePoll<string[]>("/api/audit/actions");
  const { data: types } = usePoll<string[]>("/api/audit/resource-types");
  const list = data?.logs ?? data?.content ?? null;

  return (
    <>
      <PageHeader title="Activity" intro="Who changed what, and when. Passwords, tokens, environment variables and compose files are never recorded." />
      <div className="flex flex-wrap items-center gap-3 mb-4">
        <Select value={resourceType} onChange={(v) => (setResourceType(v), setPage(0))} aria-label="Area" className="w-48">
          <option value="">Everything</option>
          {(types ?? []).map((t) => (
            <option key={t} value={t}>
              {titleCase(t)}
            </option>
          ))}
        </Select>
        <Select value={action} onChange={(v) => (setAction(v), setPage(0))} aria-label="Action" className="w-44">
          <option value="">All actions</option>
          {(actions ?? []).map((t) => (
            <option key={t} value={t}>
              {titleCase(t)}
            </option>
          ))}
        </Select>
        <div className="ml-auto">
          <Search value={search} onChange={(v) => (setSearch(v), setPage(0))} placeholder="Search" />
        </div>
      </div>
      {error && <Notice tone="error">{error}</Notice>}
      {!list && !error && <Spinner />}
      {list && list.length === 0 && <Empty title="Nothing found" />}
      {list && list.length > 0 && (
        <div className="table-scroll rounded-2xl border border-line bg-paper">
          <table className="w-full text-[14px]">
            <thead>
              <tr className="text-left text-[12.5px] text-slate border-b border-line">
                <th className="font-medium px-4 py-2.5">When</th>
                <th className="font-medium px-4 py-2.5">Who</th>
                <th className="font-medium px-4 py-2.5">What</th>
                <th className="font-medium px-4 py-2.5">From</th>
              </tr>
            </thead>
            <tbody>
              {list.map((e) => (
                <tr key={e.id} className="border-b border-line last:border-0 align-top">
                  <td className="px-4 py-2.5 whitespace-nowrap text-slate">{dateTime(e.timestamp)}</td>
                  <td className="px-4 py-2.5 whitespace-nowrap">{e.username ?? "system"}</td>
                  <td className="px-4 py-2.5 min-w-[18rem]">
                    <span className="flex flex-wrap items-center gap-2">
                      {titleCase(e.action)} {titleCase(e.resourceType).toLowerCase()}
                      {e.resourceId && <span className="font-mono text-[12.5px] text-slate truncate max-w-[16rem]">{e.resourceId}</span>}
                      {!e.success && <Badge tone="danger">failed</Badge>}
                    </span>
                    {e.errorMessage && <span className="block text-[12.5px] text-danger break-words">{e.errorMessage}</span>}
                  </td>
                  <td className="px-4 py-2.5 whitespace-nowrap text-slate font-mono text-[12.5px]">{e.ipAddress}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {data && (
        <Pagination
          page={page}
          pages={data.totalPages}
          total={data.totalElements ?? list?.length ?? 0}
          size={size}
          onPage={setPage}
          onSize={(n) => (setSize(n), setPage(0))}
          noun="event"
        />
      )}
    </>
  );
}
