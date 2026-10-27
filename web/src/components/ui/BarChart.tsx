import { formatMoney } from "@/lib/finance";

export type ChartSeries = { key: string; label: string; values: (string | undefined)[] };

const COLORS = ["#2a6fdb", "#d9822b", "#1e8449", "#8e44ad"];
const W = 640;
const H = 220;
const PAD = { top: 12, right: 8, bottom: 44, left: 84 };

/**
 * A grouped bar chart of figures the backend already computed. Numbers are used only to size the bars; every label shows the
 * backend's own decimal string. It is a visual aid: the same figures are always in the table beside it, and the chart is described
 * to assistive technology by `summary`.
 */
export function BarChart({ title, currency, labels, series, summary }: { title: string; currency: string; labels: string[]; series: ChartSeries[]; summary: string }) {
  const nums = series.flatMap((s) => s.values.map((v) => (v === undefined || v === "" ? 0 : Number(v)))).filter((n) => Number.isFinite(n));
  const max = Math.max(0, ...nums);
  const min = Math.min(0, ...nums);
  const span = max - min || 1;
  const y = (n: number) => PAD.top + ((max - n) / span) * (H - PAD.top - PAD.bottom);
  const zero = y(0);
  const groupW = (W - PAD.left - PAD.right) / Math.max(labels.length, 1);
  const barW = Math.max(4, Math.min(28, (groupW * 0.8) / series.length));
  const exact = (pick: "max" | "min") => {
    let best: string | undefined;
    let bestN = pick === "max" ? -Infinity : Infinity;
    for (const s of series) for (const v of s.values) {
      if (v === undefined || v === "") continue;
      const n = Number(v);
      if (Number.isFinite(n) && (pick === "max" ? n > bestN : n < bestN)) { bestN = n; best = v; }
    }
    return best;
  };
  const top = exact("max");
  const bottom = exact("min");

  return (
    <figure className="chart">
      <figcaption><strong>{title}</strong> <span className="muted">({currency}; the table below has every figure)</span></figcaption>
      <ul className="legend" aria-label="Legend">
        {series.map((s, i) => <li key={s.key}><span className="swatch" style={{ background: COLORS[i % COLORS.length] }} aria-hidden="true" /> {s.label}</li>)}
      </ul>
      <svg viewBox={`0 0 ${W} ${H}`} role="img" aria-label={summary} className="chart-svg">
        <line x1={PAD.left} x2={W - PAD.right} y1={zero} y2={zero} stroke="currentColor" strokeOpacity="0.5" />
        {max > 0 && top !== undefined && <text x={PAD.left - 6} y={PAD.top + 8} textAnchor="end" fontSize="11" fill="currentColor">{formatMoney(top, currency)}</text>}
        <text x={PAD.left - 6} y={zero + 4} textAnchor="end" fontSize="11" fill="currentColor">0</text>
        {min < 0 && bottom !== undefined && <text x={PAD.left - 6} y={H - PAD.bottom} textAnchor="end" fontSize="11" fill="currentColor">{formatMoney(bottom, currency)}</text>}
        {labels.map((label, gi) => {
          const x0 = PAD.left + gi * groupW + (groupW - barW * series.length) / 2;
          return (
            <g key={`${label}-${gi}`}>
              {series.map((s, si) => {
                const raw = s.values[gi];
                const n = raw === undefined || raw === "" ? 0 : Number(raw);
                if (!Number.isFinite(n)) return null;
                const yy = y(n);
                return (
                  <rect key={s.key} x={x0 + si * barW} y={Math.min(yy, zero)} width={barW - 2} height={Math.max(Math.abs(zero - yy), n === 0 ? 0 : 1)} fill={COLORS[si % COLORS.length]}>
                    <title>{`${label}: ${s.label} ${raw === undefined || raw === "" ? "—" : formatMoney(raw, currency)}`}</title>
                  </rect>
                );
              })}
              <text x={PAD.left + gi * groupW + groupW / 2} y={H - PAD.bottom + 16} textAnchor="middle" fontSize="11" fill="currentColor">{label.length > 12 ? `${label.slice(0, 11)}…` : label}</text>
            </g>
          );
        })}
      </svg>
    </figure>
  );
}
