package org.cloudfoundry.identity.uaa.zone;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.cloudfoundry.identity.uaa.util.UaaStringUtils.hasText;

/**
 * Runs first in the filter chain. If the request path (after context path) starts with
 * {@code /z/{subdomain}/}, rewrites the request so that the context path includes
 * {@code /z/{subdomain}}. Downstream code then sees a normal path (e.g. {@code /login})
 * and builds URLs using {@code getContextPath()} which already includes the zone path.
 * <p>
 * Does not validate the zone; {@link IdentityZoneResolvingFilter} looks up the zone by
 * subdomain and rejects invalid or missing zones. Sets request attribute
 * {@link #ZONE_SUBDOMAIN_FROM_PATH} with the subdomain string for {@link IdentityZoneResolvingFilter}.
 */
public class ZonePathContextRewritingFilter extends OncePerRequestFilter {

    public static final String BEAN_NAME = "zonePathContextRewritingFilter";

    private static final String SLASH_Z = "/z";
    private static final String ZONE_PATH_PREFIX = SLASH_Z + "/";

    /**
     * Request attribute set when the request was rewritten for a path-based zone.
     * Value is the subdomain string (e.g. "myzone"). Read by {@link IdentityZoneResolvingFilter} to look up and validate the zone.
     */
    public static final String ZONE_SUBDOMAIN_FROM_PATH = "org.cloudfoundry.identity.uaa.zone.ZoneSubdomainFromPath";

    /**
     * Request attribute always set by this filter. When the request was rewritten for a path-based zone,
     * value is the original context path (e.g. "/uaa") before the filter rewrote it to include {@code /z/{subdomain}}.
     * When the request was not rewritten, value is the empty string "".
     */
    public static final String ZONE_ORIGINAL_CONTEXT_PATH = "org.cloudfoundry.identity.uaa.zone.ZoneOriginalContextPath";

    public ZonePathContextRewritingFilter() {
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String contextPath = request.getContextPath() != null ? request.getContextPath() : "";
        String requestURI = request.getRequestURI() != null ? request.getRequestURI() : "";
        String pathAfterContext = getPathAfterContext(requestURI, contextPath);

        if (!pathAfterContext.startsWith(ZONE_PATH_PREFIX) && !pathAfterContext.equals(SLASH_Z)) {
            // Request does not use zone path: pass through unchanged so downstream (e.g. rate limiter,
            // Spring Security matchers) see the container's request as-is. Set ZONE_ORIGINAL_CONTEXT_PATH
            // to the actual context path so zone-aware link builders have a consistent attribute.
            request.setAttribute(ZONE_ORIGINAL_CONTEXT_PATH, contextPath);
            filterChain.doFilter(request, response);
            return;
        }

        //make sure we have the {subdomain} element after /z/
        String subdomain = extractSubdomainFromPath(pathAfterContext);
        if (!hasText(subdomain)) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Invalid /z/ URL");
            return;
        }

        String pathAfterZonePrefix = getPathAfterZonePrefix(pathAfterContext, subdomain);
        String newContextPath = getNewContextPath(contextPath, subdomain);
        String servletPath;
        String pathInfo;
        if ("/".equals(pathAfterZonePrefix) && pathAfterContext.endsWith("/")) {
            // /z/{subdomain}/ → servlet path "", pathInfo "/"
            servletPath = "";
            pathInfo = "/";
        } else {
            // /z/{subdomain} (no trailing) or /z/{subdomain}/something → pathInfo null
            servletPath = pathAfterZonePrefix;
            pathInfo = null;
        }
        HttpServletRequest wrappedRequest = new ZonePathRewrittenRequest(request, newContextPath, servletPath, pathInfo);
        wrappedRequest.setAttribute(ZONE_SUBDOMAIN_FROM_PATH, subdomain);
        wrappedRequest.setAttribute(ZONE_ORIGINAL_CONTEXT_PATH, contextPath);
        HttpServletResponse wrappedResponse = new CookiePathRewritingResponse(response, contextPath);

