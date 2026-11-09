package dev.aishsunil.provisioning.common;

import java.io.IOException;
import java.util.UUID;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;

/**
 * Puts a request id into the logging context for the life of one request.
 *
 * <p>An activation touches three services. When one of them fails, the only way
 * to reconstruct what happened is to find every log line belonging to that one
 * activation attempt, which means the id has to arrive with the request, survive
 * the hop, and leave on the response. The bench's failure triage depends on it,
 * so this filter is load-bearing rather than decoration.
 */
public class RequestIdFilter implements Filter {

    public static final String HEADER = "X-Request-Id";

    public static final String MDC_KEY = "requestId";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        String requestId = resolveRequestId(request);
        MDC.put(MDC_KEY, requestId);
        try {
            if (response instanceof HttpServletResponse httpResponse) {
                httpResponse.setHeader(HEADER, requestId);
            }
            chain.doFilter(request, response);
        }
        finally {
            // Servlet containers reuse threads, so a key left behind here would
            // attach this activation's id to an unrelated later request.
            MDC.remove(MDC_KEY);
        }
    }

    private String resolveRequestId(ServletRequest request) {
        if (request instanceof HttpServletRequest httpRequest) {
            String incoming = httpRequest.getHeader(HEADER);
            if (incoming != null && !incoming.isBlank()) {
                return incoming;
            }
        }
        return UUID.randomUUID().toString();
    }
}
