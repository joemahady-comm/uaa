/*
 * *****************************************************************************
 *     Cloud Foundry
 *     Copyright (c) [2009-2016] Pivotal Software, Inc. All Rights Reserved.
 *
 *     This product is licensed to you under the Apache License, Version 2.0 (the "License").
 *     You may not use this product except in compliance with the License.
 *
 *     This product includes a number of subcomponents with
 *     separate copyright notices and license terms. Your use of these
 *     subcomponents is subject to the terms and conditions of the
 *     subcomponent's license, as noted in the LICENSE file.
 *******************************************************************************/
package org.cloudfoundry.identity.api.web;

import org.cloudfoundry.identity.uaa.oauth.client.test.OAuth2ContextConfiguration;
import org.cloudfoundry.identity.uaa.oauth.common.OAuth2AccessToken;
import org.cloudfoundry.identity.uaa.test.TestAccountExtension;
import org.cloudfoundry.identity.uaa.test.UaaTestAccounts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Dave Syer
 */
@OAuth2ContextConfiguration
class AppsIntegrationTests {

    @RegisterExtension
    private static final ServerRunningExtension serverRunning = ServerRunningExtension.connect();

    private static final UaaTestAccounts testAccounts = UaaTestAccounts.standard(serverRunning);

    @RegisterExtension
    private static final TestAccountExtension testAccountExtension = TestAccountExtension.standard(serverRunning, testAccounts);

    @RegisterExtension
    private static final OAuth2ContextExtension context = OAuth2ContextExtension.withTestAccounts(serverRunning, testAccountExtension);

    /**
     * tests a happy-day flow of the native application profile.
     */
    @Test
    void happyDay() {
        RestTemplate restTemplate = serverRunning.createRestTemplate();
        ResponseEntity<String> response = restTemplate.getForEntity(serverRunning.getUrl("/api/apps"), String.class);
        // first, make sure the resource is actually protected.
        assertThat(response.getStatusCode()).isNotSameAs(HttpStatus.OK);
        HttpHeaders approvalHeaders = new HttpHeaders();
        OAuth2AccessToken accessToken = context.getAccessToken();
        approvalHeaders.set("Authorization", "bearer " + accessToken.getValue());

        try {
        ResponseEntity<String> result = serverRunning.getForString("/", approvalHeaders);
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        String body = result.getBody();
        assertThat(body).as("Wrong response: " + body).contains("dsyerapi.cloudfoundry.com");
        } catch (RestClientException e) {
        }
    }
}
