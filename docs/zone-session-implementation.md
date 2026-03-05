# Zone-Scoped Session Implementation

This document describes how UAA implements per-zone session isolation for
path-based identity zones (`/z/{subdomain}/`). A single browser `JSESSIONID`
cookie holds separate, isolated session state for each zone the user visits.

## Problem

With subdomain-based zones (`tenant.login.example.com`), the browser naturally
separates cookies by domain. With path-based zones
(`login.example.com/z/tenant/`), all requests hit the same host and share a
single `JSESSIONID` cookie. Without additional work, logging in to one zone
would overwrite the security context of another, and logging out of any zone
would destroy all sessions.

## Design

The implementation follows **Idea 1** from
[session-persistence-zone-paths.md](session-persistence-zone-paths.md): a
single JSESSIONID cookie, a single underlying container/Spring Session, and
**server-side namespacing by context path**. Each zone's session attributes live
in an isolated `ConcurrentHashMap` stored as a single attribute on the container
session, keyed by the request's context path.

### Key classes

| Class | Role |
|---|---|
| `ZonePathContextRewritingFilter` | Rewrites `/uaa/z/tenant/login` so context path becomes `/uaa/z/tenant` and servlet path becomes `/login`. Also wraps the response to normalize cookie paths. |
| `SessionRepositoryFilter` (Spring Session) | Loads/saves the container session from the configured store (JDBC or in-memory). |
| `ZoneContextPathSessionFilter` | Wraps the request so `getSession()` returns a `ZonePathHttpSession` scoped to the current context path. Wraps the response to prevent premature JSESSIONID clearing. |
| `ZoneContextPathSessionRequestWrapper` | `HttpServletRequestWrapper` that intercepts `getSession()` and `changeSessionId()`. Caches a single `ZonePathHttpSession` per request (since each request has exactly one context path). |
| `ZoneContextPathSessionResponseWrapper` | `HttpServletResponseWrapper` that blocks `Set-Cookie` headers that would clear the JSESSIONID (since other zones may still be active). |
| `ZonePathHttpSession` | `HttpSession` view over one zone's attribute map. `invalidate()` clears only this zone's slice; it does not invalidate the container session. |

## Filter Chain Order

The order of the servlet filters is critical. Each filter wraps the request
and/or response, and the **last filter to wrap** is the wrapper that application
code (Spring Security, controllers) sees first via `request.getSession()`.

