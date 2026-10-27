package com.finora.identityaccess.auth;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;

/**
 * Argon2id password hashing (OWASP-recommended minimum: 19 MiB memory, 2 iterations, 1 lane) and the password policy.
 * The policy is deliberately simple: length, a small deny-list, and no use of the account's own email name.
 * Checking against a breached-password corpus needs an external data source and is not implemented.
 */
public class PasswordService {

    static final int MIN_LENGTH = 12;
    static final int MAX_LENGTH = 128;

    private static final Set<String> COMMON = Set.of("password1234", "passwordpassword", "123456789012", "qwertyuiop12",
            "qwertyuiopas", "1234567890123", "letmein123456", "welcome12345", "administrator", "iloveyou12345",
            "changeme1234", "password12345", "p@ssw0rd1234", "abcdefghijkl", "111111111111", "000000000000");

    private final Argon2PasswordEncoder encoder = new Argon2PasswordEncoder(16, 32, 1, 19_456, 2);
    private final String dummyHash;

    public PasswordService() {
        // A real hash of an unguessable value, verified against when the account does not exist so that timing does
        // not reveal which emails are registered.
        this.dummyHash = encoder.encode(Tokens.newToken());
    }

    public String hash(String password) {
        return encoder.encode(password);
    }

    public boolean matches(String password, String hash) {
        return encoder.matches(password, hash);
    }

    /** Burns the same CPU as a real verification. Always returns false. */
    public boolean matchesDummy(String password) {
        encoder.matches(password, dummyHash);
        return false;
    }

    /** @return human-readable problems (no echo of the password); empty when acceptable */
    public List<String> validate(String password, String normalizedEmail) {
        List<String> problems = new ArrayList<>();
        if (password == null || password.length() < MIN_LENGTH) {
            problems.add("password must be at least " + MIN_LENGTH + " characters");
            return problems;
        }
        if (password.length() > MAX_LENGTH || password.getBytes(StandardCharsets.UTF_8).length > 4 * MAX_LENGTH) {
            problems.add("password must be at most " + MAX_LENGTH + " characters");
            return problems;
        }
        String lower = password.toLowerCase(Locale.ROOT);
        if (COMMON.contains(lower) || password.chars().distinct().count() < 5) {
            problems.add("password is too common or too repetitive");
        }
        if (normalizedEmail != null) {
            String local = normalizedEmail.substring(0, Math.max(0, normalizedEmail.indexOf('@')));
            if (local.length() >= 4 && lower.contains(local)) {
                problems.add("password must not contain your email name");
            }
        }
        return problems;
    }
}
