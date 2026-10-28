// Exact decimal arithmetic on money strings for DISPLAY aggregation only (sums of figures the server already returned, and ratios
// used to size bars). Amounts stay strings; nothing here is a JavaScript floating-point number, so no rounding error can enter a sum.

type Parsed = { negative: boolean; units: bigint; scale: number };

function parse(value: string): Parsed | null {
  const m = /^(-?)(\d+)(?:\.(\d+))?$/.exec(value.trim());
  if (!m) return null;
  const fraction = m[3] ?? "";
  return { negative: m[1] === "-", units: BigInt(`${m[2]}${fraction}`), scale: fraction.length };
}

function toScale(p: Parsed, scale: number): bigint {
  const signed = p.negative ? -p.units : p.units;
  return signed * 10n ** BigInt(scale - p.scale);
}

function format(units: bigint, scale: number): string {
  const negative = units < 0n;
  const digits = (negative ? -units : units).toString().padStart(scale + 1, "0");
  const whole = digits.slice(0, digits.length - scale);
  const fraction = scale > 0 ? `.${digits.slice(-scale)}` : "";
  return `${negative ? "-" : ""}${whole}${fraction}`;
}

/** Sum of decimal strings, exact, at the finest scale among the inputs. Unparseable inputs are skipped. */
export function sumAmounts(values: string[]): string {
  const parsed = values.map(parse).filter((p): p is Parsed => p !== null);
  if (parsed.length === 0) return "0";
  const scale = Math.max(...parsed.map((p) => p.scale));
  return format(parsed.reduce((acc, p) => acc + toScale(p, scale), 0n), scale);
}

/** a - b, exact. */
export function subtractAmounts(a: string, b: string): string {
  const pa = parse(a);
  const pb = parse(b);
  if (!pa || !pb) return "0";
  const scale = Math.max(pa.scale, pb.scale);
  return format(toScale(pa, scale) - toScale(pb, scale), scale);
}

/** -1, 0 or 1; unparseable values compare as zero. */
export function compareAmounts(a: string, b: string): number {
  const pa = parse(a) ?? { negative: false, units: 0n, scale: 0 };
  const pb = parse(b) ?? { negative: false, units: 0n, scale: 0 };
  const scale = Math.max(pa.scale, pb.scale);
  const x = toScale(pa, scale);
  const y = toScale(pb, scale);
  return x < y ? -1 : x > y ? 1 : 0;
}

export const isZero = (a: string): boolean => compareAmounts(a, "0") === 0;
export const isNegative = (a: string): boolean => compareAmounts(a, "0") < 0;

/** part / whole as a whole-number percentage (0-100+, rounded half up), or null when whole is zero. For labels and bar sizes only. */
export function percentOf(part: string, whole: string): number | null {
  const p = parse(part);
  const w = parse(whole);
  if (!p || !w) return null;
  const scale = Math.max(p.scale, w.scale);
  const x = toScale(p, scale);
  const y = toScale(w, scale);
  if (y === 0n) return null;
  const sign = (x < 0n) !== (y < 0n) ? -1n : 1n;
  const ax = x < 0n ? -x : x;
  const ay = y < 0n ? -y : y;
  return Number(sign * ((ax * 200n + ay) / (ay * 2n)));
}
