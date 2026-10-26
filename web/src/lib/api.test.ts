import { afterEach, describe, expect, it, vi } from "vitest";
import { api, ApiError, describeError, setCsrfToken } from "./api";

afterEach(() => {
  vi.unstubAllGlobals();
  setCsrfToken(null);
});

function stub(status: number, body?: unknown) {
  const fn = vi.fn(async () => new Response(status === 204 ? null : JSON.stringify(body ?? {}), { status }));
  vi.stubGlobal("fetch", fn);
  return fn;
}

describe("api client", () => {
  it("sends the CSRF token on state-changing requests only", async () => {
    setCsrfToken("tok");
    const fn = stub(200, { ok: true });
    await api("/v1/x", { method: "POST", body: { a: 1 } });
    await api("/v1/x");
    const [post, get] = fn.mock.calls.map((c) => (c as unknown as [string, RequestInit])[1].headers as Record<string, string>);
    expect(post?.["X-CSRF-Token"]).toBe("tok");
    expect(get?.["X-CSRF-Token"]).toBeUndefined();
  });

  it("talks only to its own origin and never sends credentials elsewhere", async () => {
    const fn = stub(200, {});
    await api("/v1/me");
    const [url, init] = fn.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toBe("/api/gw/v1/me");
    expect(init.credentials).toBe("same-origin");
    expect(init.cache).toBe("no-store");
  });

  it("maps problem documents to ApiError without exposing internals", async () => {
    stub(403, { code: "FORBIDDEN", detail: "secret detail", request_id: "x" });
    await expect(api("/v1/x")).rejects.toMatchObject({ status: 403, code: "FORBIDDEN" });
    expect(describeError(new ApiError(403, "FORBIDDEN", "secret detail"))).toBe("You do not have permission to do that.");
  });

  it("returns undefined for 204 and reports network failure distinctly", async () => {
    stub(204);
    await expect(api("/v1/x", { method: "DELETE" })).resolves.toBeUndefined();
    vi.stubGlobal("fetch", vi.fn(async () => { throw new TypeError("failed"); }));
    await expect(api("/v1/x")).rejects.toMatchObject({ code: "NETWORK" });
  });

  it("survives a non-JSON error body", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => new Response("<html>bad gateway</html>", { status: 502 })));
    const error = await api("/v1/x").catch((e: unknown) => e);
    expect(error).toBeInstanceOf(ApiError);
    expect(describeError(error)).toMatch(/temporarily unavailable/);
  });

  it("explains rate limits with the wait time and never echoes unknown errors", () => {
    expect(describeError(new ApiError(429, "RATE_LIMITED", undefined, 30))).toContain("30 seconds");
    expect(describeError(new Error("stack trace here"))).toBe("Something went wrong. Please try again.");
  });
});
