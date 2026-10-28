"use client";

import { useState } from "react";
import { formatMoney } from "@/lib/finance";
import { Legend, Tip, numberOf, useWidth, type ChartSeries } from "./BarChart";

const H = 260;
const PAD = { top: 16, right: 12, bottom: 38, left: 112 };

/** A tiny trend line for a summary card. Decorative: the figures and the comparison next to it carry the meaning. */
export function Sparkline({ values, tone = "var(--series-1)" }: { values: (string | undefined)[]; tone?: string }) {
  const nums = values.map(numberOf);
  if (nums.length < 2) return null;
  const min = Math.min(...nums);
  const max = Math.max(...nums);
  const span = max - min || 1;
  const w = 96;
  const h = 30;
  const points = nums.map((n, i) => `${(i / (nums.length - 1)) * w},${h - 3 - ((n - min) / span) * (h - 6)}`).join(" ");
  return (
    <svg className="sparkline" viewBox={`0 0 ${w} ${h}`} width={w} height={h} aria-hidden="true" focusable="false">
      <polyline points={points} fill="none" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" style={{ stroke: tone }} />
    </svg>
  );
}

/**
 * Income and spending as paired bars per month with the net savings as a line over them. Numbers only size the drawing; every label is the
 * backend's own decimal string, the chart has a text description, and the same figures are listed beside it.
 */
export function CashFlowChart({ currency, labels, income, spending, net, summary }: { currency: string; labels: string[]; income: (string | undefined)[]; spending: (string | undefined)[]; net: (string | undefined)[]; summary: string }) {
  const [hover, setHover] = useState<number | null>(null);
  const [boxRef, W] = useWidth();
  const all = [...income, ...spending, ...net].map(numberOf);
  const max = Math.max(0, ...all);
  const min = Math.min(0, ...all);
  const span = max - min || 1;
  const y = (n: number) => PAD.top + ((max - n) / span) * (H - PAD.top - PAD.bottom);
  const group = (W - PAD.left - PAD.right) / Math.max(labels.length, 1);
  const barW = Math.max(6, Math.min(26, group * 0.32));
  const cx = (i: number) => PAD.left + group * i + group / 2;
  const every = Math.max(1, Math.ceil(56 / group));
  const topValue = [...income, ...spending, ...net].filter((v): v is string => v !== undefined && v !== "").sort((a, b) => numberOf(b) - numberOf(a))[0];
  const series: ChartSeries[] = [{ key: "income", label: "Income", values: income }, { key: "spending", label: "Spending", values: spending }, { key: "net", label: "Net saved", values: net }];
  const colors = ["var(--series-1)", "var(--series-4)", "var(--series-2)"];
  return (
    <figure className="chart">
      <Legend series={series} colors={colors} />
      <div className="chart-box" ref={boxRef}>
        <svg viewBox={`0 0 ${W} ${H}`} role="img" aria-label={summary} className="chart-svg" style={{ height: H }} onMouseLeave={() => setHover(null)}>
          {[0.25, 0.5, 0.75].map((f) => <line key={f} className="chart-grid" x1={PAD.left} x2={W - PAD.right} y1={PAD.top + f * (H - PAD.top - PAD.bottom)} y2={PAD.top + f * (H - PAD.top - PAD.bottom)} />)}
          <line x1={PAD.left} x2={W - PAD.right} y1={y(0)} y2={y(0)} stroke="currentColor" strokeOpacity="0.45" />
          {max > 0 && topValue !== undefined && <text x={PAD.left - 8} y={PAD.top + 8} textAnchor="end">{formatMoney(topValue, currency)}</text>}
          <text x={PAD.left - 8} y={y(0) + 4} textAnchor="end">0</text>
          {labels.map((label, i) => (
            <g key={`${label}-${i}`} onMouseEnter={() => setHover(i)}>
              <rect data-hit x={PAD.left + group * i} y={PAD.top} width={group} height={H - PAD.top - PAD.bottom} fill={hover === i ? "var(--accent-soft)" : "transparent"} />
              <rect data-bar x={cx(i) - barW - 1} y={Math.min(y(numberOf(income[i])), y(0))} width={barW} height={Math.max(Math.abs(y(0) - y(numberOf(income[i]))), numberOf(income[i]) === 0 ? 0 : 1)} rx={4} style={{ fill: colors[0] }}><title>{`${label}: Income ${income[i] ? formatMoney(income[i]!, currency) : "—"}`}</title></rect>
              <rect data-bar x={cx(i) + 1} y={Math.min(y(numberOf(spending[i])), y(0))} width={barW} height={Math.max(Math.abs(y(0) - y(numberOf(spending[i]))), numberOf(spending[i]) === 0 ? 0 : 1)} rx={4} style={{ fill: colors[1] }}><title>{`${label}: Spending ${spending[i] ? formatMoney(spending[i]!, currency) : "—"}`}</title></rect>
              {i % every === 0 && <text x={cx(i)} y={H - PAD.bottom + 18} textAnchor="middle">{label}</text>}
            </g>
          ))}
          {labels.length > 1 && <polyline fill="none" strokeWidth={2.5} strokeLinejoin="round" strokeLinecap="round" points={labels.map((_, i) => `${cx(i)},${y(numberOf(net[i]))}`).join(" ")} style={{ stroke: colors[2] }} />}
          {labels.map((label, i) => <circle key={`n-${label}`} data-point cx={cx(i)} cy={y(numberOf(net[i]))} r={hover === i ? 5 : 3.5} style={{ fill: colors[2] }}><title>{`${label}: Net saved ${net[i] ? formatMoney(net[i]!, currency) : "—"}`}</title></circle>)}
        </svg>
        {hover !== null && labels[hover] !== undefined && (
          <Tip leftPct={(cx(hover) / W) * 100} label={labels[hover]!} currency={currency} rows={series.map((s, i) => ({ key: s.key, label: s.label, value: s.values[hover], color: colors[i]! }))} />
        )}
      </div>
    </figure>
  );
}
