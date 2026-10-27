package com.finora.finance;

import static org.assertj.core.api.Assertions.assertThat;

import com.finora.testkit.TestKeys;
import com.finora.testkit.TestTokens;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
 * Harness for the finance integration tests: the real service on a real PostgreSQL, a controllable clock (starting at
 * 2026-03-15T10:00:00Z), and gateway-style verified tokens. Every test uses its own random profile (tenant), so tests are
 * independent of each other and double as isolation checks.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(FinanceTestSupport.Beans.class)
@ExtendWith(OutputCaptureExtension.class)
public abstract class FinanceTestSupport {

    public static final class MutableClock extends Clock {
        private volatile Instant now = Instant.parse("2026-03-15T10:00:00Z");

        public void set(String instant) {
            now = Instant.parse(instant);
        }

        public void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            // Honour the zone like a real clock does: "today" depends on it.
            MutableClock self = this;
            return new Clock() {
                @Override
                public ZoneId getZone() {
                    return zone;
                }

                @Override
                public Clock withZone(ZoneId other) {
                    return self.withZone(other);
                }

                @Override
                public Instant instant() {
                    return self.instant();
                }
            };
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @TestConfiguration
    static class Beans {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock();
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("finora.security.internal-token.trusted-issuers.gateway.public-key-path", () -> TestKeys.publicPem(TestTokens.GATEWAY).toString());
        registry.add("finora.security.internal-token.trusted-issuers.testcaller.public-key-path", () -> TestKeys.publicPem(TestTokens.CALLER).toString());
        registry.add("finora.finance.recurring.scheduler-enabled", () -> "false");
    }

    @Value("${local.server.port}")
    protected int port;

    @Autowired protected MutableClock clock;
    @Autowired protected JdbcClient jdbc;
    @Autowired protected ObjectMapper json;

    private final HttpClient http = HttpClient.newHttpClient();

    /** Every test starts at the same instant, whatever an earlier test did to the clock. */
    @org.junit.jupiter.api.BeforeEach
    void resetClock() {
        clock.set("2026-03-15T10:00:00Z");
    }

    public record Resp(int status, JsonNode body, HttpResponse<String> raw) {
        public String code() {
            return body.path("code").asString();
        }

        public String header(String name) {
            return raw.headers().firstValue(name).orElse(null);
        }

        public String id() {
            return body.path("id").asString();
        }

        public String text(String field) {
            return body.path(field).asString();
        }
    }

    /** One personal profile: its workspace id is the tenant. */
    public record Person(UUID userId, UUID space) {
    }

    protected Person person() {
        return new Person(UUID.randomUUID(), UUID.randomUUID());
    }

    protected String token(Person p, UUID tenant, String role, List<String> scopes) {
        return TestTokens.issuer(TestTokens.GATEWAY, Duration.ofMinutes(2), clock).issueUser("finance", p.userId(), UUID.randomUUID(), tenant,
                UUID.randomUUID(), role, scopes, null);
    }

    protected String token(Person p) {
        return token(p, p.space(), "OWNER", List.of("workspace:personal"));
    }

    protected Resp raw(String method, String path, String bearer, Object body, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(30))
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

    public record BinResp(int status, byte[] bytes, HttpResponse<byte[]> raw) {
        public String header(String name) {
            return raw.headers().firstValue(name).orElse(null);
        }

        public String text() {
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        }

        public tools.jackson.databind.JsonNode json(ObjectMapper m) {
            return m.readTree(bytes);
        }
    }

    /** A request with a raw byte body (an upload) and a binary or text response. */
    protected BinResp bytes(String method, String path, String bearer, byte[] body, String contentType, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + bearer)
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
        if (body != null) {
            b.header("Content-Type", contentType);
        }
        for (int i = 0; i < headers.length; i += 2) {
            b.header(headers[i], headers[i + 1]);
        }
        HttpResponse<byte[]> r = http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
        return new BinResp(r.statusCode(), r.body(), r);
    }

    /** An upload whose response is JSON (a {@link Resp} like any other request). */
    protected Resp upload(String path, String bearer, byte[] body, String contentType, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + bearer).header("Content-Type", contentType).POST(HttpRequest.BodyPublishers.ofByteArray(body));
        for (int i = 0; i < headers.length; i += 2) {
            b.header(headers[i], headers[i + 1]);
        }
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return new Resp(r.statusCode(), r.body().isEmpty() ? json.createObjectNode() : json.readTree(r.body()), r);
    }

    protected String base(Person p) {
        return "/v1/spaces/" + p.space();
    }

    protected Resp get(Person p, String suffix) throws Exception {
        return raw("GET", base(p) + suffix, token(p), null);
    }

    protected Resp post(Person p, String suffix, Object body) throws Exception {
        return raw("POST", base(p) + suffix, token(p), body, "Idempotency-Key", UUID.randomUUID().toString());
    }

    protected Resp postKeyed(Person p, String suffix, Object body, String key) throws Exception {
        return raw("POST", base(p) + suffix, token(p), body, "Idempotency-Key", key);
    }

    protected Resp postNoKey(Person p, String suffix, Object body) throws Exception {
        return raw("POST", base(p) + suffix, token(p), body);
    }

    protected Resp patch(Person p, String suffix, Object body) throws Exception {
        return raw("PATCH", base(p) + suffix, token(p), body);
    }

    protected Resp put(Person p, String suffix, Object body) throws Exception {
        return raw("PUT", base(p) + suffix, token(p), body);
    }

    // ---- fixtures ----

    protected String account(Person p, String name, String type, String currency, String opening, String openingDate) throws Exception {
        Resp r = post(p, "/accounts", Map.of("name", name, "type", type, "currency", currency, "opening_balance", opening, "opening_date", openingDate));
        assertThat(r.status()).as(r.raw().body()).isEqualTo(201);
        return r.id();
    }

    protected String bank(Person p, String name, String opening) throws Exception {
        return account(p, name, "BANK", "INR", opening, "2026-01-01");
    }

    protected String category(Person p, String name, String kind) throws Exception {
        Resp r = raw("POST", base(p) + "/categories", token(p), Map.of("name", name, "kind", kind));
        assertThat(r.status()).as(r.raw().body()).isEqualTo(201);
        return r.id();
    }

    protected Resp tx(Person p, String kind, String account, String amount, String date, String category, String... tags) throws Exception {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("kind", kind);
        body.put("account_id", account);
        body.put("amount", amount);
        body.put("occurred_on", date);
        if (category != null) {
            body.put("category_id", category);
        }
        if (tags.length > 0) {
            body.put("tags", List.of(tags));
        }
        return post(p, "/transactions", body);
    }

    protected String ok(Resp r) {
        assertThat(r.status()).as(r.raw().body()).isIn(200, 201);
        return r.id();
    }

    protected String balance(Person p, String account) throws Exception {
        return get(p, "/accounts/" + account).text("balance");
    }

    protected long count(String sql, Object... params) {
        var q = jdbc.sql(sql);
        for (int i = 0; i < params.length; i++) {
            q = q.param("p" + i, params[i]);
        }
        return q.query(Long.class).single();
    }

    protected List<Integer> concurrently(List<Callable<Integer>> calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.size());
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (Callable<Integer> c : calls) {
                futures.add(pool.submit(c));
            }
            List<Integer> out = new ArrayList<>();
            for (Future<Integer> f : futures) {
                out.add(f.get());
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }
}
