"use client";

import Link from "next/link";
import { useSession } from "@/lib/session";
import { WorkspaceSwitcher } from "./WorkspaceSwitcher";

const initials = (name: string): string => name.split(/\s+/).filter(Boolean).slice(0, 2).map((p) => p[0]!.toUpperCase()).join("") || "?";

/** Who is signed in (name and email from the session, nothing invented; it opens Account and security) and the workspace switcher. */
export function SidebarProfile() {
  const { state } = useSession();
  if (state.status !== "ready") return null;
  const name = state.user.display_name || state.user.email;
  return (
    <>
      <Link href="/account" className="profile-card" title="Account and security" aria-label={`${name}, account and security`}>
        <span className="avatar" aria-hidden="true">{initials(name)}</span>
        <span className="profile-text"><strong>{name}</strong><span>{state.user.email}</span></span>
      </Link>
      <WorkspaceSwitcher />
    </>
  );
}
