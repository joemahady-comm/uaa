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

package org.cloudfoundry.identity.uaa.mock.zones;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.cloudfoundry.identity.uaa.DefaultTestContext;
import org.cloudfoundry.identity.uaa.mock.util.MockMvcUtils;
import org.cloudfoundry.identity.uaa.test.TestClient;
import org.cloudfoundry.identity.uaa.util.AlphanumericRandomValueStringGenerator;
import org.cloudfoundry.identity.uaa.zone.IdentityZone;
import org.cloudfoundry.identity.uaa.zone.IdentityZoneResolvingFilter;
import org.cloudfoundry.identity.uaa.zone.MultitenancyFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DefaultTestContext
class IdentityZoneResolvingMockMvcTest {

    private Set<String> originalHostnames;

    private MockMvc mockMvc;
    private TestClient testClient;
    private IdentityZoneResolvingFilter identityZoneResolvingFilter;

    @BeforeEach
    void storeSettings(
            @Autowired MockMvc mockMvc,
            @Autowired TestClient testClient,
            @Autowired FilterRegistrationBean<IdentityZoneResolvingFilter> identityZoneResolvingFilter
    ) {
        this.mockMvc = mockMvc;
        this.testClient = testClient;
        this.identityZoneResolvingFilter = identityZoneResolvingFilter.getFilter();

        originalHostnames = this.identityZoneResolvingFilter.getDefaultZoneHostnames();
    }

    @AfterEach
    void restoreSettings() {
        identityZoneResolvingFilter.restoreDefaultHostnames(originalHostnames);
    }

    @Test
    void switchingZones() throws Exception {
        // Authenticate with new Client in new Zone
        mockMvc.perform(
                        get("/login")
                                .header("Host", "testsomeother.ip.com")
                )
                .andExpect(status().isOk());
    }

    @Nested
    @DefaultTestContext
    class WithCustomInternalHostnames {

        @BeforeEach
        void setUp() {
            Set<String> hosts = new HashSet<>(Arrays.asList("localhost", "testsomeother.ip.com"));
            identityZoneResolvingFilter.setDefaultInternalHostnames(hosts);
        }

        @ParameterizedTest
        @ValueSource(strings = {"localhost", "testsomeother.ip.com"})
        void isFound(String hostname) throws Exception {
            // Authenticate with new Client in new Zone
            mockMvc.perform(
                            get("/login")
                                    .header("Host", hostname)
                    )
                    .andExpect(status().isOk());

        }

