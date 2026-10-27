package com.finora.identityaccess.workspace;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.finora.commons.error.ApiException;
import com.finora.commons.error.ErrorCode;
import com.finora.commons.security.InternalPrincipal;
import com.finora.commons.security.PrincipalKind;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Workspace API. The caller is whoever the gateway authenticated (a verified internal token); the workspace in the URL is
 * only a request. Authorization is decided in {@link WorkspaceService} from database state.
 */
@RestController
public class WorkspaceController {

    public record NameRequest(String name) {
    }

    public record RoleRequest(String role) {
    }

    public record InviteRequest(String email, String role) {
    }

    public record TokenRequest(String token) {
    }

    public record TransferRequest(@JsonProperty("to_membership_id") UUID toMembershipId) {
    }

    private final WorkspaceService workspaces;

    public WorkspaceController(WorkspaceService workspaces) {
        this.workspaces = workspaces;
    }

    private WorkspaceService.Actor actor(HttpServletRequest request) {
        InternalPrincipal p = (InternalPrincipal) request.getAttribute(InternalPrincipal.REQUEST_ATTRIBUTE);
        if (p.kind() != PrincipalKind.USER || p.sessionId() == null) {
            throw new ApiException(ErrorCode.AUTH_REQUIRED, null);
        }
        return workspaces.actor(UUID.fromString(p.subject()), p.sessionId());
    }

    private static <T> T required(T body) {
        if (body == null) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "Request body is required");
        }
        return body;
    }

    @GetMapping("/v1/workspaces")
    public Map<String, Object> mine(HttpServletRequest request) {
        return Map.of("items", workspaces.mine(actor(request)));
    }

    @PostMapping("/v1/workspaces")
    public ResponseEntity<WorkspaceService.WorkspaceView> create(@RequestBody(required = false) NameRequest body, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(workspaces.create(actor(request), required(body).name()));
    }

    @GetMapping("/v1/spaces/{space}")
    public WorkspaceService.WorkspaceView get(@PathVariable UUID space, HttpServletRequest request) {
        return workspaces.get(actor(request), space);
    }

    @PatchMapping("/v1/spaces/{space}")
    public WorkspaceService.WorkspaceView rename(@PathVariable UUID space, @RequestBody(required = false) NameRequest body, HttpServletRequest request) {
        return workspaces.rename(actor(request), space, required(body).name());
    }

    @GetMapping("/v1/spaces/{space}/members")
    public Map<String, Object> members(@PathVariable UUID space, HttpServletRequest request) {
        return Map.of("items", workspaces.members(actor(request), space));
    }

    @PatchMapping("/v1/spaces/{space}/members/{membershipId}")
    public WorkspaceService.MemberView changeRole(@PathVariable UUID space, @PathVariable UUID membershipId,
            @RequestBody(required = false) RoleRequest body, HttpServletRequest request) {
        return workspaces.changeRole(actor(request), space, membershipId, required(body).role());
    }

    @DeleteMapping("/v1/spaces/{space}/members/{membershipId}")
    public ResponseEntity<Void> remove(@PathVariable UUID space, @PathVariable UUID membershipId, HttpServletRequest request) {
        workspaces.remove(actor(request), space, membershipId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/v1/spaces/{space}/invitations")
    public Map<String, Object> invitations(@PathVariable UUID space, HttpServletRequest request) {
        List<WorkspaceService.InvitationView> items = workspaces.invitations(actor(request), space);
        return Map.of("items", items);
    }

    @PostMapping("/v1/spaces/{space}/invitations")
    public ResponseEntity<WorkspaceService.InvitationView> invite(@PathVariable UUID space,
            @RequestBody(required = false) InviteRequest body, HttpServletRequest request) {
        InviteRequest r = required(body);
        return ResponseEntity.status(HttpStatus.CREATED).body(workspaces.invite(actor(request), space, r.email(), r.role()));
    }

    @DeleteMapping("/v1/spaces/{space}/invitations/{invitationId}")
    public ResponseEntity<Void> revokeInvitation(@PathVariable UUID space, @PathVariable UUID invitationId, HttpServletRequest request) {
        workspaces.revokeInvitation(actor(request), space, invitationId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/v1/invitations/accept")
    public WorkspaceService.WorkspaceView accept(@RequestBody(required = false) TokenRequest body, HttpServletRequest request) {
        return workspaces.accept(actor(request), required(body).token());
    }

    @PostMapping("/v1/invitations/decline")
    public ResponseEntity<Void> decline(@RequestBody(required = false) TokenRequest body, HttpServletRequest request) {
        workspaces.decline(actor(request), required(body).token());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/v1/spaces/{space}/ownership-transfers")
    public WorkspaceService.WorkspaceView transfer(@PathVariable UUID space, @RequestBody(required = false) TransferRequest body, HttpServletRequest request) {
        return workspaces.transferOwnership(actor(request), space, required(body).toMembershipId());
    }
}