```
┌─────────────────────────────────────────────────────────────────────┐
│                         Incoming HTTP Request                       │
│                   GET /uaa/z/tenant/login HTTP/1.1                  │
│                   Cookie: JSESSIONID=abc123                         │
└────────────────────────────────┬────────────────────────────────────┘
                                 │
                                 ▼
┌─────────────────────────────────────────────────────────────────────┐
│  ① ZonePathContextRewritingFilter       (order: HIGHEST + 1)       │
│                                                                     │
│  • Rewrites request:                                                │
│      contextPath:  /uaa  →  /uaa/z/tenant                          │
│      servletPath:  /z/tenant/login  →  /login                      │
│  • Sets request attributes:                                         │
│      ZONE_SUBDOMAIN_FROM_PATH = "tenant"                            │
│      ZONE_ORIGINAL_CONTEXT_PATH = "/uaa"                            │
│  • Wraps response with CookiePathRewritingResponse:                 │
│      rewrites cookie path "/" → "/uaa" (the original context path)  │
│                                                                     │
└────────────────────────────────┬────────────────────────────────────┘
                                 │  request: contextPath = /uaa/z/tenant
                                 │  response: cookie-path-rewriting wrapper
                                 ▼
┌─────────────────────────────────────────────────────────────────────┐
│  ② SessionRepositoryFilter (Spring Session)   (order: HIGHEST + 2) │
│                                                                     │
│  • Loads the session from the configured store (JDBC / memory)      │
│    using the JSESSIONID cookie value.                               │
│  • Wraps the request so getSession() returns a Spring Session-      │
│    backed HttpSession (the "container session").                     │
│  • On response completion, persists dirty session attributes back   │
│    to the store.                                                    │
│  • Cookie path is forced to "/" by UaaSessionConfig, then the       │
│    CookiePathRewritingResponse (from step ①) rewrites it to the    │
│    original context path (e.g. /uaa).                               │
│                                                                     │
└────────────────────────────────┬────────────────────────────────────┘
                                 │  request: getSession() → Spring Session
                                 ▼
┌─────────────────────────────────────────────────────────────────────┐
│  ③ ZoneContextPathSessionFilter           (order: HIGHEST + 51)    │
│                                                                     │
│  • Wraps request with ZoneContextPathSessionRequestWrapper:         │
│      getSession() now returns a ZonePathHttpSession scoped to       │
│      the current contextPath ("/uaa/z/tenant").                     │
│  • Wraps response with ZoneContextPathSessionResponseWrapper:       │
│      blocks Set-Cookie headers that would clear JSESSIONID          │
│      (protects other zones' sessions).                              │
│  • On completion (finally block):                                   │
│      a) If the cached sub-session is dirty, flushes its map back    │
│         to the container session via setAttribute() so Spring       │
│         Session marks it dirty.                                     │
│      b) If all sub-sessions have been invalidated, emits a          │
│         Set-Cookie to clear the JSESSIONID.                         │
│                                                                     │
└────────────────────────────────┬────────────────────────────────────┘
                                 │  request: getSession() → ZonePathHttpSession
                                 │  response: blocks JSESSIONID clearing
                                 ▼
┌─────────────────────────────────────────────────────────────────────┐
│  ④ Spring Security Filter Chain           (order: -100)            │
│                                                                     │
│  • Includes IdentityZoneResolvingFilter, authentication filters,    │
│    SecurityContextPersistenceFilter, etc.                           │
│  • Calls request.getSession() and gets the ZonePathHttpSession.     │
│  • Reads/writes SPRING_SECURITY_CONTEXT from/to the zone-scoped    │
│    sub-session — fully isolated from other zones.                   │
│                                                                     │
└────────────────────────────────┬────────────────────────────────────┘
                                 │
                                 ▼
┌─────────────────────────────────────────────────────────────────────┐
│                     DispatcherServlet / Controllers                  │
└─────────────────────────────────────────────────────────────────────┘
```

### Order values (Spring Boot embedded)

| Filter | `Ordered` value | Configured in |
|---|---|---|
| `ZonePathContextRewritingFilter` | `HIGHEST_PRECEDENCE + 1` | `ZonePathContextRewritingFilterConfiguration` |
| `SessionRepositoryFilter` | `HIGHEST_PRECEDENCE + 2` | `UaaBootConfiguration.sessionRepositoryFilterRegistration()` |
| `ZoneContextPathSessionFilter` | `HIGHEST_PRECEDENCE + 51` | `ZonePathContextRewritingFilterConfiguration` |
| Spring Security (`springSecurityFilterChain`) | `-100` (Spring Boot default) | Spring Boot auto-configuration |

For traditional WAR deployments, the same order is established by registration
sequence in `UaaWebApplicationInitializer.onStartup()`.

## How the Container Session Stores Sub-Sessions

The container session (the one managed by Spring Session or Tomcat) holds one
attribute per active zone. The attribute name is prefixed with a well-known
constant derived from the class name:

```
org.cloudfoundry.identity.uaa.zone.ZonePathHttpSession.<key>
```

Where `<key>` is the context path (e.g. `/uaa/z/tenant`) or `"default"` for the
root context path (empty string maps to `"default"`).

Requests to `/z/default/...` are **not** rejected. They are rewritten so that
the context path **includes** `/z/default` (e.g. `/uaa/z/default`), like any other
zone path. `ZONE_SUBDOMAIN_FROM_PATH` is **not** set, so
`IdentityZoneResolvingFilter` falls through to hostname-based resolution and
naturally resolves the default zone. `ZoneContextPathSessionRequestWrapper`
maps context path `/uaa/z/default` to the same session key as the root (e.g.
`/uaa`), so `/profile` and `/z/default/profile` share the **same cookie and
session**.

Each attribute value is a `ConcurrentHashMap<String, Object>` that holds the
zone's session attributes (security context, saved requests, CSRF tokens, etc.).

**Example** — a user logged in to the default zone and two path-based zones:

