"use client";

import Link from "next/link";
import { FileCode2 } from "lucide-react";
import { getSession } from "@/lib/api";

/**
 * Marks something the configuration file manages. Changes to it go through the file, so pages hide their edit and
 * delete buttons next to this badge. Pass link={false} when it sits inside a link already.
 */
export function ManagedBadge({ entry, link = true }: { entry?: string; link?: boolean }) {
  const title = `Managed by the configuration file${entry ? ` (${entry})` : ""}. Change it there.`;
  const body = (
    <span
      title={title}
      className="inline-flex items-center gap-1 rounded-full px-2 h-5 text-[11.5px] font-medium whitespace-nowrap bg-accent/10 text-accent"
    >
      <FileCode2 size={12} aria-hidden />
      Config file
    </span>
  );
  // Only administrators can open the Configuration page. Inside another link (a list row), no link of its own.
  return link && getSession()?.admin ? (
    <Link href="/config/" className="hover:opacity-80">
      {body}
    </Link>
  ) : (
    body
  );
}

/** A one-line explanation for edit forms and panels of managed items. */
export function ManagedNote({ what = "This" }: { what?: string }) {
  return (
    <p className="text-[13px] text-slate flex items-center gap-1.5">
      <FileCode2 size={14} aria-hidden className="text-accent" />
      {what} is managed by the configuration file, so it can&apos;t be changed here.
    </p>
  );
}
