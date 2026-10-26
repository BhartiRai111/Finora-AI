package com.finora.identityaccess.auth;

import com.finora.commons.correlation.CorrelationId;
import com.finora.commons.error.ApiException;
import com.finora.commons.error.ErrorCode;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Registration, login, sessions and password recovery. Server-side opaque sessions: the browser holds only a random
 * token; the database holds its SHA-256 hash. Messages returned to callers never say whether an account exists, except
 * on registration where a duplicate email is reported (email verification, which would avoid that, needs a mail provider).
 */
public class AuthService {

    private static final Logger LOG = LoggerFactory.getLogger(AuthService.class);
    private static final Pattern EMAIL = Pattern.compile("^[A-Za-z0-9._%+'-]{1,64}@[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,63}$");

    public record User(UUID id, String email, String displayName, boolean emailVerified, OffsetDateTime createdAt) {
    }

    /** A newly created session. {@code token} is shown once (set in the cookie) and never stored. */
    public record IssuedSession(UUID sessionId, String token, String csrfToken, OffsetDateTime absoluteExpiresAt,
            User user, boolean mfaRequired) {
    }

    public record Membership(UUID workspaceId, UUID membershipId, String role) {
    }

    public record Validation(boolean valid, UUID userId, UUID sessionId, boolean csrfValid, boolean mfaPending,
            OffsetDateTime stepUpUntil, List<Membership> memberships) {
        static Validation invalid() {
            return new Validation(false, null, null, false, false, null, List.of());
        }
    }

    private final AuthRepository repo;
    private final PasswordService passwords;
    private final PasswordResetNotifier notifier;
    private final AuthProperties props;
    private final TransactionTemplate tx;
    private final Clock clock;

