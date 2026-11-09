package dev.aishsunil.provisioning.gateway;

import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.UUID;

import dev.aishsunil.provisioning.common.RequestIdFilter;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Puts a request id on the inbound request itself, before it is proxied.
 *
 * <p>The filter in {@code common} records the id for logging and echoes it on
 * the response, which is enough for a service that only talks to itself. The
 * gateway forwards the request it was given, so an id that exists only on the
 * response never reaches the service behind it, and the whole point of the id
 * is that all three services log the same one. Wrapping the request is what
 * makes it travel.
 */
@Configuration(proxyBeanMethods = false)
public class RequestIdInjectingFilter {

    @Bean
    public FilterRegistrationBean<Filter> requestIdInjectingFilterRegistration() {
        FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>(new InjectingFilter());
        registration.addUrlPatterns("/*");
        // Ahead of the logging filter in common, which reads what this writes.
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE - 1);
        return registration;
    }

    static class InjectingFilter implements Filter {

        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
                throws IOException, ServletException {
            if (!(request instanceof HttpServletRequest httpRequest)) {
                chain.doFilter(request, response);
                return;
            }
            String incoming = httpRequest.getHeader(RequestIdFilter.HEADER);
            if (incoming != null && !incoming.isBlank()) {
                chain.doFilter(httpRequest, response);
                return;
            }
            chain.doFilter(new WithRequestId(httpRequest, UUID.randomUUID().toString()), response);
        }

    }

    static class WithRequestId extends HttpServletRequestWrapper {

        private final String requestId;

        WithRequestId(HttpServletRequest request, String requestId) {
            super(request);
            this.requestId = requestId;
        }

        @Override
        public String getHeader(String name) {
            if (RequestIdFilter.HEADER.equalsIgnoreCase(name)) {
                return this.requestId;
            }
            return super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            if (RequestIdFilter.HEADER.equalsIgnoreCase(name)) {
                return Collections.enumeration(Collections.singletonList(this.requestId));
            }
            return super.getHeaders(name);
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            java.util.List<String> names = Collections.list(super.getHeaderNames());
            if (names.stream().noneMatch(RequestIdFilter.HEADER::equalsIgnoreCase)) {
                names.add(RequestIdFilter.HEADER);
            }
            return Collections.enumeration(names);
        }

    }

}
