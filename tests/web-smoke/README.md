# Browser smoke test (manual)

Drives the Next.js app in a real Chromium against the real gateway, identity-access, finance service and PostgreSQL. It is a manual check, not part of
`./mvnw verify` or `pnpm test` (those remain the automated gates); run it with `tests/web-smoke/run.sh` after building the jars and starting dev PostgreSQL
(see the comments at the top of the script for prerequisites).

* `personal.js`: anonymous deep link to a sign-in prompt that keeps the destination, register and sign in, shell navigation and current-page marking,
  document title, real data created through the API shown in a report with its chart and exact table figures, reload restoring the session, account menu with the
  keyboard, honest notification/integration/billing states, unknown section, small-screen menu, and a session that ends elsewhere.
* `business.js`: Owner MFA enrolment, business workspace creation, role-aware navigation, real invoice listing, the workspace-only administration overview,
  integrations never shown as connected, a Viewer invited and accepted, denied by URL and by the server (403), and a foreign workspace id answering 404.

Nothing here is mocked. Test accounts use `example.test` addresses and a throw-away database and keys.
