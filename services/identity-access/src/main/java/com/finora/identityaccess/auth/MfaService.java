package com.finora.identityaccess.auth;

import com.finora.commons.correlation.CorrelationId;
import com.finora.commons.error.ApiException;
import com.finora.commons.error.ErrorCode;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * TOTP multi-factor authentication (AUTH-4, DEC-20): enrolment, login verification, step-up and disabling.
 * Secrets are stored encrypted, time steps cannot be replayed, recovery codes are shown once and stored hashed, and
 * every state change rotates the session. Mandatory-MFA enforcement for Owner/Admin lives in the workspace layer.
 */
public class MfaService {

    public record Enrollment(String secret, String otpauthUri) {
    }

    public record Enabled(AuthService.IssuedSession session, List<String> recoveryCodes) {
    }

    private static final String RECOVERY_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final AuthRepository repo;
    private final AuthService auth;
    private final PasswordService passwords;
    private final MfaCrypto crypto;
    private final AuthProperties props;
    private final TransactionTemplate tx;
    private final Clock clock;

    public MfaService(AuthRepository repo, AuthService auth, PasswordService passwords, MfaCrypto crypto,
            AuthProperties props, TransactionTemplate tx, Clock clock) {
        this.repo = repo;
        this.auth = auth;
        this.passwords = passwords;
        this.crypto = crypto;
        this.props = props;
        this.tx = tx;
        this.clock = clock;
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private static String throttleKey(UUID userId) {
        return HexFormat.of().formatHex(Tokens.hash("mfa:" + userId));
    }

    private void requireAvailable() {
        if (!crypto.available()) {
            throw new ApiException(ErrorCode.FEATURE_UNAVAILABLE, "Multi-factor authentication is not configured on this server");
        }
    }

    public Enrollment enroll(UUID userId) {
        requireAvailable();
        AuthRepository.UserRow user = repo.findUserById(userId).orElseThrow(() -> new ApiException(ErrorCode.AUTH_REQUIRED, null));
        if (repo.findFactor(userId).filter(f -> f.confirmedAt() != null).isPresent()) {
            throw new ApiException(ErrorCode.CONFLICT, "Multi-factor authentication is already enabled");
        }
        byte[] secret = Totp.newSecret();
        repo.upsertUnconfirmedFactor(userId, crypto.encrypt(userId, secret), now());
        String b32 = Totp.base32(secret);
        String issuer = props.getMfa().getIssuerName();
        String label = java.net.URLEncoder.encode(issuer + ":" + user.email(), java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
        String uri = "otpauth://totp/" + label + "?secret=" + b32 + "&issuer="
                + java.net.URLEncoder.encode(issuer, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20")
                + "&algorithm=SHA1&digits=6&period=30";
        return new Enrollment(b32, uri);
    }

    /** Confirms enrolment with a first code; signs out every other session and returns a fresh verified one. */
    public Enabled confirm(UUID userId, String code) {
        requireAvailable();
        String key = throttleKey(userId);
        auth.assertNotLocked(key);
        AuthRepository.FactorRow factor = repo.findFactor(userId).filter(f -> f.confirmedAt() == null)
                .orElseThrow(() -> new ApiException(ErrorCode.CONFLICT, "Start enrolment first"));
        long step = Totp.match(crypto.decrypt(userId, factor.secretCiphertext()), code, clock.instant().getEpochSecond());
        if (step < 0) {
            auth.recordFailure(key, userId);
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS, null);
        }
        AuthRepository.UserRow user = repo.findUserById(userId).orElseThrow();
        OffsetDateTime now = now();
        List<String> codes = newRecoveryCodes();
        return tx.execute(s -> {
            repo.confirmFactor(userId, step, now);
            repo.deleteRecoveryCodes(userId);
            for (String c : codes) {
                repo.insertRecoveryCode(userId, Tokens.hash(normalizeRecovery(c)), now);
            }
            repo.revokeAllSessions(userId, "MFA_ENABLED", now);
            repo.insertEvent("MFA_ENABLED", userId, CorrelationId.current(), now);
            audit(userId, "MFA_ENABLED", now);
            auth.clearThrottle(key);
            return new Enabled(auth.createSession(user, now, false, now), codes);
        });
    }

    /** Second login step. {@code sessionId} is the pending session, which is replaced by a verified one. */
    public AuthService.IssuedSession verify(UUID userId, UUID sessionId, String code, String recoveryCode) {
        String key = throttleKey(userId);
        auth.assertNotLocked(key);
        AuthRepository.SessionRow session = auth.requireActiveRow(sessionId, true);
        if (!session.mfaPending()) {
            throw new ApiException(ErrorCode.CONFLICT, "This session does not need verification");
        }
        if (!checkSecondFactor(userId, code, recoveryCode)) {
            auth.recordFailure(key, userId);
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS, null);
        }
        AuthRepository.UserRow user = repo.findUserById(userId).orElseThrow();
        OffsetDateTime now = now();
        return tx.execute(s -> {
            repo.revokeSession(sessionId, "MFA_ROTATED", now);
            repo.insertEvent("MFA_VERIFIED", userId, CorrelationId.current(), now);
            auth.clearThrottle(key);
            return auth.createSession(user, now, false, now);
        });
    }

    /** Re-authentication for sensitive operations (PERM-10): password, plus a second factor when enrolled. */
    public OffsetDateTime stepUp(UUID userId, UUID sessionId, String password, String code, String recoveryCode) {
        String key = throttleKey(userId);
        auth.assertNotLocked(key);
        AuthRepository.UserRow user = repo.findUserById(userId).orElseThrow(() -> new ApiException(ErrorCode.AUTH_REQUIRED, null));
        boolean mfa = repo.hasConfirmedMfa(userId);
        boolean ok = password != null && passwords.matches(password, user.passwordHash())
                && (!mfa || checkSecondFactor(userId, code, recoveryCode));
        if (!ok) {
            auth.recordFailure(key, userId);
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS, null);
        }
        OffsetDateTime now = now();
        OffsetDateTime until = now.plus(props.getStepUpTtl());
        tx.executeWithoutResult(s -> {
            repo.setStepUp(sessionId, until);
            repo.insertEvent("STEP_UP", userId, CorrelationId.current(), now);
        });
        auth.clearThrottle(key);
        return until;
    }

