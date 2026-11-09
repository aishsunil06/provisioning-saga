package dev.aishsunil.provisioning.common;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;

import static org.assertj.core.api.Assertions.assertThat;

class RequestIdAutoConfigurationTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RequestIdAutoConfiguration.class));

    @Test
    void registersTheFilterForEveryRequest() {
        this.runner.run(context -> {
            assertThat(context).hasSingleBean(FilterRegistrationBean.class);
            FilterRegistrationBean<?> registration = context.getBean(FilterRegistrationBean.class);
            assertThat(registration.getFilter()).isInstanceOf(RequestIdFilter.class);
            assertThat(registration.getUrlPatterns()).containsExactly("/*");
        });
    }

    @Test
    void backsOffWhenTheApplicationDefinesItsOwnFilter() {
        this.runner.withBean("requestIdFilter", RequestIdFilter.class, RequestIdFilter::new)
                .run(context -> assertThat(context).hasSingleBean(RequestIdFilter.class));
    }
}
