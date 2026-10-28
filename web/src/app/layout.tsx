import type { Metadata, Viewport } from "next";
import type { ReactNode } from "react";
import { Header } from "@/components/Header";
import { SessionProvider } from "@/lib/session";
import { THEME_BOOT_SCRIPT } from "@/lib/theme";
import "./globals.css";

export const metadata: Metadata = {
  title: "Finora AI",
  description: "AI expense and financial intelligence for personal and business finance.",
};

// Mobile-first: respect device width and let the system choose the colour scheme.
export const viewport: Viewport = {
  width: "device-width",
  initialScale: 1,
  colorScheme: "light dark",
};

export default function RootLayout({ children }: Readonly<{ children: ReactNode }>) {
  return (
    <html lang="en" data-theme="dark" suppressHydrationWarning>
      <head>
        <script dangerouslySetInnerHTML={{ __html: THEME_BOOT_SCRIPT }} />
      </head>
      <body>
        <SessionProvider>
          <a href="#main" className="skip-link">Skip to main content</a>
          <Header />
          {children}
        </SessionProvider>
      </body>
    </html>
  );
}
