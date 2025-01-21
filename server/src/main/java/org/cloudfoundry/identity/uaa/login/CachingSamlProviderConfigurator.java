package org.cloudfoundry.identity.uaa.login;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import org.cloudfoundry.identity.uaa.provider.IdentityProviderProvisioning;
import org.cloudfoundry.identity.uaa.provider.SamlIdentityProviderDefinition;
import org.cloudfoundry.identity.uaa.provider.saml.FixedHttpMetaDataProvider;
import org.cloudfoundry.identity.uaa.provider.saml.SamlIdentityProviderConfigurator;
import org.cloudfoundry.identity.uaa.zone.IdentityZone;
import org.cloudfoundry.identity.uaa.zone.beans.IdentityZoneManager;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

/**
 * Extension for {@link SamlIdentityProviderConfigurator} that caches the IdPs returned by
 * {@link SamlIdentityProviderConfigurator#getIdentityProviderDefinitions(List, IdentityZone)}. The cache is neither
 * synchronized across app instances nor invalidated after the set of IdPs changes (e.g., when an IdP is deleted).
 * Therefore, an inconsistent set of IdPs might be returned until the cache expires.
 */
public class CachingSamlProviderConfigurator extends SamlIdentityProviderConfigurator {

    private final LoadingCache<CacheKey, List<SamlIdentityProviderDefinition>> cache;

    public CachingSamlProviderConfigurator(
            final IdentityProviderProvisioning providerProvisioning,
            final IdentityZoneManager identityZoneManager,
            final FixedHttpMetaDataProvider fixedHttpMetaDataProvider,
            final long cacheDurationInMs
    ) {
        super(providerProvisioning, identityZoneManager, fixedHttpMetaDataProvider);

        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(cacheDurationInMs, MILLISECONDS)
                .build(cacheKey -> {
                    final List<String> allowedIdps = Optional.ofNullable(cacheKey.allowedIdps())
                            .map(ArrayList::new)
                            .orElse(null); // all IdPs are allowed
                    return super.getIdentityProviderDefinitions(allowedIdps, cacheKey.zone());
                });
    }

    private record CacheKey(IdentityZone zone, Set<String> allowedIdps) {
    }

    @Override
    public List<SamlIdentityProviderDefinition> getIdentityProviderDefinitions(
            @Nullable final List<String> allowedIdps,
            @Nonnull final IdentityZone zone
    ) {
        final Set<String> allowedIdpsAsSet = Optional.ofNullable(allowedIdps)
                .map(HashSet::new)
                .orElse(null); // all IdPs are allowed
        final CacheKey cacheKey = new CacheKey(zone, allowedIdpsAsSet);
        return cache.get(cacheKey);
    }
}
