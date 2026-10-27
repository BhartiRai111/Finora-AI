package com.finora.identityaccess.auth;

import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AuthProperties.class)
class AuthConfiguration {

    @Bean
    PasswordService passwordService() {
        return new PasswordService();
    }

    @Bean
    @ConditionalOnMissingBean(PasswordResetNotifier.class)
    PasswordResetNotifier passwordResetNotifier() {
        return new UnconfiguredPasswordResetNotifier();
    }

    @Bean
    MfaCrypto mfaCrypto(AuthProperties props) throws java.io.IOException {
        String path = props.getMfa().getEncryptionKeyPath();
        if (path == null || path.isBlank() || !java.nio.file.Files.isRegularFile(java.nio.file.Path.of(path))) {
            org.slf4j.LoggerFactory.getLogger(AuthConfiguration.class).warn(
                    "MFA encryption key not found: multi-factor authentication cannot be enrolled until one is configured");
            return new MfaCrypto(null);
        }
        byte[] key = java.util.Base64.getDecoder().decode(java.nio.file.Files.readString(java.nio.file.Path.of(path)).trim());
        return new MfaCrypto(key);
    }

    @Bean
    MfaService mfaService(AuthRepository repo, AuthService auth, PasswordService passwords, MfaCrypto crypto,
            AuthProperties props, TransactionTemplate tx, Clock clock) {
        return new MfaService(repo, auth, passwords, crypto, props, tx, clock);
    }

    @Bean
    AuthService authService(AuthRepository repo, PasswordService passwords, PasswordResetNotifier notifier,
            AuthProperties props, TransactionTemplate tx, Clock clock) {
        return new AuthService(repo, passwords, notifier, props, tx, clock);
    }
}
