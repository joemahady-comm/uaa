# Session Persistence for Path-Based Zones

This document compares two implementation strategies for session persistence in the UI when tenants (identity zones) are accessed via URL paths (e.g. `https://login.sys.cf.com/z/tenant-1/someurl`) in addition to subdomains (e.g. `https://tenant-1.login.sys.cf.com/someurl`).

## Context

- **Subdomain mode**: Cookies are naturally separated by domain, so one JSESSIONID per tenant with no overlap.
- **Path mode**: All requests hit the same host (`login.sys.cf.com`), so we must separate sessions by cookie path and/or server-side scoping. The default zone uses path `/`; zone paths use `/z/{subdomain}/`, which overlaps with `/`.

## Codebase Context

- **Zone resolution**: `IdentityZoneResolvingFilter` sets the zone from host or from path `/z/{subdomain}/` and stores it in `IdentityZoneHolder` for the request. It is registered as part of the Spring Security filter chain (e.g. in `SpringServletXmlSecurityConfiguration`).
- **Session**: Spring Session is used (memory or DB via `servlet.session-store`). `UaaSessionConfig` defines a `CookieSerializer` with name `JSESSIONID`; path is not set, so it defaults to context root (effectively `/`).
- **Filter order (current)**: In the servlet setup, `springSessionRepositoryFilter` is registered first in `UaaWebApplicationInitializer`, then the security filter chain (which contains `IdentityZoneResolvingFilter`). So **the session is loaded before the zone is set**. For Idea 1, zone resolution must run before the Spring Session filter; fixing this order is a required, one-time change (e.g. register the zone filter before the session filter, or use `FilterRegistrationBean.setOrder()` so zone resolution runs first).
- **Zone vs session**: `SessionResetFilter` already enforces that the authenticated user's zone matches the current request zone (`identityZoneManager.getCurrentIdentityZoneId()` vs `authentication.getPrincipal().getZoneId()`). So the app already assumes "one logical session per zone" from a security perspective.
- **Subdomain behavior**: With subdomains, the browser sends different cookies per host, so zone isolation is automatic. With path-based access, everything is on the same host, so we have to achieve isolation via cookie path and/or server-side session scoping.
- **Cookie-hijack test**: `ZoneValidationCookieHijackIT` asserts that a session cookie from one zone (e.g. zone1) cannot be used to access another zone (e.g. zone2); path-based equivalents are needed for path-based zone access.

---

## Idea 1: Single JSESSIONID, One Server Session, Namespaced by Zone

**Mechanism**: One cookie (e.g. path `/`), one session id. Server-side, the session store is keyed by `(sessionId, zoneId)` so each zone has its own "slice" of that session. The app still sees a normal `HttpSession` per request; the zone slice is chosen using the request path (and thus `IdentityZoneHolder`).

### Pros

- **One cookie**: No cookie overlap, no "which JSESSIONID did the browser send?" problem. No Valve needed for cookie selection.
- **Single place for isolation**: Zone isolation lives entirely in the session store (composite key + zone from `IdentityZoneHolder`). No second critical path (Valve + cookie path) that could mis-route sessions.
- **Fits existing zone check**: `SessionResetFilter` already rejects mismatched zones; we only need the session repository to expose the slice for the current zone so that the right `SecurityContext` is in the session for that zone.
- **Natural for "same user, many zones"**: One identity, multiple zone-specific logins (e.g. tenant-1 and tenant-2) without multiplying cookies.
- **Implementation at Spring Session layer**: We already use Spring Session and a custom `CookieSerializer`. We can implement this with a **zone-aware session repository** (and possibly a thin wrapper) rather than Tomcat internals. No Valve required.

### Cons

- **Custom session repository**: We must implement (or wrap) `SessionRepository` so that:
  - Read/write use a composite key like `(sessionId, zoneId)`.
  - Zone comes from `IdentityZoneHolder.get()` (or equivalent), so **filter order matters**: `IdentityZoneResolvingFilter` must run **before** the Spring Session filter so the zone is set when the session is loaded/saved. Today the session filter runs first; we need to ensure zone resolution runs first (e.g. register `IdentityZoneResolvingFilter` before `springSessionRepositoryFilter`, or use explicit filter order).
- **DB store**: If we use `servlet.session-store=database`, the JDBC implementation must be extended similarly (compound key, zone from context). More work than memory.
- **Session id semantics**: The same session id refers to different logical sessions per zone; debugging and ops must think in terms of (sessionId, zone).

### Difficulty

Medium. Main work: zone-aware `SessionRepository` (and DB schema/usage if we use DB sessions), ensuring filter order, and tests (e.g. path-based equivalent of `ZoneValidationCookieHijackIT`).

