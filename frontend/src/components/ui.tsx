"use client";

import Link from "next/link";
import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useId,
  useRef,
  useState,
  type ButtonHTMLAttributes,
  type ReactNode,
} from "react";
import { Check, ChevronDown, Copy, Download, Loader2, MoreHorizontal, X } from "lucide-react";

// --- buttons ---------------------------------------------------------------------------------

type Tone = "primary" | "quiet" | "danger" | "ghost";

const TONES: Record<Tone, string> = {
  primary: "bg-accent text-accent-ink hover:opacity-85 disabled:opacity-40",
  quiet: "bg-transparent text-ink border border-line hover:bg-well disabled:opacity-40",
  danger: "bg-transparent text-danger border border-line hover:bg-well disabled:opacity-40",
  ghost: "bg-transparent text-slate hover:text-ink hover:bg-well disabled:opacity-40",
};

export function Button({
  tone = "primary",
  busy = false,
  size = "md",
  className = "",
  children,
  disabled,
  ...props
}: ButtonHTMLAttributes<HTMLButtonElement> & { tone?: Tone; busy?: boolean; size?: "sm" | "md" }) {
  const sizes = { sm: "h-8 px-3 text-[13px]", md: "h-9 px-4 text-[14px]" };
  return (
    <button
      className={`inline-flex items-center justify-center gap-2 rounded-full font-medium whitespace-nowrap transition-[opacity,background-color] disabled:cursor-not-allowed ${sizes[size]} ${TONES[tone]} ${className}`}
      disabled={disabled || busy}
      aria-busy={busy || undefined}
      {...props}
    >
      {busy && <Loader2 size={15} className="animate-spin" aria-hidden />}
      {children}
    </button>
  );
}

export function LinkButton({
  href,
  children,
  tone = "primary",
  size = "md",
}: {
  href: string;
  children: ReactNode;
  tone?: Tone;
  size?: "sm" | "md";
}) {
  const sizes = { sm: "h-8 px-3 text-[13px]", md: "h-9 px-4 text-[14px]" };
  return (
    <Link
      href={href}
      className={`inline-flex items-center justify-center gap-2 rounded-full font-medium whitespace-nowrap transition-opacity ${sizes[size]} ${TONES[tone]}`}
    >
      {children}
    </Link>
  );
}

export function IconButton({
  label,
  children,
  className = "",
  ...props
}: ButtonHTMLAttributes<HTMLButtonElement> & { label: string }) {
  return (
    <button
      aria-label={label}
      title={label}
      className={`size-8 grid place-items-center rounded-full text-slate hover:text-ink hover:bg-well disabled:opacity-40 ${className}`}
      {...props}
    >
      {children}
    </button>
  );
}

// --- page structure --------------------------------------------------------------------------

export type Art = "harbour" | "containers" | "stacks" | "images" | "deploy" | "ingress" | "cleanup" | "health" | "setup";

const ART_FOCUS: Partial<Record<Art, string>> = {};

/** Page title. With `art`, a painting sits above it as a wide banner. */
export function PageHeader({
  title,
  intro,
  art,
  back,
  children,
}: {
  title: ReactNode;
  intro?: ReactNode;
  art?: Art;
  back?: { href: string; label: string };
  children?: ReactNode;
}) {
  return (
    <header className="mb-8">
      {art && (
        <div className="relative mb-7 h-[120px] sm:h-[170px] rounded-2xl overflow-hidden bg-well">
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <img
            src={`/art/${art}.webp`}
            alt=""
            className="absolute inset-0 size-full object-cover"
            style={{ objectPosition: ART_FOCUS[art] ?? "center" }}
          />
          <div className="absolute inset-0 bg-gradient-to-t from-black/45 via-black/5 to-transparent" aria-hidden />
        </div>
      )}
      {back && (
        <Link href={back.href} className="inline-block text-[14px] text-slate hover:text-ink mb-2">
          ← {back.label}
        </Link>
      )}
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div className="min-w-0 max-w-[70ch]">
          <h1 className="font-display text-[1.75rem] leading-tight break-words">{title}</h1>
          {intro && <div className="text-slate mt-2">{intro}</div>}
        </div>
        {children && <div className="flex flex-wrap items-center gap-2">{children}</div>}
      </div>
    </header>
  );
}

