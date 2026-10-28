import type { IconName } from "@/components/ui/Icon";
import { businessAccess, insightsAccess } from "./business";
import { canViewMembers } from "./permissions";
import type { Role, Workspace } from "./types";

// The route map of the application. Personal finance lives under /finance/<section>; a business workspace under
// /workspaces/<id>/<section>. Visibility here is a UI hint only: the server authorizes every request.

export type Section = { id: string; label: string; title: string; description: string; group: string; icon: IconName };

export const PERSONAL_SECTIONS: Section[] = [
  { id: "overview", label: "Overview", group: "Main", icon: "dashboard", title: "Overview", description: "Your balances, this month's income and spending, budgets, upcoming payments and goals in one place." },
  { id: "calendar", label: "Calendar", group: "Main", icon: "calendar", title: "Calendar and planner", description: "See what you spent on each day, upcoming bills and recurring payments, and add an expense to any date." },
  { id: "transactions", label: "Transactions", group: "Main", icon: "list", title: "Transactions", description: "Income, expenses, transfers and refunds. Search, filter and correct them here." },
  { id: "budgets", label: "Budgets", group: "Main", icon: "wallet", title: "Budget planner", description: "Monthly limits per category and how much of each you have used." },
  { id: "reports", label: "Analytics & Reports", group: "Main", icon: "chart", title: "Analytics and reports", description: "Cash flow, budget versus actual and net worth, with CSV export and a statistical forecast." },
  { id: "goals", label: "Savings goals", group: "Main", icon: "target", title: "Savings goals", description: "Savings goals and your progress towards them." },
  { id: "recurring", label: "Recurring payments", group: "Main", icon: "repeat", title: "Recurring payments", description: "Rules that create transactions on a schedule." },
  { id: "assistant", label: "AI Insights", group: "Main", icon: "sparkle", title: "AI insights", description: "Ask about your own figures. Answers come from your recorded data and are read-only." },
  { id: "accounts", label: "Accounts", group: "Money", icon: "bank", title: "Accounts", description: "Bank, cash, card and loan accounts. Balances are calculated from your recorded transactions." },
  { id: "bills", label: "Bills", group: "Money", icon: "bill", title: "Bills", description: "Payments you owe, with due dates and payment history." },
  { id: "subscriptions", label: "Subscriptions", group: "Money", icon: "card", title: "Your subscriptions", description: "Services you pay for regularly, with their yearly cost." },
  { id: "categories", label: "Categories", group: "Money", icon: "tag", title: "Categories", description: "The categories used to group your income and spending." },
  { id: "imports", label: "Imports", group: "Data", icon: "upload", title: "Imports", description: "Bring in transactions from a CSV file: preview, map columns, review and confirm." },
  { id: "documents", label: "Documents", group: "Data", icon: "file", title: "Documents", description: "Upload receipts and bills and link them to your records." },
  { id: "reconcile", label: "Reconcile", group: "Data", icon: "link", title: "Reconciliation", description: "Review suggested matches between statement lines and your transactions. Nothing is matched without your confirmation." },
  { id: "regional", label: "Currency & region", group: "Settings", icon: "globe", title: "Currency and regional settings", description: "Display currency, formats, fiscal year and your own exchange rates." },
  { id: "notifications", label: "Notifications", group: "Settings", icon: "bell", title: "Notifications", description: "Messages from Finora about your imports, alerts and plan. They appear in the app only." },
  { id: "alerts", label: "Alerts", group: "Settings", icon: "alert", title: "Financial alerts", description: "Rule-based alerts about budgets, spending and cash, checked when you ask." },
  { id: "plan", label: "Plan & billing", group: "Settings", icon: "card", title: "Plan and billing", description: "Your plan, what it includes and billing records." },
  { id: "integrations", label: "Integrations", group: "Settings", icon: "plug", title: "Integrations", description: "The real state of every external service Finora can use." },
];

