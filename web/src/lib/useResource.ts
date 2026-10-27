"use client";

import { useCallback, useEffect, useRef, useState } from "react";

export type ResourceState<T> =
  | { status: "loading" }
  | { status: "error"; error: unknown }
  | { status: "ready"; data: T };

/** Loads data with explicit loading and error states, cancelling stale requests. */
export function useResource<T>(load: (signal: AbortSignal) => Promise<T>): ResourceState<T> & { reload: () => void } {
  const [state, setState] = useState<ResourceState<T>>({ status: "loading" });
  const [tick, setTick] = useState(0);
  const loader = useRef(load);

  useEffect(() => {
    loader.current = load;
  });

  useEffect(() => {
    const controller = new AbortController();
    loader
      .current(controller.signal)
      .then((data) => {
        if (!controller.signal.aborted) setState({ status: "ready", data });
      })
      .catch((error: unknown) => {
        if (!controller.signal.aborted) setState({ status: "error", error });
      });
    return () => controller.abort();
  }, [tick]);

  const reload = useCallback(() => {
    // Keep showing current data while refreshing (forms and notices stay mounted); errors go back to loading.
    setState((previous) => (previous.status === "ready" ? previous : { status: "loading" }));
    setTick((n) => n + 1);
  }, []);
  return { ...state, reload };
}
