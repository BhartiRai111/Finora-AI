package com.finora.apigateway;

import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * One public route: an exact path pattern owned by one service.
 *
 * @param access   whether a session is required
 * @param uploads  whether multipart/octet-stream bodies are accepted (otherwise JSON only)
 * @param setsCookies whether the owning service may set cookies (session issuance)
 * @param strictRate whether the stricter credential-endpoint rate limit applies
 */
public record Route(String pattern, String service, Access access, boolean uploads, boolean setsCookies,
        boolean strictRate, PathPattern compiled) {

    public enum Access { SESSION_REQUIRED, SESSION_OPTIONAL }

    private static final PathPatternParser PARSER = new PathPatternParser();

    public static Route of(String pattern, String service, Access access) {
        return new Route(pattern, service, access, false, false, false, PARSER.parse(pattern));
    }

    public Route withUploads() {
        return new Route(pattern, service, access, true, setsCookies, strictRate, compiled);
    }

    public Route withCookies() {
        return new Route(pattern, service, access, uploads, true, strictRate, compiled);
    }

    public Route withStrictRate() {
        return new Route(pattern, service, access, uploads, setsCookies, true, compiled);
    }
}
