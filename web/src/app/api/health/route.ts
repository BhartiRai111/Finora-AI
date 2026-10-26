// Liveness for the web application. It reports only that the process can serve requests and
// intentionally exposes no versions, configuration or dependency details.
export const dynamic = "force-dynamic";

export function GET() {
  return Response.json({ status: "UP" }, { headers: { "Cache-Control": "no-store" } });
}
