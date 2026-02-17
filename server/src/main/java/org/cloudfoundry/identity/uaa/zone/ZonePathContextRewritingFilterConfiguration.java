package org.cloudfoundry.identity.uaa.zone;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ZonePathContextRewritingFilterConfiguration {

    /**
     * Zone path rewriting runs as a servlet filter (enabled) so it executes before Spring Security
     * selects a filter chain. That way the request path is rewritten to context path + servlet path
     * (e.g. /z/myzone + /Codes) before security matchers run, so patterns like /Codes/** match correctly.
     */
    @Bean(ZonePathContextRewritingFilter.BEAN_NAME)
    FilterRegistrationBean<ZonePathContextRewritingFilter> zonePathContextRewritingFilter() {
        ZonePathContextRewritingFilter filter = new ZonePathContextRewritingFilter();
        FilterRegistrationBean<ZonePathContextRewritingFilter> bean = new FilterRegistrationBean<>(filter);
        bean.setOrder(org.springframework.core.Ordered.HIGHEST_PRECEDENCE + 50); // before Spring Security (default -100)
        return bean;
    }
}
