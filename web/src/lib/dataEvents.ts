// A tiny in-page signal: when a record is saved from anywhere (the "Add expense" dialog in the top bar, the calendar, a form),
// screens showing that data refetch it. Nothing is cached or shared; it only tells screens to ask the server again.
const EVENT = "finora:data-changed";

export function announceDataChanged(): void {
  if (typeof window !== "undefined") window.dispatchEvent(new CustomEvent(EVENT));
}

export function onDataChanged(listener: () => void): () => void {
  window.addEventListener(EVENT, listener);
  return () => window.removeEventListener(EVENT, listener);
}
