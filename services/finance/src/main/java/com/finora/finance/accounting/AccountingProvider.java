package com.finora.finance.accounting;

/**
 * The seam for a future accounting integration. No provider is approved, so there is no implementation beyond "none": the status endpoint says so
 * and no connect, webhook or sync route exists. When the owner approves a provider (with its scopes and API contract), an adapter implements this
 * interface and the token vault, webhook verifier, duplicate-event store and sync ledger in this package are already there to be used.
 */
public interface AccountingProvider {

    /** A stable lower-case key, e.g. the provider's name once one is approved; "none" when nothing is configured. */
    String key();

    boolean configured();
}
