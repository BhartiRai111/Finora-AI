import { render } from "@testing-library/react";
import type { ReactElement } from "react";
import { vi } from "vitest";
import { SessionProvider } from "@/lib/session";
import type { User } from "@/lib/types";

export type Handler = (init: { method: string; body: unknown; headers: Record<string, string> }) => { status: number; body?: unknown } | Promise<{ status: number; body?: unknown }>;

export const USER: User = {
  id: "11111111-1111-4111-8111-111111111111",
  email: "asha@example.test",
  display_name: "Asha",
  email_verified: false,
  created_at: "2026-01-01T00:00:00Z",
  mfa_enabled: true,
  csrf_token: "csrf-abc",
};

/** Replaces fetch with a table of `"METHOD /path"` handlers. Unlisted requests fail the test loudly. */
export function mockApi(routes: Record<string, Handler>) {
  const calls: { key: string; body: unknown; headers: Record<string, string> }[] = [];
  const fn = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input).replace("/api/gw", "");
    const method = init?.method ?? "GET";
    const key = `${method} ${url}`;
    const handler = routes[key];
    if (!handler) throw new Error(`Unexpected request: ${key}`);
    const body = init?.body ? JSON.parse(String(init.body)) : undefined;
    const headers = (init?.headers ?? {}) as Record<string, string>;
    calls.push({ key, body, headers });
    const result = await handler({ method, body, headers });
    const text = result.body === undefined ? "" : JSON.stringify(result.body);
    return new Response(result.status === 204 ? null : text, { status: result.status });
  });
  vi.stubGlobal("fetch", fn);
  return { calls, fn };
}

export const problem = (status: number, code: string, detail?: string) => ({ status, body: { code, detail, request_id: "r" } });

export function renderWithSession(ui: ReactElement) {
  return render(<SessionProvider>{ui}</SessionProvider>);
}