export function Panel({
  title,
  action,
  children,
  className = "",
  pad = true,
}: {
  title?: ReactNode;
  action?: ReactNode;
  children: ReactNode;
  className?: string;
  pad?: boolean;
}) {
  return (
    <section className={`min-w-0 rounded-2xl bg-paper border border-line ${pad ? "p-5" : ""} ${className}`}>
      {(title || action) && (
        <div className={`flex flex-wrap items-center justify-between gap-x-3 gap-y-1 ${pad ? "mb-4" : "px-5 pt-5 pb-3"}`}>
          {title && <h2 className="font-display text-[1.05rem]">{title}</h2>}
          {action}
        </div>
      )}
      {children}
    </section>
  );
}

export function Section({ title, intro, children, action }: { title: string; intro?: ReactNode; children: ReactNode; action?: ReactNode }) {
  return (
    <section className="mt-10 first:mt-0">
      <div className="flex flex-wrap items-end justify-between gap-3 mb-3">
        <div>
          <h2 className="font-display text-[1.15rem]">{title}</h2>
          {intro && <p className="text-[14px] text-slate mt-0.5">{intro}</p>}
        </div>
        {action}
      </div>
      {children}
    </section>
  );
}

// --- forms -----------------------------------------------------------------------------------

export function Field({
  label,
  hint,
  error,
  children,
  optional,
}: {
  label: string;
  hint?: ReactNode;
  error?: string | null;
  children: ReactNode;
  optional?: boolean;
}) {
  return (
    <label className="block min-w-0">
      <span className="block text-[13px] font-medium mb-1.5">
        {label}
        {optional && <span className="text-slate font-normal"> (optional)</span>}
      </span>
      {children}
      {error ? (
        <span className="block text-[13px] text-danger mt-1">{error}</span>
      ) : (
        hint && <span className="block text-[13px] text-slate mt-1">{hint}</span>
      )}
    </label>
  );
}

export const inputClass =
  "w-full min-w-0 rounded-xl bg-paper border border-line px-3.5 py-2.5 text-[14px] placeholder:text-slate/70 focus:outline-none focus:border-slate transition-colors disabled:opacity-50";

export const selectClass = inputClass + " appearance-none pr-9 bg-no-repeat";

export function Select({
  value,
  onChange,
  children,
  className = "",
  ...props
}: Omit<React.SelectHTMLAttributes<HTMLSelectElement>, "onChange"> & { onChange: (v: string) => void }) {
  return (
    <div className={`relative ${className}`}>
      <select className={selectClass} value={value} onChange={(e) => onChange(e.target.value)} {...props}>
        {children}
      </select>
      <ChevronDown size={16} className="absolute right-3 top-1/2 -translate-y-1/2 text-slate pointer-events-none" aria-hidden />
    </div>
  );
}

export function Toggle({
  checked,
  onChange,
  label,
  hint,
  disabled,
}: {
  checked: boolean;
  onChange: (v: boolean) => void;
  label: ReactNode;
  hint?: ReactNode;
  disabled?: boolean;
}) {
  const id = useId();
  return (
    <div className="flex items-start gap-3">
      <button
        id={id}
        role="switch"
        type="button"
        aria-checked={checked}
        disabled={disabled}
        onClick={() => onChange(!checked)}
        className={`mt-0.5 relative shrink-0 w-9 h-5 rounded-full transition-colors disabled:opacity-40 ${
          checked ? "bg-accent" : "bg-well border border-line"
        }`}
      >
        <span
          className={`absolute top-0.5 size-4 rounded-full transition-all ${
            checked ? "left-[18px] bg-accent-ink" : "left-0.5 bg-slate"
          }`}
        />
      </button>
      <label htmlFor={id} className="text-[14px] leading-snug cursor-pointer">
        {label}
        {hint && <span className="block text-[13px] text-slate mt-0.5">{hint}</span>}
      </label>
    </div>
  );
}

export function Segmented<T extends string>({
  value,
  options,
  onChange,
  label,
}: {
  value: T;
  options: { value: T; label: string; hint?: string }[];
  onChange: (v: T) => void;
  label: string;
}) {
  return (
    <div role="radiogroup" aria-label={label} className="inline-flex flex-wrap rounded-full bg-well p-0.5 gap-0.5">
      {options.map((o) => (
        <button
          key={o.value}
          type="button"
          role="radio"
          aria-checked={value === o.value}
          title={o.hint}
          onClick={() => onChange(o.value)}
          className={`rounded-full px-3.5 h-8 text-[13px] font-medium transition-colors ${
            value === o.value ? "bg-paper text-ink border border-line" : "text-slate hover:text-ink border border-transparent"
          }`}
        >
          {o.label}
        </button>
      ))}
    </div>
  );
}

