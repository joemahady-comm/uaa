package org.cloudfoundry.identity.uaa.zone;

import org.cloudfoundry.identity.uaa.authentication.UaaAuthentication;
import org.cloudfoundry.identity.uaa.authentication.UaaPrincipal;
import org.cloudfoundry.identity.uaa.oauth.UaaOauth2Authentication;
import org.cloudfoundry.identity.uaa.zone.beans.IdentityZoneManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.RedirectStrategy;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.io.IOException;

import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IdentityZoneMismatchCheckFilterTest {

    private static final String REDIRECT_URL = "/login";
    private static final String ZONE1_ID = "zone1";
    private static final String ZONE2_ID = "zone2";

    @Mock
    private IdentityZoneManager identityZoneManager;

    @Mock
    private RedirectStrategy redirectStrategy;

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpSession session;

    @Mock
    private HttpServletResponse response;

    @Mock
    private FilterChain filterChain;

    private final MockedStatic<SecurityContextHolder> securityContextHolderMockedStatic = mockStatic(SecurityContextHolder.class);

    private IdentityZoneMismatchCheckFilter filter;

    @BeforeEach
    void setUp() {
        // return mock session to check if it is invalidated when necessary
        lenient().when(request.getSession(false)).thenReturn(session);

        filter = new IdentityZoneMismatchCheckFilter(identityZoneManager, redirectStrategy, REDIRECT_URL);
    }

    @AfterEach
    void tearDown() {
        securityContextHolderMockedStatic.close();
    }

    @Test
    void shouldPassOnRequestWithoutAuthentication() throws Exception {
        arrangeAuthenticationInSecurityContext(null);

        filter.doFilterInternal(request, response, filterChain);

        assertRequestIsPassedOnToFilterChain();
    }

    @Test
    void shouldRedirectIfZoneInSessionDoesNotMatchZoneInIdentityZoneHolder() throws Exception {
        final UaaAuthentication uaaAuthentication = buildUaaAuthenticationMock(ZONE1_ID);
        arrangeAuthenticationInSecurityContext(uaaAuthentication);

        arrangeCurrentIdentityZone(ZONE2_ID);

        filter.doFilterInternal(request, response, filterChain);

        assertSessionIsInvalidatedAndRedirectIsPerformed();
    }

    @Test
    void shouldPassOnRequestIfZoneInSessionMatchesZoneInIdentityZoneHolder() throws Exception {
        final UaaAuthentication uaaAuthentication = buildUaaAuthenticationMock(ZONE1_ID);
        arrangeAuthenticationInSecurityContext(uaaAuthentication);

        arrangeCurrentIdentityZone(ZONE1_ID);

        filter.doFilterInternal(request, response, filterChain);

        assertRequestIsPassedOnToFilterChain();
    }

    @Test
    void shouldRedirectIfZoneInTokenDoesNotMatchZoneInIdentityZoneHolder() throws Exception {
        final UaaOauth2Authentication uaaOauth2Authentication = buildUaaOauth2AuthenticationMock(ZONE1_ID);
        arrangeAuthenticationInSecurityContext(uaaOauth2Authentication);

        arrangeCurrentIdentityZone(ZONE2_ID);

        filter.doFilterInternal(request, response, filterChain);

        assertSessionIsInvalidatedAndRedirectIsPerformed();
    }

    @Test
    void shouldPassOnRequestIfZoneInTokenMatchesZoneInIdentityZoneHolder() throws Exception {
        final UaaOauth2Authentication uaaOauth2Authentication = buildUaaOauth2AuthenticationMock(ZONE1_ID);
        arrangeAuthenticationInSecurityContext(uaaOauth2Authentication);

        arrangeCurrentIdentityZone(ZONE1_ID);

        filter.doFilterInternal(request, response, filterChain);

        assertRequestIsPassedOnToFilterChain();
    }

    private void arrangeCurrentIdentityZone(final String zoneId) {
        when(identityZoneManager.getCurrentIdentityZoneId()).thenReturn(zoneId);
    }

    private void arrangeAuthenticationInSecurityContext(final Authentication authentication) {
        final SecurityContext mockSecurityContext = mock(SecurityContext.class);
        when(mockSecurityContext.getAuthentication()).thenReturn(authentication);
        securityContextHolderMockedStatic.when(SecurityContextHolder::getContext).thenReturn(mockSecurityContext);
    }

    private void assertRequestIsPassedOnToFilterChain() throws IOException, ServletException {
        verify(filterChain).doFilter(request, response);
    }

    private void assertSessionIsInvalidatedAndRedirectIsPerformed() throws IOException {
        verify(session).invalidate();
        verify(redirectStrategy).sendRedirect(request, response, REDIRECT_URL);
    }

    private static UaaAuthentication buildUaaAuthenticationMock(final String zoneId) {
        final UaaAuthentication uaaAuthentication = mock(UaaAuthentication.class);
        final UaaPrincipal uaaPrincipal = mock(UaaPrincipal.class);
        when(uaaAuthentication.getPrincipal()).thenReturn(uaaPrincipal);
        when(uaaPrincipal.getZoneId()).thenReturn(zoneId);
        return uaaAuthentication;
    }

    private static UaaOauth2Authentication buildUaaOauth2AuthenticationMock(final String zoneId) {
        final UaaOauth2Authentication uaaOauth2Authentication = mock(UaaOauth2Authentication.class);
        when(uaaOauth2Authentication.getZoneId()).thenReturn(zoneId);
        return uaaOauth2Authentication;
    }
}