    public AuthService(AuthRepository repo, PasswordService passwords, PasswordResetNotifier notifier,
            AuthProperties props, TransactionTemplate tx, Clock clock) {
        this.repo = repo;
        this.passwords = passwords;
        this.notifier = notifier;
        this.props = props;
        this.tx = tx;
        this.clock = clock;
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    static String normalizeEmail(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    // ---- registration ----

    public User register(String email, String password, String displayName) {
        String normalized = normalizeEmail(email);
        String name = displayName == null ? null : displayName.trim();
        List<String> problems = new ArrayList<>();
        if (normalized == null || normalized.length() > 254 || !EMAIL.matcher(normalized).matches()
                || normalized.contains("..")) {
            problems.add("email is not a valid address");
        }
        if (name == null || name.isEmpty() || name.length() > 100 || name.chars().anyMatch(Character::isISOControl)) {
            problems.add("display_name must be 1-100 characters without control characters");
        }
        if (password == null) {
            problems.add("password is required");
        } else if (problems.isEmpty() || normalized != null) {
            problems.addAll(passwords.validate(password, normalized));
        }
        if (!problems.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, String.join("; ", problems));
        }
        String hash = passwords.hash(password);
        UUID id = UUID.randomUUID();
        OffsetDateTime now = now();
        try {
            tx.executeWithoutResult(s -> {
                repo.insertUser(id, email.trim(), normalized, name, hash, now);
                // Personal profile: its own tenant, with the user as sole Owner (PRD §2).
                repo.insertPersonalWorkspace(UUID.randomUUID(), UUID.randomUUID(), id, now);
                repo.insertEvent("REGISTERED", id, CorrelationId.current(), now);
            });
        } catch (DuplicateKeyException e) {
            throw new ApiException(ErrorCode.CONFLICT, "An account with this email already exists");
        }
        return new User(id, email.trim(), name, false, now);
    }

    // ---- login ----

    /**
     * @param presentedSessionId the caller's current session, revoked so that a new identifier is always issued
     */
    public IssuedSession login(String email, String password, UUID presentedSessionId) {
        String normalized = normalizeEmail(email);
        if (normalized == null || normalized.isEmpty() || password == null || password.isEmpty()
                || normalized.length() > 254 || password.length() > 1024) {
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS, null);
        }
        String key = HexFormat.of().formatHex(Tokens.hashEmailKey(normalized));
        OffsetDateTime now = now();
        Optional<AuthRepository.ThrottleRow> throttle = repo.readThrottle(key);
        if (throttle.isPresent() && throttle.get().lockedUntil() != null && throttle.get().lockedUntil().isAfter(now)) {
            long wait = Math.max(1, Duration.between(now, throttle.get().lockedUntil()).toSeconds() + 1);
            tx.executeWithoutResult(s -> repo.insertEvent("LOGIN_LOCKED", null, CorrelationId.current(), now));
            throw new ApiException(ErrorCode.RATE_LIMITED, "Too many failed attempts", (int) wait);
        }
        Optional<AuthRepository.UserRow> user = repo.findUserByEmail(normalized);
        boolean ok = user.isPresent() && "ACTIVE".equals(user.get().status())
                ? passwords.matches(password, user.get().passwordHash())
                : passwords.matchesDummy(password);
        if (!ok) {
            recordFailure(key, user.map(AuthRepository.UserRow::id).orElse(null));
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS, null);
        }
        AuthRepository.UserRow row = user.get();
        return tx.execute(s -> {
            repo.deleteThrottle(key);
            if (presentedSessionId != null) {
                repo.revokeSession(presentedSessionId, "ROTATED", now);
            }
            // A user with a confirmed second factor gets a session that can do nothing but complete MFA.
            IssuedSession issued = createSession(row, now, repo.hasConfirmedMfa(row.id()), null);
            repo.insertEvent("LOGIN_SUCCESS", row.id(), CorrelationId.current(), now);
            return issued;
        });
    }

    void recordFailure(String key, UUID userId) {
        OffsetDateTime now = now();
        AuthProperties.Lockout cfg = props.getLockout();
        tx.executeWithoutResult(s -> {
            repo.ensureThrottleRow(key, now);
            AuthRepository.ThrottleRow row = repo.lockThrottleRow(key).orElseThrow();
            int count = row.lastFailureAt().plus(cfg.getWindow()).isBefore(now) ? 1 : row.failureCount() + 1;
            OffsetDateTime lockedUntil = row.lockedUntil();
            if (count >= cfg.getThreshold()) {
                long seconds = (long) Math.min((double) cfg.getMaxDelay().toSeconds(),
                        cfg.getBaseDelay().toSeconds() * Math.pow(2, Math.min(count - cfg.getThreshold(), 20)));
                lockedUntil = now.plusSeconds(seconds);
            }
            repo.saveThrottle(key, count, now, lockedUntil);
            repo.insertEvent("LOGIN_FAILURE", userId, CorrelationId.current(), now);
        });
    }

    /** Package-visible so MFA and password flows can issue rotated sessions with the right assurance. */
    IssuedSession createSession(AuthRepository.UserRow user, OffsetDateTime now, boolean mfaPending,
            OffsetDateTime mfaVerifiedAt) {
        String token = Tokens.newToken();
        String csrf = Tokens.newToken();
        UUID sessionId = UUID.randomUUID();
        OffsetDateTime absolute = now.plus(props.getAbsoluteTimeout());
        repo.insertSession(sessionId, user.id(), Tokens.hash(token), csrf, now, absolute, mfaPending, mfaVerifiedAt);
        return new IssuedSession(sessionId, token, csrf, absolute, toUser(user), mfaPending);
    }

    private static User toUser(AuthRepository.UserRow row) {
        return new User(row.id(), row.email(), row.displayName(), row.emailVerifiedAt() != null, row.createdAt());
    }

    /** Package-visible throttle helpers shared with the MFA flow. */
    void assertNotLocked(String key) {
        OffsetDateTime now = now();
        Optional<AuthRepository.ThrottleRow> throttle = repo.readThrottle(key);
        if (throttle.isPresent() && throttle.get().lockedUntil() != null && throttle.get().lockedUntil().isAfter(now)) {
            throw new ApiException(ErrorCode.RATE_LIMITED, "Too many failed attempts",
                    (int) Math.max(1, Duration.between(now, throttle.get().lockedUntil()).toSeconds() + 1));
        }
    }

    void clearThrottle(String key) {
        repo.deleteThrottle(key);
    }

    /** What the authorization layer needs to know about the caller's live session. */
    public record ActiveSession(UUID sessionId, UUID userId, boolean mfaPending, OffsetDateTime mfaVerifiedAt,
            OffsetDateTime stepUpUntil) {
    }

    /** Rejects a session that is revoked/expired in the database, or (unless allowed) still waiting for MFA. */
    public ActiveSession requireActiveSession(UUID sessionId, boolean allowMfaPending) {
        return toActive(requireActiveRow(sessionId, allowMfaPending));
    }

    private static ActiveSession toActive(AuthRepository.SessionRow r) {
        return new ActiveSession(r.id(), r.userId(), r.mfaPending(), r.mfaVerifiedAt(), r.stepUpUntil());
    }

    AuthRepository.SessionRow requireActiveRow(UUID sessionId, boolean allowMfaPending) {
        AuthRepository.SessionRow row = repo.findSessionById(sessionId)
                .orElseThrow(() -> new ApiException(ErrorCode.AUTH_REQUIRED, null));
        OffsetDateTime now = now();
        if (row.revokedAt() != null || !"ACTIVE".equals(row.userStatus()) || !now.isBefore(row.absoluteExpiresAt())
                || !now.isBefore(row.lastSeenAt().plus(props.getIdleTimeout()))) {
            throw new ApiException(ErrorCode.AUTH_REQUIRED, null);
        }
        if (row.mfaPending() && !allowMfaPending) {
            throw new ApiException(ErrorCode.MFA_REQUIRED, "Complete multi-factor authentication first");
        }
        return row;
    }

    // ---- sessions ----

    /** Called by the gateway for every request carrying a session cookie. Never throws for bad input. */
    public Validation validate(String sessionToken, String csrfToken) {
        if (sessionToken == null || sessionToken.isEmpty() || sessionToken.length() > 128) {
            return Validation.invalid();
        }
        Optional<AuthRepository.SessionRow> found = repo.findSessionByHash(Tokens.hash(sessionToken));
        if (found.isEmpty()) {
            return Validation.invalid();
        }
        AuthRepository.SessionRow session = found.get();
        OffsetDateTime now = now();
        if (session.revokedAt() != null || !"ACTIVE".equals(session.userStatus())) {
            return Validation.invalid();
        }
        boolean absoluteExpired = !now.isBefore(session.absoluteExpiresAt());
        boolean idleExpired = !now.isBefore(session.lastSeenAt().plus(props.getIdleTimeout()));
        if (absoluteExpired || idleExpired) {
            repo.revokeSession(session.id(), "EXPIRED", now);
            return Validation.invalid();
        }
        if (Duration.between(session.lastSeenAt(), now).compareTo(props.getTouchInterval()) > 0) {
            repo.touchSession(session.id(), now);
        }
        boolean csrfValid = csrfToken != null && !csrfToken.isEmpty() && csrfToken.length() <= 128
                && Tokens.constantTimeEquals(session.csrfToken(), csrfToken);
        List<Membership> memberships = session.mfaPending() ? List.of()
                : repo.activeMemberships(session.userId()).stream()
                        .map(m -> new Membership(m.workspaceId(), m.membershipId(), m.role())).toList();
        return new Validation(true, session.userId(), session.id(), csrfValid, session.mfaPending(),
                session.stepUpUntil(), memberships);
    }

    public void logout(UUID sessionId, UUID userId) {
        OffsetDateTime now = now();
        tx.executeWithoutResult(s -> {
            repo.revokeSession(sessionId, "LOGOUT", now);
            repo.insertEvent("LOGOUT", userId, CorrelationId.current(), now);
        });
    }

    public User profile(UUID userId) {
        return repo.findUserById(userId).map(AuthService::toUser)
                .orElseThrow(() -> new ApiException(ErrorCode.AUTH_REQUIRED, null));
    }

    public User updateDisplayName(UUID userId, String displayName) {
        String name = displayName == null ? "" : displayName.trim();
        if (name.isEmpty() || name.length() > 100 || name.chars().anyMatch(Character::isISOControl)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "display_name must be 1-100 characters without control characters");
        }
        OffsetDateTime now = now();
        tx.executeWithoutResult(s -> {
            repo.updateDisplayName(userId, name, now);
            repo.insertEvent("PROFILE_UPDATED", userId, CorrelationId.current(), now);
        });
        return profile(userId);
    }

    public boolean mfaEnabled(UUID userId) {
        return repo.hasConfirmedMfa(userId);
    }

    public String csrfTokenFor(UUID sessionId) {
        return repo.findSessionById(sessionId).filter(r -> r.revokedAt() == null)
                .map(AuthRepository.SessionRow::csrfToken)
                .orElseThrow(() -> new ApiException(ErrorCode.AUTH_REQUIRED, null));
    }

    // ---- password change / reset ----

    /** Changes the password, revokes every session (including the caller's) and issues a fresh one. */
    public IssuedSession changePassword(UUID userId, UUID currentSessionId, String currentPassword, String newPassword) {
        AuthRepository.UserRow user = repo.findUserById(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.AUTH_REQUIRED, null));
        String key = HexFormat.of().formatHex(Tokens.hashEmailKey(normalizeEmail(user.email())));
        OffsetDateTime now = now();
        Optional<AuthRepository.ThrottleRow> throttle = repo.readThrottle(key);
        if (throttle.isPresent() && throttle.get().lockedUntil() != null && throttle.get().lockedUntil().isAfter(now)) {
            throw new ApiException(ErrorCode.RATE_LIMITED, "Too many failed attempts",
                    (int) Math.max(1, Duration.between(now, throttle.get().lockedUntil()).toSeconds() + 1));
        }
        if (currentPassword == null || !passwords.matches(currentPassword, user.passwordHash())) {
            recordFailure(key, userId);
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS, null);
        }
        List<String> problems = passwords.validate(newPassword, normalizeEmail(user.email()));
        if (!problems.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, String.join("; ", problems));
        }
        String hash = passwords.hash(newPassword);
        // The replacement session keeps the MFA assurance of the one it replaces.
        OffsetDateTime keptMfa = repo.findSessionById(currentSessionId).map(AuthRepository.SessionRow::mfaVerifiedAt).orElse(null);
        return tx.execute(s -> {
            repo.updatePassword(userId, hash, now);
            repo.revokeAllSessions(userId, "PASSWORD_CHANGED", now);
            repo.deleteThrottle(key);
            repo.invalidateUnusedResetTokens(userId, now);
            IssuedSession issued = createSession(user, now, false, keptMfa);
            repo.insertEvent("PASSWORD_CHANGED", userId, CorrelationId.current(), now);
            return issued;
        });
    }

    /** Always returns normally for a well-formed email so callers cannot probe which accounts exist. */
    public void requestPasswordReset(String email) {
        String normalized = normalizeEmail(email);
        if (normalized == null || normalized.length() > 254 || !EMAIL.matcher(normalized).matches()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "email is not a valid address");
        }
        Optional<AuthRepository.UserRow> user = repo.findUserByEmail(normalized);
        if (user.isEmpty() || !"ACTIVE".equals(user.get().status())) {
            return;
        }
        OffsetDateTime now = now();
        UUID userId = user.get().id();
        Optional<OffsetDateTime> last = repo.latestResetTokenCreatedAt(userId);
        if (last.isPresent() && last.get().plus(props.getResetRequestInterval()).isAfter(now)) {
            return; // too soon after the previous request: no second token (limits mail-bombing)
        }
        String token = Tokens.newToken();
        OffsetDateTime expires = now.plus(props.getResetTokenTtl());
        tx.executeWithoutResult(s -> {
            repo.invalidateUnusedResetTokens(userId, now);
            repo.insertResetToken(UUID.randomUUID(), userId, Tokens.hash(token), now, expires);
            repo.insertEvent("PASSWORD_RESET_REQUESTED", userId, CorrelationId.current(), now);
        });
        try {
            PasswordResetNotifier.Outcome outcome = notifier.deliver(user.get().email(), token, expires.toInstant());
            if (outcome != PasswordResetNotifier.Outcome.SENT) {
                LOG.warn("Password reset token created but not delivered (outcome {})", outcome);
            }
        } catch (RuntimeException e) {
            LOG.warn("Password reset delivery failed ({})", e.getClass().getSimpleName());
        }
    }

    public void confirmPasswordReset(String token, String newPassword) {
        if (token == null || token.isEmpty() || token.length() > 128) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "Invalid or expired reset token");
        }
        byte[] hash = Tokens.hash(token);
        OffsetDateTime now = now();
        UUID userId = repo.findUsableResetToken(hash, now)
                .orElseThrow(() -> new ApiException(ErrorCode.BAD_REQUEST, "Invalid or expired reset token"));
        AuthRepository.UserRow user = repo.findUserById(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.BAD_REQUEST, "Invalid or expired reset token"));
        List<String> problems = passwords.validate(newPassword, normalizeEmail(user.email()));
        if (!problems.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, String.join("; ", problems));
        }
        String newHash = passwords.hash(newPassword);
        tx.executeWithoutResult(s -> {
            Optional<UUID> consumed = repo.consumeResetToken(hash, now);
            if (consumed.isEmpty()) {
                throw new ApiException(ErrorCode.BAD_REQUEST, "Invalid or expired reset token");
            }
            repo.updatePassword(userId, newHash, now);
            repo.revokeAllSessions(userId, "PASSWORD_RESET", now);
            repo.invalidateUnusedResetTokens(userId, now);
            repo.deleteThrottle(HexFormat.of().formatHex(Tokens.hashEmailKey(normalizeEmail(user.email()))));
            repo.insertEvent("PASSWORD_RESET_COMPLETED", userId, CorrelationId.current(), now);
        });
    }
}
