package org.cloudfoundry.identity.uaa.login;

import com.google.common.testing.FakeTicker;
import org.cloudfoundry.identity.uaa.provider.AbstractIdentityProviderDefinition;
import org.cloudfoundry.identity.uaa.provider.IdentityProvider;
import org.cloudfoundry.identity.uaa.provider.IdentityProviderProvisioning;
import org.cloudfoundry.identity.uaa.provider.oauth.OidcMetadataFetcher;
import org.cloudfoundry.identity.uaa.util.UaaRandomStringUtil;
import org.cloudfoundry.identity.uaa.zone.IdentityZoneProvisioning;
import org.cloudfoundry.identity.uaa.zone.beans.IdentityZoneManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.cloudfoundry.identity.uaa.constants.OriginKeys.OAUTH20;
import static org.cloudfoundry.identity.uaa.constants.OriginKeys.OIDC10;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CachingExternalOAuthProviderConfiguratorTest {

    private static final long CACHE_DURATION_IN_MS = 50_000;

    private final FakeTicker fakeTicker = new FakeTicker();
    private final String zoneId = UUID.randomUUID().toString();

    @Mock
    private IdentityProviderProvisioning identityProviderProvisioning;

    @Mock
    private OidcMetadataFetcher oidcMetadataFetcher;

    @Mock
    private UaaRandomStringUtil uaaRandomStringUtil;

    @Mock
    private IdentityZoneProvisioning identityZoneProvisioning;

    @Mock
    private IdentityZoneManager identityZoneManager;

    private CachingExternalOAuthProviderConfigurator configurator;

    @BeforeEach
    void setUp() {
        configurator = new CachingExternalOAuthProviderConfigurator(
                identityProviderProvisioning,
                oidcMetadataFetcher,
                uaaRandomStringUtil,
                identityZoneProvisioning,
                identityZoneManager,
                CACHE_DURATION_IN_MS,
                fakeTicker::read
        );
    }

    @Test
    void shouldUseCache_WhenAlreadyCalledEarlierAndNotExpired() {
        // arrange real result 1
        final List<IdentityProvider> idps1 = List.of(buildMockIdp(OIDC10), buildMockIdp(OAUTH20));
        arrangeIdpsForZone(idps1, zoneId, OIDC10, OAUTH20);

        final List<IdentityProvider> resultAfter1stCall = configurator.retrieveActiveByTypes(zoneId, OIDC10, OAUTH20);
        assertRealMethodIsCalled(zoneId, OIDC10, OAUTH20);
        assertThat(resultAfter1stCall).isEqualTo(idps1);

        // advance time but stay within cache duration
        fakeTicker.advance(10, MILLISECONDS);

        // arrange real result 2
        final List<IdentityProvider> idps2 = List.of(buildMockIdp(OIDC10), buildMockIdp(OAUTH20), buildMockIdp(OIDC10));
        arrangeIdpsForZone(idps2, zoneId, OIDC10, OAUTH20);

        final List<IdentityProvider> resultAfter2ndCall = configurator.retrieveActiveByTypes(zoneId, OIDC10, OAUTH20);
        assertCacheIsUsed(zoneId, OIDC10, OAUTH20);
        assertThat(resultAfter2ndCall).isEqualTo(idps1); // should still return first result (from cache)

        // advance time such that cache duration is expired
        fakeTicker.advance(CACHE_DURATION_IN_MS, MILLISECONDS);

        final List<IdentityProvider> resultAfter3rdCall = configurator.retrieveActiveByTypes(zoneId, OIDC10, OAUTH20);
        assertRealMethodIsCalled(zoneId, OIDC10, OAUTH20);
        assertThat(resultAfter3rdCall).isEqualTo(idps2); // should now return new result
    }

    @Test
    void shouldUseCache_WhenAlreadyCalledEarlierAndNotExpired_ShouldIgnoreOrder() {
        // arrange real result 1
        final List<IdentityProvider> idps1 = List.of(buildMockIdp(OIDC10), buildMockIdp(OAUTH20));
        arrangeIdpsForZone(idps1, zoneId, OIDC10, OAUTH20);

        final List<IdentityProvider> resultAfter1stCall = configurator.retrieveActiveByTypes(zoneId, OIDC10, OAUTH20);
        assertRealMethodIsCalled(zoneId, OIDC10, OAUTH20);
        assertThat(resultAfter1stCall).isEqualTo(idps1);

        // advance time but stay within cache duration
        fakeTicker.advance(10, MILLISECONDS);

        final List<IdentityProvider> resultAfter2ndCall = configurator.retrieveActiveByTypes(
                zoneId,
                OAUTH20, OIDC10 // same types, but in different order
        );
        assertCacheIsUsed(zoneId, OIDC10, OAUTH20);
        assertThat(resultAfter2ndCall).isEqualTo(idps1); // should still return first result (from cache)
    }

    @Test
    void shouldUseDifferentCacheKeysWhenDifferentArgumentsAreUsed() {
        final String otherZoneId = UUID.randomUUID().toString();

        configurator.retrieveActiveByTypes(zoneId, OAUTH20);
        assertRealMethodIsCalled(zoneId, OAUTH20);

        configurator.retrieveActiveByTypes(otherZoneId, OIDC10);
        assertRealMethodIsCalled(otherZoneId, OIDC10);

        configurator.retrieveActiveByTypes(zoneId, OIDC10);
        assertRealMethodIsCalled(zoneId, OIDC10);

        configurator.retrieveActiveByTypes(otherZoneId, OAUTH20);
        assertRealMethodIsCalled(otherZoneId, OAUTH20);

        configurator.retrieveActiveByTypes(otherZoneId, OIDC10);
        assertCacheIsUsed(otherZoneId, OIDC10);

        configurator.retrieveActiveByTypes(zoneId, OAUTH20);
        assertCacheIsUsed(zoneId, OAUTH20);

        configurator.retrieveActiveByTypes(zoneId, OIDC10);
        assertCacheIsUsed(zoneId, OIDC10);

        configurator.retrieveActiveByTypes(otherZoneId, OAUTH20);
        assertCacheIsUsed(otherZoneId, OAUTH20);
    }

    private void arrangeIdpsForZone(final List<IdentityProvider> idps, final String zoneId, final String... types) {
        when(identityProviderProvisioning.retrieveActiveByTypes(zoneId, types)).thenReturn(idps);
    }

    private void assertRealMethodIsCalled(final String zoneId, final String type) {
        verify(identityProviderProvisioning, times(1)).retrieveActiveByTypes(eq(zoneId), eq(type));
        clearInvocations(identityProviderProvisioning);
    }

    private void assertRealMethodIsCalled(final String zoneId, final String type1, final String type2) {
        verify(identityProviderProvisioning, times(1)).retrieveActiveByTypes(eq(zoneId), eq(type1), eq(type2));
        clearInvocations(identityProviderProvisioning);
    }

    private void assertCacheIsUsed(final String zoneId, final String type) {
        verify(identityProviderProvisioning, never()).retrieveActiveByTypes(eq(zoneId), eq(type));
        clearInvocations(identityProviderProvisioning);
    }

    private void assertCacheIsUsed(final String zoneId, final String type1, final String type2) {
        // no call with any order of the types should occur
        verify(identityProviderProvisioning, never()).retrieveActiveByTypes(eq(zoneId), eq(type1), eq(type2));
        verify(identityProviderProvisioning, never()).retrieveActiveByTypes(eq(zoneId), eq(type2), eq(type1));

        clearInvocations(identityProviderProvisioning);
    }

    private static IdentityProvider buildMockIdp(final String type) {
        final IdentityProvider<AbstractIdentityProviderDefinition> idp = new IdentityProvider<>();
        idp.setId(UUID.randomUUID().toString());
        idp.setType(type);
        return idp;
    }
}