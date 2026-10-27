package com.finora.identityaccess;

import static org.assertj.core.api.Assertions.assertThat;

import com.finora.identityaccess.workspace.InvitationNotifier;
import com.finora.testkit.TestKeys;
import com.finora.testkit.TestTokens;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Shared harness for the identity-access integration tests: real service, real PostgreSQL, a controllable clock, a
 * capturing invitation notifier (a TEST double; no email is sent anywhere) and gateway-style internal tokens.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(IdentityTestSupport.Beans.class)
@ExtendWith(OutputCaptureExtension.class)
public abstract class IdentityTestSupport {

    protected static final String PASSWORD = "Tr0ub4dor&3-horse-battery";

    public static final class MutableClock extends Clock {
        private volatile Instant now = Instant.now();

        public void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    public record Sent(String email, String workspace, String role, String link) {
        public String token() {
            return link.substring(link.indexOf("#token=") + 7);
        }
    }

    public static final class CapturingInvitationNotifier implements InvitationNotifier {
        private final List<Sent> sent = new ArrayList<>();

        @Override
        public synchronized InvitationNotifier.Outcome deliver(String email, String workspaceName, String role, String acceptLink,
                Instant expiresAt) {
            sent.add(new Sent(email, workspaceName, role, acceptLink));
            return InvitationNotifier.Outcome.SENT;
        }

        public synchronized Sent last(String email) {
            for (int i = sent.size() - 1; i >= 0; i--) {
                if (sent.get(i).email().equalsIgnoreCase(email)) {
                    return sent.get(i);
                }
            }
            return null;
        }

        public synchronized int count() {
            return sent.size();
        }
    }

    @TestConfiguration
    static class Beans {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock();
        }

        @Bean
        @Primary
        CapturingInvitationNotifier capturingInvitationNotifier() {
            return new CapturingInvitationNotifier();
        }
    }

    private static final Path MFA_KEY = newMfaKeyFile();

