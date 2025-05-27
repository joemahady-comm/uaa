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
package org.cloudfoundry.identity.uaa.zone;

import org.cloudfoundry.identity.uaa.util.UaaUrlUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * This filter ensures that all requests are targeting a specific identity zone
 * by hostname. If the hostname doesn't match an identity zone, a 404 error is
 * sent.
 *
 */
public class IdentityZoneResolvingFilter extends OncePerRequestFilter implements InitializingBean {

    /**
     * Header for specifying the identity zone in which the request should be performed. If both a subdomain and the
     * header are defined, the header takes precedence.
     */
    private static final String X_ZID_HEADER = "X-zid";

    private final boolean zidHeaderEnabled;

    private final IdentityZoneProvisioning dao;
    private final Set<String> staticResources = Set.of("/resources/", "/vendor/font-awesome/");
    private final Set<String> defaultZoneHostnames = new HashSet<>();
    private final Logger logger = LoggerFactory.getLogger(getClass());

    public IdentityZoneResolvingFilter(final IdentityZoneProvisioning dao, final boolean zidHeaderEnabled) {
        this.dao = dao;
        this.zidHeaderEnabled = zidHeaderEnabled;
    }

    @Override
    protected void doFilterInternal(
            final HttpServletRequest request,
            final HttpServletResponse response,
            final FilterChain filterChain
    ) throws ServletException, IOException {
        final String zidFromHeader = request.getHeader(X_ZID_HEADER);
        final String hostname = request.getServerName();
        final String subdomain = getSubdomain(hostname);

        IdentityZone identityZone = null;
        String zoneResolvingDescription = null; // for logging and error messages
        try {
            if (zidHeaderEnabled && zidFromHeader != null) {
                zoneResolvingDescription = "zid '%s'".formatted(zidFromHeader);
                identityZone = dao.retrieve(zidFromHeader);
            } else {
                zoneResolvingDescription = "subdomain '%s'".formatted(subdomain);
                identityZone = dao.retrieveBySubdomain(subdomain);
            }
        } catch (final EmptyResultDataAccessException | ZoneDoesNotExistsException ex) {
            logger.debug("Cannot find identity zone for {}", zoneResolvingDescription);
        } catch (final Exception ex) {
            final String message = "Internal server error while fetching identity zone for %s"
                    .formatted(zoneResolvingDescription);
            logger.warn(message, ex);
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, message);
            return;
        }

        if (identityZone == null) {
            // skip filter to static resources in order to serve images and css in case of invalid zones
            final boolean isStaticResource = staticResources.stream().anyMatch(UaaUrlUtils.getRequestPath(request)::startsWith);
            if (isStaticResource) {
                filterChain.doFilter(request, response);
                return;
            }

            request.setAttribute("error_message_code", "zone.not.found");
            response.sendError(HttpServletResponse.SC_NOT_FOUND, "Cannot find identity zone for %s".formatted(zoneResolvingDescription));
            return;
        }

        try {
            IdentityZoneHolder.set(identityZone);
            filterChain.doFilter(request, response);
        } finally {
            IdentityZoneHolder.clear();
        }
    }

    private String getSubdomain(String hostname) {
        String lowerHostName = hostname.toLowerCase();
        if (defaultZoneHostnames.contains(lowerHostName)) {
            return "";
        }
        for (String internalHostname : defaultZoneHostnames) {
            if (lowerHostName.endsWith("." + internalHostname)) {
                return lowerHostName.substring(0, lowerHostName.length() - internalHostname.length() - 1);
            }
        }
        //UAA is catch all if we haven't configured anything
        if (defaultZoneHostnames.size() == 1 && defaultZoneHostnames.contains("localhost")) {
            logger.debug("No root domains configured, UAA is catch-all domain for host:{}", hostname);
            return "";
        }
        if (logger.isDebugEnabled()) {
            logger.debug("Unable to determine subdomain for host:{}; root domains:{}", hostname, Arrays.toString(defaultZoneHostnames.toArray()));
        }
        return null;
    }

    public void setAdditionalInternalHostnames(Set<String> hostnames) {
        if (hostnames != null) {
            hostnames
                    .forEach(
                            entry -> this.defaultZoneHostnames.add(entry.toLowerCase())
                    );
        }
    }

    public void setDefaultInternalHostnames(Set<String> hostnames) {
        if (hostnames != null) {
            hostnames
                    .forEach(
                            entry -> this.defaultZoneHostnames.add(entry.toLowerCase())
                    );
        }
    }

    public synchronized void restoreDefaultHostnames(Set<String> hostnames) {
        this.defaultZoneHostnames.clear();
        if (hostnames != null) {
            hostnames
                    .forEach(
                            entry -> this.defaultZoneHostnames.add(entry.toLowerCase())
                    );
        }
    }

    public Set<String> getDefaultZoneHostnames() {
        return new HashSet<>(defaultZoneHostnames);
    }

    @Override
    public void afterPropertiesSet() throws ServletException {
        super.afterPropertiesSet();
        String domainArray = Arrays.toString(getDefaultZoneHostnames().toArray());
        logger.info("Zone Resolving Root domains are: {}", domainArray);
    }
}
