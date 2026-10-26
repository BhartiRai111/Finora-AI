"use client";

import { useCallback, useState, type ReactNode } from "react";
import { ApiError } from "@/lib/api";
import { useSession } from "@/lib/session";
import { StepUpForm } from "./StepUpForm";

/**
 * Runs an action; if the server asks for recent re-authentication, shows the confirmation form and then re-runs it once.
 * Returns the form to render and a runner.
 */
export function useStepUp(): { run: <T>(action: () => Promise<T>) => Promise<T | undefined>; form: ReactNode } {
  const { state } = useSession();
  const mfaEnabled = state.status === "ready" && state.user.mfa_enabled === true;
  const [pending, setPending] = useState<{ retry: () => void; cancel: () => void } | null>(null);

  const run = useCallback(
    async <T,>(action: () => Promise<T>): Promise<T | undefined> => {
      try {
        return await action();
      } catch (error) {
        if (!(error instanceof ApiError) || error.code !== "STEP_UP_REQUIRED") throw error;
        return new Promise<T | undefined>((resolve, reject) => {
          setPending({
            retry: () => {
              setPending(null);
              action().then(resolve, reject);
            },
            cancel: () => {
              setPending(null);
              resolve(undefined);
            },
          });
        });
      }
    },
    [],
  );

  const form = pending ? <StepUpForm mfaEnabled={mfaEnabled} onDone={pending.retry} onCancel={pending.cancel} /> : null;
  return { run, form };
}
