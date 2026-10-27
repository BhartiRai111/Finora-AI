package com.finora.finance.notifications;

import com.finora.finance.insights.InsightsAccess;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The notification centre API. Identity and workspace come only from the verified token. */
@RestController
public class NotificationController {

    private final InsightsAccess access;
    private final NotificationService notifications;

    public NotificationController(InsightsAccess access, NotificationService notifications) {
        this.access = access;
        this.notifications = notifications;
    }

    @GetMapping("/v1/spaces/{space}/finance-notifications")
    public NotificationService.Page list(@PathVariable UUID space, @RequestParam(name = "unread_only", defaultValue = "false") boolean unreadOnly, @RequestParam(required = false) String category,
            @RequestParam(defaultValue = "20") int limit, @RequestParam(defaultValue = "0") int offset, HttpServletRequest request) {
        return notifications.list(access.resolve(request, space), unreadOnly, category, limit, offset);
    }

    @GetMapping("/v1/spaces/{space}/finance-notifications/unread-count")
    public Map<String, Long> unread(@PathVariable UUID space, HttpServletRequest request) {
        return Map.of("unread", notifications.unreadCount(access.resolve(request, space)));
    }

    @GetMapping("/v1/spaces/{space}/finance-notifications/channels")
    public Map<String, Object> channels(@PathVariable UUID space, HttpServletRequest request) {
        return notifications.channels(access.resolve(request, space));
    }

    @PostMapping("/v1/spaces/{space}/finance-notifications/read-all")
    public Map<String, Integer> readAll(@PathVariable UUID space, HttpServletRequest request) {
        return Map.of("marked", notifications.markAllRead(access.resolve(request, space)));
    }

    @PostMapping("/v1/spaces/{space}/finance-notifications/{id}/read")
    public NotificationService.Item read(@PathVariable UUID space, @PathVariable UUID id, HttpServletRequest request) {
        return notifications.markRead(access.resolve(request, space), id);
    }
}
