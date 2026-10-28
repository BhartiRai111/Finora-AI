"use client";

import { useEffect, useRef, useState } from "react";
import { formatMoney } from "@/lib/finance";

export type ChartSeries = { key: string; label: string; values: (string | undefined)[] };

export const SERIES_COLORS = ["var(--series-1)", "var(--series-2)", "var(--series-3)", "var(--series-4)", "var(--series-5)", "var(--series-6)"];
const DEFAULT_W = 640;
const H = 240;
const PAD = { top: 14, right: 10, bottom: 40, left: 112 };

/** The drawing width follows the box it sits in, so axis text stays at its real pixel size instead of shrinking with the page. */
function useWidth(): [React.RefObject<HTMLDivElement | null>, number] {
  const ref = useRef<HTMLDivElement>(null);
  const [w, setW] = useState(DEFAULT_W);
  useEffect(() => {
    const el = ref.current;
    if (!el || typeof ResizeObserver === "undefined") return;
    const ro = new ResizeObserver(([entry]) => {
      const next = Math.round(entry?.contentRect.width ?? 0);
      if (next >= 260) setW(Math.min(next, 900));
    });
    ro.observe(el);
    return () => ro.disconnect();
  }, []);
  return [ref, w];
}

function numberOf(v: string | undefined): number {
  if (v === undefined || v === "") return 0;
  const n = Number(v);
  return Number.isFinite(n) ? n : 0;
}

/** Exact label for the largest or smallest value, taken from the backend's own strings. */
function extreme(series: ChartSeries[], pick: "max" | "min"): string | undefined {
  let best: string | undefined;
  let bestN = pick === "max" ? -Infinity : Infinity;
  for (const s of series) for (const v of s.values) {
    if (v === undefined || v === "") continue;
    const n = Number(v);
    if (Number.isFinite(n) && (pick === "max" ? n > bestN : n < bestN)) { bestN = n; best = v; }
  }
  return best;
}

export function Legend({ series }: { series: { key: string; label: string }[] }) {
  return (
    <ul className="legend" aria-label="Legend">
      {series.map((s, i) => <li key={s.key}><span className="swatch" style={{ background: SERIES_COLORS[i % SERIES_COLORS.length] }} aria-hidden="true" />{s.label}</li>)}
    </ul>
  );
}

/** A hover tooltip listing every series at one label, with exact backend figures. Purely visual: the same figures are in the table or list beside each chart. */
function Tip({ leftPct, label, rows, currency }: { leftPct: number; label: string; rows: { key: string; label: string; value: string | undefined; color: string }[]; currency: string }) {
  return (
    <div className="chart-tip" role="presentation" style={{ left: `${Math.min(80, Math.max(2, leftPct))}%`, top: 0 }}>
      <strong>{label}</strong>
      {rows.map((r) => (
        <div key={r.key}><span className="swatch" style={{ background: r.color }} aria-hidden="true" />{r.label}: {r.value === undefined || r.value === "" ? "—" : formatMoney(r.value, currency)}</div>
      ))}
    </div>
  );
}

/**
 * A grouped bar chart of figures the backend already computed. Numbers are used only to size the bars; every label shows the backend's own
 * decimal string. It is a visual aid: the same figures are always in the table beside it, and the chart is described to assistive technology
 * by `summary`.
 */
