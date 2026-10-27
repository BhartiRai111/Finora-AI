package com.finora.finance.recon;

import com.finora.finance.insights.InsightsAccess;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ReconciliationController {

    private final InsightsAccess access;
    private final ReconciliationService recon;

    public ReconciliationController(InsightsAccess access, ReconciliationService recon) {
        this.access = access;
        this.recon = recon;
    }

    @GetMapping("/v1/spaces/{space}/finance-reconciliation/summary")
    public ReconciliationService.Summary summary(@PathVariable UUID space, HttpServletRequest request) {
        return recon.summary(access.resolve(request, space));
    }

    @GetMapping("/v1/spaces/{space}/finance-reconciliation/lines")
    public ReconciliationService.Page lines(@PathVariable UUID space, @RequestParam(required = false) String status, @RequestParam(name = "account_id", required = false) UUID accountId,
            @RequestParam(required = false) Integer limit, @RequestParam(required = false) Integer offset, HttpServletRequest request) {
        return recon.lines(access.resolve(request, space), status, accountId, limit, offset);
    }

    @GetMapping("/v1/spaces/{space}/finance-reconciliation/lines/{id}")
    public ReconciliationService.Line line(@PathVariable UUID space, @PathVariable UUID id, HttpServletRequest request) {
        return recon.get(access.resolve(request, space), id);
    }

    @GetMapping("/v1/spaces/{space}/finance-reconciliation/unmatched-records")
    public Map<String, List<ReconciliationService.UnmatchedRecord>> unmatched(@PathVariable UUID space, @RequestParam(name = "account_id", required = false) UUID accountId,
            @RequestParam(required = false) Integer limit, @RequestParam(required = false) Integer offset, HttpServletRequest request) {
        return Map.of("items", recon.unmatchedRecords(access.resolve(request, space), accountId, limit == null ? 50 : limit, offset == null ? 0 : offset));
    }

    @PostMapping("/v1/spaces/{space}/finance-reconciliation/lines/{id}/match")
    public ReconciliationService.Line match(@PathVariable UUID space, @PathVariable UUID id, @RequestBody(required = false) ReconciliationService.MatchRequest body, HttpServletRequest request) {
        return recon.confirm(access.resolve(request, space), id, body == null ? new ReconciliationService.MatchRequest(null, null, null) : body);
    }

    @PostMapping("/v1/spaces/{space}/finance-reconciliation/matches/{id}/undo")
    public ReconciliationService.Line undo(@PathVariable UUID space, @PathVariable UUID id, @RequestBody(required = false) ReconciliationService.ReasonRequest body, HttpServletRequest request) {
        return recon.undo(access.resolve(request, space), id, body == null ? new ReconciliationService.ReasonRequest(null) : body);
    }

    @PostMapping("/v1/spaces/{space}/finance-reconciliation/lines/{id}/ignore")
    public ReconciliationService.Line ignore(@PathVariable UUID space, @PathVariable UUID id, @RequestBody(required = false) ReconciliationService.ReasonRequest body, HttpServletRequest request) {
        return recon.ignore(access.resolve(request, space), id, body == null ? new ReconciliationService.ReasonRequest(null) : body, true);
    }

    @PostMapping("/v1/spaces/{space}/finance-reconciliation/lines/{id}/restore")
    public ReconciliationService.Line restore(@PathVariable UUID space, @PathVariable UUID id, HttpServletRequest request) {
        return recon.ignore(access.resolve(request, space), id, new ReconciliationService.ReasonRequest(null), false);
    }
}
