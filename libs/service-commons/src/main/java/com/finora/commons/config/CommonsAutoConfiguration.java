package com.finora.commons.config;

import com.finora.commons.correlation.CorrelationIdFilter;
import com.finora.commons.error.ProblemExceptionHandler;
import com.finora.commons.error.ProblemWriter;
import com.finora.commons.security.InternalAuthFilter;
import com.finora.commons.security.InternalPingController;
import com.finora.commons.security.InternalTokenHealthIndicator;
import com.finora.commons.security.InternalTokenIssuer;
import com.finora.commons.security.InternalTokenProperties;
import com.finora.commons.security.InternalTokenVerifier;
import com.finora.commons.security.KeyLoader;
import com.finora.commons.web.SecurityHeadersFilter;
import java.nio.file.Path;
import java.security.interfaces.ECPublicKey;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import tools.jackson.databind.ObjectMapper;

/**
 * Wires the cross-cutting HTTP concerns into every servlet service: correlation id, security headers, safe problem
 * errors and internal token authentication. Services opt out of token verification (the public gateway) with
 * {@code finora.security.internal-token.verify-incoming=false}.
 */
@AutoConfiguration(afterName = "org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(InternalTokenProperties.class)
@Import(ProblemExceptionHandler.class)
public class CommonsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    Clock finoraClock() {
        return Clock.systemUTC();
    }

    @Bean
    @ConditionalOnMissingBean
    ProblemWriter problemWriter(ObjectMapper mapper) {
        return new ProblemWriter(mapper);
    }

    @Bean
    FilterRegistrationBean<CorrelationIdFilter> correlationIdFilter() {
        FilterRegistrationBean<CorrelationIdFilter> bean = new FilterRegistrationBean<>(new CorrelationIdFilter());
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return bean;
    }

    @Bean
    FilterRegistrationBean<SecurityHeadersFilter> securityHeadersFilter() {
        FilterRegistrationBean<SecurityHeadersFilter> bean = new FilterRegistrationBean<>(new SecurityHeadersFilter());
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE + 5);
        return bean;
    }

    /** Verification side: present unless the service opts out (the public gateway authenticates sessions instead). */
    @Bean
    @ConditionalOnBooleanProperty(name = "finora.security.internal-token.verify-incoming", matchIfMissing = true)
    InternalTokenVerifier internalTokenVerifier(InternalTokenProperties props, Environment env, Clock clock) {
        String audience = props.getServiceName() != null ? props.getServiceName()
                : env.getRequiredProperty("spring.application.name");
        Map<String, ECPublicKey> issuers = new LinkedHashMap<>();
        props.getTrustedIssuers().forEach((name, issuer) -> {
            if (issuer.getPublicKeyPath() == null || issuer.getPublicKeyPath().isBlank()) {
                throw new IllegalStateException("Trusted issuer '" + name + "' has no public-key-path");
            }
            issuers.put(name, KeyLoader.loadPublicKey(Path.of(issuer.getPublicKeyPath())));
        });
        return new InternalTokenVerifier(audience, props.getGatewayIssuer(), issuers, props.getMaxTtl(),
                props.getClockSkew(), clock);
    }

    @Bean
    @ConditionalOnBooleanProperty(name = "finora.security.internal-token.verify-incoming", matchIfMissing = true)
    FilterRegistrationBean<InternalAuthFilter> internalAuthFilter(InternalTokenVerifier verifier, ProblemWriter problems) {
        FilterRegistrationBean<InternalAuthFilter> bean = new FilterRegistrationBean<>(new InternalAuthFilter(verifier, problems));
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return bean;
    }

    @Bean(name = "internalTokenHealthIndicator")
    @ConditionalOnBooleanProperty(name = "finora.security.internal-token.verify-incoming", matchIfMissing = true)
    InternalTokenHealthIndicator internalTokenHealthIndicator(InternalTokenVerifier verifier) {
        return new InternalTokenHealthIndicator(verifier);
    }

    @Bean
    @ConditionalOnBooleanProperty(name = "finora.security.internal-token.verify-incoming", matchIfMissing = true)
    InternalPingController internalPingController(InternalTokenVerifier verifier) {
        return new InternalPingController(verifier);
    }

    /** Signing side: only when a private key is configured (gateway, and services that call other services). */
    @Bean
    @ConditionalOnProperty("finora.security.internal-token.signing.private-key-path")
    InternalTokenIssuer internalTokenIssuer(InternalTokenProperties props, Environment env, Clock clock) {
        InternalTokenProperties.Signing signing = props.getSigning();
        String issuer = signing.getIssuer() != null ? signing.getIssuer() : env.getRequiredProperty("spring.application.name");
        return new InternalTokenIssuer(issuer, KeyLoader.loadPrivateKey(Path.of(signing.getPrivateKeyPath())),
                signing.getTtl(), clock);
    }
}
