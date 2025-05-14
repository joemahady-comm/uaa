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

import org.cloudfoundry.identity.uaa.oauth.client.test.RestTemplateHolder;
import org.cloudfoundry.identity.uaa.test.UrlHelper;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.platform.commons.logging.Logger;
import org.junit.platform.commons.logging.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestOperations;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriTemplate;

import java.io.IOException;
import java.net.HttpURLConnection;


/**
 * <p>
 * An Extension that fails integration tests if the server application
 * is not running or not accessible.
 * Usage:
 * </p>
 *
 * <pre>
 * &#064;RegisterExtension
 * public static final ServerRunningExtension serverRunning = ServerRunningExtension.connect();
 *
 * &#064;Test
 * void testSendAndReceive() {
 *      ResponseEntity<Void> response = serverRunning.postForResponse(serverRunning.getAuthorizationUri(), headers, params);
 * }
 * </pre>
 *
 * @author Dave Syer
 * @author Duane May
 * <p>
 * There is a second class in the server module that is mostly the same. Should refactor Test Utils for reuse.
 */
public final class ServerRunningExtension implements BeforeAllCallback, RestTemplateHolder, UrlHelper {

    private static final Logger logger = LoggerFactory.getLogger(ServerRunningExtension.class);

    private static final int DEFAULT_PORT = 8080;

    private static final int DEFAULT_UAA_PORT = 8080;

    private static final String DEFAULT_HOST = "localhost";

    private static final String DEFAULT_AUTH_SERVER_ROOT = "/uaa";

    private final String authServerRoot = DEFAULT_AUTH_SERVER_ROOT;

    private int port;

    private int uaaPort;

    private String hostName = DEFAULT_HOST;

    private RestOperations client;

    /**
     * @return a new rule that assumes an existing running broker
     */
    public static ServerRunningExtension connect() {
        return new ServerRunningExtension();
    }

    private ServerRunningExtension() {
        setPort(DEFAULT_PORT);
        setUaaPort(DEFAULT_UAA_PORT);
        setHostName(DEFAULT_HOST);
    }

    @Override
    public void beforeAll(ExtensionContext context) throws Exception {
        try {
            RestTemplate client = new RestTemplate();
            client.getForEntity(new UriTemplate(getUrl("/uaa/login", uaaPort)).toString(), String.class);
            client.getForEntity(new UriTemplate(getUrl("/api/index.html")).toString(), String.class);
            logger.debug(() -> "Basic connectivity test passed");
        } catch (RestClientException e) {
         }
    }

    public void setUaaPort(int uaaPort) {
        this.uaaPort = uaaPort;
    }

    /**
     * @param port the port to set
     */
    public void setPort(int port) {
        this.port = port;
        client = createRestTemplate();
    }

    /**
     * @param hostName the hostName to set
     */
    public void setHostName(String hostName) {
        this.hostName = hostName;
    }

    @Override
    public String getBaseUrl() {
        return "http://" + hostName + ":" + port;
    }

    @Override
    public String getAccessTokenUri() {
        return getUrl(authServerRoot + "/oauth/token");
    }

    @Override
    public String getAuthorizationUri() {
        return getUrl(authServerRoot + "/oauth/authorize");
    }

    @Override
    public String getClientsUri() {
        return getUrl(authServerRoot + "/oauth/clients");
    }

    @Override
    public String getUsersUri() {
        return getUrl(authServerRoot + "/Users");
    }

    @Override
    public String getUserUri() {
        return getUrl(authServerRoot + "/Users");
    }

    @Override
    public String getUrl(String path) {
        return getUrl(path, port);
    }

    public String getUrl(String path, int port) {
        if (path.startsWith("http:")) {
            return path;
        }
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        return "http://" + hostName + ":" + port + path;
    }

    public ResponseEntity<String> getForString(String path) {
        return getForString(path, new HttpHeaders());
    }

    public ResponseEntity<String> getForString(String path, HttpHeaders headers) {
        HttpEntity<Void> request = new HttpEntity<>((Void) null, headers);
        return client.exchange(getUrl(path), HttpMethod.GET, request, String.class);
    }

    @Override
    public void setRestTemplate(RestOperations restTemplate) {
        client = restTemplate;
    }

    @Override
    public RestOperations getRestTemplate() {
        return client;
    }

    public RestTemplate createRestTemplate() {
        RestTemplate client = new RestTemplate();
        client.setRequestFactory(new SimpleClientHttpRequestFactory() {
            @Override
            protected void prepareConnection(HttpURLConnection connection, String httpMethod) throws IOException {
                super.prepareConnection(connection, httpMethod);
                connection.setInstanceFollowRedirects(false);
            }
        });
        client.setErrorHandler(new ResponseErrorHandler() {
            // Pass errors through in response entity for status code analysis
            @Override
            public boolean hasError(ClientHttpResponse response) {
                return false;
            }
        });
        return client;
    }
}
