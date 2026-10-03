package org.example.seatreservation.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.regex.Pattern;

@Component
public class RequestLoggingFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);
    private static final Pattern SAFE_REQUEST_ID = Pattern.compile("[A-Za-z0-9._-]{1,100}");
    private final ObjectMapper objectMapper;

    public RequestLoggingFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String suppliedId = request.getHeader("X-Request-Id");
        String requestId = suppliedId != null && SAFE_REQUEST_ID.matcher(suppliedId).matches()
                ? suppliedId : UUID.randomUUID().toString();
        long started = System.nanoTime();
        MDC.put("request_id", requestId);
        response.setHeader("X-Request-Id", requestId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            var event = new LinkedHashMap<String, Object>();
            event.put("event", "http_request");
            event.put("request_id", requestId);
            event.put("method", request.getMethod());
            event.put("path", request.getRequestURI());
            event.put("status", response.getStatus());
            event.put("duration_ms", (System.nanoTime() - started) / 1_000_000);
            log.info(objectMapper.writeValueAsString(event));
            MDC.remove("request_id");
        }
    }
}
