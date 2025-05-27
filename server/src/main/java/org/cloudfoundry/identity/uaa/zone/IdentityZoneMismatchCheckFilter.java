package org.cloudfoundry.identity.uaa.zone;

import org.cloudfoundry.identity.uaa.authentication.UaaAuthentication;
import org.cloudfoundry.identity.uaa.oauth.UaaOauth2Authentication;
import org.cloudfoundry.identity.uaa.oauth.provider.authentication.OAuth2AuthenticationProcessingFilter;
import org.cloudfoundry.identity.uaa.zone.beans.IdentityZoneManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.RedirectStrategy;
import org.springframework.security.web.context.SecurityContextPersistenceFilter;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.io.IOException;
import java.util.Objects;
import java.util.Optional;

/**
 * Checks whether there is a mismatch between ...
 * <ul>
 *     <li>the identity zone in the {@link IdentityZoneHolder} (specified by the subdomain or "X-Zid" header) and</li>
 *     <li>the identity zone in the {@link SecurityContext} (the one set in the session or the token).</li>
 * </ul>
 * These two pieces of information being necessary also implies the position of the filter in the chain:
 * <ul>
 *     <li>after {@link IdentityZoneResolvingFilter}, which sets the identity zone in the IdentityZoneHolder and</li>
 *     <li>after {@link SecurityContextPersistenceFilter}, which sets the SecurityContext from the session</li>
 *     <li>after {@link OAuth2AuthenticationProcessingFilter}, which sets the SecurityContext from the token passed in the request</li>
 * </ul>
 * Additionally, the filter must be placed before the {@link IdentityZoneSwitchingFilter}.
 */
public class IdentityZoneMismatchCheckFilter extends OncePerRequestFilter {

    private final IdentityZoneManager identityZoneManager;
    private final RedirectStrategy redirectStrategy;
    private final String redirectUrl;

    public IdentityZoneMismatchCheckFilter(
            final IdentityZoneManager identityZoneManager,
            final RedirectStrategy redirectStrategy,
            final String redirectUrl
    ) {
        this.identityZoneManager = identityZoneManager;
        this.redirectStrategy = redirectStrategy;
        this.redirectUrl = redirectUrl;
    }

    @Override
    protected void doFilterInternal(
            final HttpServletRequest request,
            final HttpServletResponse response,
            final FilterChain filterChain
    ) throws ServletException, IOException {
        final Optional<Authentication> authenticationOpt = Optional.ofNullable(SecurityContextHolder.getContext())
                .map(SecurityContext::getAuthentication);

        if (authenticationOpt.isEmpty()) {
            // not yet authenticated -> continue
            filterChain.doFilter(request, response);
            return;
        }

        final Authentication authentication = authenticationOpt.get();

        final String zoneIdFromSessionOrToken;
        if (authentication instanceof UaaAuthentication uaaAuthentication) {
            // authenticated via session
            zoneIdFromSessionOrToken = uaaAuthentication.getPrincipal().getZoneId();
        } else if (authentication instanceof UaaOauth2Authentication uaaOauth2Authentication) {
            /* authenticated via OAuth2 token
             * IMPORTANT: already addressed by the issuer check in OAuth2AuthenticationProcessingFilter
             * -> requires zone-specific subdomain to be set in the 'iss' claim of the token */
            zoneIdFromSessionOrToken = uaaOauth2Authentication.getZoneId();
        } else {
            // no zone information in authentication
            filterChain.doFilter(request, response);
            return;
        }

        // redirect to login page if the zones do not match
        if (!Objects.equals(zoneIdFromSessionOrToken, identityZoneManager.getCurrentIdentityZoneId())) {
            handleRedirect(request, response);
            return;
        }

        filterChain.doFilter(request, response);
    }

    protected void handleRedirect(
            final HttpServletRequest request,
            final HttpServletResponse response
    ) throws IOException {
        // if a session was present, invalidate it
        final HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }

        redirectStrategy.sendRedirect(request, response, redirectUrl);
    }
}