export function Search({ value, onChange, placeholder = "Search" }: { value: string; onChange: (v: string) => void; placeholder?: string }) {
  return (
    <input
      type="search"
      value={value}
      onChange={(e) => onChange(e.target.value)}
      placeholder={placeholder}
      aria-label={placeholder}
      className={`${inputClass} h-9 py-0 rounded-full sm:w-64`}
    />
  );
}

// --- feedback --------------------------------------------------------------------------------

export function Notice({ tone = "info", children, title }: { tone?: "info" | "error" | "warn" | "ok"; children: ReactNode; title?: string }) {
  const tones = {
    info: "border-line text-ink",
    error: "border-danger/40 text-danger",
    warn: "border-caution/40",
    ok: "border-ok/40",
  };
  return (
    <div role={tone === "error" ? "alert" : "status"} className={`rounded-xl px-4 py-3 text-[14px] border bg-paper ${tones[tone]}`}>
      {title && <div className="font-medium mb-0.5">{title}</div>}
      <div className={tone === "warn" || tone === "ok" ? "text-ink" : ""}>{children}</div>
    </div>
  );
}

export function Spinner({ label = "Loading" }: { label?: string }) {
  return (
    <div className="flex items-center gap-2 text-slate text-[14px] py-6" role="status">
      <Loader2 size={16} className="animate-spin" aria-hidden />
      {label}
    </div>
  );
}

export function Empty({ title, children, action }: { title: string; children?: ReactNode; action?: ReactNode }) {
  return (
    <div className="rounded-2xl border border-dashed border-line px-6 py-12 text-center">
      <p className="font-display text-[1.05rem]">{title}</p>
      {children && <div className="text-slate text-[14px] mt-1.5 max-w-[52ch] mx-auto">{children}</div>}
      {action && <div className="mt-5 flex justify-center gap-2">{action}</div>}
    </div>
  );
}

/** A thin usage bar. `warnAt` turns it caution-coloured, `dangerAt` danger-coloured. */
export function Meter({ value, warnAt = 75, dangerAt = 90, label }: { value: number; warnAt?: number; dangerAt?: number; label?: string }) {
  const v = Math.max(0, Math.min(100, value || 0));
  const fill = v >= dangerAt ? "bg-danger" : v >= warnAt ? "bg-caution" : "bg-ink";
  return (
    <div
      className="h-1.5 rounded-full bg-well overflow-hidden"
      role="meter"
      aria-label={label}
      aria-valuenow={Math.round(v)}
      aria-valuemin={0}
      aria-valuemax={100}
    >
      <div className={`h-full rounded-full transition-[width] duration-500 ${fill}`} style={{ width: `${Math.max(v > 0 ? 2 : 0, v)}%` }} />
    </div>
  );
}

/** Container (and other) states. Marigold is reserved for "running right now". */
export function StateBadge({ state, status, pending }: { state: string; status?: string; pending?: string }) {
  if (pending) {
    // An action is under way (stopping, starting...): show it until Docker reports the new state.
    return (
      <span className="inline-flex items-center gap-1.5 text-[13px] whitespace-nowrap text-ink" role="status">
        <Loader2 size={13} className="animate-spin text-slate shrink-0" aria-hidden />
        {pending}
      </span>
    );
  }
  const s = (state || "").toLowerCase();
  const unhealthy = status?.includes("(unhealthy)");
  const failed = s === "exited" && status && !/Exited \(0\)/.test(status);
  const dot =
    unhealthy || s === "dead" || failed
      ? "bg-danger"
      : s === "running"
        ? "bg-marigold"
        : s === "restarting"
          ? "bg-marigold animate-pulse"
          : s === "paused"
            ? "bg-caution"
            : "bg-slate/60";
  const text = unhealthy ? "Unhealthy" : s === "exited" ? (failed ? "Failed" : "Stopped") : s ? s[0].toUpperCase() + s.slice(1) : "Unknown";
  return (
    <span className="inline-flex items-center gap-1.5 text-[13px] whitespace-nowrap">
      <span className={`size-2 rounded-full shrink-0 ${dot}`} aria-hidden />
      {text}
    </span>
  );
}

