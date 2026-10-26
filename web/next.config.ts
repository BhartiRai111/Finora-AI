import type { NextConfig } from "next";

// Baseline response headers. A Content-Security-Policy is deliberately not set here yet: it needs
// the real asset/script inventory and is tracked as security work (Step 09b control C-W3).
const securityHeaders = [
  { key: "X-Content-Type-Options", value: "nosniff" },
  { key: "Referrer-Policy", value: "strict-origin-when-cross-origin" },
  { key: "X-Frame-Options", value: "DENY" },
  { key: "Permissions-Policy", value: "camera=(), microphone=(), geolocation=()" },
];

const nextConfig: NextConfig = {
  poweredByHeader: false,
  reactStrictMode: true,
  // The browser talks only to its own origin; this proxies /api/gw/* to the gateway so the session cookie stays
  // same-origin and HttpOnly. The gateway address is server-side configuration, never exposed to the browser.
  async rewrites() {
    const gateway = process.env.GATEWAY_URL ?? "http://127.0.0.1:8080";
    return [{ source: "/api/gw/:path*", destination: `${gateway}/:path*` }];
  },
  async headers() {
    return [{ source: "/:path*", headers: securityHeaders }];
  },
};

export default nextConfig;
