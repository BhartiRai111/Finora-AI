package com.finora.identityaccess.workspace;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.finora.commons.authz.Action;
import com.finora.commons.authz.PermissionMatrix;
import com.finora.commons.authz.Resource;
import com.finora.commons.authz.Role;
import com.finora.commons.correlation.CorrelationId;
import com.finora.commons.error.ApiException;
import com.finora.commons.error.ErrorCode;
import com.finora.identityaccess.auth.AuthService;
import com.finora.identityaccess.auth.Tokens;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Workspaces, memberships, invitations and ownership. Every operation re-reads the caller's session and membership from
 * the database (never trusting the token's claims alone), applies the documented permission matrix with default deny,
 * and writes audit events in the same transaction as the change. Membership changes for one workspace are serialized by
 * a row lock on the workspace, which is what makes the last-Owner rule hold under concurrency.
 */
public class WorkspaceService {

    private static final Pattern EMAIL = Pattern.compile("^[A-Za-z0-9._%+'-]{1,64}@[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,63}$");
    private static final String INVALID_INVITATION = "Invalid or expired invitation";

    // ---- views ----

    public record WorkspaceView(UUID id, String name, String type, String role,
            @JsonProperty("membership_id") UUID membershipId) {
    }

    public record MemberView(@JsonProperty("membership_id") UUID membershipId, @JsonProperty("user_id") UUID userId,
            String role, String status, @JsonProperty("display_name") String displayName, String email,
            @JsonProperty("joined_at") OffsetDateTime joinedAt) {
    }

    public record InvitationView(UUID id, String email, String role, String status,
            @JsonProperty("created_at") OffsetDateTime createdAt, @JsonProperty("expires_at") OffsetDateTime expiresAt,
            String delivery) {
    }

    /** The authenticated caller, resolved from the live session. */
    public record Actor(UUID userId, UUID sessionId, OffsetDateTime mfaVerifiedAt, OffsetDateTime stepUpUntil) {
    }

    private final WorkspaceRepository repo;
    private final AuthService auth;
    private final InvitationNotifier notifier;
    private final WorkspaceProperties props;
    private final TransactionTemplate tx;
    private final TransactionTemplate auditTx;
    private final Clock clock;

    public WorkspaceService(WorkspaceRepository repo, AuthService auth, InvitationNotifier notifier,
            WorkspaceProperties props, PlatformTransactionManager manager, Clock clock) {
        this.repo = repo;
        this.auth = auth;
        this.notifier = notifier;
        this.props = props;
        this.tx = new TransactionTemplate(manager);
        // Denied attempts are recorded even though the request itself fails.
        this.auditTx = new TransactionTemplate(manager);
        this.auditTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    public Actor actor(UUID userId, UUID sessionId) {
        AuthService.ActiveSession s = auth.requireActiveSession(sessionId, false);
        if (!s.userId().equals(userId)) {
            throw new ApiException(ErrorCode.AUTH_REQUIRED, null);
        }
        return new Actor(userId, sessionId, s.mfaVerifiedAt(), s.stepUpUntil());
    }

    // ---- authorization ----

    private void audit(UUID workspaceId, UUID actor, String type, String targetType, String targetId, String outcome, String detail) {
        repo.insertAudit(workspaceId, actor, type, targetType, targetId, outcome, detail, CorrelationId.current(), now());
    }

    private void auditDenied(UUID workspaceId, Actor actor, String type, String targetType, String targetId, String why) {
        auditTx.executeWithoutResult(s -> audit(workspaceId, actor.userId(), type, targetType, targetId, "DENIED", why));
    }

    /** Membership in an active workspace, or a neutral 404 that is identical for foreign and non-existent workspaces. */
    private WorkspaceRepository.Active membership(Actor actor, UUID workspaceId) {
        return repo.findActive(actor.userId(), workspaceId).orElseGet(() -> {
            auditDenied(null, actor, "ACCESS_DENIED", "WORKSPACE", String.valueOf(workspaceId), "no active membership");
            throw new ApiException(ErrorCode.NOT_FOUND, null);
        });
    }

    private WorkspaceRepository.Active authorize(Actor actor, UUID workspaceId, Resource resource, Action action,
            boolean mfaGate, boolean stepUp, String auditType) {
        WorkspaceRepository.Active m = membership(actor, workspaceId);
        Role role = Role.parse(m.role()).orElseThrow(() -> new ApiException(ErrorCode.FORBIDDEN, null));
        if (!PermissionMatrix.allows(role, resource, action)) {
            auditDenied(workspaceId, actor, auditType, "WORKSPACE", workspaceId.toString(), "role " + role + " lacks " + resource + "/" + action);
            throw new ApiException(ErrorCode.FORBIDDEN, null);
        }
        if (mfaGate && role.requiresMfa() && !(auth.mfaEnabled(actor.userId()) && actor.mfaVerifiedAt() != null)) {
            auditDenied(workspaceId, actor, auditType, "WORKSPACE", workspaceId.toString(), "MFA required for " + role);
            throw new ApiException(ErrorCode.MFA_REQUIRED, "Owners and Admins must sign in with multi-factor authentication");
        }
        if (stepUp && (actor.stepUpUntil() == null || !actor.stepUpUntil().isAfter(now()))) {
            auditDenied(workspaceId, actor, auditType, "WORKSPACE", workspaceId.toString(), "step-up missing");
            throw new ApiException(ErrorCode.STEP_UP_REQUIRED, "Confirm your identity again to continue");
        }
        return m;
    }

    /**
     * Takes the workspace lock and re-reads the caller's membership under it. The role used for rules is the one that is
     * true now, not the one read before queuing behind another change (e.g. an Owner demoted a moment ago).
     */
    private WorkspaceRepository.Active relock(Actor actor, UUID workspaceId) {
        repo.lockWorkspace(workspaceId);
        return repo.findActive(actor.userId(), workspaceId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, null));
    }

    private static void requireBusiness(WorkspaceRepository.Active m) {
        if (!"BUSINESS".equals(m.type())) {
            throw new ApiException(ErrorCode.CONFLICT, "Not available for a personal profile");
        }
    }

    private static Role roleOf(String value) {
        return Role.parse(value).orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_FAILED, "role is not valid"));
    }

    // ---- workspaces ----

    public List<WorkspaceView> mine(Actor actor) {
        return repo.listMine(actor.userId()).stream()
                .map(w -> new WorkspaceView(w.workspaceId(), w.name(), w.type(), w.role(), w.membershipId())).toList();
    }

    public WorkspaceView get(Actor actor, UUID workspaceId) {
        WorkspaceRepository.Active m = membership(actor, workspaceId);
        return new WorkspaceView(m.workspaceId(), m.workspaceName(), m.type(), m.role(), m.membershipId());
    }

    static String cleanName(String name) {
        String n = name == null ? "" : name.trim();
        if (n.isEmpty() || n.length() > 100 || n.chars().anyMatch(Character::isISOControl)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "name must be 1-100 characters without control characters");
        }
        return n;
    }

    public WorkspaceView create(Actor actor, String name) {
        String clean = cleanName(name);
        if (repo.countOwnedBusinessWorkspaces(actor.userId()) >= props.getMaxOwnedBusinessWorkspaces()) {
            throw new ApiException(ErrorCode.CONFLICT, "Workspace limit reached");
        }
        UUID workspaceId = UUID.randomUUID();
        UUID membershipId = UUID.randomUUID();
        OffsetDateTime now = now();
        tx.executeWithoutResult(s -> {
            repo.insertWorkspace(workspaceId, clean, actor.userId(), now);
            repo.insertMembership(membershipId, workspaceId, actor.userId(), Role.OWNER.name(), null, now);
            audit(workspaceId, actor.userId(), "WORKSPACE_CREATED", "WORKSPACE", workspaceId.toString(), "SUCCESS", null);
        });
        return new WorkspaceView(workspaceId, clean, "BUSINESS", Role.OWNER.name(), membershipId);
    }

    public WorkspaceView rename(Actor actor, UUID workspaceId, String name) {
        String clean = cleanName(name);
        WorkspaceRepository.Active m = authorize(actor, workspaceId, Resource.COMPANY_SETTINGS, Action.UPDATE, true, false, "WORKSPACE_RENAME_DENIED");
        requireBusiness(m);
        OffsetDateTime now = now();
        tx.executeWithoutResult(s -> {
            repo.renameWorkspace(workspaceId, clean, now);
            audit(workspaceId, actor.userId(), "WORKSPACE_RENAMED", "WORKSPACE", workspaceId.toString(), "SUCCESS", null);
        });
        return new WorkspaceView(workspaceId, clean, m.type(), m.role(), m.membershipId());
    }

    // ---- members ----

    public List<MemberView> members(Actor actor, UUID workspaceId) {
        authorize(actor, workspaceId, Resource.MEMBERS, Action.READ, true, false, "MEMBER_LIST_DENIED");
        return repo.listMembers(workspaceId).stream().map(WorkspaceService::view).toList();
    }

    private static MemberView view(WorkspaceRepository.Member m) {
        return new MemberView(m.membershipId(), m.userId(), m.role(), m.status(), m.displayName(), m.email(), m.joinedAt());
    }

    public MemberView changeRole(Actor actor, UUID workspaceId, UUID membershipId, String newRoleName) {
        Role newRole = roleOf(newRoleName);
        WorkspaceRepository.Active me = authorize(actor, workspaceId, Resource.MEMBERS, Action.UPDATE, true, true, "ROLE_CHANGE_DENIED");
        requireBusiness(me);
        OffsetDateTime now = now();
        return tx.execute(s -> {
            Role myRole = Role.parse(relock(actor, workspaceId).role()).orElseThrow();
            if (!PermissionMatrix.allows(myRole, Resource.MEMBERS, Action.UPDATE)) {
                throw new ApiException(ErrorCode.FORBIDDEN, null);
            }
            WorkspaceRepository.Member target = repo.findMember(workspaceId, membershipId)
                    .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, null));
            Role targetRole = Role.parse(target.role()).orElseThrow();
            if (target.userId().equals(actor.userId())) {
                deniedInTx(workspaceId, actor, "ROLE_CHANGE_DENIED", target, "self role change");
                throw new ApiException(ErrorCode.FORBIDDEN, "You cannot change your own role");
            }
            if (!myRole.canManage(targetRole) || !myRole.canGrant(newRole)) {
                deniedInTx(workspaceId, actor, "ROLE_CHANGE_DENIED", target, "cannot grant " + newRole + " over " + targetRole);
                throw new ApiException(ErrorCode.FORBIDDEN, null);
            }
            if (newRole == targetRole) {
                return view(target);
            }
            if (newRole.requiresMfa() && !auth.mfaEnabled(target.userId())) {
                throw new ApiException(ErrorCode.CONFLICT, "The member must enable multi-factor authentication before becoming " + newRole);
            }
            if (targetRole == Role.OWNER && repo.countActiveOwners(workspaceId) <= 1) {
                throw new ApiException(ErrorCode.CONFLICT, "A workspace must keep at least one Owner");
            }
            repo.updateRole(workspaceId, membershipId, newRole.name(), now);
            audit(workspaceId, actor.userId(), "MEMBER_ROLE_CHANGED", "MEMBERSHIP", membershipId.toString(), "SUCCESS",
                    targetRole + " -> " + newRole);
            return view(repo.findMember(workspaceId, membershipId).orElseThrow());
        });
    }

    /** Audit rows written inside a failing transaction would roll back, so denials inside it use their own transaction. */
    private void deniedInTx(UUID workspaceId, Actor actor, String type, WorkspaceRepository.Member target, String why) {
        auditDenied(workspaceId, actor, type, "MEMBERSHIP", target.membershipId().toString(), why);
    }

    public void remove(Actor actor, UUID workspaceId, UUID membershipId) {
        WorkspaceRepository.Active me = membership(actor, workspaceId);
        requireBusiness(me);
        boolean self = me.membershipId().equals(membershipId);
        if (!self) {
            authorize(actor, workspaceId, Resource.MEMBERS, Action.ARCHIVE, true, false, "MEMBER_REMOVE_DENIED");
        }
        OffsetDateTime now = now();
        tx.executeWithoutResult(s -> {
            Role myRole = Role.parse(relock(actor, workspaceId).role()).orElseThrow();
            if (!self && !PermissionMatrix.allows(myRole, Resource.MEMBERS, Action.ARCHIVE)) {
                throw new ApiException(ErrorCode.FORBIDDEN, null);
            }
            WorkspaceRepository.Member target = repo.findMember(workspaceId, membershipId)
                    .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, null));
            Role targetRole = Role.parse(target.role()).orElseThrow();
            if (!self && !myRole.canManage(targetRole)) {
                deniedInTx(workspaceId, actor, "MEMBER_REMOVE_DENIED", target, "cannot remove " + targetRole);
                throw new ApiException(ErrorCode.FORBIDDEN, null);
            }
            if (targetRole == Role.OWNER && repo.countActiveOwners(workspaceId) <= 1) {
                throw new ApiException(ErrorCode.CONFLICT, "A workspace must keep at least one Owner; transfer ownership first");
            }
            repo.removeMembership(workspaceId, membershipId, actor.userId(), now);
            audit(workspaceId, actor.userId(), self ? "MEMBER_LEFT" : "MEMBER_REMOVED", "MEMBERSHIP", membershipId.toString(),
                    "SUCCESS", targetRole.name());
        });
    }

    public WorkspaceView transferOwnership(Actor actor, UUID workspaceId, UUID targetMembershipId) {
        WorkspaceRepository.Active me = authorize(actor, workspaceId, Resource.WORKSPACE_LIFECYCLE, Action.EXECUTE, true, true, "OWNERSHIP_TRANSFER_DENIED");
        requireBusiness(me);
        OffsetDateTime now = now();
        return tx.execute(s -> {
            if (!Role.OWNER.name().equals(relock(actor, workspaceId).role())) {
                throw new ApiException(ErrorCode.FORBIDDEN, null);
            }
            WorkspaceRepository.Member target = repo.findMember(workspaceId, targetMembershipId)
                    .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, null));
            if (target.userId().equals(actor.userId()) || !"ACTIVE".equals(target.status())) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "Choose another active member");
            }
            if (!auth.mfaEnabled(target.userId())) {
                throw new ApiException(ErrorCode.CONFLICT, "The new Owner must enable multi-factor authentication first");
            }
            // The new Owner is added before the old one is demoted, so the workspace never has zero Owners.
            repo.updateRole(workspaceId, targetMembershipId, Role.OWNER.name(), now);
            repo.updateRole(workspaceId, me.membershipId(), Role.ADMIN.name(), now);
            audit(workspaceId, actor.userId(), "OWNERSHIP_TRANSFERRED", "MEMBERSHIP", targetMembershipId.toString(), "SUCCESS", null);
            return new WorkspaceView(workspaceId, me.workspaceName(), me.type(), Role.ADMIN.name(), me.membershipId());
        });
    }

    // ---- invitations ----

    public InvitationView invite(Actor actor, UUID workspaceId, String email, String roleName) {
        String normalized = email == null ? null : email.trim().toLowerCase(Locale.ROOT);
        if (normalized == null || normalized.length() > 254 || !EMAIL.matcher(normalized).matches() || normalized.contains("..")) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "email is not a valid address");
        }
        Role role = roleOf(roleName);
        WorkspaceRepository.Active me = authorize(actor, workspaceId, Resource.MEMBERS, Action.CREATE, true, false, "INVITE_DENIED");
        requireBusiness(me);
        Role myRole = Role.parse(me.role()).orElseThrow();
        if (!myRole.canGrant(role)) {
            auditDenied(workspaceId, actor, "INVITE_DENIED", "WORKSPACE", workspaceId.toString(), myRole + " cannot grant " + role);
            throw new ApiException(ErrorCode.FORBIDDEN, null);
        }
        String token = Tokens.newToken();
        UUID invitationId = UUID.randomUUID();
        OffsetDateTime now = now();
        OffsetDateTime expires = now.plus(props.getInvitationTtl());
        tx.executeWithoutResult(s -> {
            Role current = Role.parse(relock(actor, workspaceId).role()).orElseThrow();
            if (!current.canGrant(role)) {
                throw new ApiException(ErrorCode.FORBIDDEN, null);
            }
            repo.expirePending(workspaceId, now);
            Optional<UUID> existing = repo.userIdByEmail(normalized);
            if (existing.isPresent() && repo.hasLiveMembership(workspaceId, existing.get())) {
                throw new ApiException(ErrorCode.CONFLICT, "That person is already a member");
            }
            try {
                repo.insertInvitation(invitationId, workspaceId, normalized, role.name(), Tokens.hash(token), actor.userId(), now, expires);
            } catch (org.springframework.dao.DuplicateKeyException e) {
                throw new ApiException(ErrorCode.CONFLICT, "A pending invitation for this email already exists; revoke it first");
            }
            audit(workspaceId, actor.userId(), "MEMBER_INVITED", "INVITATION", invitationId.toString(), "SUCCESS", "role " + role);
        });
        String link = props.getWebBaseUrl().replaceAll("/+$", "") + "/invitations/accept#token=" + token;
        InvitationNotifier.Outcome outcome;
        try {
            outcome = notifier.deliver(normalized, me.workspaceName(), role.name(), link, expires.toInstant());
        } catch (RuntimeException e) {
            outcome = InvitationNotifier.Outcome.FAILED;
        }
        return new InvitationView(invitationId, normalized, role.name(), "PENDING", now, expires, outcome.name());
    }

    public List<InvitationView> invitations(Actor actor, UUID workspaceId) {
        authorize(actor, workspaceId, Resource.MEMBERS, Action.READ, true, false, "INVITATION_LIST_DENIED");
        OffsetDateTime now = now();
        tx.executeWithoutResult(s -> repo.expirePending(workspaceId, now));
        return repo.listInvitations(workspaceId).stream()
                .map(i -> new InvitationView(i.id(), i.email(), i.role(), i.status(), i.createdAt(), i.expiresAt(), null)).toList();
    }

    public void revokeInvitation(Actor actor, UUID workspaceId, UUID invitationId) {
        WorkspaceRepository.Active me = authorize(actor, workspaceId, Resource.MEMBERS, Action.ARCHIVE, true, false, "INVITATION_REVOKE_DENIED");
        OffsetDateTime now = now();
        tx.executeWithoutResult(s -> {
            Role myRole = Role.parse(relock(actor, workspaceId).role()).orElseThrow();
            WorkspaceRepository.Invitation inv = repo.findInvitation(workspaceId, invitationId)
                    .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, null));
            if (!myRole.canGrant(Role.parse(inv.role()).orElseThrow())) {
                auditDenied(workspaceId, actor, "INVITATION_REVOKE_DENIED", "INVITATION", invitationId.toString(), "role too low");
                throw new ApiException(ErrorCode.FORBIDDEN, null);
            }
            if (repo.revoke(workspaceId, invitationId, actor.userId(), now).isEmpty()) {
                throw new ApiException(ErrorCode.CONFLICT, "The invitation is no longer pending");
            }
            audit(workspaceId, actor.userId(), "INVITATION_REVOKED", "INVITATION", invitationId.toString(), "SUCCESS", null);
        });
    }

    /** Accepts an invitation. Every failure is the same neutral error, and every attempt is audited. */
    public WorkspaceView accept(Actor actor, String token) {
        return resolve(actor, token, "ACCEPTED");
    }

    public void decline(Actor actor, String token) {
        resolve(actor, token, "DECLINED");
    }

    private WorkspaceView resolve(Actor actor, String token, String newStatus) {
        String type = "ACCEPTED".equals(newStatus) ? "INVITATION_ACCEPTED" : "INVITATION_DECLINED";
        if (token == null || token.isEmpty() || token.length() > 128) {
            auditDenied(null, actor, type, "INVITATION", null, "malformed token");
            throw new ApiException(ErrorCode.BAD_REQUEST, INVALID_INVITATION);
        }
        byte[] hash = Tokens.hash(token);
        String email = auth.profile(actor.userId()).email().trim().toLowerCase(Locale.ROOT);
        Optional<WorkspaceRepository.Invitation> known = repo.findInvitationByHash(hash);
        if (known.isEmpty()) {
            auditDenied(null, actor, type, "INVITATION", null, "unknown token");
            throw new ApiException(ErrorCode.BAD_REQUEST, INVALID_INVITATION);
        }
        UUID workspaceId = known.get().workspaceId();
        OffsetDateTime now = now();
        try {
            return tx.execute(s -> {
                repo.lockWorkspace(workspaceId);
                WorkspaceRepository.Invitation won = repo.resolveByToken(hash, email, newStatus, actor.userId(), now)
                        .orElseThrow(() -> new ApiException(ErrorCode.BAD_REQUEST, INVALID_INVITATION));
                if (!"ACCEPTED".equals(newStatus)) {
                    audit(workspaceId, actor.userId(), type, "INVITATION", won.id().toString(), "SUCCESS", null);
                    return null;
                }
                UUID membershipId = UUID.randomUUID();
                if (repo.hasLiveMembership(workspaceId, actor.userId())) {
                    throw new ApiException(ErrorCode.CONFLICT, "You are already a member of this workspace");
                }
                repo.insertMembership(membershipId, workspaceId, actor.userId(), won.role(), won.invitedBy(), now);
                audit(workspaceId, actor.userId(), type, "INVITATION", won.id().toString(), "SUCCESS", "role " + won.role());
                audit(workspaceId, actor.userId(), "MEMBER_ADDED", "MEMBERSHIP", membershipId.toString(), "SUCCESS", "role " + won.role());
                WorkspaceRepository.Active a = repo.findActive(actor.userId(), workspaceId).orElseThrow();
                return new WorkspaceView(workspaceId, a.workspaceName(), a.type(), a.role(), a.membershipId());
            });
        } catch (ApiException e) {
            if (e.code() == ErrorCode.BAD_REQUEST) {
                auditDenied(workspaceId, actor, type, "INVITATION", known.get().id().toString(), "wrong email, state or expired");
            }
            throw e;
        }
    }
}
