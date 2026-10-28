"use client";

import { useEffect, useRef } from "react";
import { onDataChanged } from "./dataEvents";

/** Calls `reload` whenever any screen announces that data was saved, so what is on screen is read again from the server. */
export function useDataRefresh(reload: () => void): void {
  const ref = useRef(reload);
  useEffect(() => {
    ref.current = reload;
  });
  useEffect(() => onDataChanged(() => ref.current()), []);
}