export function Badge({ children, tone = "plain" }: { children: ReactNode; tone?: "plain" | "ok" | "warn" | "danger" | "live" }) {
  const tones = {
    plain: "bg-well text-slate",
    ok: "bg-ok/15 text-ok",
    warn: "bg-caution/15 text-caution",
    danger: "bg-danger/15 text-danger",
    live: "bg-marigold/15 text-marigold",
  };
  return <span className={`inline-flex items-center rounded-full px-2 h-5 text-[11.5px] font-medium whitespace-nowrap ${tones[tone]}`}>{children}</span>;
}

// --- dialogs ---------------------------------------------------------------------------------

export function Modal({
  open,
  onClose,
  title,
  children,
  footer,
  wide = false,
}: {
  open: boolean;
  onClose: () => void;
  title: ReactNode;
  children: ReactNode;
  footer?: ReactNode;
  wide?: boolean;
}) {
  const ref = useRef<HTMLDialogElement>(null);
  useEffect(() => {
    const d = ref.current;
    if (!d) return;
    if (open && !d.open) d.showModal();
    if (!open && d.open) d.close();
  }, [open]);
  return (
    <dialog
      ref={ref}
      onClose={onClose}
      onCancel={(e) => {
        e.preventDefault();
        onClose();
      }}
      onClick={(e) => {
        if (e.target === ref.current) onClose();
      }}
      className={`m-auto w-[calc(100%-2rem)] ${wide ? "max-w-3xl" : "max-w-lg"} rounded-2xl bg-paper text-ink border border-line p-0 backdrop:bg-black/60 backdrop:backdrop-blur-sm`}
    >
      {open && (
        <div className="flex flex-col max-h-[85dvh]">
          <div className="flex items-start justify-between gap-4 px-6 pt-5 pb-3">
            <h2 className="font-display text-[1.15rem]">{title}</h2>
            <IconButton label="Close" onClick={onClose} className="-mr-2 -mt-1">
              <X size={18} />
            </IconButton>
          </div>
          <div className="px-6 pb-5 overflow-y-auto">{children}</div>
          {footer && <div className="flex flex-wrap justify-end gap-2 px-6 py-4 border-t border-line">{footer}</div>}
        </div>
      )}
    </dialog>
  );
}

interface ConfirmOptions {
  title: string;
  body?: ReactNode;
  confirm?: string;
  danger?: boolean;
}

const ConfirmContext = createContext<(o: ConfirmOptions) => Promise<boolean>>(async () => false);

/** `await confirm({...})` resolves true when the person confirms. */
export function useConfirm() {
  return useContext(ConfirmContext);
}

export function ConfirmProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState<(ConfirmOptions & { resolve: (v: boolean) => void }) | null>(null);
  const ask = useCallback((o: ConfirmOptions) => new Promise<boolean>((resolve) => setState({ ...o, resolve })), []);
  const close = (v: boolean) => {
    state?.resolve(v);
    setState(null);
  };
  return (
    <ConfirmContext.Provider value={ask}>
      {children}
      <Modal
        open={!!state}
        onClose={() => close(false)}
        title={state?.title}
        footer={
          <>
            <Button tone="quiet" onClick={() => close(false)}>
              Cancel
            </Button>
            <Button tone={state?.danger ? "danger" : "primary"} onClick={() => close(true)} autoFocus>
              {state?.confirm ?? "Continue"}
            </Button>
          </>
        }
      >
        {state?.body && <div className="text-[14px] text-slate">{state.body}</div>}
      </Modal>
    </ConfirmContext.Provider>
  );
}

// --- toasts ----------------------------------------------------------------------------------

type ToastItem = { id: number; text: string; tone: "ok" | "error" };
const ToastContext = createContext<(text: string, tone?: "ok" | "error") => void>(() => {});

export function useToast() {
  return useContext(ToastContext);
}

export function ToastProvider({ children }: { children: ReactNode }) {
  const [items, setItems] = useState<ToastItem[]>([]);
  const push = useCallback((text: string, tone: "ok" | "error" = "ok") => {
    const id = Date.now() + Math.random();
    setItems((i) => [...i.slice(-3), { id, text, tone }]);
    setTimeout(() => setItems((i) => i.filter((t) => t.id !== id)), tone === "error" ? 8000 : 4000);
  }, []);
  return (
    <ToastContext.Provider value={push}>
      {children}
      <div className="fixed bottom-4 left-1/2 -translate-x-1/2 z-50 flex flex-col items-center gap-2 w-[calc(100%-2rem)] max-w-md pointer-events-none" aria-live="polite">
        {items.map((t) => (
          <div
            key={t.id}
            role={t.tone === "error" ? "alert" : "status"}
            className={`pointer-events-auto w-full rounded-xl border px-4 py-3 text-[14px] shadow-lg bg-paper ${
              t.tone === "error" ? "border-danger/50 text-danger" : "border-line text-ink"
            }`}
          >
            {t.text}
          </div>
        ))}
      </div>
    </ToastContext.Provider>
  );
}

