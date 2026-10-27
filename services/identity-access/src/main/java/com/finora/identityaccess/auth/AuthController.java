package com.finora.identityaccess.auth;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.finora.commons.error.ApiException;
import com.finora.commons.error.ErrorCode;
import com.finora.commons.security.InternalPrincipal;
import com.finora.commons.security.InternalTokenVerifier;
import com.finora.commons.security.PrincipalKind;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public authentication API (reached only through the gateway). The caller's identity comes solely from the verified
 * internal token the gateway attached; nothing in the request body or headers is trusted to say who the user is.
 */
@RestController
public class AuthController {

    public record RegisterRequest(String email, String password, @JsonProperty("display_name") String displayName) {
    }

    public record LoginRequest(String email, String password) {
    }

    public record ChangePasswordRequest(@JsonProperty("current_password") String currentPassword,
            @JsonProperty("new_password") String newPassword) {
    }

    public record ResetRequest(String email) {
    }

    public record ResetConfirmRequest(String token, @JsonProperty("new_password") String newPassword) {
    }

    public record StepUpRequest(String password, String code, @JsonProperty("recovery_code") String recoveryCode) {
    }

    public record CodeRequest(String code, @JsonProperty("recovery_code") String recoveryCode) {
    }

    public record DisableMfaRequest(String password, String code, @JsonProperty("recovery_code") String recoveryCode) {
    }

    public record ProfileRequest(@JsonProperty("display_name") String displayName) {
    }

    public record ValidateRequest(@JsonProperty("session_token") String sessionToken,
            @JsonProperty("csrf_token") String csrfToken) {
    }

    private final AuthService auth;
    private final AuthProperties props;
    private final InternalTokenVerifier verifier;
    private final MfaService mfa;

    public AuthController(AuthService auth, AuthProperties props, InternalTokenVerifier verifier, MfaService mfa) {
        this.auth = auth;
        this.props = props;
        this.verifier = verifier;
        this.mfa = mfa;
    }

    /** A user principal whose session is still live in the database and has completed MFA (if it has MFA). */
    private InternalPrincipal fullUser(HttpServletRequest request) {
        InternalPrincipal p = requireUser(request);
        auth.requireActiveSession(p.sessionId(), false);
        return p;
    }

    private static InternalPrincipal principal(HttpServletRequest request) {
        return (InternalPrincipal) request.getAttribute(InternalPrincipal.REQUEST_ATTRIBUTE);
    }

    private static InternalPrincipal requireUser(HttpServletRequest request) {
        InternalPrincipal p = principal(request);
        if (p.kind() != PrincipalKind.USER || p.sessionId() == null) {
            throw new ApiException(ErrorCode.AUTH_REQUIRED, null);
        }
        return p;
    }

