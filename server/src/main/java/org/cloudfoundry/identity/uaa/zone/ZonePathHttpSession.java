package org.cloudfoundry.identity.uaa.zone;

import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpSession;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

import static org.cloudfoundry.identity.uaa.zone.ZonePathContextRewritingFilter.DEFAULT_ZONE_SUBDOMAIN_PATH;

/**
 * HttpSession view scoped to a context path. Each attribute is stored directly on the
 * container session under a prefixed key: {@code containerSessionAttributeName + ATTRIBUTE_KEY_DELIMITER + name}.
 * This ensures Spring Session's dirty-tracking sees every read/write immediately and
 * avoids timing issues (e.g. with MySQL JDBC session store). {@link #invalidate()}
 * removes all attributes for this context path from the container session; it does
 * not invalidate the container session itself.
 * <p>
 * Each subsession has a unique {@link #getId()} so that session repositories and
 * the application can distinguish subsessions: container session ID plus the context
 * path with '/' replaced by '-'.
 */
public class ZonePathHttpSession implements HttpSession {

    /**
     * Delimiter between the zone prefix and the attribute name on the container session.
     * Public so tests and other code can build container keys without hardcoding the delimiter.
     */
    public static final String ATTRIBUTE_KEY_DELIMITER = ".";

    /**
     * Key used for the root context path (empty or "/") in session ID suffixes.
     * Also reserved as a zone subdomain to prevent collisions.
     */
    private static final String DEFAULT_CONTEXT_PATH_KEY = DEFAULT_ZONE_SUBDOMAIN_PATH;

    private final HttpSession containerSession;
    private final String containerSessionAttributeName;
    /** Prefix for this zone's attributes on the container: containerSessionAttributeName + ATTRIBUTE_KEY_DELIMITER */
    private final String attributeKeyPrefix;
    /** Suffix for {@link #getId()}: context path with '/' replaced by '-', or {@link #DEFAULT_CONTEXT_PATH_KEY} for root. */
    private final String sessionIdSuffix;

    public ZonePathHttpSession(HttpSession containerSession,
                               String contextPathKey,
                               String containerSessionAttributeName) {
        this.containerSession = containerSession;
        this.containerSessionAttributeName = containerSessionAttributeName;
        this.attributeKeyPrefix = containerSessionAttributeName + ATTRIBUTE_KEY_DELIMITER;
        this.sessionIdSuffix = sessionIdSuffixFromContextPath(contextPathKey);
    }

    private static String sessionIdSuffixFromContextPath(String contextPathKey) {
        if (contextPathKey == null || contextPathKey.isEmpty()) {
            return DEFAULT_CONTEXT_PATH_KEY;
        }
        String suffix = contextPathKey.replaceAll("/", "-").replaceFirst("^-+", "");
        return suffix.isEmpty() ? DEFAULT_CONTEXT_PATH_KEY : suffix;
    }

    private String containerKey(String name) {
        return containerSessionAttributeName + ATTRIBUTE_KEY_DELIMITER + name;
    }

    @Override
    public Object getAttribute(String name) {
        return containerSession.getAttribute(containerKey(name));
    }

    @Override
    public void setAttribute(String name, Object value) {
        String key = containerKey(name);
        if (value != null) {
            containerSession.setAttribute(key, value);
        } else {
            containerSession.removeAttribute(key);
        }
    }

    @Override
    public void removeAttribute(String name) {
        containerSession.removeAttribute(containerKey(name));
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
        List<String> toRemove = new ArrayList<>();
        Enumeration<String> names = containerSession.getAttributeNames();
        if (names != null) {
            while (names.hasMoreElements()) {
                String name = names.nextElement();
                if (name.startsWith(attributeKeyPrefix)) {
                    toRemove.add(name);
                }
            }
        }
        for (String name : toRemove) {
            try {
                containerSession.removeAttribute(name);
            } catch (IllegalStateException ignored) {
                // Container session was invalidated
            }
        }
    }

    @Override
    public Enumeration<String> getAttributeNames() {
        List<String> result = new ArrayList<>();
        Enumeration<String> names = containerSession.getAttributeNames();
        if (names != null) {
            while (names.hasMoreElements()) {
                String name = names.nextElement();
                if (name.startsWith(attributeKeyPrefix)) {
                    result.add(name.substring(attributeKeyPrefix.length()));
                }
            }
        }
        return Collections.enumeration(result);
    }
}
