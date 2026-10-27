package com.finora.finance.accounting;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * {@code finora.finance.accounting.provider} accepts only {@code none}: no accounting provider is approved. Any other value stops the service at
 * startup instead of pretending an integration exists. The token vault key is a base64 256-bit key in a file ({@code token-key-path}), read once;
 * without it the vault is unavailable and no token can be stored.
 */
@Configuration
public class AccountingConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AccountingConfiguration.class);

    @Bean
    public AccountingProvider accountingProvider(@Value("${finora.finance.accounting.provider:none}") String provider) {
        String p = provider.trim().toLowerCase(Locale.ROOT);
        if (!p.isEmpty() && !p.equals("none")) {
            throw new IllegalStateException("Unknown finora.finance.accounting.provider '" + provider + "'; only none is supported. No accounting provider is approved yet");
        }
        return new AccountingProvider() {
            @Override
            public String key() {
                return "none";
            }

            @Override
            public boolean configured() {
                return false;
            }
        };
    }

    @Bean
    public TokenVault tokenVault(@Value("${finora.finance.accounting.token-key-path:}") String keyPath) {
        if (keyPath == null || keyPath.isBlank()) {
            return new TokenVault(null);
        }
        try {
            byte[] key = Base64.getDecoder().decode(Files.readString(Path.of(keyPath.trim())).trim());
            if (key.length != 32) {
                log.warn("Accounting token key must be 256 bits; the token vault is unavailable");
                return new TokenVault(null);
            }
            return new TokenVault(key);
        } catch (Exception e) {
            log.warn("Accounting token key could not be read; the token vault is unavailable");
            return new TokenVault(null);
        }
    }
}
