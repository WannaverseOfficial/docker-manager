"use client";

import { useId, useMemo, useRef, useState } from "react";

/**
 * One series over time: a 2px line with a soft fill, a recessive grid, and a crosshair tooltip on hover or touch.
 * Single series, so no legend; the title names it. The latest value is printed beside the title so it can be read
 * without hovering.
 */
export function LineChart({
  title,
  points,
  format,
  max,
  height = 120,
}: {
  title: string;
  points: { t: number; v: number }[];
  format: (v: number) => string;
  /** Fixed top of the scale (e.g. 100 for percentages); otherwise the data's maximum. */
  max?: number;
  height?: number;
}) {
  const ref = useRef<SVGSVGElement>(null);
  const [hover, setHover] = useState<number | null>(null);
  const gradient = useId();
  const W = 600;
  const H = height;
  const pad = { top: 8, bottom: 18 };

  const { path, area, top, xs, ys } = useMemo(() => {
    const n = points.length;
    const peak = max ?? Math.max(1e-9, ...points.map((p) => p.v)) * 1.15;
    const xs = points.map((_, i) => (n <= 1 ? W : (i / (n - 1)) * W));
    const ys = points.map((p) => pad.top + (1 - Math.min(p.v, peak) / peak) * (H - pad.top - pad.bottom));
    const path = xs.map((x, i) => `${i ? "L" : "M"}${x.toFixed(1)},${ys[i].toFixed(1)}`).join(" ");
    const base = H - pad.bottom;
    const area = n ? `${path} L${xs[n - 1].toFixed(1)},${base} L${xs[0].toFixed(1)},${base} Z` : "";
    return { path, area, top: peak, xs, ys };
  }, [points, max, H, pad.top, pad.bottom]);

  const last = points[points.length - 1];
  const shown = hover != null ? points[hover] : null;

  const pick = (clientX: number) => {
    const el = ref.current;
    if (!el || points.length === 0) return;
    const r = el.getBoundingClientRect();
    const x = ((clientX - r.left) / r.width) * W;
    let best = 0;
    for (let i = 1; i < xs.length; i++) if (Math.abs(xs[i] - x) < Math.abs(xs[best] - x)) best = i;
    setHover(best);
  };

  return (
    <figure className="min-w-0">
      <figcaption className="flex items-baseline justify-between text-[14px] mb-2">
        <span>{title}</span>
        <span className="text-slate tabular-nums">{last ? format(last.v) : "…"}</span>
      </figcaption>
      <div className="relative">
        <svg
          ref={ref}
          viewBox={`0 0 ${W} ${H}`}
          preserveAspectRatio="none"
          className="w-full block touch-none"
          style={{ height: H }}
          role="img"
          aria-label={`${title}: latest ${last ? format(last.v) : "no data"}`}
          onPointerMove={(e) => pick(e.clientX)}
          onPointerDown={(e) => pick(e.clientX)}
          onPointerLeave={() => setHover(null)}
        >
          <defs>
            <linearGradient id={gradient} x1="0" x2="0" y1="0" y2="1">
              <stop offset="0%" stopColor="var(--ink)" stopOpacity="0.14" />
              <stop offset="100%" stopColor="var(--ink)" stopOpacity="0" />
            </linearGradient>
          </defs>
          {[0.25, 0.5, 0.75].map((f) => (
            <line key={f} x1={0} x2={W} y1={pad.top + f * (H - pad.top - pad.bottom)} y2={pad.top + f * (H - pad.top - pad.bottom)} stroke="var(--line)" strokeWidth={1} vectorEffect="non-scaling-stroke" />
          ))}
          <line x1={0} x2={W} y1={H - pad.bottom} y2={H - pad.bottom} stroke="var(--line)" strokeWidth={1} vectorEffect="non-scaling-stroke" />
          {points.length > 1 && (
            <>
              <path d={area} fill={`url(#${gradient})`} />
              <path d={path} fill="none" stroke="var(--ink)" strokeWidth={2} vectorEffect="non-scaling-stroke" strokeLinejoin="round" strokeLinecap="round" />
            </>
          )}
          {hover != null && (
            <line x1={xs[hover]} x2={xs[hover]} y1={pad.top} y2={H - pad.bottom} stroke="var(--slate)" strokeWidth={1} vectorEffect="non-scaling-stroke" strokeDasharray="3 3" />
          )}
        </svg>
        {hover != null && shown && (
          <>
            <span
              className="absolute size-2.5 rounded-full bg-ink ring-2 ring-paper -translate-x-1/2 -translate-y-1/2 pointer-events-none"
              style={{ left: `${(xs[hover] / W) * 100}%`, top: ys[hover] }}
              aria-hidden
            />
            <div
              className="absolute -top-1 -translate-y-full px-2.5 py-1.5 rounded-lg bg-paper border border-line text-[12.5px] whitespace-nowrap pointer-events-none shadow-lg"
              style={{ left: `clamp(0px, calc(${(xs[hover] / W) * 100}% - 50px), calc(100% - 110px))` }}
            >
              <span className="tabular-nums">{format(shown.v)}</span>
              <span className="text-slate"> · {new Date(shown.t).toLocaleTimeString()}</span>
            </div>
          </>
        )}
        <div className="flex justify-between text-[11.5px] text-slate mt-0.5 tabular-nums" aria-hidden>
          <span>{points.length > 1 ? `${Math.round((points[points.length - 1].t - points[0].t) / 1000)} s ago` : ""}</span>
          <span>max {format(top)}</span>
        </div>
      </div>
    </figure>
  );
}