// --- menus and tabs --------------------------------------------------------------------------

export function Menu({
  items,
  label = "More actions",
  disabled = false,
}: {
  items: ({ label: string; onClick: () => void; danger?: boolean; disabled?: boolean } | false | null | undefined)[];
  label?: string;
  disabled?: boolean;
}) {
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!open) return;
    const onDoc = (e: MouseEvent) => {
      if (!ref.current?.contains(e.target as Node)) setOpen(false);
    };
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && setOpen(false);
    document.addEventListener("mousedown", onDoc);
    document.addEventListener("keydown", onKey);
    return () => {
      document.removeEventListener("mousedown", onDoc);
      document.removeEventListener("keydown", onKey);
    };
  }, [open]);
  const list = items.filter(Boolean) as { label: string; onClick: () => void; danger?: boolean; disabled?: boolean }[];
  return (
    <div ref={ref} className="relative">
      <IconButton label={label} aria-haspopup="menu" aria-expanded={open} disabled={disabled} onClick={() => setOpen(!open)}>
        <MoreHorizontal size={18} />
      </IconButton>
      {open && (
        <div role="menu" className="absolute right-0 top-9 z-30 min-w-44 rounded-xl border border-line bg-paper py-1 shadow-xl">
          {list.map((i) => (
            <button
              key={i.label}
              role="menuitem"
              disabled={i.disabled}
              onClick={() => {
                setOpen(false);
                i.onClick();
              }}
              className={`block w-full text-left px-3.5 py-2 text-[14px] hover:bg-well disabled:opacity-40 ${i.danger ? "text-danger" : ""}`}
            >
              {i.label}
            </button>
          ))}
        </div>
      )}
    </div>
  );
}

export function Tabs<T extends string>({ value, onChange, tabs }: { value: T; onChange: (v: T) => void; tabs: { value: T; label: string; count?: number }[] }) {
  return (
    // Scrolls sideways only when the tabs don't fit (phones), without a visible scrollbar. The underline sits inside
    // the row: an indicator hanging below it made the row scroll vertically by a pixel.
    <div role="tablist" className="flex gap-1 border-b border-line mb-6 overflow-x-auto overflow-y-hidden [scrollbar-width:none] [&::-webkit-scrollbar]:hidden">
      {tabs.map((t) => (
        <button
          key={t.value}
          role="tab"
          aria-selected={value === t.value}
          onClick={() => onChange(t.value)}
          className={`relative shrink-0 px-3 h-10 text-[14px] whitespace-nowrap transition-colors ${
            value === t.value ? "text-ink font-medium" : "text-slate hover:text-ink"
          }`}
        >
          {t.label}
          {t.count != null && <span className="ml-1.5 text-slate tabular-nums">{t.count}</span>}
          {value === t.value && <span className="absolute left-2 right-2 bottom-0 h-0.5 rounded-full bg-ink" aria-hidden />}
        </button>
      ))}
    </div>
  );
}

// --- data display ----------------------------------------------------------------------------

export function Stat({ label, value, sub, href }: { label: string; value: ReactNode; sub?: ReactNode; href?: string }) {
  const body = (
    <>
      <div className="text-[13px] text-slate">{label}</div>
      <div className="font-display text-[1.6rem] leading-tight mt-1 tabular-nums">{value}</div>
      {sub && <div className="text-[13px] text-slate mt-1">{sub}</div>}
    </>
  );
  const cls = "rounded-2xl bg-paper border border-line p-4 sm:p-5 min-w-0";
  return href ? (
    <Link href={href} className={`${cls} block hover:border-slate/60 transition-colors`}>
      {body}
    </Link>
  ) : (
    <div className={cls}>{body}</div>
  );
}

export function KeyValues({ rows }: { rows: [string, ReactNode][] }) {
  return (
    <dl className="grid grid-cols-[fit-content(45%)_minmax(0,1fr)] gap-x-6 gap-y-2 text-[14px]">
      {rows.map(([k, v]) => (
        <div key={k} className="contents">
          <dt className="text-slate break-words min-w-0">{k}</dt>
          <dd className="min-w-0 break-words">{v ?? <span className="text-slate">None</span>}</dd>
        </div>
      ))}
    </dl>
  );
}

