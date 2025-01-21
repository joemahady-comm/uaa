package org.cloudfoundry.identity.uaa.login;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.github.benmanes.caffeine.cache.Ticker;
import org.cloudfoundry.identity.uaa.provider.IdentityProvider;
import org.cloudfoundry.identity.uaa.provider.IdentityProviderProvisioning;
import org.cloudfoundry.identity.uaa.provider.oauth.ExternalOAuthProviderConfigurator;
import org.cloudfoundry.identity.uaa.provider.oauth.OidcMetadataFetcher;
import org.cloudfoundry.identity.uaa.util.UaaRandomStringUtil;
import org.cloudfoundry.identity.uaa.zone.IdentityZoneProvisioning;
import org.cloudfoundry.identity.uaa.zone.beans.IdentityZoneManager;

import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.stream.Collectors.toSet;

/**
 * Extension for {@link ExternalOAuthProviderConfigurator} that caches the IdPs returned by
 * {@link ExternalOAuthProviderConfigurator#retrieveActiveByTypes(String, String...)}. The cache is neither synchronized
 * across app instances nor invalidated after the set of IdPs changes (e.g., when an IdP is deleted). Therefore, an
 * inconsistent set of IdPs might be returned until the cache expires.
 */
public class CachingExternalOAuthProviderConfigurator extends ExternalOAuthProviderConfigurator {

    private final LoadingCache<CacheKey, List<IdentityProvider>> cache;

    public CachingExternalOAuthProviderConfigurator(
            final IdentityProviderProvisioning providerProvisioning,
            final OidcMetadataFetcher oidcMetadataFetcher,
            final UaaRandomStringUtil uaaRandomStringUtil,
            final IdentityZoneProvisioning identityZoneProvisioning,
            final IdentityZoneManager identityZoneManager,
            final long cacheDurationInMs
    ) {
        this(providerProvisioning, oidcMetadataFetcher, uaaRandomStringUtil, identityZoneProvisioning,
                identityZoneManager, cacheDurationInMs, Ticker.systemTicker());
    }

    /** Constructor for testing; allows injecting ticker to advance time. */
    CachingExternalOAuthProviderConfigurator(
            final IdentityProviderProvisioning providerProvisioning,
            final OidcMetadataFetcher oidcMetadataFetcher,
            final UaaRandomStringUtil uaaRandomStringUtil,
            final IdentityZoneProvisioning identityZoneProvisioning,
            final IdentityZoneManager identityZoneManager,
            final long cacheDurationInMs,
            final Ticker ticker
    ) {
        super(providerProvisioning, oidcMetadataFetcher, uaaRandomStringUtil, identityZoneProvisioning,
                identityZoneManager);

        this.cache = Caffeine.newBuilder()
                .ticker(ticker)
                .expireAfterWrite(cacheDurationInMs, MILLISECONDS)
                .build(cacheKey ->
                        super.retrieveActiveByTypes(cacheKey.zoneId(), cacheKey.types().toArray(new String[0]))
                );
    }

    private record CacheKey(String zoneId, Set<String> types) {
    }

    @Override
    public List<IdentityProvider> retrieveActiveByTypes(final String zoneId, final String... types) {
        final Set<String> typesAsSet = Stream.of(types).collect(toSet());
        final CacheKey cacheKey = new CacheKey(zoneId, typesAsSet);
        return cache.get(cacheKey);
    }
}
