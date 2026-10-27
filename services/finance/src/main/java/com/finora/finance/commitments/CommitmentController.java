package com.finora.finance.commitments;

import com.finora.finance.core.FinanceAccess;
import com.finora.finance.core.FinanceContext;
import com.finora.finance.core.Idem;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
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

/** Bills and subscriptions share one engine; the URL decides the kind. */
@RestController
public class CommitmentController {

    private final FinanceAccess access;
    private final CommitmentService commitments;
    private final Idem idem;

    public CommitmentController(FinanceAccess access, CommitmentService commitments, Idem idem) {
        this.access = access;
        this.commitments = commitments;
        this.idem = idem;
    }

    private static String kind(String segment) {
        return "bills".equals(segment) ? "BILL" : "SUBSCRIPTION";
    }

    // Spring cannot bind a literal-or-variable prefix, so both prefixes are mapped explicitly through one handler set.

    @GetMapping({"/v1/spaces/{space}/bills", "/v1/spaces/{space}/subscriptions"})
    public Map<String, Object> list(@PathVariable UUID space, @RequestParam(name = "due_within_days", required = false) Integer dueWithinDays,
            HttpServletRequest request) {
        return commitments.list(access.resolve(request, space), kind(segment(request)), dueWithinDays);
    }

    @PostMapping({"/v1/spaces/{space}/bills", "/v1/spaces/{space}/subscriptions"})
    public ResponseEntity<String> create(@PathVariable UUID space, @RequestBody(required = false) CommitmentService.CreateRequest body,
            HttpServletRequest request) {
        FinanceContext ctx = access.resolve(request, space);
        CommitmentService.CreateRequest b = body == null ? new CommitmentService.CreateRequest(null, null, null, null, null, null, null, null) : body;
        String kind = kind(segment(request));
        return idem.run(request, ctx, b, false, HttpStatus.CREATED, () -> commitments.create(ctx, kind, b));
    }

    @GetMapping({"/v1/spaces/{space}/bills/{id}", "/v1/spaces/{space}/subscriptions/{id}"})
    public CommitmentService.View get(@PathVariable UUID space, @PathVariable UUID id, HttpServletRequest request) {
        return commitments.get(access.resolve(request, space), kind(segment(request)), id);
    }

    @PatchMapping({"/v1/spaces/{space}/bills/{id}", "/v1/spaces/{space}/subscriptions/{id}"})
    public CommitmentService.View update(@PathVariable UUID space, @PathVariable UUID id,
            @RequestBody(required = false) CommitmentService.UpdateRequest body, HttpServletRequest request) {
        CommitmentService.UpdateRequest b = body == null ? new CommitmentService.UpdateRequest(null, null, null, null, null, null, null) : body;
        return commitments.update(access.resolve(request, space), kind(segment(request)), id, b);
    }

    @PostMapping({"/v1/spaces/{space}/bills/{id}/{action:pause|resume|cancel}", "/v1/spaces/{space}/subscriptions/{id}/{action:pause|resume|cancel}"})
    public CommitmentService.View status(@PathVariable UUID space, @PathVariable UUID id, @PathVariable String action, HttpServletRequest request) {
        return commitments.setStatus(access.resolve(request, space), kind(segment(request)), id, action);
    }

    @PostMapping({"/v1/spaces/{space}/bills/{id}/skip", "/v1/spaces/{space}/subscriptions/{id}/skip"})
    public CommitmentService.View skip(@PathVariable UUID space, @PathVariable UUID id, HttpServletRequest request) {
        return commitments.skip(access.resolve(request, space), kind(segment(request)), id);
    }

    @PostMapping({"/v1/spaces/{space}/bills/{id}/pay", "/v1/spaces/{space}/subscriptions/{id}/pay"})
    public ResponseEntity<String> pay(@PathVariable UUID space, @PathVariable UUID id, @RequestBody(required = false) CommitmentService.PayRequest body,
            HttpServletRequest request) {
        FinanceContext ctx = access.resolve(request, space);
        CommitmentService.PayRequest b = body == null ? new CommitmentService.PayRequest(null, null, null) : body;
        String kind = kind(segment(request));
        return idem.run(request, ctx, b, true, HttpStatus.CREATED, () -> commitments.pay(ctx, kind, id, b));
    }

    /** "bills" or "subscriptions": the fourth path segment of /v1/spaces/{space}/{segment}/... */
    private static String segment(HttpServletRequest request) {
        String[] parts = request.getRequestURI().split("/");
        return parts.length > 4 ? parts[4] : "";
    }
}