/**
 * Copies text to the clipboard. The Clipboard API only exists on HTTPS and localhost, and Docker Manager is often
 * opened over plain HTTP on a local network, so this falls back to the older selection-based copy.
 */
export async function copyText(text: string): Promise<boolean> {
  try {
    if (navigator.clipboard && window.isSecureContext) {
      await navigator.clipboard.writeText(text);
      return true;
    }
  } catch {}
  try {
    const area = document.createElement("textarea");
    area.value = text;
    area.setAttribute("readonly", "");
    area.style.position = "fixed";
    area.style.opacity = "0";
    document.body.appendChild(area);
    area.select();
    const ok = document.execCommand("copy");
    area.remove();
    return ok;
  } catch {
    return false;
  }
}

export function Copyable({ text, display, className = "" }: { text: string; display?: ReactNode; className?: string }) {
  const [copied, setCopied] = useState(false);
  return (
    <span className={`inline-flex items-center gap-1.5 min-w-0 ${className}`}>
      <span className="truncate font-mono text-[13px]">{display ?? text}</span>
      <button
        type="button"
        aria-label="Copy"
        title="Copy"
        className="shrink-0 text-slate hover:text-ink"
        onClick={async () => {
          if (await copyText(text)) {
            setCopied(true);
            setTimeout(() => setCopied(false), 1500);
          }
        }}
      >
        {copied ? <Check size={14} /> : <Copy size={14} />}
      </button>
    </span>
  );
}

/** Scrollable log output that sticks to the bottom while new lines arrive, with copy and download buttons. */
export function LogView({
  text,
  lines,
  height = "h-[420px]",
  wrap = false,
  empty = "No output yet.",
  filename = "output.log",
}: {
  text?: string;
  lines?: string[];
  height?: string;
  wrap?: boolean;
  empty?: string;
  filename?: string;
}) {
  const ref = useRef<HTMLPreElement>(null);
  const stick = useRef(true);
  const [copied, setCopied] = useState<null | "ok" | "failed">(null);
  const content = text ?? (lines ?? []).join("\n");
  useEffect(() => {
    const el = ref.current;
    if (el && stick.current) el.scrollTop = el.scrollHeight;
  }, [content]);
  const download = () => {
    const url = URL.createObjectURL(new Blob([content], { type: "text/plain;charset=utf-8" }));
    const a = document.createElement("a");
    a.href = url;
    a.download = filename;
    a.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  };
  const tool = "h-7 px-2 inline-flex items-center gap-1.5 rounded-md text-[12px] text-slate hover:text-ink hover:bg-paper border border-transparent hover:border-line";
  return (
    <div className="relative group">
      {content && (
        <div className="absolute top-2 right-4 z-10 flex items-center gap-1 rounded-lg bg-well/90 backdrop-blur-sm">
          <button
            type="button"
            className={tool}
            onClick={async () => {
              setCopied((await copyText(content)) ? "ok" : "failed");
              setTimeout(() => setCopied(null), 1500);
            }}
          >
            {copied === "ok" ? <Check size={13} aria-hidden /> : <Copy size={13} aria-hidden />}
            {copied === "ok" ? "Copied" : copied === "failed" ? "Couldn't copy" : "Copy"}
          </button>
          <button type="button" className={tool} onClick={download}>
            <Download size={13} aria-hidden /> Download
          </button>
        </div>
      )}
      <pre
        ref={ref}
        onScroll={(e) => {
          const el = e.currentTarget;
          stick.current = el.scrollHeight - el.scrollTop - el.clientHeight < 40;
        }}
        className={`log ${wrap ? "log-wrap" : ""} ${height} overflow-auto rounded-xl bg-well border border-line p-4 ${content ? "pt-11" : ""} text-ink/90`}
        tabIndex={0}
      >
        {content || <span className="text-slate">{empty}</span>}
      </pre>
    </div>
  );
}