export function BarChart({ title, currency, labels, series, summary, caption = "the table below has every figure" }: { title: string; currency: string; labels: string[]; series: ChartSeries[]; summary: string; caption?: string }) {
  const [hover, setHover] = useState<number | null>(null);
  const [boxRef, W] = useWidth();
  const nums = series.flatMap((s) => s.values.map(numberOf));
  const max = Math.max(0, ...nums);
  const min = Math.min(0, ...nums);
  const span = max - min || 1;
  const y = (n: number) => PAD.top + ((max - n) / span) * (H - PAD.top - PAD.bottom);
  const zero = y(0);
  const groupW = (W - PAD.left - PAD.right) / Math.max(labels.length, 1);
  const barW = Math.max(4, Math.min(28, (groupW * 0.8) / series.length));
  const every = Math.max(1, Math.ceil(56 / groupW)); // show every nth label when they would overlap; the table has them all
  const top = extreme(series, "max");
  const bottom = extreme(series, "min");

  return (
    <figure className="chart">
      <figcaption><strong>{title}</strong> <span className="muted">({currency}; {caption})</span></figcaption>
      <Legend series={series} />
      <div className="chart-box" ref={boxRef}>
        <svg viewBox={`0 0 ${W} ${H}`} role="img" aria-label={summary} className="chart-svg" style={{ height: H }} onMouseLeave={() => setHover(null)}>
          {[0.25, 0.5, 0.75].map((f) => <line key={f} className="chart-grid" x1={PAD.left} x2={W - PAD.right} y1={PAD.top + f * (H - PAD.top - PAD.bottom)} y2={PAD.top + f * (H - PAD.top - PAD.bottom)} />)}
          <line x1={PAD.left} x2={W - PAD.right} y1={zero} y2={zero} stroke="currentColor" strokeOpacity="0.5" />
          {max > 0 && top !== undefined && <text x={PAD.left - 6} y={PAD.top + 8} textAnchor="end">{formatMoney(top, currency)}</text>}
          <text x={PAD.left - 6} y={zero + 4} textAnchor="end">0</text>
          {min < 0 && bottom !== undefined && <text x={PAD.left - 6} y={H - PAD.bottom} textAnchor="end">{formatMoney(bottom, currency)}</text>}
          {labels.map((label, gi) => {
            const x0 = PAD.left + gi * groupW + (groupW - barW * series.length) / 2;
            return (
              <g key={`${label}-${gi}`} onMouseEnter={() => setHover(gi)}>
                <rect data-hit x={PAD.left + gi * groupW} y={PAD.top} width={groupW} height={H - PAD.top - PAD.bottom} fill={hover === gi ? "var(--accent-soft)" : "transparent"} />
                {series.map((s, si) => {
                  const raw = s.values[gi];
                  const n = numberOf(raw);
                  const yy = y(n);
                  return (
                    <rect key={s.key} data-bar x={x0 + si * barW} y={Math.min(yy, zero)} width={barW - 2} height={Math.max(Math.abs(zero - yy), n === 0 ? 0 : 1)} rx={3} style={{ fill: SERIES_COLORS[si % SERIES_COLORS.length] }}>
                      <title>{`${label}: ${s.label} ${raw === undefined || raw === "" ? "—" : formatMoney(raw, currency)}`}</title>
                    </rect>
                  );
                })}
                {gi % every === 0 && <text x={PAD.left + gi * groupW + groupW / 2} y={H - PAD.bottom + 16} textAnchor="middle">{label.length > 12 ? `${label.slice(0, 11)}…` : label}</text>}
              </g>
            );
          })}
        </svg>
        {hover !== null && labels[hover] !== undefined && (
          <Tip leftPct={((PAD.left + hover * groupW + groupW / 2) / W) * 100} label={labels[hover]!} currency={currency}
            rows={series.map((s, i) => ({ key: s.key, label: s.label, value: s.values[hover], color: SERIES_COLORS[i % SERIES_COLORS.length]! }))} />
        )}
      </div>
    </figure>
  );
}

/** A line (or several) over labelled periods. Same rules as the bar chart: exact backend labels, sizes only from numbers, a text description and a legend. */
export function LineChart({ title, currency, labels, series, summary, caption = "the list beside it has every figure" }: { title: string; currency: string; labels: string[]; series: ChartSeries[]; summary: string; caption?: string }) {
  const [hover, setHover] = useState<number | null>(null);
  const [boxRef, W] = useWidth();
  const nums = series.flatMap((s) => s.values.map(numberOf));
  const max = Math.max(0, ...nums);
  const min = Math.min(0, ...nums);
  const span = max - min || 1;
  const y = (n: number) => PAD.top + ((max - n) / span) * (H - PAD.top - PAD.bottom);
  const step = (W - PAD.left - PAD.right) / Math.max(labels.length, 1);
  const x = (i: number) => PAD.left + step * i + step / 2;
  const every = Math.max(1, Math.ceil(56 / step));
  const top = extreme(series, "max");
  const bottom = extreme(series, "min");
  return (
    <figure className="chart">
      <figcaption><strong>{title}</strong> <span className="muted">({currency}; {caption})</span></figcaption>
      <Legend series={series} />
      <div className="chart-box" ref={boxRef}>
        <svg viewBox={`0 0 ${W} ${H}`} role="img" aria-label={summary} className="chart-svg" style={{ height: H }} onMouseLeave={() => setHover(null)}>
          {[0.25, 0.5, 0.75].map((f) => <line key={f} className="chart-grid" x1={PAD.left} x2={W - PAD.right} y1={PAD.top + f * (H - PAD.top - PAD.bottom)} y2={PAD.top + f * (H - PAD.top - PAD.bottom)} />)}
          <line x1={PAD.left} x2={W - PAD.right} y1={y(0)} y2={y(0)} stroke="currentColor" strokeOpacity="0.5" />
          {max > 0 && top !== undefined && <text x={PAD.left - 6} y={PAD.top + 8} textAnchor="end">{formatMoney(top, currency)}</text>}
          <text x={PAD.left - 6} y={y(0) + 4} textAnchor="end">0</text>
          {min < 0 && bottom !== undefined && <text x={PAD.left - 6} y={H - PAD.bottom} textAnchor="end">{formatMoney(bottom, currency)}</text>}
          {series.map((s, si) => (
            <g key={s.key}>
              <polyline fill="none" strokeWidth={2.5} strokeLinejoin="round" strokeLinecap="round" points={s.values.map((v, i) => `${x(i)},${y(numberOf(v))}`).join(" ")} style={{ stroke: SERIES_COLORS[si % SERIES_COLORS.length] }} />
              {s.values.map((v, i) => <circle key={i} data-point cx={x(i)} cy={y(numberOf(v))} r={hover === i ? 5 : 3.5} style={{ fill: SERIES_COLORS[si % SERIES_COLORS.length] }}><title>{`${labels[i]}: ${s.label} ${v === undefined || v === "" ? "—" : formatMoney(v, currency)}`}</title></circle>)}
            </g>
          ))}
          {labels.map((label, i) => (
            <g key={`${label}-${i}`} onMouseEnter={() => setHover(i)}>
              <rect data-hit x={PAD.left + step * i} y={PAD.top} width={step} height={H - PAD.top - PAD.bottom} fill={hover === i ? "var(--accent-soft)" : "transparent"} />
              {i % every === 0 && <text x={x(i)} y={H - PAD.bottom + 16} textAnchor="middle">{label.length > 12 ? `${label.slice(0, 11)}…` : label}</text>}
            </g>
          ))}
        </svg>
        {hover !== null && labels[hover] !== undefined && (
          <Tip leftPct={(x(hover) / W) * 100} label={labels[hover]!} currency={currency}
            rows={series.map((s, i) => ({ key: s.key, label: s.label, value: s.values[hover], color: SERIES_COLORS[i % SERIES_COLORS.length]! }))} />
        )}
      </div>
    </figure>
  );
}

