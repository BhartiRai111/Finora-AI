package com.finora.finance.assistant;

import com.finora.finance.assistant.AssistantModel.Answer;
import com.finora.finance.insights.InsightsAccess;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The assistant API. Identity and workspace come from the verified token; nothing in a body or query can select another workspace or user. */
@RestController
public class AssistantController {

    private final InsightsAccess access;
    private final AssistantService assistant;

    public AssistantController(InsightsAccess access, AssistantService assistant) {
        this.access = access;
        this.assistant = assistant;
    }

    @GetMapping("/v1/spaces/{space}/finance-assistant")
    public AssistantService.Status status(@PathVariable UUID space, HttpServletRequest request) {
        return assistant.status(access.resolve(request, space));
    }

    @PostMapping("/v1/spaces/{space}/finance-assistant/ask")
    public Answer ask(@PathVariable UUID space, @RequestBody Map<String, Object> body, HttpServletRequest request) {
        Object q = body.get("question");
        return assistant.ask(access.resolve(request, space), q instanceof String s ? s : null);
    }

    @PostMapping("/v1/spaces/{space}/finance-assistant/what-if")
    public Answer whatIf(@PathVariable UUID space, @RequestBody Map<String, Object> body, HttpServletRequest request) {
        return assistant.run(access.resolve(request, space), "what_if", body);
    }

    @GetMapping("/v1/spaces/{space}/finance-assistant/anomalies")
    public Answer anomalies(@PathVariable UUID space, @RequestParam(required = false) String from, @RequestParam(required = false) String to,
            @RequestParam(required = false) String currency, HttpServletRequest request) {
        return assistant.run(access.resolve(request, space), "anomalies", args(from, to, currency));
    }

    @GetMapping("/v1/spaces/{space}/finance-assistant/expense-change")
    public Answer expenseChange(@PathVariable UUID space, @RequestParam(required = false) String from, @RequestParam(required = false) String to,
            @RequestParam(required = false) String currency, HttpServletRequest request) {
        return assistant.run(access.resolve(request, space), "expense_change", args(from, to, currency));
    }

    private static Map<String, Object> args(String from, String to, String currency) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("from", from);
        args.put("to", to);
        args.put("currency", currency);
        return args;
    }
}
