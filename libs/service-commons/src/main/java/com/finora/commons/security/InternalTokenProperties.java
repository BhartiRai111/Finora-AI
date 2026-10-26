package com.finora.commons.security;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration for internal (service-to-service and gateway-to-service) token authentication. */
@ConfigurationProperties("finora.security.internal-token")
public class InternalTokenProperties {

    /** Audience this service accepts; defaults to spring.application.name. */
    private String serviceName;
    /** Name of the only issuer allowed to assert end users and anonymous callers. */
    private String gatewayIssuer = "gateway";
    private Duration maxTtl = Duration.ofMinutes(10);
    private Duration clockSkew = Duration.ofSeconds(30);
    private Map<String, TrustedIssuer> trustedIssuers = new LinkedHashMap<>();
    private Signing signing = new Signing();

    public static class TrustedIssuer {
        private String publicKeyPath;

        public String getPublicKeyPath() {
            return publicKeyPath;
        }

        public void setPublicKeyPath(String publicKeyPath) {
            this.publicKeyPath = publicKeyPath;
        }
    }

    public static class Signing {
        private String issuer;
        private String privateKeyPath;
        private Duration ttl = Duration.ofMinutes(2);

        public String getIssuer() {
            return issuer;
        }

        public void setIssuer(String issuer) {
            this.issuer = issuer;
        }

        public String getPrivateKeyPath() {
            return privateKeyPath;
        }

        public void setPrivateKeyPath(String privateKeyPath) {
            this.privateKeyPath = privateKeyPath;
        }

        public Duration getTtl() {
            return ttl;
        }

        public void setTtl(Duration ttl) {
            this.ttl = ttl;
        }
    }

    public String getServiceName() {
        return serviceName;
    }

    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    public String getGatewayIssuer() {
        return gatewayIssuer;
    }

    public void setGatewayIssuer(String gatewayIssuer) {
        this.gatewayIssuer = gatewayIssuer;
    }

    public Duration getMaxTtl() {
        return maxTtl;
    }

    public void setMaxTtl(Duration maxTtl) {
        this.maxTtl = maxTtl;
    }

    public Duration getClockSkew() {
        return clockSkew;
    }

    public void setClockSkew(Duration clockSkew) {
        this.clockSkew = clockSkew;
    }

    public Map<String, TrustedIssuer> getTrustedIssuers() {
        return trustedIssuers;
    }

    public void setTrustedIssuers(Map<String, TrustedIssuer> trustedIssuers) {
        this.trustedIssuers = trustedIssuers;
    }

    public Signing getSigning() {
        return signing;
    }

    public void setSigning(Signing signing) {
        this.signing = signing;
    }
}
