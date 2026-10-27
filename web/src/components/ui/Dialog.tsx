"use client";

import { useEffect, useId, useRef, useState, type FormEvent, type ReactNode } from "react";

const FOCUSABLE = 'a[href], button:not([disabled]), textarea:not([disabled]), input:not([disabled]), select:not([disabled]), [tabindex]:not([tabindex="-1"])';

/**
 * An accessible modal dialog: labelled, focus moves in and stays inside, Escape or the backdrop closes it, and focus returns to
 * whatever opened it. It is rendered only while `open`, so closed dialogs cost nothing and are not in the accessibility tree.
 */
export function Dialog({ open, title, onClose, children }: { open: boolean; title: string; onClose: () => void; children: ReactNode }) {
  const titleId = useId();
  const box = useRef<HTMLDivElement>(null);
  const opener = useRef<Element | null>(null);

  useEffect(() => {
    if (!open) return;
    opener.current = document.activeElement;
    const node = box.current;
    const first = node?.querySelector<HTMLElement>("[data-autofocus]") ?? node?.querySelector<HTMLElement>(FOCUSABLE);
    first?.focus();
    return () => {
      if (opener.current instanceof HTMLElement) opener.current.focus();
    };
  }, [open]);

  if (!open) return null;

  function onKeyDown(event: React.KeyboardEvent) {
    if (event.key === "Escape") {
      event.stopPropagation();
      onClose();
      return;
    }
    if (event.key !== "Tab" || !box.current) return;
    const items = Array.from(box.current.querySelectorAll<HTMLElement>(FOCUSABLE));
    if (items.length === 0) return;
    const firstItem = items[0]!;
    const lastItem = items[items.length - 1]!;
    if (event.shiftKey && document.activeElement === firstItem) {
      event.preventDefault();
      lastItem.focus();
    } else if (!event.shiftKey && document.activeElement === lastItem) {
      event.preventDefault();
      firstItem.focus();
    }
  }

  return (
    <div className="dialog-backdrop" onMouseDown={(e) => { if (e.target === e.currentTarget) onClose(); }}>
      <div ref={box} role="dialog" aria-modal="true" aria-labelledby={titleId} className="dialog" onKeyDown={onKeyDown}>
        <h2 id={titleId}>{title}</h2>
        {children}
      </div>
    </div>
  );
}

/**
 * Confirmation with an optional (or required) reason, replacing browser prompts. `onConfirm` receives the trimmed reason; the
 * dialog stays open and shows the failure if it rejects, so a failed action is never presented as done.
 */
export function ReasonDialog({ open, title, description, label, required = false, confirmLabel, danger = false, onConfirm, onClose }: {
  open: boolean;
  title: string;
  description?: string;
  label: string;
  required?: boolean;
  confirmLabel: string;
  danger?: boolean;
  onConfirm: (reason: string) => Promise<void> | void;
  onClose: () => void;
}) {
  const [reason, setReason] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const fieldId = useId();

  function close() {
    if (busy) return;
    setReason("");
    setError(null);
    onClose();
  }

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (busy) return;
    const trimmed = reason.trim();
    if (required && !trimmed) return setError(`${label} is required.`);
    setBusy(true);
    setError(null);
    try {
      await onConfirm(trimmed);
      setReason("");
      onClose();
    } catch (e) {
      setError(e instanceof Error && e.message ? e.message : "That did not work. Please try again.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <Dialog open={open} title={title} onClose={close}>
      <form onSubmit={(e) => void submit(e)} noValidate>
        {description && <p>{description}</p>}
        <label htmlFor={fieldId}>{label}{required ? "" : " (optional)"}</label>
        <textarea id={fieldId} data-autofocus rows={3} maxLength={500} value={reason} onChange={(e) => setReason(e.target.value)} aria-invalid={error ? true : undefined} />
        {error && <p role="alert" className="notice notice-error">{error}</p>}
        <div className="row">
          <button type="submit" disabled={busy} className={danger ? "danger" : undefined}>{busy ? "Working…" : confirmLabel}</button>
          <button type="button" onClick={close} disabled={busy}>Cancel</button>
        </div>
      </form>
    </Dialog>
  );
}
