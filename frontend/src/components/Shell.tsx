"use client";

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useState, useSyncExternalStore, type ReactNode } from "react";
import {
  Activity,
  Boxes,
  Brush,
  ChevronsUpDown,
  Container as ContainerIcon,
  Database,
  GitBranch,
  Globe,
  HardDrive,
  HeartPulse,
  History,
  KeyRound,
  Layers,
  LayoutDashboard,
  LayoutTemplate,
  LogOut,
  Menu,
  Moon,
  Network,
  Server,
  Settings,
  Shield,
  Split,
  ShieldCheck,
  Sun,
  Telescope,
  Users,
  Workflow,
  X,
  type LucideIcon,
} from "lucide-react";
import { logout } from "@/lib/api";
import { hostLabel } from "@/lib/format";
import { usePoll } from "@/lib/hooks";
import { useHost, useSession } from "./app";

type Item = { href: string; label: string; icon: LucideIcon; admin?: boolean };

const NAV: { title: string; items: Item[] }[] = [
  {
    title: "Run",
    items: [
      { href: "/", label: "Overview", icon: LayoutDashboard },
      { href: "/containers/", label: "Containers", icon: ContainerIcon },
      { href: "/stacks/", label: "Stacks", icon: Layers },
      { href: "/templates/", label: "Templates", icon: LayoutTemplate },
      { href: "/databases/", label: "Databases", icon: Database, admin: true },
    ],
  },
  {
    title: "Resources",
    items: [
      { href: "/images/", label: "Images", icon: Boxes },
      { href: "/volumes/", label: "Volumes", icon: HardDrive },
      { href: "/networks/", label: "Networks", icon: Network },
      { href: "/cleanup/", label: "Clean up", icon: Brush, admin: true },
    ],
  },
  {
    title: "Deploy",
    items: [
      { href: "/git/", label: "Git", icon: GitBranch },
      { href: "/pipelines/", label: "Pipelines", icon: Workflow },
      { href: "/rollouts/", label: "Rollouts", icon: Split },
      { href: "/ingress/", label: "Ingress", icon: Globe },
      { href: "/history/", label: "History", icon: History },
    ],
  },
  {
    title: "Watch",
    items: [
      { href: "/health/", label: "Health", icon: HeartPulse },
      { href: "/observability/", label: "Observability", icon: Telescope, admin: true },
      { href: "/security/", label: "Security", icon: Shield, admin: true },
      { href: "/activity/", label: "Activity", icon: Activity, admin: true },
    ],
  },
  {
    title: "Setup",
    items: [
      { href: "/hosts/", label: "Hosts", icon: Server },
      { href: "/registries/", label: "Registries", icon: KeyRound },
      { href: "/policies/", label: "Image rules", icon: ShieldCheck },
      { href: "/users/", label: "Users", icon: Users, admin: true },
      { href: "/settings/", label: "Settings", icon: Settings },
    ],
  },
];

function isActive(path: string, href: string) {
  if (href === "/") return path === "/";
  return path.startsWith(href.replace(/\/$/, ""));
}

