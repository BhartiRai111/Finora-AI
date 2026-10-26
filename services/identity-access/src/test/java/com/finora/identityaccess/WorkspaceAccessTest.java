package com.finora.identityaccess;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finora.testkit.TestTokens;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.system.CapturedOutput;
import tools.jackson.databind.JsonNode;

/**
 * Workspaces, memberships, invitations, roles and isolation against the real service and PostgreSQL. Requests carry
 * gateway-style verified tokens; the service decides everything else from database state.
 */
class WorkspaceAccessTest extends IdentityTestSupport {

    /** A business workspace with one account per role. Owner and Admin have MFA (mandatory for them). */
    record Team(UUID space, TU owner, TU admin, TU accountant, TU manager, TU contributor, TU auditor, TU viewer) {
        List<TU> all() {
            return List.of(owner, admin, accountant, manager, contributor, auditor, viewer);
        }
    }

    private Team team() throws Exception {
        TU owner = withMfa(newUser());
        UUID space = createWorkspace(owner, "Acme Traders");
        TU admin = withMfa(newUser());
        addMember(owner, space, admin, "ADMIN");
        TU accountant = newUser();
        TU manager = newUser();
        TU contributor = newUser();
        TU auditor = newUser();
        TU viewer = newUser();
        addMember(owner, space, accountant, "ACCOUNTANT");
        addMember(owner, space, manager, "MANAGER");
        addMember(owner, space, contributor, "CONTRIBUTOR");
        addMember(owner, space, auditor, "AUDITOR");
        addMember(owner, space, viewer, "VIEWER");
        return new Team(space, owner, admin, accountant, manager, contributor, auditor, viewer);
    }

    private static Map<String, String> neutral(Resp r) {
        return Map.of("status", String.valueOf(r.status()), "code", r.code(), "title", r.body().path("title").asString());
    }

    // ---- personal profile and workspace creation ----

