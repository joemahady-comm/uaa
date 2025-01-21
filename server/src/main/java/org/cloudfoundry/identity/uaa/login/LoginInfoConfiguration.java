package org.cloudfoundry.identity.uaa.login;

import org.cloudfoundry.identity.uaa.provider.IdentityProviderProvisioning;
import org.cloudfoundry.identity.uaa.provider.oauth.ExternalOAuthProviderConfigurator;
import org.cloudfoundry.identity.uaa.provider.oauth.OidcMetadataFetcher;
import org.cloudfoundry.identity.uaa.provider.saml.FixedHttpMetaDataProvider;
import org.cloudfoundry.identity.uaa.provider.saml.SamlIdentityProviderConfigurator;
import org.cloudfoundry.identity.uaa.util.UaaRandomStringUtil;
import org.cloudfoundry.identity.uaa.zone.IdentityZoneProvisioning;
import org.cloudfoundry.identity.uaa.zone.beans.IdentityZoneManager;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class LoginInfoConfiguration {

    @Bean("loginInfoIdpCacheEnabled")
    public boolean loginInfoIdpCacheEnabled(
            final @Value("${login.loginInfoIdpCache.enabled:false}") boolean enabled
    ) {
        return enabled;
    }

    @Bean("loginInfoIdpCacheDurationInMs")
    public long loginInfoIdpCacheDurationInMs(
            final @Value("${login.loginInfoIdpCache.durationInMs:30000}") long durationInMs
    ) {
        return Math.max(durationInMs, 0); // must be positive
    }

    @Bean("loginInfoExternalOAuthProviderConfigurator")
    public ExternalOAuthProviderConfigurator loginInfoExternalOAuthProviderConfigurator(
            final @Qualifier("loginInfoIdpCacheEnabled") boolean idpCacheEnabled,
            final @Qualifier("loginInfoIdpCacheDurationInMs") long cacheDurationInMs,
            final @Qualifier("externalOAuthProviderConfigurator") ExternalOAuthProviderConfigurator nonCachingConfigurator,
            final @Qualifier("identityProviderProvisioning") IdentityProviderProvisioning providerProvisioning,
            final OidcMetadataFetcher oidcMetadataFetcher,
            final UaaRandomStringUtil uaaRandomStringUtil,
            final @Qualifier("identityZoneProvisioning") IdentityZoneProvisioning identityZoneProvisioning,
            final IdentityZoneManager identityZoneManager
    ) {
        if (!idpCacheEnabled) {
            return nonCachingConfigurator;
        }

        return new CachingExternalOAuthProviderConfigurator(
                providerProvisioning,
                oidcMetadataFetcher,
                uaaRandomStringUtil,
                identityZoneProvisioning,
                identityZoneManager,
                cacheDurationInMs
        );
    }

    @Bean("loginInfoSamlProviderConfigurator")
    public SamlIdentityProviderConfigurator loginInfoSamlProviderConfigurator(
            final @Qualifier("loginInfoIdpCacheEnabled") boolean idpCacheEnabled,
            final @Qualifier("loginInfoIdpCacheDurationInMs") long cacheDurationInMs,
            final @Qualifier("metaDataProviders") SamlIdentityProviderConfigurator nonCachingConfigurator,
            final @Qualifier("identityProviderProvisioning") IdentityProviderProvisioning providerProvisioning,
            final @Qualifier("identityZoneManager") IdentityZoneManager identityZoneManager,
            final @Qualifier("fixedHttpMetaDataProvider") FixedHttpMetaDataProvider fixedHttpMetaDataProvider
    ) {
        if (!idpCacheEnabled) {
            return nonCachingConfigurator;
        }

        return new CachingSamlProviderConfigurator(
                providerProvisioning,
                identityZoneManager,
                fixedHttpMetaDataProvider,
                cacheDurationInMs
        );
    }

}
