package com.finora.finance.subscriptions;

import com.finora.finance.insights.InsightsAccess;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Subscription and billing for the caller's own workspace. The workspace id comes from the verified token; a body can never select another workspace. */
@RestController
public class SubscriptionController {

    public record ChangeRequest(String planKey) {
    }

    public record CancelRequest(String reason) {
    }

    private final InsightsAccess access;
    private final SubscriptionService subscriptions;
    private final BillingService billing;

    public SubscriptionController(InsightsAccess access, SubscriptionService subscriptions, BillingService billing) {
        this.access = access;
        this.subscriptions = subscriptions;
        this.billing = billing;
    }

    @GetMapping("/v1/spaces/{space}/finance-subscription")
    public SubscriptionService.Subscription current(@PathVariable UUID space, HttpServletRequest request) {
        return subscriptions.current(access.resolve(request, space));
    }

    @GetMapping("/v1/spaces/{space}/finance-subscription/plans")
    public Map<String, List<SubscriptionService.Plan>> plans(@PathVariable UUID space, HttpServletRequest request) {
        return Map.of("items", subscriptions.plans(access.resolve(request, space)));
    }

    @GetMapping("/v1/spaces/{space}/finance-subscription/history")
    public Map<String, Object> history(@PathVariable UUID space, @RequestParam(defaultValue = "20") int limit, @RequestParam(defaultValue = "0") int offset, HttpServletRequest request) {
        return subscriptions.history(access.resolve(request, space), limit, offset);
    }

    @PostMapping("/v1/spaces/{space}/finance-subscription/change")
    public SubscriptionService.Subscription change(@PathVariable UUID space, @RequestBody(required = false) ChangeRequest body, HttpServletRequest request) {
        return subscriptions.changePlan(access.resolve(request, space), body == null ? null : body.planKey());
    }

    @PostMapping("/v1/spaces/{space}/finance-subscription/cancel")
    public SubscriptionService.Subscription cancel(@PathVariable UUID space, @RequestBody(required = false) CancelRequest body, HttpServletRequest request) {
        return subscriptions.cancel(access.resolve(request, space), body == null ? null : body.reason());
    }

    @PostMapping("/v1/spaces/{space}/finance-subscription/reactivate")
    public SubscriptionService.Subscription reactivate(@PathVariable UUID space, HttpServletRequest request) {
        return subscriptions.reactivate(access.resolve(request, space));
    }

    @GetMapping("/v1/spaces/{space}/finance-billing")
    public Map<String, Object> billing(@PathVariable UUID space, HttpServletRequest request) {
        return billing.overview(access.resolve(request, space));
    }

    @PutMapping("/v1/spaces/{space}/finance-billing/contact")
    public BillingService.Contact contact(@PathVariable UUID space, @RequestBody(required = false) BillingService.ContactRequest body, HttpServletRequest request) {
        return billing.putContact(access.resolve(request, space), body == null ? new BillingService.ContactRequest(null, null) : body);
    }

    @GetMapping("/v1/spaces/{space}/finance-billing/records")
    public Map<String, Object> records(@PathVariable UUID space, @RequestParam(defaultValue = "20") int limit, @RequestParam(defaultValue = "0") int offset, HttpServletRequest request) {
        return billing.records(access.resolve(request, space), limit, offset);
    }
}
