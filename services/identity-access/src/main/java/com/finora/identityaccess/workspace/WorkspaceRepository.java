package com.finora.identityaccess.workspace;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** All workspace SQL. Every statement that touches workspace data is scoped by workspace id. */
@Repository
class WorkspaceRepository {

    record Mine(UUID workspaceId, UUID membershipId, String name, String type, String role) {
    }

    record Active(UUID workspaceId, UUID membershipId, UUID userId, String role, String type, String workspaceName) {
    }

    record Member(UUID membershipId, UUID userId, String role, String status, String displayName, String email,
            OffsetDateTime joinedAt) {
    }

    record Invitation(UUID id, UUID workspaceId, String email, String role, String status, UUID invitedBy,
            OffsetDateTime createdAt, OffsetDateTime expiresAt) {
    }

    private final JdbcClient jdbc;

    WorkspaceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ---- workspaces ----

    void insertWorkspace(UUID id, String name, UUID createdBy, OffsetDateTime now) {
        jdbc.sql("insert into workspaces (id, name, type, status, created_by, created_at, updated_at) values (:id, :n, 'BUSINESS', 'ACTIVE', :u, :now, :now)")
                .param("id", id).param("n", name).param("u", createdBy).param("now", now).update();
    }

    void renameWorkspace(UUID id, String name, OffsetDateTime now) {
        jdbc.sql("update workspaces set name = :n, updated_at = :now where id = :id")
                .param("n", name).param("now", now).param("id", id).update();
    }

    void lockWorkspace(UUID id) {
        jdbc.sql("select id from workspaces where id = :id for update").param("id", id).query(UUID.class).optional();
    }

    long countOwnedBusinessWorkspaces(UUID userId) {
        return jdbc.sql("""
                select count(*) from memberships m join workspaces w on w.id = m.workspace_id
                where m.user_id = :u and m.role = 'OWNER' and m.status = 'ACTIVE' and w.type = 'BUSINESS'
                """).param("u", userId).query(Long.class).single();
    }

    // ---- memberships ----

    List<Mine> listMine(UUID userId) {
        return jdbc.sql("""
                select w.id, m.id, w.name, w.type, m.role from memberships m join workspaces w on w.id = m.workspace_id
                where m.user_id = :u and m.status = 'ACTIVE' and w.status = 'ACTIVE' order by w.type, w.name, w.id
                """).param("u", userId)
                .query((rs, n) -> new Mine(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                        rs.getString(4), rs.getString(5)))
                .list();
    }

    /** The caller's live membership in an active workspace; empty means "no access" (same as a missing workspace). */
    Optional<Active> findActive(UUID userId, UUID workspaceId) {
        return jdbc.sql("""
                select m.workspace_id, m.id, m.user_id, m.role, w.type, w.name from memberships m
                join workspaces w on w.id = m.workspace_id
                where m.user_id = :u and m.workspace_id = :w and m.status = 'ACTIVE' and w.status = 'ACTIVE'
                """).param("u", userId).param("w", workspaceId)
                .query((rs, n) -> new Active(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getObject(3, UUID.class), rs.getString(4), rs.getString(5), rs.getString(6)))
                .optional();
    }

    void insertMembership(UUID id, UUID workspaceId, UUID userId, String role, UUID invitedBy, OffsetDateTime now) {
        jdbc.sql("insert into memberships (id, workspace_id, user_id, role, status, invited_by, created_at, updated_at) values (:id, :w, :u, :r, 'ACTIVE', :by, :now, :now)")
                .param("id", id).param("w", workspaceId).param("u", userId).param("r", role).param("by", invitedBy)
                .param("now", now).update();
    }

    private Member mapMember(ResultSet rs, int n) throws SQLException {
        return new Member(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getString(6), rs.getObject(7, OffsetDateTime.class));
    }

    List<Member> listMembers(UUID workspaceId) {
        return jdbc.sql("""
                select m.id, m.user_id, m.role, m.status, u.display_name, u.email, m.created_at
                from memberships m join users u on u.id = m.user_id
                where m.workspace_id = :w and m.status <> 'REMOVED' order by m.created_at, m.id
                """).param("w", workspaceId).query(this::mapMember).list();
    }

    /** A live member of exactly this workspace; a membership id from another workspace never matches. */
    Optional<Member> findMember(UUID workspaceId, UUID membershipId) {
        return jdbc.sql("""
                select m.id, m.user_id, m.role, m.status, u.display_name, u.email, m.created_at
                from memberships m join users u on u.id = m.user_id
                where m.workspace_id = :w and m.id = :m and m.status <> 'REMOVED'
                """).param("w", workspaceId).param("m", membershipId).query(this::mapMember).optional();
    }

    long countActiveOwners(UUID workspaceId) {
        return jdbc.sql("select count(*) from memberships where workspace_id = :w and role = 'OWNER' and status = 'ACTIVE'")
                .param("w", workspaceId).query(Long.class).single();
    }

