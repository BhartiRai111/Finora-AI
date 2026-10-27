package com.finora.finance.documents;

import java.util.Map;

/**
 * Optical character recognition behind a small interface. No OCR engine is approved or configured for Finora, so the default
 * implementation reports "not configured" and NEVER invents values. The result is a set of CANDIDATE values, treated as untrusted input:
 * they are only shown to the user, who reviews and confirms (or corrects) them before anything becomes a financial record.
 */
public interface OcrProvider {

    boolean configured();

    String name();

    /** @throws OcrException when the document cannot be read; the message is safe to show */
    Result extract(byte[] content, String contentType) throws OcrException;

    /** Candidate fields: vendor_name, document_date, total_amount, currency, reference, description. Values are plain text. */
    record Result(Map<String, String> fields) {
    }

    class OcrException extends Exception {
        private static final long serialVersionUID = 1L;

        public OcrException(String message) {
            super(message);
        }
    }
}
