package com.finora.commons.security;

import com.finora.commons.correlation.CorrelationId;
import com.finora.commons.error.ErrorCode;
import com.finora.commons.error.ProblemWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Requires a valid internal token on everything except the health endpoints, and applies the path rules:
 * <ul>
 *   <li>{@code /internal/**}: SERVICE or USER tokens from any trusted issuer.</li>
 *   <li>everything else: USER or ANONYMOUS tokens from the gateway only.</li>
 *   <li>{@code /v1/spaces/{id}/**} with a USER token: the token tenant must equal the path space, otherwise the
 *       resource is reported as not found (foreign spaces are indistinguishable from missing ones).</li>
 * </ul>
 * Rejections carry {@code X-Internal-Auth: rejected} so the gateway can tell a misconfigured hop from a business 4xx.
 */
public class InternalAuthFilter extends OncePerRequestFilter {

    public static final String REJECTED_HEADER = "X-Internal-Auth";
    public static final String REJECTED_VALUE = "rejected";

    private static final Logger LOG = LoggerFactory.getLogger(InternalAuthFilter.class);
    private static final Pattern SPACE_PATH = Pattern.compile("^/v1/spaces/([^/]+)(/.*)?$");
    private static final Set<PrincipalKind> INTERNAL_KINDS = EnumSet.of(PrincipalKind.SERVICE, PrincipalKind.USER);
    private static final Set<PrincipalKind> PUBLIC_API_KINDS = EnumSet.of(PrincipalKind.USER, PrincipalKind.ANONYMOUS);

    private final InternalTokenVerifier verifier;
    private final ProblemWriter problems;

    public InternalAuthFilter(InternalTokenVerifier verifier, ProblemWriter problems) {
        this.verifier = verifier;
        this.problems = problems;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        return "/actuator/health".equals(path) || path.startsWith("/actuator/health/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            reject(request, response, ErrorCode.AUTH_REQUIRED, "missing bearer token");
            return;
        }
        InternalPrincipal principal;
        try {
            principal = verifier.verify(header.substring(7).trim());
        } catch (InternalAuthException e) {
            reject(request, response, ErrorCode.AUTH_REQUIRED, e.reason().name());
            return;
        }

        String path = request.getServletPath();
        boolean internalPath = path.startsWith("/internal/");
        Set<PrincipalKind> allowed = internalPath ? INTERNAL_KINDS : PUBLIC_API_KINDS;
        if (!allowed.contains(principal.kind())
                || (!internalPath && !verifier.gatewayIssuer().equals(principal.issuer()))) {
            reject(request, response, ErrorCode.FORBIDDEN, "kind " + principal.kind() + " not allowed on this path");
            return;
        }
        if (!internalPath && principal.kind() == PrincipalKind.USER) {
            Matcher m = SPACE_PATH.matcher(path);
            if (m.matches() && !spaceMatches(m.group(1), principal.tenantId())) {
                reject(request, response, ErrorCode.NOT_FOUND, "token tenant does not match path space");
                return;
            }
        }

        request.setAttribute(InternalPrincipal.REQUEST_ATTRIBUTE, principal);
        MDC.put("principalKind", principal.kind().name());
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove("principalKind");
        }
    }

    private static boolean spaceMatches(String pathSpace, UUID tokenTenant) {
        if (tokenTenant == null) {
            return false;
        }
        try {
            return UUID.fromString(pathSpace).equals(tokenTenant);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private void reject(HttpServletRequest request, HttpServletResponse response, ErrorCode code, String reason)
            throws IOException {
        // Reason goes to the log with the request id; the caller gets only the generic problem document.
        LOG.warn("Internal auth rejected request {} {} ({}): {}", request.getMethod(), request.getServletPath(), code,
                reason);
        response.setHeader(REJECTED_HEADER, REJECTED_VALUE);
        if (code == ErrorCode.AUTH_REQUIRED) {
            response.setHeader("WWW-Authenticate", "Bearer");
        }
        problems.write(request, response, code, null, null);
    }

    static String requestId(HttpServletRequest request) {
        Object id = request.getAttribute(CorrelationId.REQUEST_ATTRIBUTE);
        return id == null ? null : id.toString();
    }
}
