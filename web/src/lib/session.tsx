"use client";

import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from "react";
import { api, ApiError, setCsrfToken } from "./api";
import type { User } from "./types";

type SessionState =
  | { status: "loading" }
  | { status: "anonymous" }
  | { status: "mfa" }
  | { status: "error" }
  | { status: "ready"; user: User };

type LoginResult = "ready" | "mfa";

type SessionContextValue = {
  state: SessionState;
  login: (email: string, password: string) => Promise<LoginResult>;
  verifyMfa: (input: { code?: string; recovery_code?: string }) => Promise<void>;
  logout: () => Promise<void>;
  refresh: () => Promise<void>;
};

const SessionContext = createContext<SessionContextValue | null>(null);

/** Asks the server who we are. Pure with respect to React state, so effects can use it safely. */
async function loadSession(): Promise<SessionState> {
  try {
    const user = await api<User>("/v1/me");
    if (user.csrf_token) setCsrfToken(user.csrf_token);
    return { status: "ready", user };
  } catch (error) {
    setCsrfToken(null);
    if (error instanceof ApiError && error.code === "MFA_REQUIRED") return { status: "mfa" };
    if (error instanceof ApiError && error.status === 401) return { status: "anonymous" };
    return { status: "error" };
  }
}

export function SessionProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState<SessionState>({ status: "loading" });

  const refresh = useCallback(async () => {
    setState(await loadSession());
  }, []);

  useEffect(() => {
    let cancelled = false;
    void loadSession().then((next) => {
      if (!cancelled) setState(next);
    });
    return () => {
      cancelled = true;
    };
  }, []);

  const login = useCallback(
    async (email: string, password: string): Promise<LoginResult> => {
      const result = await api<{ csrf_token: string; mfa_required: boolean }>("/v1/auth/login", {
        method: "POST",
        body: { email, password },
      });
      setCsrfToken(result.csrf_token);
      if (result.mfa_required) {
        setState({ status: "mfa" });
        return "mfa";
      }
      await refresh();
      return "ready";
    },
    [refresh],
  );

  const verifyMfa = useCallback(
    async (input: { code?: string; recovery_code?: string }) => {
      const result = await api<{ csrf_token: string }>("/v1/auth/mfa/verify", { method: "POST", body: input });
      setCsrfToken(result.csrf_token);
      await refresh();
    },
    [refresh],
  );

  const logout = useCallback(async () => {
    try {
      await api<void>("/v1/auth/logout", { method: "POST" });
    } finally {
      setCsrfToken(null);
      setState({ status: "anonymous" });
    }
  }, []);

  const value = useMemo(() => ({ state, login, verifyMfa, logout, refresh }), [state, login, verifyMfa, logout, refresh]);
  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>;
}

export function useSession(): SessionContextValue {
  const value = useContext(SessionContext);
  if (!value) throw new Error("useSession must be used inside SessionProvider");
  return value;
}
