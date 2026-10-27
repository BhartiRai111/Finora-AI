import type { ExpenseCategory, Paged, Party, Project } from "@/lib/business";

/** Small reference lists used by select boxes (the first 100 of each, active only). */
export type Lookups = { clients: Party[]; vendors: Party[]; projects: Project[]; categories: ExpenseCategory[] };

export const NO_LOOKUPS: Lookups = { clients: [], vendors: [], projects: [], categories: [] };

export async function loadLookups(
  get: <T>(path: string, signal?: AbortSignal) => Promise<T>,
  signal: AbortSignal,
  access: { clients: boolean; expenses: boolean },
): Promise<Lookups> {
  const [clients, vendors, projects, categories] = await Promise.all([
    access.clients ? get<Paged<Party>>("/clients?limit=100", signal) : null,
    access.clients ? get<Paged<Party>>("/vendors?limit=100", signal) : null,
    access.clients ? get<Paged<Project>>("/projects?limit=100", signal) : null,
    access.expenses ? get<{ items: ExpenseCategory[] }>("/expense-categories", signal) : null,
  ]);
  return { clients: clients?.items ?? [], vendors: vendors?.items ?? [], projects: projects?.items ?? [], categories: categories?.items ?? [] };
}
