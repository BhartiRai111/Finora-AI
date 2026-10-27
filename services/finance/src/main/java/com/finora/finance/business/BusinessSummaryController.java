package com.finora.finance.business;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class BusinessSummaryController {

    private final BusinessAccess access;
    private final BusinessSummaryService summaries;

    public BusinessSummaryController(BusinessAccess access, BusinessSummaryService summaries) {
        this.access = access;
        this.summaries = summaries;
    }

    @GetMapping("/v1/spaces/{space}/business-summaries/overview")
    public BusinessSummaryService.Overview overview(@PathVariable UUID space, @RequestParam(required = false) String from, @RequestParam(required = false) String to,
            HttpServletRequest request) {
        return summaries.overview(access.resolve(request, space), from, to);
    }

    @GetMapping("/v1/spaces/{space}/business-summaries/projects")
    public Map<String, List<BusinessSummaryService.Profitability>> projects(@PathVariable UUID space, @RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(name = "include_archived", defaultValue = "false") boolean includeArchived,
            HttpServletRequest request) {
        return summaries.projectProfitability(access.resolve(request, space), from, to, includeArchived);
    }

    @GetMapping("/v1/spaces/{space}/business-summaries/projects/{id}")
    public BusinessSummaryService.Profitability project(@PathVariable UUID space, @PathVariable UUID id, @RequestParam(required = false) String from,
            @RequestParam(required = false) String to, HttpServletRequest request) {
        return summaries.oneProject(access.resolve(request, space), id, from, to);
    }
}
