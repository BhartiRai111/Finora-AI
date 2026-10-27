package com.finora.finance.business;

import com.finora.finance.core.Idem;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ProjectController {

    private final BusinessAccess access;
    private final ProjectService projects;
    private final Idem idem;

    public ProjectController(BusinessAccess access, ProjectService projects, Idem idem) {
        this.access = access;
        this.projects = projects;
        this.idem = idem;
    }

    private static ProjectService.Request none() {
        return new ProjectService.Request(null, null, null, null, null, null, null, null, null, null, null, null);
    }

    @GetMapping("/v1/spaces/{space}/projects")
    public Page<ProjectService.Project> list(@PathVariable UUID space, @RequestParam(required = false) String q, @RequestParam(required = false) String status,
            @RequestParam(name = "client_id", required = false) UUID clientId, @RequestParam(name = "include_archived", defaultValue = "false") boolean includeArchived,
            @RequestParam(required = false) Integer limit, @RequestParam(required = false) Integer offset, HttpServletRequest request) {
        return projects.list(access.resolve(request, space), q, status, clientId, includeArchived, limit, offset);
    }

    @PostMapping("/v1/spaces/{space}/projects")
    public ResponseEntity<String> create(@PathVariable UUID space, @RequestBody(required = false) ProjectService.Request body, HttpServletRequest request) {
        BusinessContext ctx = access.resolve(request, space);
        ProjectService.Request b = body == null ? none() : body;
        return idem.run(request, ctx.tenantId(), b, false, HttpStatus.CREATED, () -> projects.create(ctx, b));
    }

    @GetMapping("/v1/spaces/{space}/projects/{id}")
    public ProjectService.Detail get(@PathVariable UUID space, @PathVariable UUID id, HttpServletRequest request) {
        return projects.get(access.resolve(request, space), id);
    }

    @PatchMapping("/v1/spaces/{space}/projects/{id}")
    public ProjectService.Detail update(@PathVariable UUID space, @PathVariable UUID id, @RequestBody(required = false) ProjectService.Request body,
            HttpServletRequest request) {
        return projects.update(access.resolve(request, space), id, body == null ? none() : body);
    }

    @PostMapping("/v1/spaces/{space}/projects/{id}/archive")
    public ProjectService.Detail archive(@PathVariable UUID space, @PathVariable UUID id, HttpServletRequest request) {
        return projects.setArchived(access.resolve(request, space), id, true);
    }

    @PostMapping("/v1/spaces/{space}/projects/{id}/restore")
    public ProjectService.Detail restore(@PathVariable UUID space, @PathVariable UUID id, HttpServletRequest request) {
        return projects.setArchived(access.resolve(request, space), id, false);
    }
}
