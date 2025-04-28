package org.cloudfoundry.identity.uaa.zone;

import org.apache.commons.lang3.StringUtils;
import org.cloudfoundry.identity.uaa.annotations.WithDatabaseContext;
import org.cloudfoundry.identity.uaa.util.AlphanumericRandomValueStringGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@WithDatabaseContext
class IdentityZoneResolvingFilterTests {

    private boolean wasFilterExecuted;
    private final IdentityZoneProvisioning dao;

    public IdentityZoneResolvingFilterTests(@Autowired final JdbcTemplate jdbcTemplate) {
        dao = new JdbcIdentityZoneProvisioning(jdbcTemplate);
    }

    @BeforeEach
    void setUp() {
        wasFilterExecuted = false;
    }

    @Test
    void holderIsSetWithDefaultIdentityZone() {
        IdentityZoneHolder.clear();
        assertThat(IdentityZoneHolder.get()).isEqualTo(IdentityZone.getUaa());
    }

    @Test
    void holderIsSetWithMatchingIdentityZone() throws Exception {
        assertFindsCorrectSubdomain("myzone", "myzone.uaa.mycf.com", "uaa.mycf.com", "login.mycf.com");
    }

    @Test
    void holderIsSetWithMatchingIdentityZoneWhenSubdomainContainsUaaHostname() throws Exception {
        assertFindsCorrectSubdomain("foo.uaa.mycf.com", "foo.uaa.mycf.com.uaa.mycf.com", "uaa.mycf.com", "login.mycf.com");
    }

    @Test
    void holderIsSetWithUAAIdentityZone() throws Exception {
        assertFindsCorrectSubdomain("", "uaa.mycf.com", "uaa.mycf.com", "login.mycf.com");
        assertFindsCorrectSubdomain("", "login.mycf.com", "uaa.mycf.com", "login.mycf.com");
    }

    @Test
    void holderIsResolvedWithCaseInsensitiveIdentityZone() throws Exception {
        assertFindsCorrectSubdomain("", "Login.MyCF.COM", "uaa.mycf.com", "login.mycf.com");
    }

    @Test
    void holderIsSetWithCaseInsensitiveIdentityZone() throws Exception {
        assertFindsCorrectSubdomain("", "login.mycf.com", "uaa.mycf.com", "Login.MyCF.COM");
    }

    @Test
    void doNotThrowException_InCase_RetrievingZoneFails() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        String incomingSubdomain = "not_a_zone";
        String uaaHostname = "uaa.mycf.com";
        String incomingHostname = incomingSubdomain + "." + uaaHostname;
        request.setServerName(incomingHostname);
        request.setRequestURI("/uaa/login.html");
        MockHttpServletResponse response = new MockHttpServletResponse();