    private static Path newMfaKeyFile() {
        try {
            byte[] key = new byte[32];
            new SecureRandom().nextBytes(key);
            Path path = Files.createTempFile("finora-test-mfa", ".key");
            Files.writeString(path, Base64.getEncoder().encodeToString(key));
            path.toFile().deleteOnExit();
            return path;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("finora.security.internal-token.trusted-issuers.gateway.public-key-path",
                () -> TestKeys.publicPem(TestTokens.GATEWAY).toString());
        registry.add("finora.security.internal-token.trusted-issuers.testcaller.public-key-path",
                () -> TestKeys.publicPem(TestTokens.CALLER).toString());
        registry.add("finora.auth.mfa.encryption-key-path", MFA_KEY::toString);
        // The shared test clock jumps days ahead (invitation expiry); keep sessions alive across those jumps.
        registry.add("finora.auth.idle-timeout", () -> "30d");
        registry.add("finora.auth.absolute-timeout", () -> "60d");
    }

    @Value("${local.server.port}")
    protected int port;

    @Autowired protected MutableClock clock;
    @Autowired protected CapturingInvitationNotifier invitations;
    @Autowired protected JdbcClient jdbc;
    @Autowired protected ObjectMapper json;

    private final HttpClient http = HttpClient.newHttpClient();

    // ---- HTTP ----

    public record Resp(int status, JsonNode body, HttpResponse<String> raw) {
        public String code() {
            return body.path("code").asString();
        }

        public String header(String name) {
            return raw.headers().firstValue(name).orElse(null);
        }
    }

    /** A test account and its current session. The session changes when MFA is enabled or verified. */
    public static final class TU {
        public final String email;
        public UUID userId;
        public UUID sessionId;
        public String cookie;
        public String csrf;
        public String mfaSecret;

        TU(String email) {
            this.email = email;
        }
    }

    protected Resp call(String method, String path, String bearer, Object body, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + bearer)
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body instanceof String s ? s : json.writeValueAsString(body)));
        if (body != null) {
            b.header("Content-Type", "application/json");
        }
        for (int i = 0; i < headers.length; i += 2) {
            b.header(headers[i], headers[i + 1]);
        }
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return new Resp(r.statusCode(), r.body().isEmpty() ? json.createObjectNode() : json.readTree(r.body()), r);
    }

    protected String anon() {
        return TestTokens.issuer(TestTokens.GATEWAY, Duration.ofMinutes(2), clock).issueAnonymous("identity-access");
    }

    protected String gatewayService() {
        return TestTokens.issuer(TestTokens.GATEWAY, Duration.ofMinutes(2), clock).issueService("identity-access", null);
    }

    /** A token as the gateway would mint it: scoped to a workspace only when the path names one. */
    protected String tokenFor(TU u, UUID space) {
        return TestTokens.issuer(TestTokens.GATEWAY, Duration.ofMinutes(2), clock).issueUser("identity-access", u.userId,
                u.sessionId, space, null, null, List.of(), null);
    }

    protected Resp as(TU u, String method, String path, Object body) throws Exception {
        return call(method, path, tokenFor(u, null), body);
    }

    protected Resp inSpace(TU u, UUID space, String method, String suffix, Object body) throws Exception {
        return call(method, "/v1/spaces/" + space + suffix, tokenFor(u, space), body);
    }

    // ---- accounts ----

    protected static String email() {
        return "wk-" + UUID.randomUUID().toString().substring(0, 8) + "@example.test";
    }

    protected TU newUser() throws Exception {
        TU u = new TU(email());
        Resp reg = call("POST", "/v1/auth/register", anon(), Map.of("email", u.email, "password", PASSWORD, "display_name", "User " + u.email.substring(3, 7)));
        assertThat(reg.status()).as(reg.raw().body()).isEqualTo(201);
        u.userId = UUID.fromString(reg.body().path("id").asString());
        login(u);
        return u;
    }

    protected void login(TU u) throws Exception {
        Resp r = call("POST", "/v1/auth/login", anon(), Map.of("email", u.email, "password", PASSWORD));
        assertThat(r.status()).as(r.raw().body()).isEqualTo(200);
        adopt(u, r);
    }

    protected void adopt(TU u, Resp r) throws Exception {
        String cookie = r.header("Set-Cookie");
        u.cookie = cookie.substring(cookie.indexOf('=') + 1, cookie.indexOf(';'));
        u.csrf = r.body().path("csrf_token").asString();
        Resp v = call("POST", "/internal/v1/sessions/validate", gatewayService(), Map.of("session_token", u.cookie));
        u.sessionId = UUID.fromString(v.body().path("session_id").asString());
    }

    /** Next TOTP code that has not been used yet (advances the test clock past the previous time step). */
    protected String freshCode(TU u) {
        clock.advance(Duration.ofSeconds(31));
        return totp(Base32.decode(u.mfaSecret), clock.instant().getEpochSecond() / 30);
    }

    /** Enrols the account in MFA; afterwards its session is the fresh, MFA-verified one. */
    protected TU withMfa(TU u) throws Exception {
        Resp enroll = as(u, "POST", "/v1/auth/mfa/enroll", null);
        assertThat(enroll.status()).as(enroll.raw().body()).isEqualTo(200);
        u.mfaSecret = enroll.body().path("secret").asString();
        Resp confirm = as(u, "POST", "/v1/auth/mfa/confirm", Map.of("code", freshCode(u)));
        assertThat(confirm.status()).as(confirm.raw().body()).isEqualTo(200);
        adopt(u, confirm);
        return u;
    }

    protected void stepUp(TU u) throws Exception {
        Resp r = as(u, "POST", "/v1/auth/step-up", Map.of("password", PASSWORD, "code", freshCode(u)));
        assertThat(r.status()).as(r.raw().body()).isEqualTo(200);
    }

    protected UUID createWorkspace(TU owner, String name) throws Exception {
        Resp r = as(owner, "POST", "/v1/workspaces", Map.of("name", name));
        assertThat(r.status()).as(r.raw().body()).isEqualTo(201);
        return UUID.fromString(r.body().path("id").asString());
    }

    /** Invites {@code member} with {@code role} (as an MFA-enabled inviter) and has them accept. Returns their membership id. */
    protected UUID addMember(TU inviter, UUID space, TU member, String role) throws Exception {
        Resp inv = inSpace(inviter, space, "POST", "/invitations", Map.of("email", member.email, "role", role));
        assertThat(inv.status()).as(inv.raw().body()).isEqualTo(201);
        String token = invitations.last(member.email).token();
        Resp ok = as(member, "POST", "/v1/invitations/accept", Map.of("token", token));
        assertThat(ok.status()).as(ok.raw().body()).isEqualTo(200);
        return UUID.fromString(ok.body().path("membership_id").asString());
    }

    protected UUID membershipId(TU u, UUID space) throws Exception {
        Resp r = as(u, "GET", "/v1/workspaces", null);
        for (JsonNode w : r.body().path("items")) {
            if (w.path("id").asString().equals(space.toString())) {
                return UUID.fromString(w.path("membership_id").asString());
            }
        }
        throw new AssertionError("no membership in " + space);
    }

    // ---- TOTP (independent implementation, RFC 6238) ----

    static String totp(byte[] secret, long step) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret, "HmacSHA1"));
            byte[] h = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
            int o = h[h.length - 1] & 0xf;
            int bin = ((h[o] & 0x7f) << 24) | ((h[o + 1] & 0xff) << 16) | ((h[o + 2] & 0xff) << 8) | (h[o + 3] & 0xff);
            return String.format("%06d", bin % 1_000_000);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static final class Base32 {
        static byte[] decode(String s) {
            String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            int buffer = 0;
            int bits = 0;
            for (char c : s.toCharArray()) {
                buffer = (buffer << 5) | alphabet.indexOf(c);
                bits += 5;
                if (bits >= 8) {
                    out.write((buffer >> (bits - 8)) & 0xff);
                    bits -= 8;
                }
            }
            return out.toByteArray();
        }
    }
}
