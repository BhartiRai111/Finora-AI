package com.finora.finance.assistant;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Chooses the AI provider from {@code finora.finance.ai.provider}: {@code none} (default; the assistant still answers from the deterministic
 * calculations and says plainly that no AI explanation is available) or {@code test} (a deterministic adapter for development and tests only; it
 * is not a language model). An unknown value stops the service at startup: a real provider is wired in only after the owner approves the
 * provider, the model and the data-processing terms (decisions DL-01 and AI-1..AI-5).
 */
@Configuration
public class AiConfiguration {

    @Bean
    AiProvider aiProvider(@Value("${finora.finance.ai.provider:none}") String provider) {
        return switch (provider.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "none", "" -> new NotConfigured();
            case "test" -> new TestAdapter();
            default -> throw new IllegalStateException("Unknown finora.finance.ai.provider '" + provider + "'; use none or test. Real providers are not approved yet");
        };
    }

    @Bean
    AiLimits aiLimits(@Value("${finora.finance.ai.timeout-ms:8000}") long timeoutMs, @Value("${finora.finance.ai.max-retries:1}") int maxRetries,
            @Value("${finora.finance.ai.questions-per-hour:60}") int questionsPerHour, @Value("${finora.finance.ai.provider-calls-per-day:200}") int providerCallsPerDay,
            @Value("${finora.finance.ai.max-output-chars:1500}") int maxOutputChars) {
        return new AiLimits(Duration.ofMillis(Math.max(100, timeoutMs)), Math.max(0, Math.min(3, maxRetries)), Math.max(1, questionsPerHour),
                Math.max(0, providerCallsPerDay), Math.max(200, Math.min(4000, maxOutputChars)));
    }

    public record AiLimits(Duration timeout, int maxRetries, int questionsPerHour, int providerCallsPerDay, int maxOutputChars) {
    }

    static final class NotConfigured implements AiProvider {
        @Override
        public String name() {
            return "none";
        }

        @Override
        public boolean configured() {
            return false;
        }

        @Override
        public boolean external() {
            return false;
        }

        @Override
        public String complete(Request request) throws AiProviderException {
            throw new AiProviderException(AiProviderException.Kind.UNAVAILABLE, "No AI provider is configured");
        }
    }

    /**
     * Deterministic stand-in so the whole flow can be tested without a model. PLAN: answers with the text after "TESTPLAN:" in the data block
     * (so tests can feed it any model output, including hostile ones). EXPLAIN: answers with the text after "TESTEXPLAIN:" in the data block if
     * present, otherwise with a fixed sentence built only from the facts it was given. It understands nothing.
     */
    static final class TestAdapter implements AiProvider {
        @Override
        public String name() {
            return "test";
        }

        @Override
        public boolean configured() {
            return true;
        }

        @Override
        public boolean external() {
            return false;
        }

        @Override
        public String complete(Request request) throws AiProviderException {
            String marker = request.task() == Task.PLAN ? "TESTPLAN:" : "TESTEXPLAIN:";
            int at = request.data().indexOf(marker);
            if (at >= 0) {
                String rest = request.data().substring(at + marker.length());
                int end = rest.indexOf('\n');
                return (end < 0 ? rest : rest.substring(0, end)).trim();
            }
            if (request.task() == Task.PLAN) {
                throw new AiProviderException(AiProviderException.Kind.MALFORMED_RESPONSE, "The test adapter cannot plan without a TESTPLAN marker");
            }
            return "{\"narrative\":\"Test adapter output: the figures above come from your recorded data. This sentence is not written by a language model.\"}";
        }
    }
}