/** A plain code editor: monospace textarea with line numbers and Tab indentation. */
export function CodeEditor({
  value,
  onChange,
  rows = 18,
  placeholder,
  label,
  readOnly,
}: {
  value: string;
  onChange?: (v: string) => void;
  rows?: number;
  placeholder?: string;
  label: string;
  readOnly?: boolean;
}) {
  const lines = Math.max(rows, value.split("\n").length);
  const gutter = useRef<HTMLDivElement>(null);
  return (
    <div className="flex rounded-xl border border-line bg-paper overflow-hidden focus-within:border-slate">
      <div ref={gutter} aria-hidden className="code select-none text-right text-slate/60 bg-well px-2.5 py-2.5 overflow-hidden" style={{ minWidth: "3rem" }}>
        {Array.from({ length: lines }, (_, i) => (
          <div key={i}>{i + 1}</div>
        ))}
      </div>
      <textarea
        aria-label={label}
        value={value}
        readOnly={readOnly}
        placeholder={placeholder}
        spellCheck={false}
        autoCapitalize="off"
        autoCorrect="off"
        rows={rows}
        onScroll={(e) => {
          if (gutter.current) gutter.current.scrollTop = e.currentTarget.scrollTop;
        }}
        onChange={(e) => onChange?.(e.target.value)}
        onKeyDown={(e) => {
          if (e.key === "Tab" && !e.shiftKey && onChange && !readOnly) {
            e.preventDefault();
            const t = e.currentTarget;
            const s = t.selectionStart;
            const next = value.slice(0, s) + "  " + value.slice(t.selectionEnd);
            onChange(next);
            requestAnimationFrame(() => t.setSelectionRange(s + 2, s + 2));
          }
        }}
        className="code flex-1 min-w-0 bg-transparent px-3 py-2.5 resize-y focus:outline-none whitespace-pre overflow-auto"
        style={{ minHeight: `${rows * 1.6}em` }}
      />
    </div>
  );
}

/** Rows of key/value or single-value inputs that can be added and removed. */
export function ListEditor({
  items,
  onChange,
  placeholder,
  addLabel,
  mono = true,
}: {
  items: string[];
  onChange: (v: string[]) => void;
  placeholder: string;
  addLabel: string;
  mono?: boolean;
}) {
  return (
    <div className="flex flex-col gap-2">
      {items.map((v, i) => (
        <div key={i} className="flex gap-2">
          <input
            className={`${inputClass} ${mono ? "font-mono text-[13px]" : ""}`}
            value={v}
            placeholder={placeholder}
            aria-label={`${placeholder} ${i + 1}`}
            onChange={(e) => onChange(items.map((x, j) => (j === i ? e.target.value : x)))}
          />
          <IconButton label="Remove" onClick={() => onChange(items.filter((_, j) => j !== i))} className="mt-1.5 shrink-0">
            <X size={16} />
          </IconButton>
        </div>
      ))}
      <div>
        <Button type="button" tone="quiet" size="sm" onClick={() => onChange([...items, ""])}>
          {addLabel}
        </Button>
      </div>
    </div>
  );
}

/** A table that scrolls sideways on narrow screens. */
export function Table({ head, children, empty }: { head: ReactNode[]; children: ReactNode; empty?: ReactNode }) {
  return (
    <div className="table-scroll rounded-2xl border border-line bg-paper">
      <table className="w-full text-[14px] border-collapse">
        <thead>
          <tr className="text-left text-[12.5px] text-slate border-b border-line">
            {head.map((h, i) => (
              <th key={i} className="font-medium px-4 py-2.5 whitespace-nowrap">
                {h}
              </th>
            ))}
          </tr>
        </thead>
        <tbody className="[&>tr]:border-b [&>tr]:border-line [&>tr:last-child]:border-0 [&_td]:px-4 [&_td]:py-3 [&_td]:align-middle">{children}</tbody>
      </table>
      {empty}
    </div>
  );
}

// --- selection, bulk actions and paging ----------------------------------------------------------

export function Checkbox({
  checked,
  indeterminate = false,
  onChange,
  label,
  disabled,
  title,
}: {
  checked: boolean;
  indeterminate?: boolean;
  onChange: (v: boolean) => void;
  label: string;
  disabled?: boolean;
  title?: string;
}) {
  const on = checked || indeterminate;
  return (
    <button
      type="button"
      role="checkbox"
      aria-checked={indeterminate ? "mixed" : checked}
      aria-label={label}
      title={title ?? label}
      disabled={disabled}
      onClick={() => onChange(!checked)}
      className={`size-[18px] shrink-0 grid place-items-center rounded-[5px] border transition-colors disabled:opacity-30 disabled:cursor-not-allowed ${
        on ? "bg-accent border-accent text-accent-ink" : "border-line bg-paper hover:border-slate"
      }`}
    >
      {indeterminate ? <span className="w-2 h-0.5 rounded bg-current" /> : checked ? <Check size={13} strokeWidth={3} aria-hidden /> : null}
    </button>
  );
}

