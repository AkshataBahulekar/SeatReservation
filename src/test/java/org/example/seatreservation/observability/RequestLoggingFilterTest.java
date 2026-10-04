package org.example.seatreservation.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.slf4j.MDC;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestLoggingFilterTest {
    private final RequestLoggingFilter filter = new RequestLoggingFilter(new ObjectMapper());

    @Test
    void preservesSafeRequestIdAndCleansMdc() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/shows");
        request.addHeader("X-Request-Id", "test-request_1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> {
            assertEquals("test-request_1", MDC.get("request_id"));
            ((MockHttpServletResponse) res).setStatus(202);
        };

        filter.doFilterInternal(request, response, chain);

        assertEquals("test-request_1", response.getHeader("X-Request-Id"));
        assertEquals(202, response.getStatus());
        assertNull(MDC.get("request_id"));
    }

    @Test
    void emitsStructuredJsonRequestEvent() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(RequestLoggingFilter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/shows/123");
            request.addHeader("X-Request-Id", "correlation-123");
            MockHttpServletResponse response = new MockHttpServletResponse();
            response.setStatus(200);

            filter.doFilterInternal(request, response, (req, res) -> {});

            var event = new ObjectMapper().readTree(appender.list.get(0).getFormattedMessage());
            assertEquals("http_request", event.get("event").asText());
            assertEquals("correlation-123", event.get("request_id").asText());
            assertEquals("GET", event.get("method").asText());
            assertEquals("/shows/123", event.get("path").asText());
            assertEquals(200, event.get("status").asInt());
            assertTrue(event.get("duration_ms").asLong() >= 0);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void replacesInvalidRequestIdWithGeneratedUuid() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/shows");
        request.addHeader("X-Request-Id", "contains spaces");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, (req, res) -> {});

        String requestId = response.getHeader("X-Request-Id");
        assertNotNull(requestId);
        assertTrue(requestId.matches("[0-9a-f-]{36}"));
    }

    @Test
    void restoresPreviousMdcRequestIdWhenRequestFails() {
        MDC.put("request_id", "outer-request");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/fails");
        MockHttpServletResponse response = new MockHttpServletResponse();

        try {
            org.junit.jupiter.api.Assertions.assertThrows(
                    jakarta.servlet.ServletException.class,
                    () -> filter.doFilterInternal(request, response,
                            (req, res) -> { throw new jakarta.servlet.ServletException("failed"); }));

            assertEquals("outer-request", MDC.get("request_id"));
        } finally {
            MDC.remove("request_id");
        }
    }
}