        filterChain.doFilter(wrappedRequest, wrappedResponse);
    }

    private String getNewContextPath(String contextPath, String subdomain) {
        String baseContext =
                (contextPath != null && contextPath.endsWith("/")) ?
                        contextPath.substring(0, contextPath.length() - 1) :
                        contextPath;
        String newContextPath = (baseContext != null ? baseContext : "") + ZONE_PATH_PREFIX + subdomain;
        return newContextPath;
    }

    private String getPathAfterZonePrefix(String pathAfterContext, String subdomain) {
        String pathAfterZonePrefix = pathAfterContext.substring(ZONE_PATH_PREFIX.length() + subdomain.length());
        if (!pathAfterZonePrefix.startsWith("/")) {
            pathAfterZonePrefix = "/" + pathAfterZonePrefix;
        }
        if (pathAfterZonePrefix.isEmpty()) {
            pathAfterZonePrefix = "/";
        }
        return pathAfterZonePrefix;
    }

    protected String getPathAfterContext(String requestURI, String contextPath) {
        String pathAfterContext = requestURI.startsWith(contextPath)
                ? requestURI.substring(contextPath.length())
                : requestURI;
        if (pathAfterContext.isEmpty()) {
            pathAfterContext = "/";
        }
        if (!pathAfterContext.startsWith("/")) {
            pathAfterContext = "/" + pathAfterContext;
        }
        return pathAfterContext;
    }

    /**
     * Returns the subdomain if path starts with /z/{subdomain}/ (with at least one character after the second slash), otherwise null.
     */
    protected String extractSubdomainFromPath(String path) {
        if (path == null || !path.startsWith(ZONE_PATH_PREFIX)) {
            return null;
        }
        String afterPrefix = path.substring(ZONE_PATH_PREFIX.length());
        int slash = afterPrefix.indexOf('/');
        if (slash < 0) {
            return null;
        }
        String subdomain = afterPrefix.substring(0, slash);
        return StringUtils.hasText(subdomain) ? subdomain : null;
    }

    /**
     * Wraps the request so that getContextPath(), getServletPath(), getRequestURI(), getPathInfo(), getRequestURL()
     * reflect the rewritten path (zone prefix absorbed into context path).
     * <p>
     * Path semantics: {@code /z/{subdomain}} (no trailing slash) exposes servletPath "/", pathInfo null;
     * {@code /z/{subdomain}/} (trailing slash) exposes servletPath "", pathInfo "/"; longer paths expose
     * the path after the zone prefix as servletPath and pathInfo null.
     */
    private static final class ZonePathRewrittenRequest extends HttpServletRequestWrapper {

        private final String contextPath;
        private final String servletPath;
        private final String pathInfo;
        private final String requestURI;

        ZonePathRewrittenRequest(HttpServletRequest request, String contextPath, String servletPath, String pathInfo) {
            super(request);
            this.contextPath = contextPath;
            this.servletPath = servletPath;
            this.pathInfo = pathInfo;
            this.requestURI = contextPath + servletPath + (pathInfo != null ? pathInfo : "");
        }

        @Override
        public String getContextPath() {
            return contextPath;
        }

        @Override
        public String getServletPath() {
            return servletPath;
        }

        @Override
        public String getPathInfo() {
            return pathInfo;
        }

        @Override
        public String getRequestURI() {
            return requestURI;
        }

        @Override
        public String getPathTranslated() {
            return null;
        }

        @Override
        public StringBuffer getRequestURL() {
            HttpServletRequest req = (HttpServletRequest) getRequest();
            String scheme = req.getScheme();
            String serverName = req.getServerName();
            int serverPort = req.getServerPort();
            StringBuilder url = new StringBuilder();
            url.append(scheme).append("://").append(serverName);
            if (("http".equals(scheme) && serverPort != 80) || ("https".equals(scheme) && serverPort != 443)) {
                url.append(':').append(serverPort);
            }
            url.append(requestURI);
            return new StringBuffer(url);
        }
    }

    private static final String SET_COOKIE_HEADER = "Set-Cookie";

    /**
     * Wraps the response and rewrites cookie paths when the request was rewritten for a zone path:
     * cookies with path "/" (or null) get path set to the original context path (e.g. /uaa).
     * If there is no context path (empty or "/"), cookie path is left as "/".
     */
    private static final class CookiePathRewritingResponse extends HttpServletResponseWrapper {

        private final String originalContextPath;

        CookiePathRewritingResponse(HttpServletResponse response, String originalContextPath) {
            super(response);
            this.originalContextPath = originalContextPath != null ? originalContextPath : "";
        }

        private HttpServletResponse getHttpResponse() {
            return (HttpServletResponse) getResponse();
        }

        @Override
        public void addCookie(Cookie cookie) {
            Cookie toAdd = cookie;
            if (shouldRewritePath(cookie.getPath())) {
                toAdd = cloneCookieWithPath(cookie, effectiveCookiePath());
            }
            getHttpResponse().addCookie(toAdd);
        }

        @Override
        public void addHeader(String name, String value) {
            if (SET_COOKIE_HEADER.equalsIgnoreCase(name)) {
                getHttpResponse().addHeader(name, rewriteSetCookieHeaderValue(value));
            } else {
                getHttpResponse().addHeader(name, value);
            }
        }

        @Override
        public void setHeader(String name, String value) {
            if (SET_COOKIE_HEADER.equalsIgnoreCase(name)) {
                getHttpResponse().setHeader(name, rewriteSetCookieHeaderValue(value));
            } else {
                getHttpResponse().setHeader(name, value);
            }
        }

        private boolean shouldRewritePath(String path) {
            if (originalContextPath.isEmpty() || "/".equals(originalContextPath)) {
                return false;
            }
            return path == null || "/".equals(path);
        }

        private String effectiveCookiePath() {
            return originalContextPath.isEmpty() || "/".equals(originalContextPath) ? "/" : originalContextPath;
        }

        private Cookie cloneCookieWithPath(Cookie source, String path) {
            Cookie copy = new Cookie(source.getName(), source.getValue());
            copy.setPath(path);
            if (source.getMaxAge() >= 0) {
                copy.setMaxAge(source.getMaxAge());
            }
            copy.setSecure(source.getSecure());
            copy.setHttpOnly(source.isHttpOnly());
            if (source.getDomain() != null) {
                copy.setDomain(source.getDomain());
            }
            return copy;
        }

        private String rewriteSetCookieHeaderValue(String headerValue) {
            if (headerValue == null) {
                return null;
            }
            if (originalContextPath.isEmpty() || "/".equals(originalContextPath)) {
                return headerValue;
            }
            String pathToUse = originalContextPath;
            String[] parts = headerValue.split(";");
            List<String> result = new ArrayList<>();
            boolean pathFound = false;
            for (String part : parts) {
                String trimmed = part.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                if (trimmed.toLowerCase(Locale.ROOT).startsWith("path=")) {
                    pathFound = true;
                    String currentPath = trimmed.substring(5).trim();
                    if ("/".equals(currentPath)) {
                        result.add("Path=" + pathToUse);
                    } else {
                        result.add(trimmed);
                    }
                } else {
                    result.add(trimmed);
                }
            }
            if (!pathFound) {
                result.add("Path=" + pathToUse);
            }
            return String.join("; ", result);
        }
    }
}