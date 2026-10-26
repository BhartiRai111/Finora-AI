import Link from "next/link";

export default function HomePage() {
  return (
    <main>
      <h1>Finora AI</h1>
      <p>AI expense and financial intelligence for personal and business finance.</p>
      <p>
        <Link href="/workspaces">Your workspaces</Link> · <Link href="/login">Sign in</Link> · <Link href="/register">Create account</Link>
      </p>
    </main>
  );
}
