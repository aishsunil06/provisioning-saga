package dev.aishsunil.provisioning.common;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

/**
 * Registers {@link RequestIdFilter} in every service that puts {@code common} on
 * its classpath, so the four services do not each repeat the same registration.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class RequestIdAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(RequestIdFilter.class)
    public FilterRegistrationBean<RequestIdFilter> requestIdFilterRegistration() {
        FilterRegistrationBean<RequestIdFilter> registration = new FilterRegistrationBean<>(new RequestIdFilter());
        registration.addUrlPatterns("/*");
        // First in the chain: a log line written before the id is in the MDC
        // cannot be correlated afterwards.
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
