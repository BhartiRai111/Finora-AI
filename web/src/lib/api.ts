// Browser API client. The browser only ever talks to its own origin (/api/gw/*, proxied to the gateway by Next.js), so the
// session cookie stays HttpOnly and same-origin, and no secret is ever held by this code. The CSRF token is the only
// credential-like value kept here, in memory, and is sent on state-changing requests.

export class ApiError extends Error {
  constructor(
    public readonly status: number,
    public readonly code: string,
    public readonly detail?: string,
    public readonly retryAfterSeconds?: number,
  ) {
    super(code);
    this.name = "ApiError";
  }
}

const BASE = "/api/gw";
let csrfToken: string | null = null;

export function setCsrfToken(token: string | null): void {
  csrfToken = token;
}

type RequestOptions = { method?: "GET" | "POST" | "PATCH" | "DELETE"; body?: unknown; signal?: AbortSignal };

export async function api<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const method = options.method ?? "GET";
  const headers: Record<string, string> = { Accept: "application/json" };
  if (options.body !== undefined) headers["Content-Type"] = "application/json";
  if (method !== "GET" && csrfToken) headers["X-CSRF-Token"] = csrfToken;

  let response: Response;
  try {
    response = await fetch(`${BASE}${path}`, {
      method,
      headers,
      body: options.body === undefined ? undefined : JSON.stringify(options.body),
      credentials: "same-origin",
      cache: "no-store",
      signal: options.signal,
    });
  } catch (error) {
    if (error instanceof DOMException && error.name === "AbortError") throw error;
    throw new ApiError(0, "NETWORK", "Cannot reach the server");
  }
  if (response.status === 204) return undefined as T;

  const text = await response.text();
  let data: unknown = null;
  if (text) {
    try {
      data = JSON.parse(text);
    } catch {
      data = null;
    }
  }
  if (!response.ok) {
    const problem = (data ?? {}) as { code?: string; detail?: string; retry_after_seconds?: number };
    throw new ApiError(response.status, problem.code ?? "ERROR", problem.detail, problem.retry_after_seconds);
  }
  return data as T;
}

/** A message that is safe and useful to show. It never repeats server internals. */
export function describeError(error: unknown): string {
  if (!(error instanceof ApiError)) return "Something went wrong. Please try again.";
  switch (error.code) {
    case "NETWORK":
      return "Cannot reach the server. Check your connection and try again.";
    case "INVALID_CREDENTIALS":
      return "That did not work. Check your details and try again.";
    case "RATE_LIMITED":
      return error.retryAfterSeconds
        ? `Too many attempts. Try again in ${error.retryAfterSeconds} seconds.`
        : "Too many attempts. Please wait and try again.";
    case "AUTH_REQUIRED":
      return "Your session has ended. Please sign in again.";
    case "CSRF_INVALID":
      return "Your page is out of date. Reload and try again.";
    case "MFA_REQUIRED":
      return "Multi-factor authentication is required for this action.";
    case "STEP_UP_REQUIRED":
      return "Please confirm your identity again to continue.";
    case "FORBIDDEN":
      return "You do not have permission to do that.";
    case "NOT_FOUND":
      return "That could not be found, or you do not have access to it.";
    case "VALIDATION_FAILED":
    case "CONFLICT":
    case "BAD_REQUEST":
      return error.detail ?? "The request could not be completed.";
    case "FEATURE_UNAVAILABLE":
      return error.detail ?? "This feature is not available on this server.";
    default:
      return error.status >= 500
        ? "The service is temporarily unavailable. Please try again shortly."
        : "The request could not be completed.";
  }
}
