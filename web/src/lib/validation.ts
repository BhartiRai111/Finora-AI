// Client-side checks are for quick feedback only; the server validates everything again.

export function validateRegistration(input: { name: string; email: string; password: string }): string | null {
  if (!input.name.trim()) return "Enter your name.";
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]{2,}$/.test(input.email.trim())) return "Enter a valid email address.";
  if (input.password.length < 12) return "Choose a password of at least 12 characters.";
  return null;
}

export function validateWorkspaceName(name: string): string | null {
  const trimmed = name.trim();
  if (!trimmed) return "Enter a workspace name.";
  if (trimmed.length > 100) return "Use 100 characters or fewer.";
  return null;
}

export function validateInvite(input: { email: string }): string | null {
  return /^[^\s@]+@[^\s@]+\.[^\s@]{2,}$/.test(input.email.trim()) ? null : "Enter a valid email address.";
}
