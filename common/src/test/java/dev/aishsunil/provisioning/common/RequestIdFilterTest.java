package dev.aishsunil.provisioning.common;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class RequestIdFilterTest {

    @Test
    void generatesRequestIdWhenHeaderAbsent() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpServletResponse res = new MockHttpServletResponse();
        AtomicReference<String> seen = new AtomicReference<>();

        new RequestIdFilter().doFilter(req, res, (q, s) -> seen.set(MDC.get(RequestIdFilter.MDC_KEY)));

        assertThat(seen.get()).isNotBlank();
        assertThat(res.getHeader(RequestIdFilter.HEADER)).isEqualTo(seen.get());
        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void propagatesIncomingRequestId() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader(RequestIdFilter.HEADER, "abc-123");
        MockHttpServletResponse res = new MockHttpServletResponse();
        AtomicReference<String> seen = new AtomicReference<>();

        new RequestIdFilter().doFilter(req, res, (q, s) -> seen.set(MDC.get(RequestIdFilter.MDC_KEY)));

        assertThat(seen.get()).isEqualTo("abc-123");
        assertThat(res.getHeader(RequestIdFilter.HEADER)).isEqualTo("abc-123");
        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void clearsMdcEvenWhenTheChainThrows() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpServletResponse res = new MockHttpServletResponse();

        try {
            new RequestIdFilter().doFilter(req, res, (q, s) -> {
                throw new IllegalStateException("downstream blew up");
            });
        }
        catch (Exception expected) {
            // the filter must not swallow it
        }

        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
    }
}
