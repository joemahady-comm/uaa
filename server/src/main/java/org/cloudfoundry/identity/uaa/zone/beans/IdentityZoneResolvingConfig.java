package org.cloudfoundry.identity.uaa.zone.beans;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class IdentityZoneResolvingConfig {

    @Bean
    @Qualifier("zidHeaderEnabled")
    public boolean zidHeaderEnabled(@Value("${login.zidHeaderEnabled:false}") final boolean enabled) {
        return enabled;
    }

}
