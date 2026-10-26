package com.finora.commons.correlation;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Locale;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/** Accepts a well-formed {@code X-Request-Id}, otherwise generates one; echoes it on the response. */
public class CorrelationIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String id = request.getHeader(CorrelationId.HEADER);
        id = CorrelationId.isValid(id) ? id.toLowerCase(Locale.ROOT) : CorrelationId.generate();
        request.setAttribute(CorrelationId.REQUEST_ATTRIBUTE, id);
        response.setHeader(CorrelationId.HEADER, id);
        MDC.put(CorrelationId.MDC_KEY, id);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(CorrelationId.MDC_KEY);
        }
    }
}