    private static <T> T required(T body) {
        if (body == null) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "Request body is required");
        }
        return body;
    }

    private static Map<String, Object> userBody(AuthService.User user) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", user.id());
        body.put("email", user.email());
        body.put("display_name", user.displayName());
        body.put("email_verified", user.emailVerified());
        body.put("created_at", user.createdAt());
        return body;
    }

    private String sessionCookie(String value, Duration maxAge) {
        return ResponseCookie.from(props.cookieName(), value)
                .httpOnly(true)
                .secure(props.getCookie().isSecure())
                .sameSite(props.getCookie().getSameSite())
                .path("/")
                .maxAge(maxAge)
                .build().toString();
    }

    private ResponseEntity<Map<String, Object>> sessionResponse(HttpStatus status, AuthService.IssuedSession issued) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("user", userBody(issued.user()));
        body.put("csrf_token", issued.csrfToken());
        body.put("expires_at", issued.absoluteExpiresAt());
        body.put("mfa_required", issued.mfaRequired());
        return ResponseEntity.status(status)
                .header(HttpHeaders.SET_COOKIE, sessionCookie(issued.token(), props.getAbsoluteTimeout()))
                .body(body);
    }

    @PostMapping("/v1/auth/register")
    public ResponseEntity<Map<String, Object>> register(@RequestBody(required = false) RegisterRequest body) {
        RegisterRequest r = required(body);
        return ResponseEntity.status(HttpStatus.CREATED).body(userBody(auth.register(r.email(), r.password(), r.displayName())));
    }

    @PostMapping("/v1/auth/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody(required = false) LoginRequest body,
            HttpServletRequest request) {
        LoginRequest r = required(body);
        InternalPrincipal p = principal(request);
        UUID presented = p.kind() == PrincipalKind.USER ? p.sessionId() : null;
        return sessionResponse(HttpStatus.OK, auth.login(r.email(), r.password(), presented));
    }

    @PostMapping("/v1/auth/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        InternalPrincipal p = requireUser(request);
        auth.logout(p.sessionId(), UUID.fromString(p.subject()));
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, sessionCookie("", Duration.ZERO)).build();
    }

    @PostMapping("/v1/auth/password/change")
    public ResponseEntity<Map<String, Object>> changePassword(@RequestBody(required = false) ChangePasswordRequest body,
            HttpServletRequest request) {
        InternalPrincipal p = fullUser(request);
        ChangePasswordRequest r = required(body);
        return sessionResponse(HttpStatus.OK,
                auth.changePassword(UUID.fromString(p.subject()), p.sessionId(), r.currentPassword(), r.newPassword()));
    }

    @PostMapping("/v1/auth/password-reset/request")
    public ResponseEntity<Map<String, Object>> requestReset(@RequestBody(required = false) ResetRequest body) {
        auth.requestPasswordReset(required(body).email());
        // Identical for existing and unknown accounts.
        return ResponseEntity.accepted().body(Map.of("status", "accepted"));
    }

    @PostMapping("/v1/auth/password-reset/confirm")
    public ResponseEntity<Void> confirmReset(@RequestBody(required = false) ResetConfirmRequest body) {
        ResetConfirmRequest r = required(body);
        auth.confirmPasswordReset(r.token(), r.newPassword());
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, sessionCookie("", Duration.ZERO)).build();
    }

    @GetMapping("/v1/me")
    public Map<String, Object> me(HttpServletRequest request) {
        InternalPrincipal p = fullUser(request);
        Map<String, Object> body = userBody(auth.profile(UUID.fromString(p.subject())));
        body.put("csrf_token", auth.csrfTokenFor(p.sessionId()));
        body.put("mfa_enabled", auth.mfaEnabled(UUID.fromString(p.subject())));
        return body;
    }

    @PatchMapping("/v1/me")
    public Map<String, Object> updateProfile(@RequestBody(required = false) ProfileRequest body, HttpServletRequest request) {
        InternalPrincipal p = fullUser(request);
        Map<String, Object> out = userBody(auth.updateDisplayName(UUID.fromString(p.subject()), required(body).displayName()));
        out.put("csrf_token", auth.csrfTokenFor(p.sessionId()));
        out.put("mfa_enabled", auth.mfaEnabled(UUID.fromString(p.subject())));
        return out;
    }

    @PostMapping("/v1/auth/step-up")
    public Map<String, Object> stepUp(@RequestBody(required = false) StepUpRequest body, HttpServletRequest request) {
        InternalPrincipal p = fullUser(request);
        StepUpRequest r = required(body);
        OffsetDateTime until = mfa.stepUp(UUID.fromString(p.subject()), p.sessionId(), r.password(), r.code(), r.recoveryCode());
        return Map.of("step_up_until", until);
    }

    @PostMapping("/v1/auth/mfa/enroll")
    public Map<String, Object> enrollMfa(HttpServletRequest request) {
        InternalPrincipal p = fullUser(request);
        MfaService.Enrollment e = mfa.enroll(UUID.fromString(p.subject()));
        return Map.of("secret", e.secret(), "otpauth_uri", e.otpauthUri());
    }

    @PostMapping("/v1/auth/mfa/confirm")
    public ResponseEntity<Map<String, Object>> confirmMfa(@RequestBody(required = false) CodeRequest body, HttpServletRequest request) {
        InternalPrincipal p = fullUser(request);
        MfaService.Enabled enabled = mfa.confirm(UUID.fromString(p.subject()), required(body).code());
        ResponseEntity<Map<String, Object>> base = sessionResponse(HttpStatus.OK, enabled.session());
        Map<String, Object> out = new LinkedHashMap<>(base.getBody());
        out.put("recovery_codes", enabled.recoveryCodes());
        return ResponseEntity.ok().headers(base.getHeaders()).body(out);
    }

    @PostMapping("/v1/auth/mfa/verify")
    public ResponseEntity<Map<String, Object>> verifyMfa(@RequestBody(required = false) CodeRequest body, HttpServletRequest request) {
        InternalPrincipal p = requireUser(request);
        CodeRequest r = required(body);
        return sessionResponse(HttpStatus.OK, mfa.verify(UUID.fromString(p.subject()), p.sessionId(), r.code(), r.recoveryCode()));
    }

    @PostMapping("/v1/auth/mfa/disable")
    public ResponseEntity<Map<String, Object>> disableMfa(@RequestBody(required = false) DisableMfaRequest body, HttpServletRequest request) {
        InternalPrincipal p = fullUser(request);
        DisableMfaRequest r = required(body);
        return sessionResponse(HttpStatus.OK, mfa.disable(UUID.fromString(p.subject()), r.password(), r.code(), r.recoveryCode()));
    }

    /** Gateway-only: resolves a browser session token to a user. The token travels in the body, never in a URL or log. */
    @PostMapping("/internal/v1/sessions/validate")
    public Map<String, Object> validate(@RequestBody(required = false) ValidateRequest body, HttpServletRequest request) {
        InternalPrincipal p = principal(request);
        if (p.kind() != PrincipalKind.SERVICE || !verifier.gatewayIssuer().equals(p.issuer())) {
            throw new ApiException(ErrorCode.FORBIDDEN, null);
        }
        ValidateRequest r = required(body);
        AuthService.Validation v = auth.validate(r.sessionToken(), r.csrfToken());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("valid", v.valid());
        if (v.valid()) {
            out.put("user_id", v.userId());
            out.put("session_id", v.sessionId());
            out.put("csrf_valid", v.csrfValid());
            out.put("mfa_pending", v.mfaPending());
            out.put("step_up_until", v.stepUpUntil() == null ? null : v.stepUpUntil().toInstant().toString());
            out.put("memberships", v.memberships().stream().map(m -> {
                Map<String, Object> o = new LinkedHashMap<>();
                o.put("workspace_id", m.workspaceId());
                o.put("membership_id", m.membershipId());
                o.put("role", m.role());
                o.put("workspace_type", m.workspaceType());
                return o;
            }).toList());
        }
        return out;
    }
}
