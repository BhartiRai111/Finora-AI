"use client";

import { AuthGate } from "@/components/AuthGate";
import { FinanceApp } from "@/components/finance/FinanceApp";

export default function FinancePage() {
  return (
    <AuthGate>
      <main>
        <h1>Personal finance</h1>
        <FinanceApp />
      </main>
    </AuthGate>
  );
}
