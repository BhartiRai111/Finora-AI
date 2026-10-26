package com.finora.apigateway;

import com.finora.commons.error.ProblemWriter;
import com.finora.commons.resilience.DownstreamPolicy;
import com.finora.commons.resilience.ResilientDownstream;
import com.finora.commons.security.InternalTokenIssuer;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GatewayProperties.class)
class GatewayConfiguration {

    @Bean
    RouteTable routeTable() {
        return RouteTable.standard();
    }

    @Bean
    @ConditionalOnMissingBean(SessionAuthenticator.class)
    SessionAuthenticator sessionAuthenticator(Downstreams downstreams, InternalTokenIssuer issuer, ObjectMapper mapper,
            GatewayProperties props, @Value("${finora.auth.cookie.secure:true}") boolean secureCookie) {
        return new IdentitySessionAuthenticator(downstreams.byService().get("identity-access"), issuer, mapper,
                secureCookie ? "__Host-finora_sid" : "finora_sid", props.getReadTimeout());
    }

    @Bean
    TokenBucketRateLimiter tokenBucketRateLimiter(GatewayProperties props, Clock clock) {
        return new TokenBucketRateLimiter(props.getRateLimit().getMaxTrackedSubjects(), clock);
    }

    @Bean
    Downstreams gatewayDownstreams(GatewayProperties props, RouteTable routes) {
        Map<String, ResilientDownstream> map = new LinkedHashMap<>();
        DownstreamPolicy defaults = DownstreamPolicy.defaults();
        DownstreamPolicy policy = new DownstreamPolicy(defaults.connectTimeout(),
                props.getReadTimeout().compareTo(props.getWriteTimeout()) > 0 ? props.getReadTimeout() : props.getWriteTimeout(),
                props.getWriteTimeout().multipliedBy(2).plus(Duration.ofSeconds(1)),
                props.getMaxRetries(), defaults.initialBackoff(), defaults.maxBackoff(), defaults.backoffJitter(),
                defaults.circuitWindow(), defaults.circuitMinCalls(), defaults.circuitFailureRatePercent(),
                defaults.circuitOpenWait(), defaults.maxConcurrentCalls());
        for (String service : routes.services()) {
            String base = props.getServices().get(service);
            if (base == null || base.isBlank()) {
                throw new IllegalStateException("finora.gateway.services." + service + " is not configured");
            }
            map.put(service, new ResilientDownstream(service, URI.create(base.replaceAll("/+$", "")), policy));
        }
        return new Downstreams(map);
    }

    @Bean
    FilterRegistrationBean<GatewayProxyFilter> gatewayProxyFilter(RouteTable routes,
            Downstreams gatewayDownstreams, SessionAuthenticator authenticator,
            InternalTokenIssuer issuer, TokenBucketRateLimiter limiter, GatewayProperties props, ProblemWriter problems) {
        FilterRegistrationBean<GatewayProxyFilter> bean = new FilterRegistrationBean<>(
                new GatewayProxyFilter(routes, gatewayDownstreams.byService(), authenticator, issuer, limiter, props, problems));
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);
        return bean;
    }

    /**
     * Readiness: every routed service has a configured address. Signing keys are loaded at startup (the issuer bean is
     * required by the proxy filter), so a gateway that cannot sign never starts. Downstream health is deliberately not
     * probed: a down service is handled per request by timeouts and circuit breakers, not by pulling the gateway out.
     */
    @Bean(name = "gatewayRoutesHealthIndicator")
    HealthIndicator gatewayRoutesHealthIndicator(RouteTable routes, GatewayProperties props) {
        return () -> routes.services().stream().allMatch(s -> props.getServices().containsKey(s))
                ? Health.up().build() : Health.down().build();
    }

    record Downstreams(Map<String, ResilientDownstream> byService) {
    }
}