export type Slice = { key: string; label: string; amount: string };

/**
 * A donut of shares. Slice sizes use numbers only for geometry; the centre shows the exact total passed in and the list beside it shows each
 * slice's exact amount and its rounded share, so the picture never carries information the text does not.
 */
export function DonutChart({ title, currency, slices, total, summary }: { title: string; currency: string; slices: Slice[]; total: string; summary: string }) {
  const sum = slices.reduce((a, s) => a + numberOf(s.amount), 0);
  const R = 70;
  const C = 2 * Math.PI * R;
  let offset = 0;
  return (
    <figure className="chart" style={{ display: "grid", gap: "1rem", gridTemplateColumns: "minmax(8rem, 9.5rem) minmax(0, 1fr)", alignItems: "center" }}>
      <svg viewBox="0 0 180 180" role="img" aria-label={summary} style={{ width: "100%", maxWidth: "9.5rem" }}>
        <circle cx="90" cy="90" r={R} fill="none" strokeWidth="22" style={{ stroke: "var(--surface-3)" }} />
        {sum > 0 && slices.map((s, i) => {
          const len = (numberOf(s.amount) / sum) * C;
          const el = <circle key={s.key} data-slice cx="90" cy="90" r={R} fill="none" strokeWidth="22" strokeDasharray={`${len} ${C - len}`} strokeDashoffset={-offset} transform="rotate(-90 90 90)" style={{ stroke: SERIES_COLORS[i % SERIES_COLORS.length] }}><title>{`${s.label}: ${formatMoney(s.amount, currency)}`}</title></circle>;
          offset += len;
          return el;
        })}
        <text x="90" y="86" textAnchor="middle" style={{ fill: "var(--text-muted)", fontSize: "10px" }}>Total</text>
        <text x="90" y="104" textAnchor="middle" style={{ fill: "var(--text)", fontSize: "13px", fontWeight: 700 }}>{formatMoney(total, currency)}</text>
      </svg>
      <figcaption style={{ margin: 0 }}>
        <strong className="visually-hidden">{title}</strong>
        <ul className="donut-legend" aria-label="Categories">
          {slices.map((s, i) => (
            <li key={s.key}>
              <span><span className="swatch" style={{ background: SERIES_COLORS[i % SERIES_COLORS.length] }} aria-hidden="true" />{s.label}</span>
              <span className="num">{formatMoney(s.amount, currency)}{sum > 0 ? ` · ${Math.round((numberOf(s.amount) / sum) * 100)}%` : ""}</span>
            </li>
          ))}
        </ul>
      </figcaption>
    </figure>
  );
}

/** A progress bar with a text value; the state (on track, close, over) is also said in words by the caller, never by colour alone. */
export function ProgressBar({ percent, tone = "under", label }: { percent: number; tone?: "under" | "warning" | "over"; label: string }) {
  const value = Math.max(0, Math.min(100, percent));
  return (
    <div className="bar-track" role="progressbar" aria-label={label} aria-valuemin={0} aria-valuemax={100} aria-valuenow={Math.round(value)}>
      <div className={`bar-fill ${tone}`} style={{ width: `${value}%` }} />
    </div>
  );
}
