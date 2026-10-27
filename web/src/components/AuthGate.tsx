"use client";

import Link from "next/link";
import type { ReactNode } from "react";
import { currentPath } from "@/lib/redirect";
import { useSession } from "@/lib/session";
import { MfaForm } from "./MfaForm";
import { Loading, Notice } from "./Notice";

/** Renders children only for a fully signed-in user. Every other state has an explicit, accessible screen. */
export function AuthGate({ children }: { children: ReactNode }) {
  const { state, refresh } = useSession();
  switch (state.status) {
    case "loading":
      return <Loading label="Checking your session…" />;
    case "anonymous":
      return (
        <main id="main">
          <h1>{state.expired ? "Your session has ended" : "Sign in required"}</h1>
          <p>
            {state.expired ? "For your security you were signed out. " : ""}
            Please <Link href={`/login?next=${encodeURIComponent(currentPath())}`}>sign in</Link>{state.expired ? " again to continue where you were." : <> or <Link href="/register">create an account</Link> to continue.</>}
          </p>
        </main>
      );
    case "mfa":
      return <MfaForm />;
    case "error":
      return (
        <section>
          <Notice>We could not check your session. The service may be unavailable.</Notice>
          <button type="button" onClick={() => void refresh()}>
            Try again
          </button>
        </section>
      );
    case "ready":
      return <>{children}</>;
  }
}