    void updateRole(UUID workspaceId, UUID membershipId, String role, OffsetDateTime now) {
        jdbc.sql("update memberships set role = :r, updated_at = :now where id = :m and workspace_id = :w and status <> 'REMOVED'")
                .param("r", role).param("now", now).param("m", membershipId).param("w", workspaceId).update();
    }

    void removeMembership(UUID workspaceId, UUID membershipId, UUID by, OffsetDateTime now) {
        jdbc.sql("update memberships set status = 'REMOVED', removed_at = :now, removed_by = :by, updated_at = :now where id = :m and workspace_id = :w and status <> 'REMOVED'")
                .param("now", now).param("by", by).param("m", membershipId).param("w", workspaceId).update();
    }

    boolean hasLiveMembership(UUID workspaceId, UUID userId) {
        return jdbc.sql("select count(*) from memberships where workspace_id = :w and user_id = :u and status <> 'REMOVED'")
                .param("w", workspaceId).param("u", userId).query(Long.class).single() > 0;
    }

    Optional<UUID> userIdByEmail(String normalized) {
        return jdbc.sql("select id from users where email_normalized = :e").param("e", normalized).query(UUID.class).optional();
    }

    // ---- invitations ----

    private Invitation mapInvitation(ResultSet rs, int n) throws SQLException {
        return new Invitation(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getObject(6, UUID.class), rs.getObject(7, OffsetDateTime.class),
                rs.getObject(8, OffsetDateTime.class));
    }

    private static final String INV_COLS = "id, workspace_id, email_normalized, role, status, invited_by, created_at, expires_at";

    void insertInvitation(UUID id, UUID workspaceId, String email, String role, byte[] tokenHash, UUID by,
            OffsetDateTime now, OffsetDateTime expires) {
        jdbc.sql("insert into invitations (id, workspace_id, email_normalized, role, token_hash, status, invited_by, created_at, expires_at) values (:id, :w, :e, :r, :h, 'PENDING', :by, :now, :exp)")
                .param("id", id).param("w", workspaceId).param("e", email).param("r", role).param("h", tokenHash)
                .param("by", by).param("now", now).param("exp", expires).update();
    }

    /** Marks pending invitations of this workspace whose time has passed, freeing their (workspace, email) slot. */
    void expirePending(UUID workspaceId, OffsetDateTime now) {
        jdbc.sql("update invitations set status = 'EXPIRED', resolved_at = expires_at where workspace_id = :w and status = 'PENDING' and expires_at <= :now")
                .param("w", workspaceId).param("now", now).update();
    }

    List<Invitation> listInvitations(UUID workspaceId) {
        return jdbc.sql("select " + INV_COLS + " from invitations where workspace_id = :w order by created_at desc, id")
                .param("w", workspaceId).query(this::mapInvitation).list();
    }

    Optional<Invitation> findInvitation(UUID workspaceId, UUID id) {
        return jdbc.sql("select " + INV_COLS + " from invitations where workspace_id = :w and id = :id")
                .param("w", workspaceId).param("id", id).query(this::mapInvitation).optional();
    }

    Optional<Invitation> findInvitationByHash(byte[] hash) {
        return jdbc.sql("select " + INV_COLS + " from invitations where token_hash = :h")
                .param("h", hash).query(this::mapInvitation).optional();
    }

    /**
     * Atomically resolves a pending, unexpired invitation addressed to this email. Exactly one concurrent caller can
     * win; a wrong email, a wrong state or an expired invitation changes nothing.
     */
    Optional<Invitation> resolveByToken(byte[] hash, String email, String newStatus, UUID by, OffsetDateTime now) {
        return jdbc.sql("update invitations set status = :s, resolved_at = :now, resolved_by = :by where token_hash = :h and status = 'PENDING' and expires_at > :now and email_normalized = :e returning " + INV_COLS)
                .param("s", newStatus).param("now", now).param("by", by).param("h", hash).param("e", email)
                .query(this::mapInvitation).optional();
    }

    Optional<Invitation> revoke(UUID workspaceId, UUID id, UUID by, OffsetDateTime now) {
        return jdbc.sql("update invitations set status = 'REVOKED', resolved_at = :now, resolved_by = :by where workspace_id = :w and id = :id and status = 'PENDING' returning " + INV_COLS)
                .param("now", now).param("by", by).param("w", workspaceId).param("id", id)
                .query(this::mapInvitation).optional();
    }

    // ---- audit ----

    void insertAudit(UUID workspaceId, UUID actor, String type, String targetType, String targetId, String outcome,
            String detail, String requestId, OffsetDateTime now) {
        jdbc.sql("""
                insert into security_audit_events (id, workspace_id, actor_user_id, event_type, target_type, target_id, outcome, detail, request_id, created_at)
                values (:id, :w, :a, :t, :tt, :ti, :o, :d, :r, :now)
                """)
                .param("id", UUID.randomUUID()).param("w", workspaceId).param("a", actor).param("t", type)
                .param("tt", targetType).param("ti", targetId).param("o", outcome).param("d", detail)
                .param("r", requestId).param("now", now).update();
    }
}