        FilterChain chain = Mockito.mock(FilterChain.class);
        IdentityZoneResolvingFilter filter = new IdentityZoneResolvingFilter(dao);
        filter.setAdditionalInternalHostnames(new HashSet<>(Collections.singletonList(uaaHostname)));
        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_NOT_FOUND);
        assertThat(IdentityZoneHolder.get()).isEqualTo(IdentityZone.getUaa());
        Mockito.verifyNoInteractions(chain);
    }

    @Test
    void serveStaticContent_InCase_RetrievingZoneFails_local() throws Exception {
        checkStaticContent("/uaa", "/resources/css/application.css");
        checkStaticContent("/uaa", "/vendor/font-awesome/css/font-awesome.min.css");
    }

    @Test
    void serveStaticContent_InCase_RetrievingZoneFails() throws Exception {
        checkStaticContent(null, "/resources/css/application.css");
        checkStaticContent(null, "/vendor/font-awesome/css/font-awesome.min.css");
    }

    private void checkStaticContent(String context, String path) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        String incomingSubdomain = "not_a_zone";
        String uaaHostname = "uaa.mycf.com";
        String incomingHostname = incomingSubdomain + "." + uaaHostname;
        request.setServerName(incomingHostname);
        request.setRequestURI(context + path);
        request.setContextPath(context);
        request.setServletPath(path);
        MockHttpServletResponse response = new MockHttpServletResponse();

        MockFilterChain filterChain = new MockFilterChain() {
            @Override
            public void doFilter(ServletRequest request, ServletResponse response) throws IOException, ServletException {
                assertThat(IdentityZoneHolder.get()).isNotNull();
                wasFilterExecuted = true;
            }
        };
        IdentityZoneResolvingFilter filter = new IdentityZoneResolvingFilter(dao);
        filter.setAdditionalInternalHostnames(new HashSet<>(Arrays.asList(uaaHostname)));
        filter.doFilter(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(wasFilterExecuted).isTrue();
        assertThat(IdentityZoneHolder.get()).isEqualTo(IdentityZone.getUaa());
    }

    private void assertFindsCorrectSubdomain(final String subDomainInput, final String incomingHostname, String... additionalInternalHostnames) throws ServletException, IOException {
        final String expectedSubdomain = subDomainInput.toLowerCase();
        IdentityZoneResolvingFilter filter = new IdentityZoneResolvingFilter(dao);
        filter.setAdditionalInternalHostnames(new HashSet<>(Arrays.asList(additionalInternalHostnames)));

        IdentityZone identityZone = MultitenancyFixture.identityZone(subDomainInput, subDomainInput);
        identityZone.setSubdomain(subDomainInput);
        try {
            identityZone = dao.create(identityZone);
        } catch (ZoneAlreadyExistsException x) {
            identityZone = dao.retrieveBySubdomain(subDomainInput);
        }
        assertThat(identityZone.getSubdomain()).isEqualTo(expectedSubdomain);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setServerName(incomingHostname);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain filterChain = new MockFilterChain() {
            @Override
            public void doFilter(ServletRequest request, ServletResponse response) {
                assertThat(IdentityZoneHolder.get()).isNotNull();
                assertThat(IdentityZoneHolder.get().getSubdomain()).isEqualTo(expectedSubdomain);
                wasFilterExecuted = true;
            }
        };

        filter.doFilter(request, response, filterChain);
        assertThat(wasFilterExecuted).isTrue();
        assertThat(IdentityZoneHolder.get()).isEqualTo(IdentityZone.getUaa());
    }

    @Test
    void holderIsNotSetWithNonMatchingIdentityZone() throws Exception {
        String incomingSubdomain = "not_a_zone";
        String uaaHostname = "uaa.mycf.com";
        String incomingHostname = incomingSubdomain + "." + uaaHostname;

        IdentityZoneResolvingFilter filter = new IdentityZoneResolvingFilter(dao);

        FilterChain chain = Mockito.mock(FilterChain.class);
        filter.setAdditionalInternalHostnames(new HashSet<>(Collections.singletonList(uaaHostname)));

        IdentityZone identityZone = new IdentityZone();
        identityZone.setSubdomain(incomingSubdomain);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setServerName(incomingHostname);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_NOT_FOUND);
        assertThat(IdentityZoneHolder.get()).isEqualTo(IdentityZone.getUaa());
        Mockito.verifyNoInteractions(chain);
    }

    @Test
    void setDefaultZoneHostNamesWithNull() {
        IdentityZoneResolvingFilter filter = new IdentityZoneResolvingFilter(dao);
        filter.setDefaultInternalHostnames(null);
        assertThat(filter.getDefaultZoneHostnames()).isEmpty();
    }

    @Test
    void setAdditionalZoneHostNamesWithNull() {
        IdentityZoneResolvingFilter filter = new IdentityZoneResolvingFilter(dao);
        filter.setAdditionalInternalHostnames(null);
        assertThat(filter.getDefaultZoneHostnames()).isEmpty();
    }

    @Test
    void setRestoreZoneHostNamesWithNull() {
        IdentityZoneResolvingFilter filter = new IdentityZoneResolvingFilter(dao);
        filter.setDefaultInternalHostnames(new HashSet<>(Collections.singletonList("uaa.mycf.com")));
        filter.restoreDefaultHostnames(null);
        assertThat(filter.getDefaultZoneHostnames()).isEmpty();
    }

    @Test
    void setDefaultZoneHostNames() {
        IdentityZoneResolvingFilter filter = new IdentityZoneResolvingFilter(dao);
        filter.setDefaultInternalHostnames(new HashSet<>(Collections.singletonList("uaa.mycf.com")));
        filter.setDefaultInternalHostnames(new HashSet<>(Collections.singletonList("uaa.MYCF2.com")));
        assertThat(filter.getDefaultZoneHostnames()).hasSize(2);
        assertThat(filter.getDefaultZoneHostnames()).contains("uaa.mycf.com");
        assertThat(filter.getDefaultZoneHostnames()).contains("uaa.mycf2.com");
    }

    @Test
    void setAdditionalZoneHostNames() {
        IdentityZoneResolvingFilter filter = new IdentityZoneResolvingFilter(dao);
        filter.setAdditionalInternalHostnames(new HashSet<>(Collections.singletonList("uaa.mycf.com")));
        filter.setAdditionalInternalHostnames(new HashSet<>(Collections.singletonList("uaa.MYCF2.com")));
        assertThat(filter.getDefaultZoneHostnames()).hasSize(2);
        assertThat(filter.getDefaultZoneHostnames()).contains("uaa.mycf.com");
        assertThat(filter.getDefaultZoneHostnames()).contains("uaa.mycf2.com");
    }

    @Test
    void setRestoreZoneHostNames() {
        IdentityZoneResolvingFilter filter = new IdentityZoneResolvingFilter(dao);
        filter.setDefaultInternalHostnames(new HashSet<>(Collections.singletonList("uaa.mycf.com")));
        filter.restoreDefaultHostnames(new HashSet<>(Collections.singletonList("uaa.MYCF2.com")));
        assertThat(filter.getDefaultZoneHostnames()).hasSize(1);
        assertThat(filter.getDefaultZoneHostnames()).contains("uaa.mycf2.com");
    }

    @Nested
    class XZidHeader {
        private static final String X_ZID_HEADER = "X-Zid";

        private final String zoneASubdomain = generateRandomSubdomain();
        private final String zoneAId = UUID.randomUUID().toString();
        private final IdentityZone zoneA = MultitenancyFixture.identityZone(zoneAId, zoneASubdomain);

        private final String zoneBSubdomain = generateRandomSubdomain();
        private final String zoneBId = UUID.randomUUID().toString();
        private final IdentityZone zoneB = MultitenancyFixture.identityZone(zoneBId, zoneBSubdomain);

        private final IdentityZoneResolvingFilter filter;
        private final IdentityZoneProvisioning identityZoneProvisioning = mock(IdentityZoneProvisioning.class);

        public XZidHeader() {
            this.filter = new IdentityZoneResolvingFilter(identityZoneProvisioning);
            filter.setAdditionalInternalHostnames(Set.of("uaa.mycf.com"));
        }

        @Test
        void subdomainNotSet_XZidSetToZoneA_ZoneAExists_ShouldUseZoneA() throws ServletException, IOException {
            arrangeZoneExists(zoneA);
            assertZoneIsResolved("uaa.mycf.com", zoneAId, zoneA);
        }

        @Test
        void subdomainSetToZoneA_XZidSetToZoneB_BothZonesExist_ShouldUseZoneB() throws ServletException, IOException {
            arrangeZoneExists(zoneA);
            arrangeZoneExists(zoneB);
            assertZoneIsResolved(zoneA.getSubdomain() + ".uaa.mycf.com", zoneBId, zoneB);
        }

        @Test
        void subdomainSetToZoneA_XZidSetToZoneB_ZoneBDoesNotExist_ShouldReturn404() throws ServletException, IOException {
            arrangeZoneExists(zoneA);
            arrangeZoneDoesNotExist(zoneB);
            assertZoneIsNotFound(zoneA.getSubdomain() + ".uaa.mycf.com", zoneBId);
        }

        @Test
        void subdomainNotSet_XZidSetToZoneA_ZoneADoesNotExist_ShouldReturn404() throws ServletException, IOException {
            arrangeZoneDoesNotExist(zoneA);
            assertZoneIsNotFound("uaa.mycf.com", zoneAId);
        }

        @Test
        void subdomainNotSet_XZidEmpty_ShouldReturn404() throws ServletException, IOException {
            assertZoneIsNotFound("uaa.mycf.com", StringUtils.EMPTY);
        }

        private void assertZoneIsResolved(
                final String hostname,
                final String xZidHeader, // null -> no header
                final IdentityZone expectedZone
        ) throws ServletException, IOException {
            final MockHttpServletRequest request = new MockHttpServletRequest();
            request.setServerName(hostname);
            if (xZidHeader != null) {
                request.addHeader(X_ZID_HEADER, xZidHeader);
            }

            final MockHttpServletResponse response = new MockHttpServletResponse();

            final MockFilterChain filterChain = new MockFilterChain() {
                @Override
                public void doFilter(final ServletRequest request, final ServletResponse response) {
                    assertThat(IdentityZoneHolder.get()).isNotNull().isEqualTo(expectedZone);
                    wasFilterExecuted = true;
                }
            };

            filter.doFilter(request, response, filterChain);

            assertThat(wasFilterExecuted).isTrue();

            // IdZHolder must be reset to the UAA zone after the request is processed
            assertThat(IdentityZoneHolder.get()).isEqualTo(IdentityZone.getUaa());
        }

        private void assertZoneIsNotFound(
                final String hostname,
                final String xZidHeader // null -> no header
        ) throws ServletException, IOException {
            final MockHttpServletRequest request = new MockHttpServletRequest();
            request.setServerName(hostname);
            if (xZidHeader != null) {
                request.addHeader(X_ZID_HEADER, xZidHeader);
            }

            final MockHttpServletResponse response = mock(MockHttpServletResponse.class);

            final MockFilterChain filterChain = new MockFilterChain() {
                @Override
                public void doFilter(final ServletRequest request, final ServletResponse response) {
                    wasFilterExecuted = true;
                }
            };

            filter.doFilter(request, response, filterChain);

            assertThat(wasFilterExecuted).isFalse();
            verify(response).sendError(eq(HttpServletResponse.SC_NOT_FOUND), anyString());

            // IdZHolder must be reset to the UAA zone after the request is processed
            assertThat(IdentityZoneHolder.get()).isEqualTo(IdentityZone.getUaa());
        }

        private void arrangeZoneExists(final IdentityZone identityZone) {
            lenient().when(identityZoneProvisioning.retrieveBySubdomain(identityZone.getSubdomain())).thenReturn(identityZone);
            lenient().when(identityZoneProvisioning.retrieve(identityZone.getId())).thenReturn(identityZone);
        }

        private void arrangeZoneDoesNotExist(final IdentityZone identityZone) {
            lenient().when(identityZoneProvisioning.retrieveBySubdomain(identityZone.getSubdomain()))
                    .thenThrow(new ZoneDoesNotExistsException("zone does not exist"));
            lenient().when(identityZoneProvisioning.retrieve(identityZone.getId()))
                    .thenThrow(new ZoneDoesNotExistsException("zone does not exist"));
        }

        private static String generateRandomSubdomain() {
            final String randomString = new AlphanumericRandomValueStringGenerator(10).generate().toLowerCase();

            // ensure string starts with letter
            if ('0' <= randomString.charAt(0) && randomString.charAt(0) <= '9') {
                return 'a' + randomString.substring(1);
            }

            return randomString;
        }
    }
}