        @ParameterizedTest
        @ValueSource(strings = {"notlocalhost", "testsomeother2.ip.com"})
        void isNotFound(String hostname) throws Exception {
            mockMvc.perform(
                            get("/login")
                                    .header("Host", hostname)
                    )
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DefaultTestContext
    class XZidHeader {

        private static final String HOST_NO_SUBDOMAIN = "uaa.mycf.com";

        private final String zone1Id = UUID.randomUUID().toString();
        private final String zone1Subdomain = generateRandomSubdomain();

        private final String zone2Id = UUID.randomUUID().toString();
        private final String zone2Subdomain = generateRandomSubdomain();

        private final String nonExistingZoneId = UUID.randomUUID().toString();
        private final String nonExistingZoneSubdomain = generateRandomSubdomain();

        @BeforeEach
        void setUp() throws Exception {
            identityZoneResolvingFilter.setDefaultInternalHostnames(Set.of(HOST_NO_SUBDOMAIN, "localhost"));

            createIdz(zone1Id, zone1Subdomain);
            createIdz(zone2Id, zone2Subdomain);
        }

        @AfterEach
        void tearDown() throws Exception {
            MockMvcUtils.deleteIdentityZone(zone1Id, mockMvc);
            MockMvcUtils.deleteIdentityZone(zone2Id, mockMvc);
        }

        @Nested
        @DefaultTestContext
        class Enabled {

            @BeforeEach
            void setUp() {
                arrangeZidHeaderEnabled(true);
            }

            @Test
            void subdomainNotSet_ZidHeaderSetToZone2_Zone2Exists_ShouldReturnZone2() throws Exception {
                mockMvc.perform(
                        get("/login")
                                .header("Host", HOST_NO_SUBDOMAIN)
                                .header("X-Zid", zone2Id)
                                .header("Accept", "application/json")
                ).andExpect(resolvedZoneWithName(zone2Subdomain));
            }

            @Test
            void subdomainSetToZone1_ZidHeaderSetToZone2_BothZonesExist_ShouldReturnZone2() throws Exception {
                mockMvc.perform(
                        get("/login")
                                .header("Host", zone1Subdomain + "." + HOST_NO_SUBDOMAIN)
                                .header("X-Zid", zone2Id)
                                .header("Accept", "application/json")
                ).andExpect(resolvedZoneWithName(zone2Subdomain));
            }

            @Test
            void subdomainSetToNonExistingZone_ZidHeaderSetToZone2_ShouldReturnZone2() throws Exception {
                mockMvc.perform(
                        get("/login")
                                .header("Host", nonExistingZoneSubdomain + "." + HOST_NO_SUBDOMAIN)
                                .header("X-Zid", zone2Id)
                                .header("Accept", "application/json")
                ).andExpect(resolvedZoneWithName(zone2Subdomain));
            }

            @Test
            void subdomainNotSet_ZidHeaderSetToNonExistingZone_ShouldReturn404() throws Exception {
                mockMvc.perform(
                        get("/login")
                                .header("Host", HOST_NO_SUBDOMAIN)
                                .header("X-Zid", nonExistingZoneId)
                                .header("Accept", "application/json")
                ).andExpect(status().isNotFound());
            }

            @Test
            void subdomainSetToExistingZone_ZidHeaderSetToNonExistingZone_ShouldReturn404() throws Exception {
                mockMvc.perform(
                        get("/login")
                                .header("Host", zone1Subdomain + "." + HOST_NO_SUBDOMAIN)
                                .header("X-Zid", nonExistingZoneId)
                                .header("Accept", "application/json")
                ).andExpect(status().isNotFound());
            }

            @AfterEach
            void tearDown() {
                arrangeZidHeaderEnabled(false);
            }

            private void arrangeZidHeaderEnabled(final boolean enabled) {
                ReflectionTestUtils.setField(identityZoneResolvingFilter, "zidHeaderEnabled", enabled);
            }
        }

        @Nested
        @DefaultTestContext
        class Disabled {

            @Test
            void subdomainNotSet_ZidHeaderSetToZone2_Zone2Exists_ShouldReturnUaaZone() throws Exception {
                mockMvc.perform(
                        get("/login")
                                .header("Host", HOST_NO_SUBDOMAIN)
                                .header("X-Zid", zone2Id)
                                .header("Accept", "application/json")
                ).andExpect(resolvedUaaZone());
            }

            @Test
            void subdomainSetToZone1_ZidHeaderSetToZone2_BothZonesExist_ShouldReturnZone1() throws Exception {
                mockMvc.perform(
                        get("/login")
                                .header("Host", zone1Subdomain + "." + HOST_NO_SUBDOMAIN)
                                .header("X-Zid", zone2Id)
                                .header("Accept", "application/json")
                ).andExpect(resolvedZoneWithName(zone1Subdomain));
            }

            @Test
            void subdomainSetToNonExistingZone_ZidHeaderSetToZone2_ShouldReturn404() throws Exception {
                mockMvc.perform(
                        get("/login")
                                .header("Host", nonExistingZoneSubdomain + "." + HOST_NO_SUBDOMAIN)
                                .header("X-Zid", zone2Id)
                                .header("Accept", "application/json")
                ).andExpect(status().isNotFound());
            }

            @Test
            void subdomainNotSet_ZidHeaderSetToNonExistingZone_ShouldReturnUaaZone() throws Exception {
                mockMvc.perform(
                        get("/login")
                                .header("Host", HOST_NO_SUBDOMAIN)
                                .header("X-Zid", nonExistingZoneId)
                                .header("Accept", "application/json")
                ).andExpect(resolvedUaaZone());
            }

            @Test
            void subdomainSetToZone1_ZidHeaderSetToNonExistingZone_ShouldReturnZone1() throws Exception {
                mockMvc.perform(
                        get("/login")
                                .header("Host", zone1Subdomain + "." + HOST_NO_SUBDOMAIN)
                                .header("X-Zid", nonExistingZoneId)
                                .header("Accept", "application/json")
                ).andExpect(resolvedZoneWithName(zone1Subdomain));
            }
        }
    }

    private void createIdz(final String zoneId, final String subdomain) throws Exception {
        final String identityToken = testClient.getClientCredentialsOAuthAccessToken(
                "identity",
                "identitysecret",
                "zones.write");
        final IdentityZone zone = MultitenancyFixture.identityZone(zoneId, subdomain);
        zone.setName(subdomain);
        MockMvcUtils.createZoneUsingWebRequest(mockMvc, identityToken, zone);
    }

    private static ResultMatcher resolvedUaaZone() {
        return resolvedZoneWithName(IdentityZone.getUaa().getName());
    }

    private static ResultMatcher resolvedZoneWithName(final String idzName) {
        final ObjectMapper objectMapper = new ObjectMapper();
        return result -> {
            final MockHttpServletResponse response = result.getResponse();
            assertThat(response).isNotNull();
            assertThat(response.getStatus()).isEqualTo(200);

            final String responseContentAsString = response.getContentAsString();
            assertThat(responseContentAsString).isNotNull();
            final Map<String, Object> responseContent = objectMapper.readValue(
                    responseContentAsString,
                    new TypeReference<>() {
                    }
            );
            assertThat(responseContent).containsEntry("zone_name", idzName);
        };
    }

    private static String generateRandomSubdomain() {
        final String randomString = new AlphanumericRandomValueStringGenerator(8).generate().toLowerCase();
        if ('0' <= randomString.charAt(0) && randomString.charAt(0) <= '9') {
            // Ensure the first character is not a digit, as subdomains cannot start with a digit
            return "a" + randomString.substring(1);
        }
        return randomString;
    }
}
