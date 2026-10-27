package com.finora.finance.assistant;

import java.time.Duration;

/**
 * The only door to a language model. Provider-neutral and replaceable: an adapter turns one {@link Request} into raw text and nothing else. It
 * gets no tools, no database access and no identity; it only sees the (already minimised) text the assistant hands it. Whatever it returns is
 * untrusted: the {@link AiGateway} validates it before any of it reaches a user, and no number in it is ever used as a figure.
 * No real provider is approved yet (decision DL-01); only {@code none} and the deterministic {@code test} adapter exist.
 */
public interface AiProvider {

    enum Task { PLAN, EXPLAIN }

    /** @param system fixed instructions written by this service; @param data the minimised, delimited data block (never raw user records) */
    record Request(Task task, String system, String data, int maxOutputChars, Duration timeout) {
    }

    String name();

    boolean configured();

    /** True for a provider that sends data outside this deployment. It may only be used once data-processing terms are approved. */
    boolean external();

    String complete(Request request) throws AiProviderException;
}
