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

/** Clients and vendors: one handler set mapped under both prefixes. */
@RestController
public class PartyController {

    private static final String LIST = "/v1/spaces/{space}/{kind:clients|vendors}";
    private static final String ONE = "/v1/spaces/{space}/{kind:clients|vendors}/{id}";

    private final BusinessAccess access;
    private final PartyService parties;
    private final Idem idem;

    public PartyController(BusinessAccess access, PartyService parties, Idem idem) {
        this.access = access;
        this.parties = parties;
        this.idem = idem;
    }

    private static PartyService.Kind kind(String segment) {
        return "clients".equals(segment) ? PartyService.Kind.CLIENT : PartyService.Kind.VENDOR;
    }

    @GetMapping(LIST)
    public Page<PartyService.Party> list(@PathVariable UUID space, @PathVariable String kind, @RequestParam(required = false) String q,
            @RequestParam(name = "include_archived", defaultValue = "false") boolean includeArchived, @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Integer offset, HttpServletRequest request) {
        return parties.list(access.resolve(request, space), kind(kind), q, includeArchived, limit, offset);
    }

    @PostMapping(LIST)
    public ResponseEntity<String> create(@PathVariable UUID space, @PathVariable String kind, @RequestBody(required = false) PartyService.Request body,
            HttpServletRequest request) {
        BusinessContext ctx = access.resolve(request, space);
        PartyService.Request b = body == null ? new PartyService.Request(null, null, null, null, null, null, null, null) : body;
        return idem.run(request, ctx.tenantId(), b, false, HttpStatus.CREATED, () -> parties.create(ctx, kind(kind), b));
    }

    @GetMapping(ONE)
    public PartyService.Detail get(@PathVariable UUID space, @PathVariable String kind, @PathVariable UUID id, HttpServletRequest request) {
        return parties.get(access.resolve(request, space), kind(kind), id);
    }

    @PatchMapping(ONE)
    public PartyService.Detail update(@PathVariable UUID space, @PathVariable String kind, @PathVariable UUID id,
            @RequestBody(required = false) PartyService.Request body, HttpServletRequest request) {
        return parties.update(access.resolve(request, space), kind(kind), id,
                body == null ? new PartyService.Request(null, null, null, null, null, null, null, null) : body);
    }

    @PostMapping(ONE + "/archive")
    public PartyService.Detail archive(@PathVariable UUID space, @PathVariable String kind, @PathVariable UUID id, HttpServletRequest request) {
        return parties.setArchived(access.resolve(request, space), kind(kind), id, true);
    }

    @PostMapping(ONE + "/restore")
    public PartyService.Detail restore(@PathVariable UUID space, @PathVariable String kind, @PathVariable UUID id, HttpServletRequest request) {
        return parties.setArchived(access.resolve(request, space), kind(kind), id, false);
    }
}
