package com.finora.finance.reference;

import com.finora.finance.core.FinanceAccess;
import com.finora.finance.core.FinanceContext;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ReferenceController {

    public record MergeRequest(String intoCategoryId) {
    }

    public record TagRequest(String name) {
    }

    private final FinanceAccess access;
    private final ReferenceService reference;

    public ReferenceController(FinanceAccess access, ReferenceService reference) {
        this.access = access;
        this.reference = reference;
    }

    private static ReferenceService.CategoryRequest nonNull(ReferenceService.CategoryRequest b) {
        return b == null ? new ReferenceService.CategoryRequest(null, null, null, null) : b;
    }

    @GetMapping("/v1/spaces/{space}/categories")
    public Map<String, List<ReferenceService.CategoryView>> categories(@PathVariable UUID space,
            @RequestParam(name = "include_archived", defaultValue = "false") boolean includeArchived, HttpServletRequest request) {
        return Map.of("items", reference.categories(access.resolve(request, space), includeArchived));
    }

    @PostMapping("/v1/spaces/{space}/categories")
    public org.springframework.http.ResponseEntity<ReferenceService.CategoryView> create(@PathVariable UUID space,
            @RequestBody(required = false) ReferenceService.CategoryRequest body, HttpServletRequest request) {
        FinanceContext ctx = access.resolve(request, space);
        return org.springframework.http.ResponseEntity.status(201).body(reference.create(ctx, nonNull(body)));
    }

    @PostMapping("/v1/spaces/{space}/categories/starter")
    public Map<String, List<ReferenceService.CategoryView>> starter(@PathVariable UUID space, HttpServletRequest request) {
        return Map.of("items", reference.addStarterCategories(access.resolve(request, space)));
    }

    @PatchMapping("/v1/spaces/{space}/categories/{id}")
    public ReferenceService.CategoryView rename(@PathVariable UUID space, @PathVariable UUID id,
            @RequestBody(required = false) ReferenceService.CategoryRequest body, HttpServletRequest request) {
        ReferenceService.CategoryRequest b = nonNull(body);
        return reference.rename(access.resolve(request, space), id, b.name(), b.version());
    }

    @PostMapping("/v1/spaces/{space}/categories/{id}/archive")
    public ReferenceService.CategoryView archive(@PathVariable UUID space, @PathVariable UUID id, HttpServletRequest request) {
        return reference.setArchived(access.resolve(request, space), id, true);
    }

    @PostMapping("/v1/spaces/{space}/categories/{id}/restore")
    public ReferenceService.CategoryView restore(@PathVariable UUID space, @PathVariable UUID id, HttpServletRequest request) {
        return reference.setArchived(access.resolve(request, space), id, false);
    }

    @PostMapping("/v1/spaces/{space}/categories/{id}/merge")
    public ReferenceService.CategoryView merge(@PathVariable UUID space, @PathVariable UUID id,
            @RequestBody(required = false) MergeRequest body, HttpServletRequest request) {
        return reference.merge(access.resolve(request, space), id, body == null ? null : body.intoCategoryId());
    }

    @GetMapping("/v1/spaces/{space}/tags")
    public Map<String, List<ReferenceService.TagView>> tags(@PathVariable UUID space, HttpServletRequest request) {
        return Map.of("items", reference.tags(access.resolve(request, space)));
    }

    @PatchMapping("/v1/spaces/{space}/tags/{id}")
    public ReferenceService.TagView renameTag(@PathVariable UUID space, @PathVariable UUID id,
            @RequestBody(required = false) TagRequest body, HttpServletRequest request) {
        return reference.renameTag(access.resolve(request, space), id, body == null ? null : body.name());
    }
}