/** Floats at the bottom of the screen while something is selected. */
export function BulkBar({ count, noun, onClear, children }: { count: number; noun: string; onClear: () => void; children: ReactNode }) {
  if (!count) return null;
  return (
    <div className="sticky bottom-4 z-20 mt-4 flex justify-center pointer-events-none">
      <div role="region" aria-label="Selection" className="pointer-events-auto flex flex-wrap items-center gap-2 rounded-full border border-line bg-paper/95 backdrop-blur-lg shadow-[0_8px_30px_rgba(0,0,0,0.25)] pl-4 pr-1.5 py-1.5">
        <span className="text-[14px] tabular-nums mr-1">
          {count} {noun}
          {count === 1 ? "" : "s"} selected
        </span>
        {children}
        <Button tone="ghost" size="sm" onClick={onClear}>
          Clear
        </Button>
      </div>
    </div>
  );
}

/** Previous / next and page numbers for a list split into pages. */
export function Pagination({
  page,
  pages,
  total,
  size,
  onPage,
  onSize,
  noun = "item",
}: {
  page: number;
  pages: number;
  total: number;
  size: number;
  onPage: (p: number) => void;
  onSize?: (s: number) => void;
  noun?: string;
}) {
  if (total <= (onSize ? 25 : size) && pages <= 1) return null;
  const from = total === 0 ? 0 : page * size + 1;
  const to = Math.min(total, (page + 1) * size);
  // 1 ... 4 5 6 ... 12
  const nums: (number | "gap")[] = [];
  for (let i = 0; i < pages; i++) {
    if (i === 0 || i === pages - 1 || Math.abs(i - page) <= 1) nums.push(i);
    else if (nums[nums.length - 1] !== "gap") nums.push("gap");
  }
  const btn = "min-w-8 h-8 px-2 rounded-full text-[13px] tabular-nums transition-colors";
  return (
    <nav aria-label="Pages" className="flex flex-wrap items-center justify-between gap-3 mt-4 text-[13px] text-slate">
      <span className="tabular-nums">
        {from}–{to} of {total} {noun}
        {total === 1 ? "" : "s"}
      </span>
      <div className="flex items-center gap-1">
        {onSize && (
          <select
            aria-label="Per page"
            value={size}
            onChange={(e) => onSize(Number(e.target.value))}
            className="h-8 rounded-full border border-line bg-paper px-2 mr-2 text-[13px] text-ink"
          >
            {[25, 50, 100].map((n) => (
              <option key={n} value={n}>
                {n} per page
              </option>
            ))}
          </select>
        )}
        <button className={`${btn} hover:bg-well disabled:opacity-30`} disabled={page === 0} onClick={() => onPage(page - 1)} aria-label="Previous page">
          ‹
        </button>
        {nums.map((n, i) =>
          n === "gap" ? (
            <span key={`g${i}`} className="px-1" aria-hidden>
              …
            </span>
          ) : (
            <button key={n} onClick={() => onPage(n)} aria-current={n === page ? "page" : undefined} className={`${btn} ${n === page ? "bg-well text-ink font-medium" : "hover:bg-well"}`}>
              {n + 1}
            </button>
          ),
        )}
        <button className={`${btn} hover:bg-well disabled:opacity-30`} disabled={page + 1 >= pages} onClick={() => onPage(page + 1)} aria-label="Next page">
          ›
        </button>
      </div>
    </nav>
  );
}

/** The row above a selectable list: a checkbox for the page, a summary, and "select all". */
export function SelectHeader({
  label,
  pageIds,
  all,
  some,
  onPage,
  summary,
  selectAll,
}: {
  label: string;
  pageIds: string[];
  all: boolean;
  some: boolean;
  onPage: (on: boolean) => void;
  summary: string;
  selectAll: { text: string; onClick: () => void } | null;
}) {
  return (
    <div className="flex flex-wrap items-center gap-3 px-4 py-2.5 border-b border-line text-[13px] text-slate">
      <Checkbox label={label} checked={all} indeterminate={!all && some} disabled={!pageIds.length} onChange={onPage} />
      <span className="flex-1">{summary}</span>
      {selectAll && (
        <button className="text-ink underline underline-offset-2" onClick={selectAll.onClick}>
          {selectAll.text}
        </button>
      )}
    </div>
  );
}
