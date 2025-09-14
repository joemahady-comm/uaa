/*
 * *****************************************************************************
 *      Cloud Foundry
 *      Copyright (c) [2009-2015] Pivotal Software, Inc. All Rights Reserved.
 *      This product is licensed to you under the Apache License, Version 2.0 (the "License").
 *      You may not use this product except in compliance with the License.
 *
 *      This product includes a number of subcomponents with
 *      separate copyright notices and license terms. Your use of these
 *      subcomponents is subject to the terms and conditions of the
 *      subcomponent's license, as noted in the LICENSE file.
 * *****************************************************************************
 */

package org.cloudfoundry.identity.uaa.provider.saml;

import org.cloudfoundry.identity.uaa.provider.SamlIdentityProviderDefinition;
import org.cloudfoundry.identity.uaa.zone.IdentityZone;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SamlRedirectUtilsTest {

    private static final String ENTITY_ID = "entityId";
    private static final String ZONE_ID = "zone-id";

    @Test
    void getIdpRedirectUrl() {
        SamlIdentityProviderDefinition definition =
                new SamlIdentityProviderDefinition()
                        .setMetaDataLocation("http://some.meta.data")
                        .setIdpEntityAlias("simplesamlphp-url")
                        .setNameID("nameID")
                        .setMetadataTrustCheck(true)
                        .setLinkText("link text")
                        .setZoneId(IdentityZone.getUaaZoneId());

        String url = SamlRedirectUtils.getIdpRedirectUrl(definition);
        assertThat(url).isEqualTo("saml2/authenticate/simplesamlphp-url");
    }

    @Test
    void getZonifiedEntityId() {
        assertThat(SamlRedirectUtils.getZonifiedEntityId(ENTITY_ID, IdentityZone.getUaa())).isEqualTo(ENTITY_ID);
    }

    @Test
    void getZonifiedEntityId_forOtherZone() {
        IdentityZone otherZone = new IdentityZone();
        otherZone.setId(ZONE_ID);
        otherZone.setSubdomain(ZONE_ID);

        assertThat(SamlRedirectUtils.getZonifiedEntityId(ENTITY_ID, otherZone)).isEqualTo("zone-id.entityId");
    }

    @Test
    void zonifiedValidAndInvalidEntityID() {
        IdentityZone newZone = new IdentityZone();
        newZone.setId("new-zone-id");
        newZone.setName("new-zone-id");
        newZone.setSubdomain("new-zone-id");
        newZone.getConfig().getSamlConfig().setEntityID("local-name");

        // valid entityID from SamlConfig
        assertThat(SamlRedirectUtils.getZonifiedEntityId("local-name", newZone))
                .isEqualTo("local-name");

        // remove SamlConfig
        newZone.getConfig().setSamlConfig(null);
        assertThat(SamlRedirectUtils.getZonifiedEntityId("local-idp", newZone)).isNotNull();
        // now the entityID is generated id as before this change
        assertThat(SamlRedirectUtils.getZonifiedEntityId("local-name", newZone)).isEqualTo("new-zone-id.local-name");
    }

    @Test
    void normalizeUrlForPortComparison_withHttpStandardPort_removesPort() {
        String urlWithPort = "http://example.com:80/path";
        String expected = "http://example.com/path";
        
        String result = SamlRedirectUtils.normalizeUrlForPortComparison(urlWithPort);
        
        assertThat(result).isEqualTo(expected);
    }

    @Test
    void normalizeUrlForPortComparison_withHttpsStandardPort_removesPort() {
        String urlWithPort = "https://example.com:443/path";
        String expected = "https://example.com/path";
        
        String result = SamlRedirectUtils.normalizeUrlForPortComparison(urlWithPort);
        
        assertThat(result).isEqualTo(expected);
    }

    @Test
    void normalizeUrlForPortComparison_withHttpNonStandardPort_keepsPort() {
        String urlWithPort = "http://example.com:8080/path";
        
        String result = SamlRedirectUtils.normalizeUrlForPortComparison(urlWithPort);
        
        assertThat(result).isEqualTo(urlWithPort);
    }

    @Test
    void normalizeUrlForPortComparison_withHttpsNonStandardPort_keepsPort() {
        String urlWithPort = "https://example.com:8443/path";
        
        String result = SamlRedirectUtils.normalizeUrlForPortComparison(urlWithPort);
        
        assertThat(result).isEqualTo(urlWithPort);
    }

    @Test
    void normalizeUrlForPortComparison_withoutPort_returnsUnchanged() {
        String urlWithoutPort = "http://example.com/path";
        
        String result = SamlRedirectUtils.normalizeUrlForPortComparison(urlWithoutPort);
        
        assertThat(result).isEqualTo(urlWithoutPort);
    }

    @Test
    void normalizeUrlForPortComparison_withQueryAndFragment_preservesThem() {
        String urlWithPort = "http://example.com:80/path?query=value#fragment";
        String expected = "http://example.com/path?query=value#fragment";
        
        String result = SamlRedirectUtils.normalizeUrlForPortComparison(urlWithPort);
        
        assertThat(result).isEqualTo(expected);
    }

    @Test
    void normalizeUrlForPortComparison_withUserInfo_preservesIt() {
        String urlWithPort = "http://user:pass@example.com:80/path";
        String expected = "http://user:pass@example.com/path";
        
        String result = SamlRedirectUtils.normalizeUrlForPortComparison(urlWithPort);
        
        assertThat(result).isEqualTo(expected);
    }

    @Test
    void normalizeUrlForPortComparison_withNullUrl_returnsNull() {
        String result = SamlRedirectUtils.normalizeUrlForPortComparison(null);
        
        assertThat(result).isNull();
    }

    @Test
    void normalizeUrlForPortComparison_withMalformedUrl_returnsOriginal() {
        String malformedUrl = "not-a-valid-url";
        
        String result = SamlRedirectUtils.normalizeUrlForPortComparison(malformedUrl);
        
        assertThat(result).isEqualTo(malformedUrl);
    }

    @Test
    void normalizeUrlForPortComparison_withDifferentScheme_keepsPort() {
        String urlWithPort = "ftp://example.com:80/path";
        
        String result = SamlRedirectUtils.normalizeUrlForPortComparison(urlWithPort);
        
        assertThat(result).isEqualTo(urlWithPort);
    }

    @Test
    void normalizeUrlForPortComparison_withEmptyString_returnsEmpty() {
        String result = SamlRedirectUtils.normalizeUrlForPortComparison("");
        
        assertThat(result).isEqualTo("");
    }

    @Test
    void normalizeUrlForPortComparison_withComplexUrl_handlesCorrectly() {
        String urlWithPort = "https://subdomain.example.com:443/saml/SSO/alias/provider-name?RelayState=https://app.example.com&param=value#section";
        String expected = "https://subdomain.example.com/saml/SSO/alias/provider-name?RelayState=https://app.example.com&param=value#section";
        
        String result = SamlRedirectUtils.normalizeUrlForPortComparison(urlWithPort);
        
        assertThat(result).isEqualTo(expected);
    }
}
