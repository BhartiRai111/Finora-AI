"use client";

import { useRouter } from "next/navigation";
import { useState, type FormEvent } from "react";
import { Icon } from "../ui/Icon";

/** Searches transactions: it opens the Transactions screen with the words already in its search box (the screen asks the server). */
export function TransactionSearch() {
  const router = useRouter();
  const [q, setQ] = useState("");
  function submit(event: FormEvent) {
    event.preventDefault();
    const term = q.trim();
    router.push(term ? `/finance/transactions?q=${encodeURIComponent(term)}` : "/finance/transactions");
    setQ("");
  }
  return (
    <form role="search" aria-label="Search transactions" className="tx-search" onSubmit={submit}>
      <Icon name="search" size={16} />
      <label htmlFor="header-tx-search" className="visually-hidden">Search transactions</label>
      <input id="header-tx-search" type="search" placeholder="Search transactions" value={q} onChange={(e) => setQ(e.target.value)} maxLength={100} autoComplete="off" />
    </form>
  );
}
