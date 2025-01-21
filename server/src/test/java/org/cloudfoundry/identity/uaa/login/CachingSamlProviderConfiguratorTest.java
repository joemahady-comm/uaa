package org.cloudfoundry.identity.uaa.login;

import com.google.common.testing.FakeTicker;
import org.cloudfoundry.identity.uaa.provider.AbstractIdentityProviderDefinition;
import org.cloudfoundry.identity.uaa.provider.IdentityProvider;
import org.cloudfoundry.identity.uaa.provider.IdentityProviderProvisioning;
import org.cloudfoundry.identity.uaa.provider.SamlIdentityProviderDefinition;
import org.cloudfoundry.identity.uaa.provider.saml.FixedHttpMetaDataProvider;
import org.cloudfoundry.identity.uaa.zone.IdentityZone;
import org.cloudfoundry.identity.uaa.zone.beans.IdentityZoneManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.cloudfoundry.identity.uaa.constants.OriginKeys.SAML;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CachingSamlProviderConfiguratorTest {
    private static final long CACHE_DURATION_IN_MS = 50_000;

    private final FakeTicker fakeTicker = new FakeTicker();
    private final IdentityZone zone;

    @Mock
    private IdentityProviderProvisioning providerProvisioning;

    @Mock
    private IdentityZoneManager identityZoneManager;

    @Mock
    private FixedHttpMetaDataProvider fixedHttpMetaDataProvider;

    private CachingSamlProviderConfigurator configurator;

    public CachingSamlProviderConfiguratorTest() {
        final String zoneId = UUID.randomUUID().toString();
        zone = new IdentityZone();
        zone.setId(zoneId);
    }

    @BeforeEach
    void setUp() {
        configurator = new CachingSamlProviderConfigurator(
                providerProvisioning,
                identityZoneManager,
                fixedHttpMetaDataProvider,
                CACHE_DURATION_IN_MS,
                fakeTicker::read
        );
    }

    @Test
    void shouldUseCache_WhenAlreadyCalledEarlierAndNotExpired() {
        // arrange real result 1
        final List<IdentityProvider> idps1 = List.of(
                buildMockSamlIdp("origin1"),
                buildMockSamlIdp("origin2"),
                buildMockSamlIdp("origin3")
        );
        arrangeIdpsForZone(idps1, zone);

        final List<String> allowedIdps = List.of("origin1", "origin2", "origin4");

        final List<SamlIdentityProviderDefinition> resultAfter1stCall = configurator.getIdentityProviderDefinitions(
                allowedIdps,
                zone
        );
        assertRealMethodIsCalled(zone);
        final List<AbstractIdentityProviderDefinition> filteredIdps1 = idps1.stream()
                .filter(it -> !Objects.equals("origin3", it.getOriginKey()))
                .map(IdentityProvider::getConfig)
                .toList();
        assertThat(resultAfter1stCall).isEqualTo(filteredIdps1);

        // advance time but stay within cache duration
        fakeTicker.advance(10, MILLISECONDS);

        // arrange real result 2
        final List<IdentityProvider> idps2 = List.of(
                buildMockSamlIdp("origin1"),
                buildMockSamlIdp("origin2"),
                buildMockSamlIdp("origin3"),
                buildMockSamlIdp("origin4")
        );
        arrangeIdpsForZone(idps2, zone);

        final List<SamlIdentityProviderDefinition> resultAfter2ndCall = configurator.getIdentityProviderDefinitions(
                allowedIdps,
                zone
        );
        assertCacheIsUsed(zone);
        assertThat(resultAfter2ndCall).isEqualTo(filteredIdps1); // should still return first result (from cache)

        // advance time such that cache duration is expired
        fakeTicker.advance(CACHE_DURATION_IN_MS, MILLISECONDS);

        final List<SamlIdentityProviderDefinition> resultAfter3rdCall = configurator.getIdentityProviderDefinitions(
                allowedIdps,
                zone
        );
        assertRealMethodIsCalled(zone);
        assertThat(resultAfter3rdCall).isEqualTo(
                // should now return new result
                idps2.stream()
                        .filter(it -> !Objects.equals("origin3", it.getOriginKey()))
                        .map(IdentityProvider::getConfig)
                        .toList()
        );
    }

    @Test
    void shouldUseCache_WhenAlreadyCalledEarlierAndNotExpired_ShouldIgnoreDuplicates() {
        // arrange real result 1
        final List<IdentityProvider> idps1 = List.of(
                buildMockSamlIdp("origin1"),
                buildMockSamlIdp("origin2")
        );
        arrangeIdpsForZone(idps1, zone);

        final List<String> allowedIdps1 = List.of("origin1", "origin2");

        final List<SamlIdentityProviderDefinition> resultAfter1stCall = configurator.getIdentityProviderDefinitions(
                allowedIdps1,
                zone
        );
        assertRealMethodIsCalled(zone);
        assertThat(resultAfter1stCall).isEqualTo(idps1.stream().map(IdentityProvider::getConfig).toList());

        // advance time but stay within cache duration
        fakeTicker.advance(10, MILLISECONDS);

        final List<String> allowedIdps2 = List.of(
                "origin1", "origin2",
                "origin2" /* duplicate */
        );

        final List<SamlIdentityProviderDefinition> resultAfter2ndCall = configurator.getIdentityProviderDefinitions(
                allowedIdps2, // same values, one of them duplicated
                zone
        );
        assertCacheIsUsed(zone);
        // should still return first result (from cache)
        assertThat(resultAfter2ndCall).isEqualTo(idps1.stream().map(IdentityProvider::getConfig).toList());
    }

    @Test
    void shouldUseDifferentCacheKeysWhenDifferentArgumentsAreUsed() {
        final IdentityZone otherZone = new IdentityZone();
        otherZone.setId(UUID.randomUUID().toString());

        final List<String> allowedIdps1 = List.of("origin1", "origin2");
        final List<String> allowedIdps2 = List.of("origin3", "origin4");

        configurator.getIdentityProviderDefinitions(allowedIdps1, zone);
        assertRealMethodIsCalled(zone);

        configurator.getIdentityProviderDefinitions(allowedIdps2, otherZone);
        assertRealMethodIsCalled(otherZone);

        configurator.getIdentityProviderDefinitions(allowedIdps2, zone);
        assertRealMethodIsCalled(zone);

        configurator.getIdentityProviderDefinitions(allowedIdps1, otherZone);
        assertRealMethodIsCalled(otherZone);

        configurator.getIdentityProviderDefinitions(allowedIdps2, otherZone);
        assertCacheIsUsed(otherZone);

        configurator.getIdentityProviderDefinitions(allowedIdps1, zone);
        assertCacheIsUsed(zone);

        configurator.getIdentityProviderDefinitions(allowedIdps2, zone);
        assertCacheIsUsed(zone);

        configurator.getIdentityProviderDefinitions(allowedIdps1, otherZone);
        assertCacheIsUsed(otherZone);
    }

    private void arrangeIdpsForZone(final List<IdentityProvider> idps, final IdentityZone zone) {
        when(providerProvisioning.retrieveActiveByTypes(zone.getId(), SAML)).thenReturn(idps);
    }

    private void assertRealMethodIsCalled(final IdentityZone zone) {
        verify(providerProvisioning, times(1)).retrieveActiveByTypes(zone.getId(), SAML);
        clearInvocations(providerProvisioning);
    }

    private void assertCacheIsUsed(final IdentityZone zone) {
        verify(providerProvisioning, never()).retrieveActiveByTypes(zone.getId(), SAML);
        clearInvocations(providerProvisioning);
    }

    private static IdentityProvider buildMockSamlIdp(final String originKey) {
        final IdentityProvider<AbstractIdentityProviderDefinition> idp = new IdentityProvider<>();
        idp.setId(UUID.randomUUID().toString());
        idp.setType(SAML);
        idp.setOriginKey(originKey);
        final SamlIdentityProviderDefinition config = new SamlIdentityProviderDefinition();
        config.setIdpEntityId(idp.getId());
        idp.setConfig(config);
        return idp;
    }
}