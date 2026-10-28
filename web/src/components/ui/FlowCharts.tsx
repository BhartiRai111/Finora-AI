"use client";

import { useEffect, useRef, useState } from "react";
import { formatMoney } from "@/lib/finance";
import { Legend, Tip, numberOf, useWidth, type ChartSeries } from "./BarChart";

const MIN_H = 260;
const shortMonth = (period: string): string => {
  const [y, m] = period.split("-").map(Number);
  return y && m ? new Date(Date.UTC(y, m - 1, 1)).toLocaleString("en", { month: "short", timeZone: "UTC" }) : period;
};
const PAD = { top: 16, right: 12, bottom: 38, left: 112 };

/** The height of the chart box, which grows with the panel it sits in (the box is laid out by CSS; the drawing is positioned inside it, so it never pushes the box taller). */
function useHeight(ref: React.RefObject<HTMLDivElement | null>): number {
  const [h, setH] = useState(MIN_H);
  const last = useRef(MIN_H);
  useEffect(() => {
    const el = ref.current;
    if (!el || typeof ResizeObserver === "undefined") return;
    const ro = new ResizeObserver(() => {
      const next = Math.max(MIN_H, Math.round(el.getBoundingClientRect().height));
      if (next !== last.current) { last.current = next; setH(next); }
    });
    ro.observe(el);
    return () => ro.disconnect();
  }, [ref]);
  return h;
}

/**
 * Income, spending and net savings as three bars per month. Numbers only size the drawing; every label is the
 * backend's own decimal string, the chart has a text description, and the same figures are listed beside it.
 */
export function CashFlowChart({ currency, labels, income, spending, net, summary }: { currency: string; labels: string[]; income: (string | undefined)[]; spending: (string | undefined)[]; net: (string | undefined)[]; summary: string }) {
  const [hover, setHover] = useState<number | null>(null);
  const [boxRef, W] = useWidth();
  const H = useHeight(boxRef);
  const all = [...income, ...spending, ...net].map(numberOf);
  const max = Math.max(0, ...all);
  const min = Math.min(0, ...all);
  const span = max - min || 1;
  const y = (n: number) => PAD.top + ((max - n) / span) * (H - PAD.top - PAD.bottom);
  const group = (W - PAD.left - PAD.right) / Math.max(labels.length, 1);
  const barW = Math.max(4, Math.min(16, group * 0.2));
  const cx = (i: number) => PAD.left + group * i + group / 2;
  const every = Math.max(1, Math.ceil(56 / group));
  const topValue = [...income, ...spending, ...net].filter((v): v is string => v !== undefined && v !== "").sort((a, b) => numberOf(b) - numberOf(a))[0];
  const series: ChartSeries[] = [{ key: "income", label: "Income", values: income }, { key: "spending", label: "Spending", values: spending }, { key: "net", label: "Net saved", values: net }];
  const colors = ["var(--series-pale)", "var(--series-2)", "var(--series-1)"];
  return (
    <figure className="chart chart-fill">
      <Legend series={series} colors={colors} />
      <div className="chart-box" ref={boxRef}>
        <svg viewBox={`0 0 ${W} ${H}`} role="img" aria-label={summary} className="chart-svg" style={{ height: H }} onMouseLeave={() => setHover(null)}>
          {[0.25, 0.5, 0.75].map((f) => <line key={f} className="chart-grid" x1={PAD.left} x2={W - PAD.right} y1={PAD.top + f * (H - PAD.top - PAD.bottom)} y2={PAD.top + f * (H - PAD.top - PAD.bottom)} />)}
          <line x1={PAD.left} x2={W - PAD.right} y1={y(0)} y2={y(0)} stroke="currentColor" strokeOpacity="0.45" />
          {max > 0 && topValue !== undefined && <text x={PAD.left - 8} y={PAD.top + 8} textAnchor="end">{formatMoney(topValue, currency)}</text>}
          <text x={PAD.left - 8} y={y(0) + 4} textAnchor="end">0</text>
          {labels.map((label, i) => (
            <g key={`${label}-${i}`} onMouseEnter={() => setHover(i)}>
              <rect data-hit x={PAD.left + group * i} y={PAD.top} width={group} height={H - PAD.top - PAD.bottom} rx={8} fill="transparent" style={{ stroke: hover === i ? "var(--series-1)" : "transparent" }} strokeWidth={1.5} />
              {[income, spending, net].map((vals, k) => {
                const v = numberOf(vals[i]);
                return (
                  <rect key={k} data-bar x={cx(i) + (k - 1.5) * (barW + 2) + 1} y={Math.min(y(v), y(0))} width={barW} height={Math.max(Math.abs(y(0) - y(v)), v === 0 ? 0 : 1)} rx={4} style={{ fill: colors[k] }}>
                    <title>{`${label}: ${series[k]!.label} ${vals[i] ? formatMoney(vals[i]!, currency) : "—"}`}</title>
                  </rect>
                );
              })}
              {i % every === 0 && <text x={cx(i)} y={H - PAD.bottom + 18} textAnchor="middle">{shortMonth(label)}</text>}
            </g>
          ))}
        </svg>
        {hover !== null && labels[hover] !== undefined && (
          <Tip leftPct={(cx(hover) / W) * 100} label={labels[hover]!} currency={currency} rows={series.map((s, i) => ({ key: s.key, label: s.label, value: s.values[hover], color: colors[i]! }))} />
        )}
      </div>
    </figure>
  );
}