export default function Shell({ children }: { children: ReactNode }) {
  const path = usePathname();
  const { session, ready } = useSession();
  const [open, setOpen] = useState(false);
  const [lastPath, setLastPath] = useState(path);
  if (path !== lastPath) {
    setLastPath(path);
    setOpen(false);
  }

  // Sign-in and the forced password change get a plain frame.
  const bare = path.startsWith("/login") || (path.startsWith("/account") && session?.mustChangePassword);
  if (bare) return <main className="min-h-dvh">{children}</main>;
  if (!ready || !session) return <div className="min-h-dvh" aria-busy />;

  return (
    <div className="min-h-dvh lg:grid lg:grid-cols-[232px_1fr]">
      <div className="lg:hidden flex items-center justify-between px-4 h-14 border-b border-line bg-fog/85 backdrop-blur-lg sticky top-0 z-30">
        <Wordmark />
        <button
          onClick={() => setOpen(!open)}
          aria-label={open ? "Close menu" : "Open menu"}
          aria-expanded={open}
          className="size-10 grid place-items-center rounded-full hover:bg-well"
        >
          {open ? <X size={20} /> : <Menu size={20} />}
        </button>
      </div>

      <aside
        className={`${open ? "block" : "hidden"} lg:block fixed lg:sticky inset-x-0 top-14 lg:top-0 bottom-0 lg:h-dvh z-20 bg-fog lg:border-r border-line`}
      >
        {/* The nav scrolls on short screens; the host and account footer stays in view. */}
        <div className="flex flex-col h-full">
          <div className="hidden lg:block px-5 pt-5 mb-6">
            <Wordmark />
          </div>
          <nav aria-label="Main" className="flex-1 min-h-0 overflow-y-auto flex flex-col gap-5 px-3 pt-5 lg:pt-0 pb-4">
            {NAV.map((g) => (
              <div key={g.title}>
                <div className="px-2.5 mb-1 text-[12px] text-slate">{g.title}</div>
                <ul className="flex flex-col">
                  {g.items
                    .filter((i) => !i.admin || session.admin)
                    .map((i) => (
                      <NavItem key={i.href} item={i} active={isActive(path, i.href)} />
                    ))}
                </ul>
              </div>
            ))}
          </nav>
          <SidebarFooter />
        </div>
      </aside>

      <main className="min-w-0 px-4 sm:px-8 lg:px-12 pt-6 pb-16 lg:pt-8">
        <div className="max-w-[1180px] mx-auto">
          <NoHostGuard>{children}</NoHostGuard>
        </div>
      </main>
    </div>
  );
}

function Wordmark() {
  return (
    <Link href="/" className="font-display text-[1.45rem] leading-none">
      docker<span className="text-slate">.manager</span>
    </Link>
  );
}

function NavItem({ item, active }: { item: Item; active: boolean }) {
  const Icon = item.icon;
  const badge = useHealthBadge(item.href === "/health/");
  return (
    <li>
      <Link
        href={item.href}
        aria-current={active ? "page" : undefined}
        className={`flex items-center gap-2.5 rounded-lg px-2.5 h-9 text-[15px] transition-colors ${
          active ? "bg-well text-ink font-medium" : "text-slate hover:text-ink hover:bg-well/60"
        }`}
      >
        <Icon size={17} strokeWidth={1.75} aria-hidden />
        {item.label}
        {!!badge && (
          <span className="ml-auto min-w-5 h-5 px-1.5 grid place-items-center rounded-full bg-danger/20 text-danger text-[11px] font-medium">
            <span className="sr-only">, open issues: </span>
            {badge}
          </span>
        )}
      </Link>
    </li>
  );
}

/** Open health issues, shown next to Health in the sidebar. */
function useHealthBadge(enabled: boolean) {
  const { data } = usePoll<{ count: number }>(enabled ? "/api/container-health/active/count" : null, 30000);
  return data?.count ?? 0;
}

function HostSwitcher() {
  const { hosts, host, select, loaded } = useHost();
  const { session } = useSession();
  const { data: ping } = usePoll<{ connected: boolean }>(host ? `/api/docker/hosts/${host.id}/ping` : null, 20000);
  if (!loaded) return <div className="h-[42px]" />;
  if (!hosts.length) {
    if (!session?.admin) {
      return <p className="px-2.5 text-[13px] text-slate">No hosts shared with you yet</p>;
    }
    return (
      <Link href="/hosts/" className="px-2.5 inline-flex items-center gap-2 text-[13px] text-slate hover:text-ink">
        <Server size={15} aria-hidden /> Add a Docker host
      </Link>
    );
  }
  const status = ping == null ? "checking" : ping.connected ? "connected" : "offline";
  const text = status === "connected" ? "Connected" : status === "offline" ? "Not reachable" : "Checking";
  const many = hosts.length > 1;
  return (
    <div className={`relative px-2.5 py-1 -my-1 rounded-lg ${many ? "hover:bg-well/60 focus-within:bg-well/60" : ""}`}>
      <div className="flex items-center gap-2 min-w-0">
        <span
          className={`size-2 rounded-full shrink-0 ${status === "connected" ? "bg-ok" : status === "offline" ? "bg-danger" : "bg-slate/50 animate-pulse"}`}
          aria-hidden
        />
        <span className="flex-1 min-w-0 truncate text-[14px] text-ink" title={hostLabel(host)}>
          {hostLabel(host)}
        </span>
        {many && <ChevronsUpDown size={14} className="shrink-0 text-slate" aria-hidden />}
      </div>
      <div className="pl-4 text-[12px] text-slate" role="status">
        {text}
        {many ? ` · ${hosts.length} hosts` : ""}
      </div>
      {many && (
        // An invisible native select over the block: the system picker, keyboard and screen readers for free.
        <select aria-label="Switch Docker host" value={host?.id} onChange={(e) => select(e.target.value)} className="absolute inset-0 w-full opacity-0 cursor-pointer">
          {hosts.map((h) => (
            <option key={h.id} value={h.id}>
              {hostLabel(h)}
            </option>
          ))}
        </select>
      )}
    </div>
  );
}

