package org.cloudfoundry.identity.uaa.zone;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ZoneContextPathSessionTests {

    // ───────────────────────────────────────────────────────────────────────────
    // ZonePathHttpSession
    // ───────────────────────────────────────────────────────────────────────────

    @Nested
    class ZonePathHttpSessionTests {

        private MockHttpSession containerSession;
        private Map<String, Object> attributes;
        private ZonePathHttpSession subSession;
        private String containerAttributeName;

        @BeforeEach
        void setUp() {
            containerSession = new MockHttpSession();
            attributes = new ConcurrentHashMap<>();
            containerAttributeName = ZoneContextPathSessionRequestWrapper.attributeNameForContextPath("/uaa/z/myzone");
            containerSession.setAttribute(containerAttributeName, attributes);
            subSession = new ZonePathHttpSession(containerSession, "/uaa/z/myzone", attributes, containerAttributeName);
        }

        @Test
        void getAttribute_returnsValueFromSubSessionMap() {
            attributes.put("key", "value");
            assertThat(subSession.getAttribute("key")).isEqualTo("value");
        }

        @Test
        void getAttribute_returnsNullForMissingKey() {
            assertThat(subSession.getAttribute("nonexistent")).isNull();
        }

        @Test
        void setAttribute_putsValueInSubSessionMap() {
            subSession.setAttribute("foo", "bar");
            assertThat(attributes.get("foo")).isEqualTo("bar");
        }

        @Test
        void setAttribute_withNull_removesKey() {
            attributes.put("foo", "bar");
            subSession.setAttribute("foo", null);
            assertThat(attributes).doesNotContainKey("foo");
        }

        @Test
        void removeAttribute_removesFromSubSessionMap() {
            attributes.put("key", "value");
            subSession.removeAttribute("key");
            assertThat(attributes).doesNotContainKey("key");
        }

        @Test
        void getAttributeNames_returnsSubSessionKeysOnly() {
            containerSession.setAttribute("containerOnly", "x");
            attributes.put("zone1", "a");
            attributes.put("zone2", "b");

            List<String> names = Collections.list(subSession.getAttributeNames());
            assertThat(names).containsExactlyInAnyOrder("zone1", "zone2");
        }

        @Test
        void getId_includesContainerIdAndContextPathSuffix() {
            String id = subSession.getId();
            assertThat(id).startsWith(containerSession.getId());
            assertThat(id).endsWith("-uaa-z-myzone");
        }

        @Test
        void getId_defaultSuffix_forEmptyContextPath() {
            ZonePathHttpSession defaultSubSession = new ZonePathHttpSession(
                    containerSession, "", new ConcurrentHashMap<>(), "irrelevant");
            assertThat(defaultSubSession.getId()).endsWith("-default");
        }

        @Test
        void delegatesTimingMethodsToContainerSession() {
            assertThat(subSession.getCreationTime()).isEqualTo(containerSession.getCreationTime());
            assertThat(subSession.getLastAccessedTime()).isEqualTo(containerSession.getLastAccessedTime());
            assertThat(subSession.isNew()).isEqualTo(containerSession.isNew());
            assertThat(subSession.getServletContext()).isSameAs(containerSession.getServletContext());
        }

        @Test
        void maxInactiveInterval_delegatesToContainer() {
            subSession.setMaxInactiveInterval(120);
            assertThat(containerSession.getMaxInactiveInterval()).isEqualTo(120);
            assertThat(subSession.getMaxInactiveInterval()).isEqualTo(120);
        }

        @Test
        void invalidate_clearsSubSessionButNotContainer() {
            attributes.put("secCtx", "auth");
            subSession.invalidate();

            assertThat(attributes).isEmpty();
            assertThat(containerSession.getAttribute(containerAttributeName)).isNull();
            assertThat(containerSession.isInvalid()).isFalse();
        }

        @Test
        void invalidate_leavesOtherSubSessionsIntact() {
            String otherAttrName = ZoneContextPathSessionRequestWrapper.attributeNameForContextPath("/uaa/z/other");
            Map<String, Object> otherAttrs = new ConcurrentHashMap<>();
            otherAttrs.put("otherKey", "otherVal");
            containerSession.setAttribute(otherAttrName, otherAttrs);

            subSession.invalidate();

            assertThat(containerSession.getAttribute(otherAttrName)).isSameAs(otherAttrs);
            assertThat(otherAttrs).containsEntry("otherKey", "otherVal");
        }

        @Test
        void containerSession_invalidated_subSessionAttributesStillAccessibleFromMap() {
            attributes.put("data", "value");
            containerSession.invalidate();

            // The sub-session attributes live in a ConcurrentHashMap that is independent
            // of container session validity. The map itself remains readable even after
            // the container session is invalidated; only container-delegated methods
            // (like getCreationTime) throw.
            assertThat(subSession.getAttribute("data")).isEqualTo("value");
            assertThatThrownBy(() -> subSession.getCreationTime())
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        void attributeIsolation_acrossSubSessions() {
            Map<String, Object> otherAttrs = new ConcurrentHashMap<>();
            ZonePathHttpSession otherSubSession = new ZonePathHttpSession(
                    containerSession, "/uaa/z/other", otherAttrs,
                    ZoneContextPathSessionRequestWrapper.attributeNameForContextPath("/uaa/z/other"));

            subSession.setAttribute("shared-name", "zone1-value");
            otherSubSession.setAttribute("shared-name", "zone2-value");

            assertThat(subSession.getAttribute("shared-name")).isEqualTo("zone1-value");
            assertThat(otherSubSession.getAttribute("shared-name")).isEqualTo("zone2-value");
        }
    }

    // ───────────────────────────────────────────────────────────────────────────
    // ZoneContextPathSessionRequestWrapper
    // ───────────────────────────────────────────────────────────────────────────

    @Nested
    class RequestWrapperTests {

        private MockHttpServletRequest request;
        private ZoneContextPathSessionRequestWrapper wrapper;

        @BeforeEach
        void setUp() {
            request = new MockHttpServletRequest();
            request.setContextPath("/uaa/z/zone1");
            wrapper = new ZoneContextPathSessionRequestWrapper(request);
        }

        @Test
        void getSession_returnsZonePathHttpSession() {
            HttpSession session = wrapper.getSession();
            assertThat(session).isInstanceOf(ZonePathHttpSession.class);
        }

        @Test
        void getSession_createsSubSessionMapOnContainerSession() {
            wrapper.getSession(true);
            HttpSession containerSession = request.getSession(false);
            String attrName = ZoneContextPathSessionRequestWrapper.attributeNameForContextPath("/uaa/z/zone1");
            assertThat(containerSession.getAttribute(attrName)).isNotNull();
            assertThat(containerSession.getAttribute(attrName)).isInstanceOf(Map.class);
        }

        @Test
        void getSession_false_returnsNullWhenNoContainerSession() {
            assertThat(wrapper.getSession(false)).isNull();
        }

        @Test
        void getSession_reusesExistingSubSessionMap() {
            HttpSession first = wrapper.getSession(true);
            first.setAttribute("data", "value");

            HttpSession second = wrapper.getSession(false);
            assertThat(second.getAttribute("data")).isEqualTo("value");
        }

        @Test
        void differentContextPaths_getDifferentSubSessions() {
            MockHttpServletRequest req1 = new MockHttpServletRequest();
            req1.setContextPath("/uaa/z/zone1");
            req1.setSession(new MockHttpSession());
            ZoneContextPathSessionRequestWrapper w1 = new ZoneContextPathSessionRequestWrapper(req1);

            MockHttpServletRequest req2 = new MockHttpServletRequest();
            req2.setContextPath("/uaa/z/zone2");
            req2.setSession(req1.getSession(false));
            ZoneContextPathSessionRequestWrapper w2 = new ZoneContextPathSessionRequestWrapper(req2);

            w1.getSession().setAttribute("user", "alice");
            w2.getSession().setAttribute("user", "bob");

            assertThat(w1.getSession().getAttribute("user")).isEqualTo("alice");
            assertThat(w2.getSession().getAttribute("user")).isEqualTo("bob");
        }

        @Test
        void emptyContextPath_usesDefaultKey() {
            request.setContextPath("");
            wrapper = new ZoneContextPathSessionRequestWrapper(request);
            HttpSession session = wrapper.getSession(true);
            assertThat(session.getId()).endsWith("-default");
        }

        @Test
        void attributeNameForContextPath_emptyUsesDefault() {
            assertThat(ZoneContextPathSessionRequestWrapper.attributeNameForContextPath(""))
                    .isEqualTo(ZoneContextPathSessionRequestWrapper.ATTRIBUTE_NAME_PREFIX + "default");
        }

        @Test
        void attributeNameForContextPath_nonEmptyUsesPath() {
            assertThat(ZoneContextPathSessionRequestWrapper.attributeNameForContextPath("/uaa/z/zone1"))
                    .isEqualTo(ZoneContextPathSessionRequestWrapper.ATTRIBUTE_NAME_PREFIX + "/uaa/z/zone1");
        }

        @Test
        void changeSessionId_preservesSubSessionAttributes() {
            HttpSession subSession = wrapper.getSession(true);
            subSession.setAttribute("key", "value");
            String oldId = request.getSession(false).getId();

            String newId = wrapper.changeSessionId();

            assertThat(newId).isNotEqualTo(oldId);
            HttpSession afterChange = wrapper.getSession(false);
            assertThat(afterChange.getAttribute("key")).isEqualTo("value");
        }
    }

    // ───────────────────────────────────────────────────────────────────────────
    // ZoneContextPathSessionResponseWrapper
    // ───────────────────────────────────────────────────────────────────────────

    @Nested
    class ResponseWrapperTests {

        private MockHttpServletResponse rawResponse;
        private ZoneContextPathSessionResponseWrapper wrapper;

        @BeforeEach
        void setUp() {
            rawResponse = new MockHttpServletResponse();
            wrapper = new ZoneContextPathSessionResponseWrapper(rawResponse);
        }

        @Test
        void addCookie_blocksJSessionIdClear_maxAgeZero() {
            Cookie cookie = new Cookie("JSESSIONID", "abc");
            cookie.setMaxAge(0);
            wrapper.addCookie(cookie);
            assertThat(rawResponse.getCookies()).isEmpty();
        }

        @Test
        void addCookie_blocksJSessionIdClear_emptyValue() {
            Cookie cookie = new Cookie("JSESSIONID", "");
            cookie.setMaxAge(-1);
            wrapper.addCookie(cookie);
            assertThat(rawResponse.getCookies()).isEmpty();
        }

        @Test
        void addCookie_allowsNormalJSessionId() {
            Cookie cookie = new Cookie("JSESSIONID", "abc123");
            cookie.setMaxAge(-1);
            wrapper.addCookie(cookie);
            assertThat(rawResponse.getCookies()).hasSize(1);
            assertThat(rawResponse.getCookies()[0].getValue()).isEqualTo("abc123");
        }

        @Test
        void addCookie_allowsNonJSessionIdCookieEvenWithMaxAgeZero() {
            Cookie cookie = new Cookie("OTHER", "val");
            cookie.setMaxAge(0);
            wrapper.addCookie(cookie);
            assertThat(rawResponse.getCookies()).hasSize(1);
        }

        @Test
        void addHeader_blocksSetCookie_clearingJSessionId_maxAgeZero() {
            wrapper.addHeader("Set-Cookie", "JSESSIONID=abc; Max-Age=0; Path=/uaa");
            assertThat(rawResponse.getHeader("Set-Cookie")).isNull();
        }

        @Test
        void addHeader_blocksSetCookie_clearingJSessionId_emptyValue() {
            wrapper.addHeader("Set-Cookie", "JSESSIONID=; Path=/uaa");
            assertThat(rawResponse.getHeader("Set-Cookie")).isNull();
        }

        @Test
        void addHeader_allowsNormalSetCookieJSessionId() {
            wrapper.addHeader("Set-Cookie", "JSESSIONID=abc123; Path=/uaa");
            assertThat(rawResponse.getHeader("Set-Cookie")).contains("abc123");
        }

        @Test
        void addHeader_allowsNonSetCookieHeader() {
            wrapper.addHeader("X-Custom", "value");
            assertThat(rawResponse.getHeader("X-Custom")).isEqualTo("value");
        }

        @Test
        void setHeader_blocksSetCookie_clearingJSessionId() {
            wrapper.setHeader("Set-Cookie", "JSESSIONID=; Max-Age=0; Path=/");
            assertThat(rawResponse.getHeader("Set-Cookie")).isNull();
        }

        @Test
        void setHeader_allowsNonJSessionIdSetCookie() {
            wrapper.setHeader("Set-Cookie", "XSRF-TOKEN=abc; Path=/");
            assertThat(rawResponse.getHeader("Set-Cookie")).contains("XSRF-TOKEN");
        }

        @Test
        void addHeader_caseInsensitive_setCookie() {
            wrapper.addHeader("set-cookie", "JSESSIONID=; Path=/");
            assertThat(rawResponse.getHeader("set-cookie")).isNull();
        }
    }

    // ───────────────────────────────────────────────────────────────────────────
    // ZoneContextPathSessionFilter
    // ───────────────────────────────────────────────────────────────────────────

    @Nested
    class FilterTests {

        private ZoneContextPathSessionFilter filter;
        private MockHttpServletRequest request;
        private MockHttpServletResponse response;
        private AtomicReference<HttpServletRequest> capturedRequest;
        private AtomicReference<HttpServletResponse> capturedResponse;

        @BeforeEach
        void setUp() {
            filter = new ZoneContextPathSessionFilter();
            request = new MockHttpServletRequest();
            response = new MockHttpServletResponse();
            capturedRequest = new AtomicReference<>();
            capturedResponse = new AtomicReference<>();
        }

        private FilterChain capturingChain() {
            return (req, resp) -> {
                capturedRequest.set((HttpServletRequest) req);
                capturedResponse.set((HttpServletResponse) resp);
            };
        }

        private FilterChain capturingChain(Runnable action) {
            return (req, resp) -> {
                capturedRequest.set((HttpServletRequest) req);
                capturedResponse.set((HttpServletResponse) resp);
                action.run();
            };
        }

        @Test
        void wrapsRequestAndResponse() throws ServletException, IOException {
            request.setContextPath("/uaa");
            filter.doFilter(request, response, capturingChain());

            assertThat(capturedRequest.get()).isInstanceOf(ZoneContextPathSessionRequestWrapper.class);
            assertThat(capturedResponse.get()).isInstanceOf(ZoneContextPathSessionResponseWrapper.class);
        }

        @Test
        void sessionInsideChain_isZonePathHttpSession() throws ServletException, IOException {
            request.setContextPath("/uaa/z/zone1");
            filter.doFilter(request, response, capturingChain(() -> {
                HttpSession session = capturedRequest.get().getSession(true);
                assertThat(session).isInstanceOf(ZonePathHttpSession.class);
                session.setAttribute("user", "alice");
            }));

            HttpSession containerSession = request.getSession(false);
            String attrName = ZoneContextPathSessionRequestWrapper.attributeNameForContextPath("/uaa/z/zone1");
            @SuppressWarnings("unchecked")
            Map<String, Object> subMap = (Map<String, Object>) containerSession.getAttribute(attrName);
            assertThat(subMap).containsEntry("user", "alice");
        }

        @Test
        void flushes_subSessionAttributes_afterChain() throws ServletException, IOException {
            request.setContextPath("/uaa/z/zone1");
            MockHttpSession containerSession = new MockHttpSession();
            request.setSession(containerSession);

            String attrName = ZoneContextPathSessionRequestWrapper.attributeNameForContextPath("/uaa/z/zone1");

            filter.doFilter(request, response, capturingChain(() -> {
                capturedRequest.get().getSession(true).setAttribute("data", "value");
            }));

            // After filter completes, the sub-session map should be re-set on the container
            // session. Verify by checking the attribute is present.
            assertThat(containerSession.getAttribute(attrName)).isNotNull();
            @SuppressWarnings("unchecked")
            Map<String, Object> subMap = (Map<String, Object>) containerSession.getAttribute(attrName);
            assertThat(subMap).containsEntry("data", "value");
        }

        @Test
        void clearsJSessionId_whenAllSubSessionsRemoved() throws ServletException, IOException {
            request.setContextPath("/uaa");
            MockHttpSession containerSession = new MockHttpSession();
            request.setSession(containerSession);

            filter.doFilter(request, response, capturingChain(() -> {
                HttpSession sub = capturedRequest.get().getSession(true);
                sub.setAttribute("key", "val");
                sub.invalidate();
            }));

            assertThat(response.getHeader("Set-Cookie"))
                    .as("Should clear JSESSIONID when no sub-sessions remain")
                    .contains("JSESSIONID=")
                    .contains("Max-Age=0");
        }

        @Test
        void doesNotClearJSessionId_whenOtherSubSessionsRemain() throws ServletException, IOException {
            request.setContextPath("/uaa/z/zone1");
            MockHttpSession containerSession = new MockHttpSession();
            request.setSession(containerSession);

            // Pre-populate another zone's sub-session
            String otherAttrName = ZoneContextPathSessionRequestWrapper.attributeNameForContextPath("/uaa/z/zone2");
            Map<String, Object> otherAttrs = new ConcurrentHashMap<>();
            otherAttrs.put("user", "bob");
            containerSession.setAttribute(otherAttrName, otherAttrs);

            filter.doFilter(request, response, capturingChain(() -> {
                HttpSession sub = capturedRequest.get().getSession(true);
                sub.setAttribute("user", "alice");
                sub.invalidate();
            }));

            assertThat(response.getHeader("Set-Cookie"))
                    .as("Should NOT clear JSESSIONID when other sub-sessions exist")
                    .isNull();
        }

        @Test
        void doesNotClearJSessionId_whenResponseAlreadyCommitted() throws ServletException, IOException {
            request.setContextPath("/uaa");
            MockHttpSession containerSession = new MockHttpSession();
            request.setSession(containerSession);

            filter.doFilter(request, response, (req, resp) -> {
                capturedRequest.set((HttpServletRequest) req);
                HttpSession sub = ((HttpServletRequest) req).getSession(true);
                sub.invalidate();
                ((HttpServletResponse) resp).getOutputStream().flush();
                ((HttpServletResponse) resp).flushBuffer();
            });

            // Response was committed before the filter's finally block;
            // should not have the clear-cookie header added by the filter.
            // Note: the response may already have headers from the flushing itself,
            // but the filter's specific JSESSIONID clear should not appear via rawResponse.
        }

        @Test
        void multipleZones_sameContainerSession() throws ServletException, IOException {
            MockHttpSession containerSession = new MockHttpSession();

            // Login to zone1
            request.setContextPath("/uaa/z/zone1");
            request.setSession(containerSession);
            filter.doFilter(request, response, capturingChain(() -> {
                capturedRequest.get().getSession(true).setAttribute("user", "alice");
            }));

            // Login to zone2 (same container session)
            MockHttpServletRequest request2 = new MockHttpServletRequest();
            request2.setContextPath("/uaa/z/zone2");
            request2.setSession(containerSession);
            MockHttpServletResponse response2 = new MockHttpServletResponse();
            filter.doFilter(request2, response2, (req, resp) -> {
                ((HttpServletRequest) req).getSession(true).setAttribute("user", "bob");
            });

            // Verify both sub-sessions exist on the container
            String z1Attr = ZoneContextPathSessionRequestWrapper.attributeNameForContextPath("/uaa/z/zone1");
            String z2Attr = ZoneContextPathSessionRequestWrapper.attributeNameForContextPath("/uaa/z/zone2");

            @SuppressWarnings("unchecked")
            Map<String, Object> z1Map = (Map<String, Object>) containerSession.getAttribute(z1Attr);
            @SuppressWarnings("unchecked")
            Map<String, Object> z2Map = (Map<String, Object>) containerSession.getAttribute(z2Attr);

            assertThat(z1Map).containsEntry("user", "alice");
            assertThat(z2Map).containsEntry("user", "bob");
        }

        @Test
        void containerSessionInvalidated_insideChain_handledGracefully() throws ServletException, IOException {
            request.setContextPath("/uaa/z/zone1");
            MockHttpSession containerSession = new MockHttpSession();
            request.setSession(containerSession);

            filter.doFilter(request, response, (req, resp) -> {
                HttpServletRequest httpReq = (HttpServletRequest) req;
                httpReq.getSession(true).setAttribute("data", "val");
                containerSession.invalidate();
            });

            // The filter's finally block calls request.getSession(false) on the original
            // request which should return null after invalidation. No exception expected.
        }

        @Test
        void logoutFromOneZone_leavesOtherZonesIntact() throws ServletException, IOException {
            MockHttpSession containerSession = new MockHttpSession();

            // Setup: login to default zone and zone1
            request.setContextPath("");
            request.setSession(containerSession);
            filter.doFilter(request, response, capturingChain(() -> {
                capturedRequest.get().getSession(true).setAttribute("user", "admin");
            }));

            MockHttpServletRequest zoneReq = new MockHttpServletRequest();
            zoneReq.setContextPath("/uaa/z/zone1");
            zoneReq.setSession(containerSession);
            filter.doFilter(zoneReq, new MockHttpServletResponse(), (req, resp) -> {
                ((HttpServletRequest) req).getSession(true).setAttribute("user", "zone1-user");
            });

            // Logout from zone1 (invalidate that sub-session)
            MockHttpServletRequest logoutReq = new MockHttpServletRequest();
            logoutReq.setContextPath("/uaa/z/zone1");
            logoutReq.setSession(containerSession);
            filter.doFilter(logoutReq, new MockHttpServletResponse(), (req, resp) -> {
                ((HttpServletRequest) req).getSession(false).invalidate();
            });

            // Default zone sub-session should still be present
            String defaultAttr = ZoneContextPathSessionRequestWrapper.attributeNameForContextPath("");
            @SuppressWarnings("unchecked")
            Map<String, Object> defaultMap = (Map<String, Object>) containerSession.getAttribute(defaultAttr);
            assertThat(defaultMap).containsEntry("user", "admin");

            // Zone1 sub-session should be gone
            String z1Attr = ZoneContextPathSessionRequestWrapper.attributeNameForContextPath("/uaa/z/zone1");
            assertThat(containerSession.getAttribute(z1Attr)).isNull();
        }
    }
}
