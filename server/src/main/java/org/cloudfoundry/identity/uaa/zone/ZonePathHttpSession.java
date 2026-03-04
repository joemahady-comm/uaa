package org.cloudfoundry.identity.uaa.zone;

import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpSession;

import java.util.Enumeration;
import java.util.Map;
import java.util.Collections;

import static org.cloudfoundry.identity.uaa.zone.ZonePathContextRewritingFilter.DEFAULT_ZONE_SUBDOMAIN_PATH;

/**
 * HttpSession view scoped to a context path. Attributes are stored in a map held
 * in the container session under one attribute per context path. {@link #invalidate()}
 * clears this context path's attributes and removes the attribute from the container
 * session; it does not invalidate the container session itself.
 * <p>
 * Each subsession has a unique {@link #getId()} so that session repositories and
 * the application can distinguish subsessions: container session ID plus the context
 * path with '/' replaced by '-'. The application only reads and writes attributes
 * through this view (the context-path map), not the container session.
 */
public class ZonePathHttpSession implements HttpSession {

    /**
     * Key used for the root context path (empty or "/") in sub-session attribute names and session ID suffixes.
     * Also reserved as a zone subdomain to prevent collisions.
     */
    private static final String DEFAULT_CONTEXT_PATH_KEY = DEFAULT_ZONE_SUBDOMAIN_PATH;

    private final HttpSession containerSession;
    private final Map<String, Object> attributes;
    private final String containerSessionAttributeName;
    /** Suffix for {@link #getId()}: context path with '/' replaced by '-', or {@link #DEFAULT_CONTEXT_PATH_KEY} for root. */
    private final String sessionIdSuffix;
    private boolean dirty;

    public ZonePathHttpSession(HttpSession containerSession,
                               String contextPathKey,
                               Map<String, Object> attributes,
                               String containerSessionAttributeName) {
        this.containerSession = containerSession;
        this.attributes = attributes;
        this.containerSessionAttributeName = containerSessionAttributeName;
        this.sessionIdSuffix = sessionIdSuffixFromContextPath(contextPathKey);
    }

    private static String sessionIdSuffixFromContextPath(String contextPathKey) {
        if (contextPathKey == null || contextPathKey.isEmpty()) {
            return DEFAULT_CONTEXT_PATH_KEY;
        }
        String suffix = contextPathKey.replaceAll("/", "-").replaceFirst("^-+", "");
        return suffix.isEmpty() ? DEFAULT_CONTEXT_PATH_KEY : suffix;
    }

    @Override
    public Object getAttribute(String name) {
        return attributes.get(name);
    }

    @Override
    public void setAttribute(String name, Object value) {
        dirty = true;
        if (value != null) {
            attributes.put(name, value);
        } else {
            attributes.remove(name);
        }
    }

    @Override
    public void removeAttribute(String name) {
        dirty = true;
        attributes.remove(name);
    }

    @Override
    public String getId() {
        return containerSession.getId() + "-" + sessionIdSuffix;
    }

    @Override
    public ServletContext getServletContext() {
        return containerSession.getServletContext();
    }

    @Override
    public long getCreationTime() {
        return containerSession.getCreationTime();
    }

    @Override
    public long getLastAccessedTime() {
        return containerSession.getLastAccessedTime();
    }

    @Override
    public void setMaxInactiveInterval(int interval) {
        containerSession.setMaxInactiveInterval(interval);
    }

    @Override
    public int getMaxInactiveInterval() {
        return containerSession.getMaxInactiveInterval();
    }

    @Override
    public boolean isNew() {
        return containerSession.isNew();
    }

    @Override
    public void invalidate() {
        dirty = false;
        attributes.clear();
        containerSession.removeAttribute(containerSessionAttributeName);
    }

    public boolean isDirty() {
        return dirty;
    }

    /**
     * Re-sets this sub-session's attribute map on the container session so that
     * Spring Session's dirty-tracking detects the change, then clears the dirty flag.
     * Silently does nothing if the container session has been invalidated.
     */
    public void flushToContainerSession() {
        try {
            containerSession.setAttribute(containerSessionAttributeName, attributes);
        } catch (IllegalStateException ignored) {
            // Container session was invalidated during the request
        }
        dirty = false;
    }

    @Override
    public Enumeration<String> getAttributeNames() {
        return Collections.enumeration(attributes.keySet());
    }
}