/** Pages that need a host explain how to add one instead of showing errors. */
function NoHostGuard({ children }: { children: ReactNode }) {
  const { hosts, loaded } = useHost();
  const { session } = useSession();
  const path = usePathname();
  const free = ["/hosts", "/users", "/settings", "/account", "/registries", "/policies", "/activity", "/templates"].some((p) =>
    path.startsWith(p),
  );
  if (!loaded || hosts.length || free) return <>{children}</>;
  if (!session?.admin) {
    return (
      <div className="max-w-xl mx-auto text-center pt-16">
        <h1 className="font-display text-[1.75rem]">No hosts yet</h1>
        <p className="text-slate mt-2">You don&apos;t have access to any Docker host yet. Ask an administrator to give you permissions on one.</p>
      </div>
    );
  }
  return (
    <div className="max-w-xl mx-auto text-center pt-16">
      <h1 className="font-display text-[1.75rem]">Connect a Docker host</h1>
      <p className="text-slate mt-2">
        Docker Manager needs a Docker engine to manage. When it runs in a container with <code className="font-mono text-[13px]">/var/run/docker.sock</code> mounted,
        that host is added automatically. Otherwise, add one by address.
      </p>
      <div className="mt-6">
        <Link href="/hosts/" className="inline-flex items-center rounded-full px-4 h-9 text-[14px] font-medium bg-accent text-accent-ink hover:opacity-85">
          Add a host
        </Link>
      </div>
    </div>
  );
}

// --- footer: host, account and theme -------------------------------------------------------------

const THEME_EVENT = "dm-theme";

function readTheme(): "light" | "dark" {
  return document.documentElement.dataset.theme === "light" ? "light" : "dark";
}

function ThemeToggle() {
  const theme = useSyncExternalStore(
    (cb) => {
      window.addEventListener(THEME_EVENT, cb);
      return () => window.removeEventListener(THEME_EVENT, cb);
    },
    readTheme,
    () => "dark" as const,
  );
  const next = theme === "dark" ? "light" : "dark";
  return (
    <button
      onClick={() => {
        if (next === "light") document.documentElement.dataset.theme = "light";
        else delete document.documentElement.dataset.theme;
        try {
          localStorage.setItem("dm.theme", next);
        } catch {}
        window.dispatchEvent(new Event(THEME_EVENT));
      }}
      className="flex items-center gap-2 rounded-lg px-1.5 h-8 text-[13px] text-slate hover:text-ink hover:bg-well/60"
    >
      {theme === "dark" ? <Sun size={15} aria-hidden /> : <Moon size={15} aria-hidden />}
      {theme === "dark" ? "Light mode" : "Dark mode"}
    </button>
  );
}

function SidebarFooter() {
  const { session } = useSession();
  const router = useRouter();
  const { data: status } = usePoll<{ version: string }>("/api/system/status");
  return (
    <div className="border-t border-line px-3 pt-3 pb-4 flex flex-col gap-3">
      <HostSwitcher />
      <div className="flex flex-col gap-2 px-1">
      <Link href="/account/" className="px-1.5 text-[14px] hover:text-ink text-slate truncate" title="Your account">
        Signed in as <span className="text-ink">{session?.username}</span>
      </Link>
      <div className="flex items-center justify-between gap-2">
        <ThemeToggle />
        <button
          onClick={async () => {
            await logout();
            router.replace("/login/");
          }}
          className="flex items-center gap-1.5 rounded-lg px-1.5 h-8 text-[13px] text-slate hover:text-ink hover:bg-well/60"
        >
          <LogOut size={15} aria-hidden /> Sign out
        </button>
      </div>
      {status?.version && <div className="px-1.5 text-[12px] text-slate tabular-nums">Version {status.version}</div>}
      </div>
    </div>
  );
}
