package org.example.seatreservation.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.slf4j.MDC;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
    void replacesInvalidRequestIdWithGeneratedUuid() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/shows");
        request.addHeader("X-Request-Id", "contains spaces");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, (req, res) -> {});

        String requestId = response.getHeader("X-Request-Id");
        assertNotNull(requestId);
        assertTrue(requestId.matches("[0-9a-f-]{36}"));
    }
}
