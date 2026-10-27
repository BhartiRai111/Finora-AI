package com.finora.finance.step22;

import static org.assertj.core.api.Assertions.assertThat;

import com.finora.finance.notifications.NotificationService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/** In-app notifications: persistence, per-user read state, audiences, pagination and isolation. Nothing is delivered outside the application. */
class NotificationsTest extends Step22Support {

    @Autowired NotificationService notifications;

    private boolean make(Biz b, UUID recipient, String audience, String category, String key) {
        return notifications.create(b.space(), clock, recipient, audience, category, "INFO", "Title " + key, "Body " + key, Map.of("k", key), key);
    }

    private JsonNode list(Biz b, UUID user, String role, String query) throws Exception {
        Resp r = as(b, user, role, "GET", "/finance-notifications" + query, null);
        assertThat(r.status()).as(r.raw().body()).isEqualTo(200);
        return r.body();
    }

    @Test
    void notificationsArePersistedWithCategoryStatusTimeAndSafeMetadata() throws Exception {
        Biz b = biz();
        assertThat(make(b, null, "REPORTS", "ALERT", "k1")).isTrue();
        JsonNode page = list(b, b.owner(), "OWNER", "");
        assertThat(page.path("total").asLong()).isEqualTo(1);
        assertThat(page.path("unread").asLong()).isEqualTo(1);
        JsonNode n = page.path("items").get(0);
        assertThat(n.path("category").asString()).isEqualTo("ALERT");
        assertThat(n.path("severity").asString()).isEqualTo("INFO");
        assertThat(n.path("read").asBoolean()).isFalse();
        assertThat(n.path("created_at").asString()).startsWith("2026-03-15");
        assertThat(n.path("metadata").path("k").asString()).isEqualTo("k1");
        assertThat(n.has("tenant_id")).as("no internal identifiers beyond the notification id").isFalse();
        assertThat(n.has("recipient_user_id")).isFalse();
        assertThat(make(b, null, "REPORTS", "ALERT", "k1")).as("the same event is never recorded twice").isFalse();
        assertThat(list(b, b.owner(), "OWNER", "").path("total").asLong()).isEqualTo(1);
        Resp channels = as(b, b.owner(), "OWNER", "GET", "/finance-notifications/channels", null);
        assertThat(channels.body().path("in_app").path("status").asString()).isEqualTo("AVAILABLE");
        assertThat(channels.body().path("external").path("status").asString()).isEqualTo("NOT_CONFIGURED");
        assertThat(channels.body().path("external").path("message").asString()).contains("nothing is sent outside it");
        assertThat(count("select count(*) from information_schema.tables where table_name in ('notification_deliveries', 'notification_outbox')")).as("no fake delivery tracking exists").isZero();
    }

    @Test
    void readStateIsPerUserSoOneMemberReadingNeverMarksItReadForAnother() throws Exception {
        Biz b = biz();
        UUID accountant = UUID.randomUUID();
        make(b, null, "REPORTS", "ALERT", "shared1");
        make(b, null, "REPORTS", "ALERT", "shared2");
        assertThat(list(b, b.owner(), "OWNER", "").path("unread").asLong()).isEqualTo(2);
        assertThat(list(b, accountant, "ACCOUNTANT", "").path("unread").asLong()).isEqualTo(2);
        String id = list(b, b.owner(), "OWNER", "").path("items").get(0).path("id").asString();
        Resp read = as(b, b.owner(), "OWNER", "POST", "/finance-notifications/" + id + "/read", Map.of());
        assertThat(read.status()).isEqualTo(200);
        assertThat(read.body().path("read").asBoolean()).isTrue();
        assertThat(as(b, b.owner(), "OWNER", "POST", "/finance-notifications/" + id + "/read", Map.of()).status()).as("idempotent").isEqualTo(200);
        assertThat(as(b, b.owner(), "OWNER", "GET", "/finance-notifications/unread-count", null).body().path("unread").asLong()).isEqualTo(1);
        assertThat(as(b, accountant, "ACCOUNTANT", "GET", "/finance-notifications/unread-count", null).body().path("unread").asLong()).as("the accountant's state is separate").isEqualTo(2);
        assertThat(list(b, b.owner(), "OWNER", "?unread_only=true").path("items")).hasSize(1);
        Resp all = as(b, b.owner(), "OWNER", "POST", "/finance-notifications/read-all", Map.of());
        assertThat(all.body().path("marked").asInt()).isEqualTo(1);
        assertThat(as(b, b.owner(), "OWNER", "POST", "/finance-notifications/read-all", Map.of()).body().path("marked").asInt()).isZero();
        assertThat(list(b, b.owner(), "OWNER", "").path("unread").asLong()).isZero();
        assertThat(list(b, accountant, "ACCOUNTANT", "").path("unread").asLong()).isEqualTo(2);
        assertThat(count("select count(*) from notification_reads where tenant_id = :p0", b.space())).isEqualTo(2);
    }

