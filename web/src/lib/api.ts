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

type RequestOptions = {
  method?: "GET" | "POST" | "PATCH" | "PUT" | "DELETE";
  body?: unknown;
  signal?: AbortSignal;
  /** Makes a retried write safe: the server runs a request with this key at most once. */
  idempotencyKey?: string;
};

export async function api<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const method = options.method ?? "GET";
  const headers: Record<string, string> = { Accept: "application/json" };
  if (options.body !== undefined) headers["Content-Type"] = "application/json";
  if (method !== "GET" && csrfToken) headers["X-CSRF-Token"] = csrfToken;
  if (options.idempotencyKey) headers["Idempotency-Key"] = options.idempotencyKey;

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

async function failure(response: Response): Promise<ApiError> {
  let data: { code?: string; detail?: string; retry_after_seconds?: number } = {};
  try {
    data = (await response.json()) as typeof data;
  } catch {
    data = {};
  }
  return new ApiError(response.status, data.code ?? "ERROR", data.detail, data.retry_after_seconds);
}

/** Uploads a file as the raw request body (the browser-provided type is never relied on by the server). */
export async function apiUpload<T>(path: string, file: Blob, contentType: string, signal?: AbortSignal): Promise<T> {
  const headers: Record<string, string> = { Accept: "application/json", "Content-Type": contentType };
  if (csrfToken) headers["X-CSRF-Token"] = csrfToken;
  let response: Response;
  try {
    response = await fetch(`${BASE}${path}`, { method: "POST", headers, body: file, credentials: "same-origin", cache: "no-store", signal });
  } catch (error) {
    if (error instanceof DOMException && error.name === "AbortError") throw error;
    throw new ApiError(0, "NETWORK", "Cannot reach the server");
  }
  if (!response.ok) throw await failure(response);
  return (await response.json()) as T;
}

/** Fetches a file (CSV export, document) as a Blob together with the file name the server suggested. */
export async function apiDownload(path: string, signal?: AbortSignal): Promise<{ blob: Blob; filename: string }> {
  let response: Response;
  try {
    response = await fetch(`${BASE}${path}`, { method: "GET", credentials: "same-origin", cache: "no-store", signal });
  } catch (error) {
    if (error instanceof DOMException && error.name === "AbortError") throw error;
    throw new ApiError(0, "NETWORK", "Cannot reach the server");
  }
  if (!response.ok) throw await failure(response);
  const disposition = response.headers.get("Content-Disposition") ?? "";
  const match = /filename="([^"\\]+)"/.exec(disposition);
  return { blob: await response.blob(), filename: match?.[1] ?? "download" };
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
        : (error.detail ?? "Too many attempts. Please wait and try again.");
    case "AUTH_REQUIRED":
      return "Your session has ended. Please sign in again.";
    case "CSRF_INVALID":
      return "Your page is out of date. Reload and try again.";
    case "MFA_REQUIRED":
      return "Multi-factor authentication is required for this action.";
    case "STEP_UP_REQUIRED":
      return "Please confirm your identity again to continue.";
    case "FORBIDDEN":
      // A plan or subscription refusal explains itself ("Your current plan ... does not include ..."); anything else stays generic.
      return error.detail?.startsWith("Your ") ? error.detail : "You do not have permission to do that.";
    case "NOT_FOUND":
      return "That could not be found, or you do not have access to it.";
    case "VALIDATION_FAILED":
    case "CONFLICT":
    case "BAD_REQUEST":
    case "ALLOCATION_EXCEEDS":
    case "ALLOCATION_EXISTS":
    case "INVOICE_IMMUTABLE":
    case "INVALID_STATE_TRANSITION":
    case "TOTAL_MISMATCH":
      return error.detail ?? "The request could not be completed.";
    case "IDEMPOTENCY_KEY_REUSED":
      return "That request was already used for something different. Please try again.";
    case "FEATURE_UNAVAILABLE":
      return error.detail ?? "This feature is not available on this server.";
    default:
      return error.status >= 500
        ? "The service is temporarily unavailable. Please try again shortly."
        : "The request could not be completed.";
  }
}
