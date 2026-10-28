"use client";

import { useState } from "react";
import { apiDownload, describeError } from "@/lib/api";
import { monthBounds } from "@/lib/calendar";
import { saveBlob } from "@/lib/insights";
import { useMonthContext } from "@/lib/month";
import { Icon } from "../ui/Icon";

/** Downloads the server's cash-flow CSV for the month being viewed (the same export as Analytics and reports). A refusal or failure is shown, never swallowed. */
export function ExportButton({ space }: { space: string }) {
  const ctx = useMonthContext();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  async function run() {
    if (busy) return;
    setBusy(true);
    setError(null);
    try {
      const { from, to } = monthBounds(ctx?.month ?? new Date().toISOString().slice(0, 7));
      const file = await apiDownload(`/v1/spaces/${space}/finance-reports/cash-flow/export?from=${from}&to=${to}&granularity=MONTH`);
      saveBlob(file.blob, file.filename);
    } catch (e) {
      setError(describeError(e));
    } finally {
      setBusy(false);
    }
  }
  return (
    <span className="export-wrap">
      <button type="button" onClick={() => void run()} disabled={busy}><Icon name="download" size={16} />{busy ? "Preparing…" : "Export"}</button>
      {error && <span role="alert" className="export-error">{error}</span>}
    </span>
  );
}