    @Test
    void aNotificationAddressedToOneUserOrToAnAudienceIsInvisibleToEveryoneElse() throws Exception {
        Biz b = biz();
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        make(b, alice, "REPORTS", "IMPORT", "for-alice");
        make(b, null, "OWNER", "SUBSCRIPTION", "owners-only");
        make(b, null, "SETTINGS", "INTEGRATION", "settings-audience");
        String aliceId = null;
        for (JsonNode item : list(b, alice, "ACCOUNTANT", "").path("items")) {
            if ("Title for-alice".equals(item.path("title").asString())) {
                aliceId = item.path("id").asString();
            }
        }
        assertThat(aliceId).isNotNull();
        assertThat(list(b, alice, "ACCOUNTANT", "").path("items").toString()).contains("for-alice").doesNotContain("owners-only");
        assertThat(list(b, bob, "ACCOUNTANT", "").path("items").toString()).as("another user of the same role").doesNotContain("for-alice");
        assertThat(as(b, bob, "ACCOUNTANT", "POST", "/finance-notifications/" + aliceId + "/read", Map.of()).status()).as("marking another user's notification").isEqualTo(404);
        assertThat(count("select count(*) from notification_reads where tenant_id = :p0", b.space())).isZero();
        assertThat(list(b, b.owner(), "OWNER", "").path("total").asLong()).as("the owner sees the owner and settings audiences but not alice's private one").isEqualTo(2);
        assertThat(list(b, b.owner(), "OWNER", "").path("items").toString()).doesNotContain("for-alice");
        for (String role : List.of("VIEWER", "CONTRIBUTOR", "NOT_A_ROLE")) {
            assertThat(as(b, UUID.randomUUID(), role, "GET", "/finance-notifications", null).status()).as(role).isEqualTo(403);
            assertThat(as(b, UUID.randomUUID(), role, "GET", "/finance-notifications/unread-count", null).status()).as(role).isEqualTo(403);
            assertThat(as(b, UUID.randomUUID(), role, "POST", "/finance-notifications/read-all", Map.of()).status()).as(role).isEqualTo(403);
        }
        assertThat(as(b, UUID.randomUUID(), "ADMIN", "GET", "/finance-notifications", null).body().path("items").toString()).as("an Admin sees the settings audience").contains("settings-audience");
    }