    /** Turning MFA off needs the password and a current second factor, and is refused for Owners/Admins (DEC-20). */
    public AuthService.IssuedSession disable(UUID userId, String password, String code, String recoveryCode) {
        String key = throttleKey(userId);
        auth.assertNotLocked(key);
        AuthRepository.UserRow user = repo.findUserById(userId).orElseThrow(() -> new ApiException(ErrorCode.AUTH_REQUIRED, null));
        if (!repo.hasConfirmedMfa(userId)) {
            throw new ApiException(ErrorCode.CONFLICT, "Multi-factor authentication is not enabled");
        }
        if (password == null || !passwords.matches(password, user.passwordHash()) || !checkSecondFactor(userId, code, recoveryCode)) {
            auth.recordFailure(key, userId);
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS, null);
        }
        if (repo.hasPrivilegedBusinessRole(userId)) {
            throw new ApiException(ErrorCode.CONFLICT,
                    "Multi-factor authentication is mandatory while you hold an Owner or Admin role");
        }
        OffsetDateTime now = now();
        return tx.execute(s -> {
            repo.deleteFactor(userId);
            repo.revokeAllSessions(userId, "MFA_DISABLED", now);
            repo.insertEvent("MFA_DISABLED", userId, CorrelationId.current(), now);
            audit(userId, "MFA_DISABLED", now);
            auth.clearThrottle(key);
            return auth.createSession(user, now, false, null);
        });
    }

    private boolean checkSecondFactor(UUID userId, String code, String recoveryCode) {
        AuthRepository.FactorRow factor = repo.findFactor(userId).filter(f -> f.confirmedAt() != null).orElse(null);
        if (factor == null) {
            return false;
        }
        if (code != null && !code.isEmpty()) {
            long step = Totp.match(crypto.decrypt(userId, factor.secretCiphertext()), code, clock.instant().getEpochSecond());
            return step > 0 && repo.advanceLastUsedStep(userId, step);
        }
        if (recoveryCode != null && !recoveryCode.isEmpty() && recoveryCode.length() <= 32) {
            return repo.consumeRecoveryCode(userId, Tokens.hash(normalizeRecovery(recoveryCode)), now());
        }
        return false;
    }

    private void audit(UUID userId, String type, OffsetDateTime now) {
        // Account-level event (no workspace); stored in the same local security audit log as workspace events.
        repo.insertAudit(null, userId, type, "USER", userId.toString(), "SUCCESS", null, CorrelationId.current(), now);
    }

    private static String normalizeRecovery(String code) {
        return code.trim().toUpperCase(Locale.ROOT).replace("-", "");
    }

    private static List<String> newRecoveryCodes() {
        List<String> codes = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            StringBuilder sb = new StringBuilder();
            for (int j = 0; j < 12; j++) {
                sb.append(RECOVERY_ALPHABET.charAt(RANDOM.nextInt(RECOVERY_ALPHABET.length())));
                if (j == 3 || j == 7) {
                    sb.append('-');
                }
            }
            codes.add(sb.toString());
        }
        return codes;
    }
}