export const BUSINESS_SECTIONS: Section[] = [
  { id: "overview", icon: "dashboard", label: "Summary", group: "Finance", title: "Business summary", description: "Invoiced, received, outstanding and overdue amounts, and project profitability at a glance." },
  { id: "invoices", icon: "bill", label: "Invoices", group: "Finance", title: "Invoices", description: "Create, issue and track invoices. Statuses are those recorded by the server." },
  { id: "payments", icon: "card", label: "Payments", group: "Finance", title: "Payments", description: "Payments received and how they are allocated to invoices." },
  { id: "expenses", icon: "wallet", label: "Expenses", group: "Finance", title: "Expenses", description: "Business expenses with vendors, categories and projects." },
  { id: "clients", icon: "users", label: "Clients", group: "Contacts", title: "Clients", description: "The people and companies you invoice." },
  { id: "vendors", icon: "briefcase", label: "Vendors", group: "Contacts", title: "Vendors", description: "The suppliers you buy from." },
  { id: "projects", icon: "target", label: "Projects", group: "Contacts", title: "Projects", description: "Projects with budgets, costs and profitability." },
  { id: "reports", icon: "chart", label: "Reports", group: "Insights", title: "Reports and forecast", description: "Business performance, receivables aging and project profitability, with CSV export." },
  { id: "assistant", icon: "sparkle", label: "Assistant", group: "Insights", title: "Finance assistant", description: "Ask about your business figures. Answers come from recorded data and are read-only." },
  { id: "imports", icon: "upload", label: "Imports", group: "Data", title: "Imports", description: "Bring in expenses from a CSV file: preview, map columns, review and confirm." },
  { id: "documents", icon: "file", label: "Documents", group: "Data", title: "Documents", description: "Upload vendor bills and receipts and link them to records." },
  { id: "reconcile", icon: "link", label: "Reconcile", group: "Data", title: "Reconciliation", description: "Review suggested matches. Nothing is matched without confirmation." },
  { id: "regional", icon: "globe", label: "Currency & region", group: "Tax & region", title: "Currency and regional settings", description: "Display currency, formats, fiscal year and manually entered exchange rates." },
  { id: "gst", icon: "bank", label: "GST", group: "Tax & region", title: "GST preparation", description: "GST details and a preparation worksheet for a limited set of supply types. This is not an official return and files nothing." },
  { id: "notifications", icon: "bell", label: "Notifications", group: "Platform", title: "Notifications", description: "Messages about imports, alerts, integrations and your plan. They appear in the app only." },
  { id: "alerts", icon: "alert", label: "Alerts", group: "Platform", title: "Financial alerts", description: "Rule-based alerts about receivables, imports and integrations, checked when you ask." },
  { id: "integrations", icon: "plug", label: "Integrations", group: "Platform", title: "Integrations", description: "The real state of every external service and the accounting integration." },
  { id: "plan", icon: "card", label: "Plan & billing", group: "Platform", title: "Plan and billing", description: "Your workspace plan, what it includes and billing records." },
  { id: "admin", icon: "shield", label: "Administration", group: "Platform", title: "Workspace administration", description: "An overview of this workspace only. It is not platform-wide administration." },
  { id: "settings", icon: "settings", label: "Business settings", group: "Workspace", title: "Business settings", description: "Company name, base currency, time zone and invoice numbering." },
  { id: "members", icon: "users", label: "Members", group: "Workspace", title: "Members", description: "Who belongs to this workspace and their roles." },
  { id: "invitations", icon: "mail", label: "Invitations", group: "Workspace", title: "Invitations", description: "Invite people to this workspace." },
  { id: "workspace", icon: "briefcase", label: "Workspace", group: "Workspace", title: "Workspace", description: "Rename this workspace or leave it." },
];

/** Section ids served by the business finance screens (the rest are workspace administration of members and the workspace itself). */
export const WORKSPACE_ADMIN_SECTIONS = ["members", "invitations", "workspace"] as const;

/** Whether a role is expected to see a business section. A hint for navigation and for the denied screen; the server decides for real. */
export function businessSectionVisible(role: Role, id: string): boolean {
  const full = businessAccess.read(role);
  switch (id) {
    case "overview": case "invoices": case "payments": case "clients": case "vendors": case "projects": case "settings": case "integrations": return full;
    case "expenses": return businessAccess.expensesRead(role);
    case "imports": case "reconcile": return insightsAccess.importsRead(role);
    case "documents": return insightsAccess.documentsRead(role);
    case "reports": case "assistant": case "regional": case "gst": case "notifications": case "alerts": case "plan": return insightsAccess.reports(role);
    case "admin": return businessAccess.settingsWrite(role);
    case "members": case "invitations": return canViewMembers(role);
    case "workspace": return true;
    default: return false;
  }
}

export function businessSections(workspace: Workspace): Section[] {
  return BUSINESS_SECTIONS.filter((s) => businessSectionVisible(workspace.role, s.id));
}

export type NavGroup = { label: string; items: { id: string; label: string; href: string; icon: IconName }[] };

/** Groups sections for the sidebar, keeping the order in which they are declared. */
export function toNav(sections: Section[], hrefFor: (id: string) => string): NavGroup[] {
  const groups: NavGroup[] = [];
  for (const s of sections) {
    let g = groups.find((x) => x.label === s.group);
    if (!g) groups.push((g = { label: s.group, items: [] }));
    g.items.push({ id: s.id, label: s.label, href: hrefFor(s.id), icon: s.icon });
  }
  return groups;
}