    @Test
    void workspacesNeverSeeOrChangeEachOthersNotifications() throws Exception {
        Biz a = biz();
        Biz other = biz();
        make(a, null, "REPORTS", "ALERT", "a-only");
        String id = list(a, a.owner(), "OWNER", "").path("items").get(0).path("id").asString();
        assertThat(list(other, other.owner(), "OWNER", "").path("total").asLong()).isZero();
        assertThat(as(other, other.owner(), "OWNER", "POST", "/finance-notifications/" + id + "/read", Map.of()).status()).as("same id through another workspace").isEqualTo(404);
        assertThat(raw("GET", "/v1/spaces/" + a.space() + "/finance-notifications", btoken(other, other.owner(), "OWNER"), null).status()).as("another workspace's token on this path").isEqualTo(404);
        assertThat(raw("POST", "/v1/spaces/" + a.space() + "/finance-notifications/read-all", btoken(other, other.owner(), "OWNER"), Map.of()).status()).isEqualTo(404);
        assertThat(list(a, a.owner(), "OWNER", "").path("unread").asLong()).as("nothing was marked").isEqualTo(1);
        assertThat(as(a, a.owner(), "OWNER", "POST", "/finance-notifications/" + UUID.randomUUID() + "/read", Map.of()).status()).isEqualTo(404);
        Person p = person();
        assertThat(get(p, "/finance-notifications").status()).as("a personal owner has a personal inbox").isEqualTo(200);
        assertThat(raw("GET", base(p) + "/finance-notifications", token(p, p.space(), "VIEWER", List.of("workspace:personal")), null).status()).isEqualTo(403);
        assertThat(raw("GET", base(p) + "/finance-notifications", "not-a-token", null).status()).isEqualTo(401);
    }

    @Test
    void historyIsPagedAndFilterableAndBadInputIsRejected() throws Exception {
        Biz b = biz();
        for (int i = 0; i < 25; i++) {
            clock.advance(java.time.Duration.ofSeconds(1));
            make(b, null, "REPORTS", i % 2 == 0 ? "ALERT" : "IMPORT", "p" + i);
        }
        JsonNode first = list(b, b.owner(), "OWNER", "?limit=10&offset=0");
        assertThat(first.path("total").asLong()).isEqualTo(25);
        assertThat(first.path("items")).hasSize(10);
        assertThat(first.path("items").get(0).path("title").asString()).as("newest first").isEqualTo("Title p24");
        assertThat(list(b, b.owner(), "OWNER", "?limit=10&offset=20").path("items")).hasSize(5);
        assertThat(list(b, b.owner(), "OWNER", "?category=import").path("total").asLong()).isEqualTo(12);
        assertThat(list(b, b.owner(), "OWNER", "?limit=1000").path("limit").asInt()).as("the page size is capped").isEqualTo(100);
        assertThat(as(b, b.owner(), "OWNER", "GET", "/finance-notifications?category=NOPE", null).status()).isEqualTo(422);
        String id = first.path("items").get(0).path("id").asString();
        as(b, b.owner(), "OWNER", "POST", "/finance-notifications/" + id + "/read", Map.of());
        assertThat(list(b, b.owner(), "OWNER", "?unread_only=true").path("total").asLong()).isEqualTo(24);
        assertThat(list(b, b.owner(), "OWNER", "?unread_only=true&category=ALERT&limit=5").path("items")).hasSize(5);
    }

    @Test
    void notificationsAreImmutableAndCarryNoSecretsOrFinancialRecords() throws Exception {
        Biz b = biz();
        make(b, null, "REPORTS", "ALERT", "x");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.sql("update notifications set title = 'changed' where tenant_id = :t").param("t", b.space()).update()).isNotNull();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.sql("delete from notifications where tenant_id = :t").param("t", b.space()).update()).isNotNull();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> notifications.create(b.space(), clock, null, "EVERYONE", "ALERT", "INFO", "t", null, null, "bad1")).isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> notifications.create(b.space(), clock, null, "REPORTS", "BOGUS", "INFO", "t", null, null, "bad2")).isInstanceOf(IllegalArgumentException.class);
        assertThat(notifications.create(b.space(), clock, null, "REPORTS", "ALERT", "INFO", "t".repeat(400), "b".repeat(900), Map.of(), "long")).isTrue();
        JsonNode n = list(b, b.owner(), "OWNER", "?limit=100").path("items").get(0);
        assertThat(n.path("title").asString().length()).isLessThanOrEqualTo(160);
        assertThat(n.path("body").asString().length()).isLessThanOrEqualTo(500);
    }
}