```
Container session id: abc123
  Attributes:
    ...ZonePathHttpSession.default         → {SPRING_SECURITY_CONTEXT → <admin auth>}
    ...ZonePathHttpSession./uaa/z/tenant1  → {SPRING_SECURITY_CONTEXT → <alice auth>}
    ...ZonePathHttpSession./uaa/z/tenant2  → {SPRING_SECURITY_CONTEXT → <bob auth>}
```

## Session Lifecycle

### Login

1. Browser sends `POST /uaa/z/tenant/login.do` with `JSESSIONID=abc123`.
2. Filters ①–③ process the request; Spring Security authenticates the user.
3. Spring Security stores the `SecurityContext` via `session.setAttribute(...)`.
   Because `getSession()` returns a `ZonePathHttpSession`, the write goes into
   the `ConcurrentHashMap` for context path `/uaa/z/tenant`.
4. Filter ③'s `finally` block flushes the map back to the container session
   (`containerSession.setAttribute(attrName, map)`) so Spring Session marks it
   dirty.
5. Filter ② commits the session to the store (JDBC or memory).
6. The `JSESSIONID` cookie (path `/uaa`) is shared across all zone paths.

### Subsequent request to a different zone

1. Browser sends `GET /uaa/z/other/profile` with the same `JSESSIONID=abc123`.
2. Filter ② loads the same container session from the store.
3. Filter ③ provides a `ZonePathHttpSession` for context path `/uaa/z/other`.
4. Spring Security reads `SPRING_SECURITY_CONTEXT` from the `/uaa/z/other`
   sub-session — this is a completely separate authentication from `/uaa/z/tenant`.

### Logout from one zone

1. Browser sends `GET /uaa/z/tenant/logout.do`.
2. Spring Security calls `session.invalidate()` on the `ZonePathHttpSession`.
3. `ZonePathHttpSession.invalidate()` clears the `ConcurrentHashMap` and removes
   the attribute from the container session. **The container session itself is not
   invalidated.**
4. The `ZoneContextPathSessionResponseWrapper` blocks Spring Security's attempt
   to clear the `JSESSIONID` cookie (since other zones are still active).
5. In the filter's `finally` block, `maybeClearJSessionIdIfNoSubSessions` checks
   whether any sub-session attributes remain. If yes (other zones are still
   logged in), the cookie is left alone. If all sub-sessions are gone, a
   `Set-Cookie` header is emitted to clear the `JSESSIONID`.

### Container session invalidation

If something invalidates the container session itself (not the sub-session),
all zones lose their sessions simultaneously. This mirrors the behavior of
subdomain-based zones where the session cookie would be deleted by the
browser. Application code should only invalidate the `ZonePathHttpSession`,
never the container session directly.

## Cookie Path

Spring Session's `DefaultCookieSerializer` is configured with a fixed cookie
path of `"/"` (`UaaSessionConfig.uaaCookieSerializer()`). This ensures the
browser always sends the `JSESSIONID` regardless of which zone path is being
accessed.

The `CookiePathRewritingResponse` (part of `ZonePathContextRewritingFilter`)
rewrites cookie paths from `"/"` to the original context path (e.g. `/uaa`) so
the cookie is properly scoped to the UAA deployment and not the entire domain.

## Spring Session Dirty-Tracking

Spring Session JDBC tracks which session attributes have been modified using a
delta map. Only attributes explicitly set via `containerSession.setAttribute()`
are considered dirty. Since `ZonePathHttpSession.setAttribute()` mutates the
`ConcurrentHashMap` in-place without calling `setAttribute` on the container
session, Spring Session would not detect the change.

`ZoneContextPathSessionFilter` solves this in its `finally` block: after the
filter chain completes, it checks the request's single cached `ZonePathHttpSession`.
If the session was modified (dirty), it re-sets the attribute map on the container
session via `containerSession.setAttribute(name, value)`. This triggers Spring
Session's dirty-tracking, ensuring the updated map is persisted to the store.

## Test Coverage

| Test class | Scope |
|---|---|
| `ZoneContextPathSessionTests` (server module, unit) | `ZonePathHttpSession`, `ZoneContextPathSessionRequestWrapper`, `ZoneContextPathSessionResponseWrapper`, `ZoneContextPathSessionFilter` — attribute isolation, invalidation semantics, filter flush, cookie blocking, multi-zone lifecycle |
| `ZonePathSessionMockMvcTests` (uaa module, MockMvc) | End-to-end login, profile access, and logout across multiple zones using `MockMvc` with the real filter chain. |
| `ZoneSessionPathsIT` (uaa module, integration) | Selenium-driven tests against a running UAA server. Logs in to default zone and two path-based zones, verifies profile isolation, verifies logout from one zone leaves others intact. |

