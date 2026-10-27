package com.finora.finance.summaries;

import com.finora.finance.core.FinanceAccess;
import com.finora.finance.core.Validate;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SummaryController {

    private final FinanceAccess access;
    private final SummaryService summaries;

    public SummaryController(FinanceAccess access, SummaryService summaries) {
        this.access = access;
        this.summaries = summaries;
    }

    @GetMapping("/v1/spaces/{space}/summaries/cashflow")
    public SummaryService.Cashflow cashflow(@PathVariable UUID space, @RequestParam(required = false) String from,
            @RequestParam(required = false) String to, HttpServletRequest request) {
        return summaries.cashflow(access.resolve(request, space), Validate.date("from", from, false), Validate.date("to", to, false));
    }

    @GetMapping("/v1/spaces/{space}/net-worth")
    public SummaryService.NetWorth netWorth(@PathVariable UUID space, @RequestParam(name = "as_of", required = false) String asOf,
            HttpServletRequest request) {
        return summaries.netWorth(access.resolve(request, space), Validate.date("as_of", asOf, false));
    }

    @GetMapping("/v1/spaces/{space}/net-worth/series")
    public SummaryService.Series series(@PathVariable UUID space, @RequestParam(required = false) String from,
            @RequestParam(required = false) String to, HttpServletRequest request) {
        return summaries.series(access.resolve(request, space), Validate.date("from", from, false), Validate.date("to", to, false));
    }
}