### Risks

- Forgetting to run zone resolution before session resolution (wrong order) and thus wrong or missing zone when loading/saving.
- Any code that assumes "one session id = one global session" (e.g. some admin or logging) may need to be zone-aware.

---

## Idea 2: One JSESSIONID per Zone (Path-Scoped Cookies)

**Mechanism**: Default zone: cookie path `/`. Non-default zone: cookie path `/z/{subdomain}/`. Each zone gets its own session id and its own cookie. When the request is under `/z/tenant-1/`, the browser may send both the `/` and `/z/tenant-1/` cookies; a Valve (or filter) ensures only the cookie that matches the request path is used for session lookup.

### Pros

- **Clear isolation**: One session per zone, one cookie per zone; matches the mental model of "separate session per tenant" and aligns with `SessionResetFilter` and `ZoneValidationCookieHijackIT`.
- **No change to session store**: Existing `MapSessionRepository` / JDBC store stay as-is; only cookie path and request-side cookie handling change.
- **Easier to reason about**: Session id uniquely identifies a single zone's session; no compound key in the store.

### Cons

- **Overlapping cookies**: Requests to `/z/tenant-1/...` can send both `JSESSIONID` for `/` and `JSESSIONID` for `/z/tenant-1/`. We **must** ensure the container/framework uses only the path-matching cookie, or we risk using the default-zone session in a tenant context (or the reverse).
- **Valve (or equivalent) required**: Something must run before session resolution and normalize the `Cookie` header (or the way the session id is resolved) so that only the cookie whose path matches the request path is used. That's a Tomcat Valve or an early filter that wraps the request and filters `getCookies()` / `Cookie` header.
- **CookieSerializer must be zone-aware**: When writing the cookie (e.g. after login), we must set the path from the current zone: `/` for default zone, `/z/{subdomain}/` for others. So the serializer (or the code that calls it) must read `IdentityZoneHolder` and set path accordingly. `DefaultCookieSerializer.setCookiePath()` is a fixed value; we need a **custom serializer** that sets path per response based on zone.
- **Logout / invalidation**: Logging out of one zone should clear only that zone's cookie (and invalidate that zone's session). We must ensure we only clear the cookie for the current path/zone.

### Difficulty

Medium–high. We need: (1) custom `CookieSerializer` that sets path by zone, (2) Valve (or early filter) that restricts which `JSESSIONID` is used based on request path, (3) correct behavior when multiple `JSESSIONID` cookies are present, and (4) logout and redirect flows that set/clear the right cookie.

### Risks

- **Cookie selection bugs**: If the Valve/filter is wrong or order is wrong, the wrong session can be used (e.g. default-zone session used for `/z/tenant-1/`), which weakens tenant isolation. This is the main risk.
- **Browser/client behavior**: RFC 6265 path matching can send multiple cookies; relying on "most specific first" is fragile. Explicitly selecting by path in the Valve/filter is safer.
- **Edge cases**: Default zone at `/` and tenant at `/z/tenant-1/` with links/redirects between them; ensure cookie path and session resolution are consistent in every flow.

---

## Recommendation

**Idea 1** is the preferred approach for path-based zones:

- Single cookie and no path overlap, so no Valve for cookie selection.
- Fits existing "zone must match principal" logic and keeps isolation in one place (session store keyed by zone).
- Implementation stays in the Spring Session layer (repository + filter order), which we already control.
- Filter order is fixable in a one-time, well-defined way (zone resolution before session resolution).

**Idea 2** is viable if we strongly prefer "one session id per zone" and are willing to implement and maintain the Valve and custom cookie serialization and to test all path/cookie combinations carefully. The ongoing risk is cookie-selection bugs that could weaken tenant isolation.

### If Choosing Idea 1

1. **Composite key**: Define `(sessionId, zoneId)` and implement (or wrap) `SessionRepository` so that all reads/writes use it, with zone from `IdentityZoneHolder`.
2. **Filter order**: Ensure `IdentityZoneResolvingFilter` runs **before** the Spring Session filter. Today the session filter is registered first; register the zone filter first (e.g. in `UaaWebApplicationInitializer`) or use `FilterRegistrationBean.setOrder()` so zone resolution runs before `springSessionRepositoryFilter`.
3. **Path-based tests**: Add tests analogous to `ZoneValidationCookieHijackIT`: session established via `/z/tenant-1/` must not authorize `/z/tenant-2/`; default-zone session must not authorize tenant paths and vice versa.
4. **DB store (if used)**: Extend schema and JDBC repository to support the composite key and pass zone from request context.