    @Test
    void everyAccountStartsWithAPersonalProfileThatIsItsOwnTenant() throws Exception {
        TU u = newUser();
        JsonNode items = as(u, "GET", "/v1/workspaces", null).body().path("items");
        assertThat(items).hasSize(1);
        assertThat(items.get(0).path("type").asString()).isEqualTo("PERSONAL");
        assertThat(items.get(0).path("role").asString()).isEqualTo("OWNER");
        assertThat(jdbc.sql("select count(*) from workspaces where created_by = :u and type = 'PERSONAL'").param("u", u.userId)
                .query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    void personalProfilesCannotBeSharedRenamedOrTransferred() throws Exception {
        TU u = withMfa(newUser());
        UUID personal = UUID.fromString(as(u, "GET", "/v1/workspaces", null).body().path("items").get(0).path("id").asString());
        assertThat(inSpace(u, personal, "POST", "/invitations", Map.of("email", email(), "role", "VIEWER")).status()).isEqualTo(409);
        assertThat(inSpace(u, personal, "PATCH", "", Map.of("name", "X")).status()).isEqualTo(409);
        stepUp(u);
        assertThat(inSpace(u, personal, "POST", "/ownership-transfers", Map.of("to_membership_id", UUID.randomUUID())).status()).isEqualTo(409);
    }

    @Test
    void creatingAWorkspaceMakesTheCreatorItsOnlyOwner() throws Exception {
        TU u = newUser();
        UUID space = createWorkspace(u, "  Sharma & Sons  ");
        JsonNode items = as(u, "GET", "/v1/workspaces", null).body().path("items");
        assertThat(items).hasSize(2);
        assertThat(inSpace(u, space, "GET", "", null).body().path("name").asString()).isEqualTo("Sharma & Sons");
        assertThat(jdbc.sql("select count(*) from memberships where workspace_id = :w and role = 'OWNER' and status = 'ACTIVE'")
                .param("w", space).query(Long.class).single()).isEqualTo(1L);
        for (String bad : new String[] {"", "   ", "x".repeat(101), "tab\there"}) {
            assertThat(as(u, "POST", "/v1/workspaces", Map.of("name", bad)).status()).as(bad).isEqualTo(422);
        }
        assertThat(call("POST", "/v1/workspaces", anon(), Map.of("name", "Nope")).status()).isEqualTo(401);
    }

    @Test
    void renamingNeedsOwnerOrAdminWithMfa() throws Exception {
        Team t = team();
        assertThat(inSpace(t.owner, t.space, "PATCH", "", Map.of("name", "Renamed Ltd")).status()).isEqualTo(200);
        assertThat(inSpace(t.admin, t.space, "PATCH", "", Map.of("name", "Renamed Again")).status()).isEqualTo(200);
        for (TU u : List.of(t.accountant, t.manager, t.contributor, t.auditor, t.viewer)) {
            assertThat(inSpace(u, t.space, "PATCH", "", Map.of("name", "Hacked")).status()).isEqualTo(403);
        }
        assertThat(inSpace(t.owner, t.space, "GET", "", null).body().path("name").asString()).isEqualTo("Renamed Again");
    }

    // ---- mandatory MFA for Owner/Admin ----

    @Test
    void ownersAndAdminsMustUseMfaForWorkspaceAdministration() throws Exception {
        TU owner = newUser(); // no MFA yet
        UUID space = createWorkspace(owner, "No MFA Ltd");
        for (Resp r : List.of(inSpace(owner, space, "GET", "/members", null), inSpace(owner, space, "PATCH", "", Map.of("name", "X")),
                inSpace(owner, space, "POST", "/invitations", Map.of("email", email(), "role", "VIEWER")))) {
            assertThat(r.status()).isEqualTo(403);
            assertThat(r.code()).isEqualTo("MFA_REQUIRED");
        }
        withMfa(owner);
        assertThat(inSpace(owner, space, "GET", "/members", null).status()).isEqualTo(200);
    }

    @Test
    void anAdminWhoNeverEnrolledMfaCannotAdminister() throws Exception {
        TU owner = withMfa(newUser());
        UUID space = createWorkspace(owner, "Mixed Ltd");
        TU admin = newUser();
        addMember(owner, space, admin, "ADMIN");
        assertThat(inSpace(admin, space, "GET", "/members", null).code()).isEqualTo("MFA_REQUIRED");
        assertThat(inSpace(admin, space, "GET", "", null).status()).as("can still see the workspace and switch away").isEqualTo(200);
    }

    // ---- permissions per role ----

    @Test
    void memberAdministrationFollowsThePermissionMatrixForEveryRole() throws Exception {
        Team t = team();
        Map<String, Integer> listExpected = Map.of("OWNER", 200, "ADMIN", 200, "AUDITOR", 200, "ACCOUNTANT", 403,
                "MANAGER", 403, "CONTRIBUTOR", 403, "VIEWER", 403);
        List<String> names = List.of("OWNER", "ADMIN", "ACCOUNTANT", "MANAGER", "CONTRIBUTOR", "AUDITOR", "VIEWER");
        for (int i = 0; i < names.size(); i++) {
            TU u = t.all().get(i);
            assertThat(inSpace(u, t.space, "GET", "/members", null).status()).as("list members as " + names.get(i))
                    .isEqualTo(listExpected.get(names.get(i)));
            assertThat(inSpace(u, t.space, "GET", "/invitations", null).status()).as("list invitations as " + names.get(i))
                    .isEqualTo(listExpected.get(names.get(i)));
            boolean mayInvite = names.get(i).equals("OWNER") || names.get(i).equals("ADMIN");
            Resp inv = inSpace(u, t.space, "POST", "/invitations", Map.of("email", email(), "role", "VIEWER"));
            assertThat(inv.status()).as("invite as " + names.get(i)).isEqualTo(mayInvite ? 201 : 403);
        }
    }

    @Test
    void anAdminCanGrantOnlyUpToAccountantLevel() throws Exception {
        Team t = team();
        for (String ok : List.of("ACCOUNTANT", "MANAGER", "CONTRIBUTOR", "AUDITOR", "VIEWER")) {
            assertThat(inSpace(t.admin, t.space, "POST", "/invitations", Map.of("email", email(), "role", ok)).status()).as(ok).isEqualTo(201);
        }
        for (String no : List.of("ADMIN", "OWNER")) {
            assertThat(inSpace(t.admin, t.space, "POST", "/invitations", Map.of("email", email(), "role", no)).status()).as(no).isEqualTo(403);
        }
        assertThat(inSpace(t.owner, t.space, "POST", "/invitations", Map.of("email", email(), "role", "OWNER")).status()).isEqualTo(201);
        assertThat(inSpace(t.owner, t.space, "POST", "/invitations", Map.of("email", email(), "role", "SUPERUSER")).status()).isEqualTo(422);
    }

    // ---- role changes ----

    @Test
    void roleChangesNeedStepUpAndApplyOnTheNextRequest() throws Exception {
        Team t = team();
        UUID mid = membershipId(t.contributor, t.space);
        Resp noStepUp = inSpace(t.owner, t.space, "PATCH", "/members/" + mid, Map.of("role", "ACCOUNTANT"));
        assertThat(noStepUp.status()).isEqualTo(403);
        assertThat(noStepUp.code()).isEqualTo("STEP_UP_REQUIRED");
        stepUp(t.owner);
        assertThat(inSpace(t.owner, t.space, "PATCH", "/members/" + mid, Map.of("role", "ACCOUNTANT")).status()).isEqualTo(200);
        assertThat(membershipRole(t.contributor, t.space)).isEqualTo("ACCOUNTANT");
        clock.advance(Duration.ofMinutes(11));
        assertThat(inSpace(t.owner, t.space, "PATCH", "/members/" + mid, Map.of("role", "VIEWER")).code())
                .as("step-up expires after 10 minutes").isEqualTo("STEP_UP_REQUIRED");
    }

    private String membershipRole(TU u, UUID space) throws Exception {
        for (JsonNode w : as(u, "GET", "/v1/workspaces", null).body().path("items")) {
            if (w.path("id").asString().equals(space.toString())) {
                return w.path("role").asString();
            }
        }
        return null;
    }

    @Test
    void roleChangeRulesPreventEscalation() throws Exception {
        Team t = team();
        UUID ownerMid = membershipId(t.owner, t.space);
        UUID adminMid = membershipId(t.admin, t.space);
        UUID viewerMid = membershipId(t.viewer, t.space);
        UUID contributorMid = membershipId(t.contributor, t.space);
        stepUp(t.admin);
        stepUp(t.owner);
        assertThat(inSpace(t.admin, t.space, "PATCH", "/members/" + viewerMid, Map.of("role", "ADMIN")).status()).as("admin grants admin").isEqualTo(403);
        assertThat(inSpace(t.admin, t.space, "PATCH", "/members/" + viewerMid, Map.of("role", "OWNER")).status()).isEqualTo(403);
        assertThat(inSpace(t.admin, t.space, "PATCH", "/members/" + ownerMid, Map.of("role", "VIEWER")).status()).as("admin demotes owner").isEqualTo(403);
        assertThat(inSpace(t.admin, t.space, "PATCH", "/members/" + adminMid, Map.of("role", "VIEWER")).status()).as("admin changes own role").isEqualTo(403);
        assertThat(inSpace(t.owner, t.space, "PATCH", "/members/" + ownerMid, Map.of("role", "VIEWER")).status()).as("owner changes own role").isEqualTo(403);
        assertThat(inSpace(t.admin, t.space, "PATCH", "/members/" + viewerMid, Map.of("role", "AUDITOR")).status()).isEqualTo(200);
        // A contributor cannot grant themselves anything, even with a valid session and a forged workspace header.
        assertThat(call("PATCH", "/v1/spaces/" + t.space + "/members/" + contributorMid, tokenFor(t.contributor, t.space),
                Map.of("role", "OWNER"), "X-Workspace-Role", "OWNER").status()).isEqualTo(403);
        assertThat(membershipRole(t.contributor, t.space)).isEqualTo("CONTRIBUTOR");
        assertThat(inSpace(t.owner, t.space, "PATCH", "/members/" + viewerMid, Map.of("role", "NOT_A_ROLE")).status()).isEqualTo(422);
    }

    @Test
    void ownerOrAdminRolesGoOnlyToMembersWhoHaveEnabledMfa() throws Exception {
        Team t = team();
        stepUp(t.owner);
        UUID viewerMid = membershipId(t.viewer, t.space);
        Resp r = inSpace(t.owner, t.space, "PATCH", "/members/" + viewerMid, Map.of("role", "ADMIN"));
        assertThat(r.status()).isEqualTo(409);
        assertThat(r.body().path("detail").asString()).contains("multi-factor");
        withMfa(t.viewer);
        stepUp(t.owner);
        assertThat(inSpace(t.owner, t.space, "PATCH", "/members/" + viewerMid, Map.of("role", "ADMIN")).status()).isEqualTo(200);
    }

    // ---- removal, leaving, last owner ----

    @Test
    void removingAMemberCutsAccessOnTheVeryNextRequest() throws Exception {
        Team t = team();
        assertThat(inSpace(t.contributor, t.space, "GET", "", null).status()).isEqualTo(200);
        UUID mid = membershipId(t.contributor, t.space);
        assertThat(inSpace(t.owner, t.space, "DELETE", "/members/" + mid, null).status()).isEqualTo(204);
        // Same live session, same still-valid token: the database says no.
        assertThat(inSpace(t.contributor, t.space, "GET", "", null).status()).isEqualTo(404);
        assertThat(as(t.contributor, "GET", "/v1/workspaces", null).body().path("items")).hasSize(1);
        Resp validate = call("POST", "/internal/v1/sessions/validate", gatewayService(), Map.of("session_token", t.contributor.cookie));
        assertThat(validate.body().path("valid").asBoolean()).isTrue();
        List<String> spaces = new ArrayList<>();
        validate.body().path("memberships").forEach(m -> spaces.add(m.path("workspace_id").asString()));
        assertThat(spaces).doesNotContain(t.space.toString());
        assertThat(jdbc.sql("select status from memberships where id = :m").param("m", mid).query(String.class).single()).isEqualTo("REMOVED");
    }

    @Test
    void anAdminCannotRemoveOwnersOrAdminsButCanRemoveLowerRoles() throws Exception {
        Team t = team();
        assertThat(inSpace(t.admin, t.space, "DELETE", "/members/" + membershipId(t.owner, t.space), null).status()).isEqualTo(403);
        TU admin2 = withMfa(newUser());
        addMember(t.owner, t.space, admin2, "ADMIN");
        assertThat(inSpace(t.admin, t.space, "DELETE", "/members/" + membershipId(admin2, t.space), null).status()).isEqualTo(403);
        assertThat(inSpace(t.admin, t.space, "DELETE", "/members/" + membershipId(t.viewer, t.space), null).status()).isEqualTo(204);
        assertThat(inSpace(t.accountant, t.space, "DELETE", "/members/" + membershipId(t.manager, t.space), null).status()).isEqualTo(403);
    }

    @Test
    void membersCanLeaveButTheLastOwnerCannot() throws Exception {
        Team t = team();
        assertThat(inSpace(t.viewer, t.space, "DELETE", "/members/" + membershipId(t.viewer, t.space), null).status()).isEqualTo(204);
        assertThat(inSpace(t.viewer, t.space, "GET", "", null).status()).isEqualTo(404);
        Resp last = inSpace(t.owner, t.space, "DELETE", "/members/" + membershipId(t.owner, t.space), null);
        assertThat(last.status()).isEqualTo(409);
        assertThat(inSpace(t.owner, t.space, "GET", "", null).status()).isEqualTo(200);
    }

    @Test
    void aWorkspaceNeverEndsUpWithoutAnOwnerUnderConcurrentLeaves() throws Exception {
        TU a = withMfa(newUser());
        UUID space = createWorkspace(a, "Two Owners Ltd");
        TU b = withMfa(newUser());
        addMember(a, space, b, "OWNER");
        UUID midA = membershipId(a, space);
        UUID midB = membershipId(b, space);
        List<Integer> statuses = concurrently(
                () -> inSpace(a, space, "DELETE", "/members/" + midA, null).status(),
                () -> inSpace(b, space, "DELETE", "/members/" + midB, null).status());
        assertThat(statuses).containsExactlyInAnyOrder(204, 409);
        assertThat(activeOwners(space)).isEqualTo(1L);
    }

    @Test
    void aWorkspaceNeverEndsUpWithoutAnOwnerWhenOwnersDemoteEachOtherAtOnce() throws Exception {
        TU a = withMfa(newUser());
        UUID space = createWorkspace(a, "Duel Ltd");
        TU b = withMfa(newUser());
        addMember(a, space, b, "OWNER");
        UUID midA = membershipId(a, space);
        UUID midB = membershipId(b, space);
        stepUp(a);
        stepUp(b);
        List<Integer> statuses = concurrently(
                () -> inSpace(a, space, "PATCH", "/members/" + midB, Map.of("role", "ADMIN")).status(),
                () -> inSpace(b, space, "PATCH", "/members/" + midA, Map.of("role", "ADMIN")).status());
        assertThat(statuses).containsOnlyOnce(200);
        assertThat(statuses.stream().filter(s -> s != 200).toList()).hasSize(1);
        assertThat(activeOwners(space)).isEqualTo(1L);
    }

    private long activeOwners(UUID space) {
        return jdbc.sql("select count(*) from memberships where workspace_id = :w and role = 'OWNER' and status = 'ACTIVE'")
                .param("w", space).query(Long.class).single();
    }

    private static List<Integer> concurrently(Callable<Integer> first, Callable<Integer> second) throws Exception {
        return concurrentlyList(List.of(first, second));
    }

    private static List<Integer> concurrentlyList(List<Callable<Integer>> calls) throws Exception {
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

    // ---- ownership transfer ----

    @Test
    void ownershipTransferNeedsStepUpAnMfaEnabledTargetAndKeepsAnOwner() throws Exception {
        Team t = team();
        UUID adminMid = membershipId(t.admin, t.space);
        UUID viewerMid = membershipId(t.viewer, t.space);
        assertThat(inSpace(t.owner, t.space, "POST", "/ownership-transfers", Map.of("to_membership_id", adminMid)).code()).isEqualTo("STEP_UP_REQUIRED");
        stepUp(t.owner);
        assertThat(inSpace(t.owner, t.space, "POST", "/ownership-transfers", Map.of("to_membership_id", viewerMid)).status())
                .as("target has no MFA").isEqualTo(409);
        assertThat(inSpace(t.owner, t.space, "POST", "/ownership-transfers", Map.of("to_membership_id", membershipId(t.owner, t.space))).status())
                .as("to self").isEqualTo(422);
        assertThat(inSpace(t.owner, t.space, "POST", "/ownership-transfers", Map.of("to_membership_id", UUID.randomUUID())).status()).isEqualTo(404);
        assertThat(inSpace(t.admin, t.space, "POST", "/ownership-transfers", Map.of("to_membership_id", adminMid)).status()).as("admin cannot").isEqualTo(403);

        Resp ok = inSpace(t.owner, t.space, "POST", "/ownership-transfers", Map.of("to_membership_id", adminMid));
        assertThat(ok.status()).as(ok.raw().body()).isEqualTo(200);
        assertThat(membershipRole(t.admin, t.space)).isEqualTo("OWNER");
        assertThat(membershipRole(t.owner, t.space)).isEqualTo("ADMIN");
        assertThat(activeOwners(t.space)).isEqualTo(1L);
        assertThat(inSpace(t.owner, t.space, "POST", "/ownership-transfers", Map.of("to_membership_id", adminMid)).status())
                .as("former owner can no longer transfer").isEqualTo(403);
    }

    // ---- invitations ----

    @Test
    void invitationsAreSecretSingleUseAndStoredHashed() throws Exception {
        Team t = team();
        String email = email();
        Resp inv = inSpace(t.owner, t.space, "POST", "/invitations", Map.of("email", email.toUpperCase(), "role", "VIEWER"));
        assertThat(inv.status()).isEqualTo(201);
        assertThat(inv.body().path("delivery").asString()).isEqualTo("SENT");
        String token = invitations.last(email).token();
        assertThat(inv.raw().body()).doesNotContain(token);
        assertThat(inSpace(t.owner, t.space, "GET", "/invitations", null).raw().body()).doesNotContain(token);
        byte[] stored = jdbc.sql("select token_hash from invitations where id = :id").param("id", UUID.fromString(inv.body().path("id").asString()))
                .query(byte[].class).single();
        assertThat(stored).isEqualTo(MessageDigest.getInstance("SHA-256").digest(token.getBytes()));
        assertThat(token.length()).isGreaterThanOrEqualTo(43);
    }

    @Test
    void invitingDuplicatesAndExistingMembersIsRefused() throws Exception {
        Team t = team();
        String email = email();
        assertThat(inSpace(t.owner, t.space, "POST", "/invitations", Map.of("email", email, "role", "VIEWER")).status()).isEqualTo(201);
        assertThat(inSpace(t.owner, t.space, "POST", "/invitations", Map.of("email", email, "role", "AUDITOR")).status()).isEqualTo(409);
        assertThat(inSpace(t.owner, t.space, "POST", "/invitations", Map.of("email", t.viewer.email, "role", "AUDITOR")).status()).isEqualTo(409);
        assertThat(inSpace(t.owner, t.space, "POST", "/invitations", Map.of("email", "not-an-email", "role", "VIEWER")).status()).isEqualTo(422);
    }

    @Test
    void anInvitationBelongsToItsRecipientOnly() throws Exception {
        Team t = team();
        TU invitee = newUser();
        TU intruder = newUser();
        inSpace(t.owner, t.space, "POST", "/invitations", Map.of("email", invitee.email, "role", "AUDITOR"));
        String token = invitations.last(invitee.email).token();
        Resp wrong = as(intruder, "POST", "/v1/invitations/accept", Map.of("token", token));
        assertThat(wrong.status()).isEqualTo(400);
        assertThat(wrong.body().path("detail").asString()).isEqualTo("Invalid or expired invitation");
        assertThat(as(intruder, "GET", "/v1/workspaces", null).body().path("items")).hasSize(1);
        // The failed attempt did not burn the invitation.
        assertThat(as(invitee, "POST", "/v1/invitations/accept", Map.of("token", token)).status()).isEqualTo(200);
        assertThat(membershipRole(invitee, t.space)).isEqualTo("AUDITOR");
        assertThat(call("POST", "/v1/invitations/accept", anon(), Map.of("token", token)).status()).isEqualTo(401);
    }

    @Test
    void invitationsCannotBeReusedExpiredRevokedOrGuessed() throws Exception {
        Team t = team();
        TU a = newUser();
        inSpace(t.owner, t.space, "POST", "/invitations", Map.of("email", a.email, "role", "VIEWER"));
        String tokenA = invitations.last(a.email).token();
        assertThat(as(a, "POST", "/v1/invitations/accept", Map.of("token", tokenA)).status()).isEqualTo(200);
        assertThat(as(a, "POST", "/v1/invitations/accept", Map.of("token", tokenA)).status()).as("reuse").isEqualTo(400);

        TU b = newUser();
        inSpace(t.owner, t.space, "POST", "/invitations", Map.of("email", b.email, "role", "VIEWER"));
        String tokenB = invitations.last(b.email).token();
        clock.advance(Duration.ofDays(8));
        assertThat(as(b, "POST", "/v1/invitations/accept", Map.of("token", tokenB)).status()).as("expired").isEqualTo(400);
        assertThat(inSpace(t.owner, t.space, "GET", "/invitations", null).raw().body()).contains("EXPIRED");

        TU c = newUser();
        Resp invC = inSpace(t.owner, t.space, "POST", "/invitations", Map.of("email", c.email, "role", "VIEWER"));
        String tokenC = invitations.last(c.email).token();
        assertThat(inSpace(t.owner, t.space, "DELETE", "/invitations/" + invC.body().path("id").asString(), null).status()).isEqualTo(204);
        assertThat(as(c, "POST", "/v1/invitations/accept", Map.of("token", tokenC)).status()).as("revoked").isEqualTo(400);
        assertThat(inSpace(t.owner, t.space, "DELETE", "/invitations/" + invC.body().path("id").asString(), null).status()).as("already revoked").isEqualTo(409);

        for (String guess : new String[] {"garbage", "", "a".repeat(500), UUID.randomUUID().toString()}) {
            assertThat(as(c, "POST", "/v1/invitations/accept", Map.of("token", guess)).status()).as(guess).isEqualTo(400);
        }
        // A fresh invitation to the same address works after the earlier one expired.
        assertThat(inSpace(t.owner, t.space, "POST", "/invitations", Map.of("email", b.email, "role", "VIEWER")).status()).isEqualTo(201);
    }

    @Test
    void decliningAnInvitationEndsItWithoutCreatingAMembership() throws Exception {
        Team t = team();
        TU u = newUser();
        inSpace(t.owner, t.space, "POST", "/invitations", Map.of("email", u.email, "role", "VIEWER"));
        String token = invitations.last(u.email).token();
        assertThat(as(u, "POST", "/v1/invitations/decline", Map.of("token", token)).status()).isEqualTo(204);
        assertThat(as(u, "POST", "/v1/invitations/accept", Map.of("token", token)).status()).isEqualTo(400);
        assertThat(as(u, "GET", "/v1/workspaces", null).body().path("items")).hasSize(1);
    }

    @Test
    void concurrentAcceptsOfOneInvitationCreateExactlyOneMembership() throws Exception {
        Team t = team();
        TU u = newUser();
        inSpace(t.owner, t.space, "POST", "/invitations", Map.of("email", u.email, "role", "VIEWER"));
        String token = invitations.last(u.email).token();
        List<Callable<Integer>> calls = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            calls.add(() -> as(u, "POST", "/v1/invitations/accept", Map.of("token", token)).status());
        }
        List<Integer> statuses = concurrentlyList(calls);
        assertThat(statuses.stream().filter(s -> s == 200).count()).isEqualTo(1);
        assertThat(statuses.stream().filter(s -> s != 200)).allMatch(s -> s == 400);
        assertThat(jdbc.sql("select count(*) from memberships where workspace_id = :w and user_id = :u and status <> 'REMOVED'")
                .param("w", t.space).param("u", u.userId).query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    void thePendingInvitationLimitHoldsEvenForConcurrentInvites() throws Exception {
        Team t = team();
        String email = email();
        List<Callable<Integer>> calls = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            calls.add(() -> inSpace(t.owner, t.space, "POST", "/invitations", Map.of("email", email, "role", "VIEWER")).status());
        }
        List<Integer> statuses = concurrentlyList(calls);
        assertThat(statuses.stream().filter(s -> s == 201).count()).isEqualTo(1);
        assertThat(statuses.stream().filter(s -> s != 201)).allMatch(s -> s == 409);
    }

    // ---- isolation ----

    @Test
    void foreignAndNonExistentWorkspacesAreIndistinguishable() throws Exception {
        Team t = team();
        TU outsider = newUser();
        UUID missing = UUID.randomUUID();
        for (String suffix : List.of("", "/members", "/invitations")) {
            Resp foreign = inSpace(outsider, t.space, "GET", suffix, null);
            Resp nothing = inSpace(outsider, missing, "GET", suffix, null);
            assertThat(foreign.status()).as(suffix).isEqualTo(404);
            assertThat(neutral(foreign)).as(suffix).isEqualTo(neutral(nothing));
            assertThat(foreign.raw().body().replaceAll("\"request_id\":\"[^\"]+\"", "")).isEqualTo(nothing.raw().body().replaceAll("\"request_id\":\"[^\"]+\"", ""));
        }
        assertThat(inSpace(outsider, t.space, "POST", "/invitations", Map.of("email", email(), "role", "VIEWER")).status()).isEqualTo(404);
        assertThat(inSpace(outsider, t.space, "PATCH", "", Map.of("name", "Mine now")).status()).isEqualTo(404);
    }

    @Test
    void manipulatedIdsFromAnotherWorkspaceNeverMatch() throws Exception {
        Team a = team();
        Team b = team();
        UUID foreignMember = membershipId(b.viewer, b.space);
        stepUp(a.owner);
        assertThat(inSpace(a.owner, a.space, "PATCH", "/members/" + foreignMember, Map.of("role", "OWNER")).status()).isEqualTo(404);
        assertThat(inSpace(a.owner, a.space, "DELETE", "/members/" + foreignMember, null).status()).isEqualTo(404);
        assertThat(inSpace(a.owner, a.space, "POST", "/ownership-transfers", Map.of("to_membership_id", foreignMember)).status()).isEqualTo(404);
        Resp invB = inSpace(b.owner, b.space, "POST", "/invitations", Map.of("email", email(), "role", "VIEWER"));
        assertThat(inSpace(a.owner, a.space, "DELETE", "/invitations/" + invB.body().path("id").asString(), null).status()).isEqualTo(404);
        assertThat(membershipRole(b.viewer, b.space)).isEqualTo("VIEWER");
        // The owner of A is not a member of B: B's data is invisible even with a token naming B.
        assertThat(inSpace(a.owner, b.space, "GET", "/members", null).status()).isEqualTo(404);
        // A token scoped to A cannot be replayed against B's path.
        Resp crossed = call("GET", "/v1/spaces/" + b.space + "/members", tokenFor(a.owner, a.space), null);
        assertThat(crossed.status()).isEqualTo(404);
        assertThat(crossed.header("X-Internal-Auth")).isEqualTo("rejected");
    }

    @Test
    void forgedIdentityAndWorkspaceHeadersChangeNothing() throws Exception {
        Team t = team();
        TU outsider = newUser();
        Resp r = call("GET", "/v1/spaces/" + t.space + "/members", tokenFor(outsider, t.space), null,
                "X-User-Id", t.owner.userId.toString(), "X-Tenant-Id", t.space.toString(), "X-Workspace-Id", t.space.toString(),
                "X-User-Role", "OWNER", "X-Forwarded-User", t.owner.email);
        assertThat(r.status()).isEqualTo(404);
    }

    @Test
    void directCallsWithoutAProperUserSessionAreRefused() throws Exception {
        Team t = team();
        String path = "/v1/spaces/" + t.space + "/members";
        assertThat(call("GET", path, anon(), null).status()).as("anonymous caller").isEqualTo(401);
        assertThat(call("GET", path, TestTokens.issuer(TestTokens.CALLER, Duration.ofMinutes(2), clock).issueService("identity-access", null), null).status()).isEqualTo(403);
        assertThat(call("GET", path, "garbage", null).status()).isEqualTo(401);
        // A token for a user whose session has been revoked is useless although its signature and expiry are fine.
        String stale = tokenFor(t.owner, t.space);
        assertThat(call("POST", "/v1/auth/logout", tokenFor(t.owner, null), null).status()).isEqualTo(204);
        assertThat(call("GET", path, stale, null).status()).isEqualTo(401);
        assertThat(call("GET", "/v1/workspaces", stale, null).status()).isEqualTo(401);
    }

    // ---- audit ----

    @Test
    void securitySensitiveActionsAreAuditedIncludingDeniedAttempts() throws Exception {
        Team t = team();
        UUID mid = membershipId(t.contributor, t.space);
        inSpace(t.contributor, t.space, "GET", "/members", null); // denied
        stepUp(t.owner);
        inSpace(t.owner, t.space, "PATCH", "/members/" + mid, Map.of("role", "VIEWER"));
        inSpace(t.owner, t.space, "DELETE", "/members/" + mid, null);
        List<String> events = jdbc.sql("select event_type || ':' || outcome from security_audit_events where workspace_id = :w order by created_at, id")
                .param("w", t.space).query(String.class).list();
        assertThat(events).contains("WORKSPACE_CREATED:SUCCESS", "MEMBER_INVITED:SUCCESS", "INVITATION_ACCEPTED:SUCCESS",
                "MEMBER_ADDED:SUCCESS", "MEMBER_LIST_DENIED:DENIED", "MEMBER_ROLE_CHANGED:SUCCESS", "MEMBER_REMOVED:SUCCESS");
        assertThat(jdbc.sql("select actor_user_id from security_audit_events where workspace_id = :w and event_type = 'MEMBER_LIST_DENIED'")
                .param("w", t.space).query(UUID.class).single()).isEqualTo(t.contributor.userId);
    }

    @Test
    void failedInvitationAttemptsAreAuditedWithoutTheToken() throws Exception {
        Team t = team();
        TU u = newUser();
        inSpace(t.owner, t.space, "POST", "/invitations", Map.of("email", email(), "role", "VIEWER"));
        as(u, "POST", "/v1/invitations/accept", Map.of("token", "a-secret-looking-token-value-that-is-not-real"));
        List<String> rows = jdbc.sql("select coalesce(detail, '') || coalesce(target_id, '') from security_audit_events where actor_user_id = :u")
                .param("u", u.userId).query(String.class).list();
        assertThat(rows).isNotEmpty().allSatisfy(r -> assertThat(r).doesNotContain("a-secret-looking-token"));
        assertThat(jdbc.sql("select count(*) from security_audit_events where actor_user_id = :u and event_type = 'INVITATION_ACCEPTED' and outcome = 'DENIED'")
                .param("u", u.userId).query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    void theAuditLogIsAppendOnlyForTheRuntimeRole() throws Exception {
        Team t = team();
        assertThatThrownBy(() -> jdbc.sql("update security_audit_events set outcome = 'SUCCESS' where workspace_id = :w").param("w", t.space).update())
                .rootCause().hasMessageContaining("permission denied");
        assertThatThrownBy(() -> jdbc.sql("delete from security_audit_events where workspace_id = :w").param("w", t.space).update())
                .rootCause().hasMessageContaining("permission denied");
        assertThatThrownBy(() -> jdbc.sql("truncate security_audit_events").update()).rootCause().hasMessageContaining("permission denied");
    }

    @Test
    void invitationTokensNeverReachTheLogs(CapturedOutput output) throws Exception {
        Team t = team();
        TU u = newUser();
        inSpace(t.owner, t.space, "POST", "/invitations", Map.of("email", u.email, "role", "VIEWER"));
        String token = invitations.last(u.email).token();
        as(u, "POST", "/v1/invitations/accept", Map.of("token", token));
        as(newUser(), "POST", "/v1/invitations/accept", Map.of("token", token));
        assertThat(output.getAll()).doesNotContain(token);
    }

    // ---- database constraints ----

    @Test
    void databaseConstraintsProtectMembershipIntegrity() throws Exception {
        Team t = team();
        UUID someMember = t.viewer.userId;
        assertThatThrownBy(() -> jdbc.sql("insert into memberships (id, workspace_id, user_id, role, status, created_at, updated_at) values (:id, :w, :u, 'VIEWER', 'ACTIVE', now(), now())")
                .param("id", UUID.randomUUID()).param("w", t.space).param("u", someMember).update()).hasMessageContaining("memberships_live_idx");
        assertThatThrownBy(() -> jdbc.sql("update memberships set role = 'GOD' where workspace_id = :w").param("w", t.space).update())
                .hasMessageContaining("memberships_role_chk");
        assertThatThrownBy(() -> jdbc.sql("insert into workspaces (id, name, type, created_by, created_at, updated_at) values (:id, 'Second', 'PERSONAL', :u, now(), now())")
                .param("id", UUID.randomUUID()).param("u", t.owner.userId).update()).hasMessageContaining("workspaces_one_personal_per_user_idx");
        assertThatThrownBy(() -> jdbc.sql("insert into invitations (id, workspace_id, email_normalized, role, token_hash, invited_by, created_at, expires_at) values (:id, :w, 'x@example.test', 'VIEWER', :h, :u, now(), now() - interval '1 day')")
                .param("id", UUID.randomUUID()).param("w", t.space).param("h", new byte[32]).param("u", t.owner.userId).update())
                .hasMessageContaining("invitations_expiry_chk");
        assertThatThrownBy(() -> jdbc.sql("insert into memberships (id, workspace_id, user_id, role, status, created_at, updated_at) values (:id, :w, :u, 'VIEWER', 'ACTIVE', now(), now())")
                .param("id", UUID.randomUUID()).param("w", UUID.randomUUID()).param("u", newUserId()).update()).hasMessageContaining("foreign key");
    }

    private UUID newUserId() throws Exception {
        return newUser().userId;
    }

    // ---- profile ----

    @Test
    void profileCanBeUpdatedWithValidationAndRequiresASession() throws Exception {
        TU u = newUser();
        Resp ok = as(u, "PATCH", "/v1/me", Map.of("display_name", "  Asha Rao "));
        assertThat(ok.status()).isEqualTo(200);
        assertThat(ok.body().path("display_name").asString()).isEqualTo("Asha Rao");
        assertThat(as(u, "GET", "/v1/me", null).body().path("display_name").asString()).isEqualTo("Asha Rao");
        assertThat(as(u, "PATCH", "/v1/me", Map.of("display_name", "")).status()).isEqualTo(422);
        assertThat(as(u, "PATCH", "/v1/me", Map.of("display_name", "x".repeat(101))).status()).isEqualTo(422);
        assertThat(call("PATCH", "/v1/me", anon(), Map.of("display_name", "Evil")).status()).isEqualTo(401);
    }
}
