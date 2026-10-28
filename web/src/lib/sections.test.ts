import { describe, expect, it } from "vitest";
import { safeNext } from "./redirect";
import { BUSINESS_SECTIONS, PERSONAL_SECTIONS, businessSectionVisible, businessSections, toNav } from "./sections";
import type { Role, Workspace } from "./types";

const ws = (role: Role): Workspace => ({ id: "b1", name: "Acme", type: "BUSINESS", role, membership_id: "m" });
const ids = (role: Role) => businessSections(ws(role)).map((s) => s.id);

describe("route map", () => {
  it("has unique ids and a title and description for every section", () => {
    for (const list of [PERSONAL_SECTIONS, BUSINESS_SECTIONS]) {
      expect(new Set(list.map((s) => s.id)).size).toBe(list.length);
      for (const s of list) {
        expect(s.title.length).toBeGreaterThan(3);
        expect(s.description.length).toBeGreaterThan(10);
      }
    }
  });

  it("shows each role only what its permissions suggest (a hint; the server decides)", () => {
    expect(ids("OWNER")).toEqual(expect.arrayContaining(["invoices", "gst", "admin", "members", "invitations", "workspace", "plan"]));
    expect(ids("ADMIN")).toContain("admin");
    expect(ids("ACCOUNTANT")).toEqual(expect.arrayContaining(["imports", "reconcile", "documents", "reports"]));
    expect(ids("ACCOUNTANT")).not.toContain("admin");
    expect(ids("CONTRIBUTOR")).toEqual(expect.arrayContaining(["expenses", "documents"]));
    expect(ids("CONTRIBUTOR")).not.toContain("invoices");
    expect(ids("CONTRIBUTOR")).not.toContain("reports");
    expect(ids("VIEWER")).toEqual(["workspace"]);
    expect(ids("MANAGER")).toEqual(["workspace"]);
    expect(businessSectionVisible("AUDITOR", "members")).toBe(true);
    expect(businessSectionVisible("AUDITOR", "imports")).toBe(true);
    expect(businessSectionVisible("OWNER", "does-not-exist")).toBe(false);
  });

  it("groups navigation in declaration order with the given links", () => {
    const nav = toNav(PERSONAL_SECTIONS, (id) => `/finance/${id}`);
    expect(nav.map((g) => g.label)).toEqual(["Main", "Money", "Data", "Settings"]);
    expect(nav[0]!.items[0]).toEqual({ id: "overview", label: "Overview", href: "/finance/overview", icon: "dashboard" });
    expect(nav[0]!.items.map((i) => i.label)).toEqual(["Overview", "Calendar", "Transactions", "Budgets", "Analytics & Reports", "Savings goals", "Recurring payments", "AI Insights"]);
  });
});

describe("safeNext", () => {
  it("allows same-site paths only", () => {
    expect(safeNext("/finance/budgets")).toBe("/finance/budgets");
    expect(safeNext("/workspaces/abc/invoices?x=1")).toBe("/workspaces/abc/invoices?x=1");
    for (const bad of ["https://evil.example", "//evil.example", "/\\evil", "javascript:alert(1)", "evil", "", "/login", "/register?x=1", "/a\nb", null, undefined]) {
      expect(safeNext(bad as string | null)).toBeNull();
    }
    expect(safeNext("/" + "a".repeat(400))).toBeNull();
  });
});
