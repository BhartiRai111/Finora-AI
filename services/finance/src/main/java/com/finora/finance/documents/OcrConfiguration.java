package com.finora.finance.documents;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Chooses the OCR provider from {@code finora.finance.ocr.provider}: {@code none} (default, truthfully unavailable) or {@code test}
 * (a deterministic adapter for development and tests only, which reads a plain-text marker; it is not OCR). No real engine is wired in.
 */
@Configuration
public class OcrConfiguration {

    @Bean
    OcrProvider ocrProvider(@Value("${finora.finance.ocr.provider:none}") String provider) {
        return switch (provider.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "none", "" -> new NotConfigured();
            case "test" -> new TestAdapter();
            default -> throw new IllegalStateException("Unknown finora.finance.ocr.provider '" + provider + "'; use none or test");
        };
    }

    static final class NotConfigured implements OcrProvider {
        @Override
        public boolean configured() {
            return false;
        }

        @Override
        public String name() {
            return "none";
        }

        @Override
        public Result extract(byte[] content, String contentType) throws OcrException {
            throw new OcrException("No OCR provider is configured");
        }
    }

    /**
     * Test adapter: finds "OCRTEXT:" in the bytes and reads key=value lines after it. It exists so the review-and-confirm flow can be tested;
     * it does not recognise text in images.
     */
    static final class TestAdapter implements OcrProvider {
        @Override
        public boolean configured() {
            return true;
        }

        @Override
        public String name() {
            return "test";
        }

        @Override
        public Result extract(byte[] content, String contentType) throws OcrException {
            String text = new String(content, StandardCharsets.ISO_8859_1);
            int at = text.indexOf("OCRTEXT:");
            if (at < 0) {
                throw new OcrException("No text could be read from this document");
            }
            Map<String, String> fields = new LinkedHashMap<>();
            for (String line : text.substring(at + 8).split("\\R")) {
                if (line.startsWith("--")) {
                    break;
                }
                int eq = line.indexOf('=');
                if (eq > 0) {
                    fields.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
                }
            }
            if (fields.containsKey("FAIL")) {
                throw new OcrException("The recognition engine could not process this document");
            }
            return new Result(fields);
        }
    }
}