## MySQL and Spring Session JDBC

When using Spring Session with the **database** store on **MySQL**, the default
flush mode (`ON_SAVE`) can cause session attributes written in one request to be
invisible when the next request loads the session. That leads to flows that
store data in the session (e.g. the OAuth authorization request with PKCE
`code_challenge` during `/oauth/authorize`) and then read it on a subsequent
request (e.g. after login, when the user approves) seeing a null or incomplete
session, with errors such as "Cannot approve uninitialized authorization request"
or wrong token responses. The same flows work correctly on PostgreSQL and with
the in-memory session store.

**Why PostgreSQL works:** With Spring Session JDBC, both MySQL and PostgreSQL
persist session attributes in a BLOB/BYTEA column. PostgreSQL's transaction
and commit semantics, together with how the JDBC driver and Spring Session
interact, typically make writes visible to the next request when the session is
saved at end of request (`ON_SAVE`). On MySQL, the same sequence can leave the
next request not seeing the attributes (e.g. different connection, commit
timing, or BLOB handling). So the issue is timing/visibility of the persisted
session on MySQL, not the application logic.

**Fixes (both applied for MySQL):**

1. **Flush mode:** For the MySQL profile only, UAA sets
   `spring.session.jdbc.flush-mode=IMMEDIATE` in
   `server/src/main/resources/application-mysql.properties`. That makes Spring
   Session persist session attributes as soon as they are set, so the next
   request is more likely to see them. PostgreSQL and other stores keep the
   default `ON_SAVE` (flush at end of request).

2. **Same-request flush of zone session to container:** The zone session
   (`ZonePathHttpSession`) holds attributes in a map stored as one attribute on
   the container session. Spring MVC stores `@SessionAttributes` (e.g. the
   authorization request) in the model; the framework copies them to the session
   after the controller returns, and the zone filter flushes that map to the
   container session in its `finally` block. On MySQL, that ordering can still
   leave the container session not updated or not persisted before the response
   is sent. So in `UaaAuthorizationEndpoint`, after placing the authorization
   request in the model, we also set the same attributes directly on the
   session and, when the session is a `ZonePathHttpSession`, call
   `flushToContainerSession()` immediately. That puts the attributes in the
   container session in the same request and triggers Spring Session's
   dirty-tracking (and with `IMMEDIATE`, persistence) before the controller
   returns, so the next request reliably sees them on all databases.

When the integration test task starts the UAA server with the `mysql` profile
(see `uaa/build.gradle`), it also passes
`-Dspring.session.jdbc.flush-mode=IMMEDIATE` on the JVM command line so the
flush-mode fix is active for the full integration test suite.

**Remaining limitation:** Flows that trigger a **session ID change on login**
(session fixation protection) create a new session and copy attributes from the
old one. On MySQL, that copy or the new session’s persistence can still exhibit
visibility issues in some cases, so a few tests (e.g. some OpenID hybrid flow
tests) may pass on PostgreSQL but fail on MySQL. If you see such a failure, it
is the same class of MySQL + Spring Session JDBC behaviour; further mitigation
would require changing how the new session is persisted or avoiding session ID
change for the MySQL profile (with security trade-offs).

## Configuration

| Property | Value | Effect |
|---|---|---|
| `servlet.session-store` | `database` or `memory` | Selects Spring Session JDBC or in-memory store. |
| `servlet.session-cookie.encode-base64` | `true`/`false` | Base64 encoding of the JSESSIONID value. |
| Cookie path | Fixed to `"/"` in `UaaSessionConfig` | Ensures one JSESSIONID is sent for all paths; rewritten to context path by `CookiePathRewritingResponse`. |
| `spring.session.jdbc.flush-mode` (MySQL only) | `IMMEDIATE` in `application-mysql.properties` | Ensures session attributes are visible on the next request; see [MySQL and Spring Session JDBC](#mysql-and-spring-session-jdbc). |
