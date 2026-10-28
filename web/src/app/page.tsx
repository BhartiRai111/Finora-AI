import Link from "next/link";

export default function HomePage() {
  return (
    <main id="main" className="hero">
      <h1>Finora AI</h1>
      <p style={{ fontSize: "1.1rem" }}>AI expense and financial intelligence for personal and business finance: a calendar of your spending, budgets you can trust, and answers drawn only from your own records.</p>
      <p className="row">
        <Link href="/workspaces" className="btn-primary" style={{ padding: "0.6rem 1.1rem", borderRadius: "var(--r-md)", color: "var(--on-accent)", textDecoration: "none", fontWeight: 600 }}>Your workspaces</Link>
        <Link href="/login">Sign in</Link>
        <Link href="/register">Create account</Link>
      </p>
    </main>
  );
}
