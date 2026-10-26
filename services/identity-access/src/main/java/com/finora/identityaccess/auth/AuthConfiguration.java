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
    AuthService authService(AuthRepository repo, PasswordService passwords, PasswordResetNotifier notifier,
            AuthProperties props, TransactionTemplate tx, Clock clock) {
        return new AuthService(repo, passwords, notifier, props, tx, clock);
    }
}
