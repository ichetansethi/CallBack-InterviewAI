# auth-service — Architecture & Design Notes

This document explains what exists in `auth-service`, why each piece is built the way it is,
and the reasoning behind the non-obvious decisions. Update this file whenever the
architecture changes — new endpoints, new security rules, changed conventions — so it
stays a reliable map of the service instead of going stale.

## Request flow (registration → login → authenticated call)

```
POST /auth/register  ──▶ AuthController.register()
                            └─▶ UserService.register()
                                  └─▶ UserRepository (JPA) → Postgres "users" table

POST /auth/login      ──▶ AuthController.login()
                            └─▶ AuthenticationManager.authenticate(email, password)
                                  └─▶ CustomUserDetailsService.loadUserByUsername(email)
                                  └─▶ PasswordEncoder.matches(rawPassword, hash)
                            └─▶ JwtIssuerService.generateToken(email)
                            ◀── LoginResponse { token, expiresAt }

GET  /auth/me (Authorization: Bearer <token>)
        ──▶ JwtAuthenticationFilter (common-security; runs before UsernamePasswordAuthenticationFilter)
              ├─ no/invalid header → pass through, unauthenticated
              └─ valid signature (JwtValidator.isValid) → principal = email string, no DB lookup
                             → SecurityContextHolder.setAuthentication(...)
        ──▶ SecurityFilterChain authorization check (authenticated() required)
        ──▶ AuthController.me(Authentication) → returns authentication.getName()
```

### Token signing/verification split (RS256, common-security module)

Tokens are RS256-signed, not HS256. The keypair lives in two places on purpose:

```
common-security/src/main/resources/keys/public_key.pem   ← shipped in the shared jar, safe to
                                                             hand to every service
auth-service/src/main/resources/keys/private_key.pem      ← never committed (.gitignore'd),
                                                             lives only in auth-service
```

- **`JwtIssuerService`** (auth-service only) — loads the private key via `RsaPrivateKeyLoader`
  and is the *only* class in the whole system that can call `signWith(...)`. No other module has
  a dependency path to a private key at all.
- **`RsaPrivateKeyLoader`** (auth-service) and **`RsaPublicKeyLoader`** (common-security) are
  deliberately two separate, near-identical classes rather than one shared PEM-decoding utility.
  A few duplicated lines is the price of keeping common-security's dependency graph from ever
  needing to know a private-key type exists.
- **`common-security`'s `JwtValidator`** — loads the public key (via `RsaPublicKeyLoader`) bundled
  in its own jar and does everything read-only: `isValid`, `extractEmail`, `extractExpiration`.
  Any service that adds a `common-security` dependency gets full verification for free, but gains
  zero ability to issue tokens — there's no code path in that jar that even imports a private key
  type.
- **`common-security`'s `JwtAuthenticationFilter`** — moved out of auth-service so every service
  can reuse the exact same request-authentication logic. It trusts the RS256 signature alone and
  sets the token's email straight as the `Authentication` principal (`UsernamePasswordAuthenticationToken(email, null, ...)`)
  — no DB lookup. A service holding only the public key has no users table to check against
  anyway; the trade-off is that a deleted/deactivated user's still-unexpired token keeps working
  until it expires (no live revocation check), which is the standard trade-off for stateless
  multi-service JWT verification.
- **auth-service depends on `common-security` too**, and `AuthController.login()` calls
  `jwtValidator.extractExpiration(token)` on the token it just signed, rather than re-deriving
  `now + expirationMs` locally. This means the issuer verifies through the exact same public-key
  path every other service will use — no special-cased shortcut for the service that happens to
  hold the private key.
- **`AuthServiceApplication` uses `@SpringBootApplication(scanBasePackages = "com.callback")`**
  instead of the default (package-of-the-app-class-and-below) scan. `JwtValidator` and
  `JwtAuthenticationFilter` live in `com.callback.security.jwt`, a sibling package to
  `com.callback.auth`, so the default scan would miss them entirely. Any future service that
  wants the shared verifier/filter needs the same `scanBasePackages` (or an explicit `@Import`).

Unauthenticated/invalid-token requests to a protected endpoint never reach the controller —
they're rejected by the `SecurityFilterChain`'s authorization check and returned as 401 via
the custom `AuthenticationEntryPoint` (see `SecurityConfig`).

---

## `pom.xml` — dependencies

- **`spring-boot-starter-security`** — brings in the filter chain, `AuthenticationManager`,
  `UserDetailsService` contract. Nothing security-related works without it.
- **`jjwt-api` / `jjwt-impl` / `jjwt-jackson`** — split deliberately. `jjwt-api` is the only one
  on the compile classpath (`Jwts`, `Jwts.builder()`); `jjwt-impl` (signing/parsing engine) and
  `jjwt-jackson` (claims JSON serialization) are `runtime`-scoped only. This is JJWT's own
  convention — application code should never compile against JJWT's implementation internals.
- **`jjwt.version` property** — pinned locally (currently `0.12.6`) because the parent POM's
  `spring-boot-dependencies` BOM doesn't manage JJWT versions.
- **`spring-security-test`** (test scope) — standard companion to `spring-boot-starter-security`
  for future controller tests (`@WithMockUser`, etc.). Not used by any test yet.
- **`spring-security-crypto`** — pre-existing, backs `BCryptPasswordEncoder`.

---

## `service/JwtIssuerService.java` — token issuance (private key, auth-service only)

- **`PrivateKey privateKey`** is parsed once in the constructor (via `common-security`'s
  `PemUtils.readPrivateKey`) and stored `final` — signing is stateless per call, no reason to
  re-parse the PEM file per request.
- **Private key loaded via `@Value("classpath:keys/private_key.pem") Resource`**, not a
  `@Value` `String` — lets Spring resolve the classpath resource and hand back an `InputStream`
  directly, rather than manually building a path.
- **`generateToken(subject)`** — subject is the user's **email**, not a numeric/UUID id. Decided
  early to avoid the id-vs-UUID question; email is already unique (`User.email` has a unique
  DB constraint) and needs no extra lookup indirection.
- **`signWith(privateKey, Jwts.SIG.RS256)`** — RS256, not HS256. See the "Token
  signing/verification split" section above for why the keypair is split across two modules.
- **Time uses `java.time.Instant`**, converted to `java.util.Date` only at the JJWT call
  boundary. JJWT 0.12's builder (`issuedAt(Date)`, `expiration(Date)`) still requires `Date`,
  so `Instant` does all the actual arithmetic and `Date.from(instant)` converts right before
  the value is handed to `Jwts.builder()`. Keeps the legacy `Date` type confined to the
  smallest possible surface.
- **No `extractEmail`/`extractExpiration`/`isValid` here anymore** — those are verification,
  not issuance, and now live on `common-security`'s `JwtValidator` so every service (including
  this one) verifies the same way.

## `common-security`'s `JwtValidator` — token verification (public key, every service)

- **`PublicKey publicKey`** is parsed once in the constructor (via `RsaPublicKeyLoader`) from
  the jar's own bundled `keys/public_key.pem` — no configuration needed; a service just adds
  the Maven dependency and the key comes with it.
- **`extractEmail`** — verifies the signature (`verifyWith(publicKey)`) before reading the
  subject. A forged/tampered token throws instead of silently returning an attacker-chosen
  email.
- **`extractExpiration`** — lets `AuthController`'s `LoginResponse.expiresAt` read from the
  *actual issued token* instead of re-deriving `now + expirationMs` independently. Single
  source of truth for expiry, and proof the issuer verifies through the same path as everyone
  else.
- **`isValid`** — catches `JwtException` (expired / malformed / bad signature) and
  `IllegalArgumentException` (null/empty token) internally, returns `false` rather than
  letting either propagate. Required because this runs on every request via the filter — it
  can't be throwing 500s for a garbage `Authorization` header.

---

## `service/CustomUserDetailsService.java` — bridges `User` entity to Spring Security

- **Implements `UserDetailsService`**, does **not** make the JPA `User` entity implement
  `UserDetails` directly. Keeps `User.java` a plain persistence model with zero Spring
  Security coupling — swapping auth frameworks later wouldn't touch the entity.
- **`org.springframework.security.core.userdetails.User.builder()`** referenced fully
  qualified inline (not imported) because the class name collides with
  `com.callback.auth.model.User`.
- **`.authorities(Collections.emptyList())`** — no roles/permissions concept exists yet;
  left empty rather than inventing a placeholder `ROLE_USER`.
- **Throws `UsernameNotFoundException`** on missing user rather than returning `null` — a
  `null` return would cause an unchecked NPE deeper inside Spring Security's authentication
  provider instead of a clean, expected failure.

---

## `common-security`'s `JwtAuthenticationFilter.java` — runs once per request, shared by every service

- **Lives in `common-security`, not auth-service** — every service that adds the
  `common-security` dependency and enables `scanBasePackages = "com.callback"` gets identical
  request authentication for free, without duplicating this filter per service.
- **Extends `OncePerRequestFilter`**, not a plain `Filter` — guarantees `doFilterInternal`
  runs exactly once per request even across internal forwards/includes. Standard idiom for
  this kind of filter.
- **Missing/malformed `Authorization` header → pass through, no exception.** This filter's
  only job is to *populate* the security context when a valid token is present; deciding
  whether the request is *allowed to proceed* is the `SecurityFilterChain`'s job
  (`.anyRequest().authenticated()`), enforced later in the same chain. This split is why an
  unauthenticated request still correctly reaches the 401 entry point instead of being
  rejected awkwardly mid-filter.
- **Checks `SecurityContextHolder.getContext().getAuthentication() == null`** before setting
  it — avoids redundant work if something upstream already authenticated the request.
- **Sets the token's email directly as the principal (`UsernamePasswordAuthenticationToken(email, null, ...)`),
  no DB lookup** — a service that only holds the public key generally doesn't have a users
  table to check against. This means the trade-off from the old auth-service-only filter
  (immediately locking out a deleted/deactivated user) is gone: a still-unexpired token for a
  deleted user keeps authenticating until it naturally expires. Acceptable for a stateless
  multi-service verification story; revisit with a short token TTL or a revocation list if that
  trade-off ever matters.
- **`WebAuthenticationDetailsSource().buildDetails(request)`** — attaches request metadata
  (remote address, session id) to the `Authentication`. Not required for auth to function;
  it's the conventional detail to attach in case of future audit logging.

---

## `config/SecurityConfig.java` — wires everything together

- **`passwordEncoder()` bean** (`BCryptPasswordEncoder`) — must be a bean (not a private
  `new BCryptPasswordEncoder()` inside a service) so Spring Boot's `AuthenticationConfiguration`
  can discover and auto-wire it into the global `AuthenticationManager`.
- **`authenticationManager()` bean built from `AuthenticationConfiguration`** — the modern
  Spring Security 6+ minimal-boilerplate pattern. Because `CustomUserDetailsService` is the
  only `UserDetailsService` bean and the `PasswordEncoder` above is the only bean of its kind
  in the context, Spring Boot's `InitializeUserDetailsBeanManagerConfigurer` auto-wires both
  into the manager — no explicit `DaoAuthenticationProvider` bean needed. Confirmed at
  startup via the log line:
  `Global AuthenticationManager configured with UserDetailsService bean with name customUserDetailsService`.
- **CSRF disabled** — appropriate specifically because this is a stateless, token-authenticated
  JSON API with no cookies/browser sessions. CSRF protection exists to defend session-cookie
  auth, which doesn't apply here.
- **`SessionCreationPolicy.STATELESS`** — Spring Security never creates/reads an `HttpSession`.
  Consistent with JWT auth: all state travels in the token, the server holds none.
- **Custom `AuthenticationEntryPoint` (`HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)`)** —
  without this, Spring Security's default fallback (`Http403ForbiddenEntryPoint`, used when no
  `formLogin()`/`httpBasic()` is configured) returns **403** for unauthenticated requests. This
  override was added after manual testing showed 403 instead of the required 401 on
  missing/bad tokens.
- **`permitAll()` on `/auth/register`, `/auth/login`; `authenticated()` on everything else** —
  default-deny posture. Any new endpoint added later is locked down by default unless
  explicitly opened up.
- **`addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)`** —
  positions the JWT filter ahead of form-login's filter (unused here) so the security context
  is populated from the token before the authorization check runs.

---

## DTOs — `model/LoginRequest.java`, `model/LoginResponse.java`

- **`LoginRequest`**: `@NotBlank @Email` on email, `@NotBlank` on password — no minimum
  `@Size` on password (unlike `RegisterRequest`'s 8-char minimum). Deliberate: login checks an
  already-existing password against whatever rule was enforced at registration time;
  re-validating length here would reject legitimate older passwords if the policy ever
  changed, and adds no security value since `AuthenticationManager.authenticate()` rejects a
  wrong password regardless of length.
- **`LoginResponse`**: `record(String token, Instant expiresAt)` — plain immutable data
  carrier, same style as `RegisterResponse`.

---

## `controller/AuthController.java`

- **`login()`** calls `authenticationManager.authenticate(new UsernamePasswordAuthenticationToken(email, password))`.
  This single call does the entire credential check — looks up the user via
  `CustomUserDetailsService`, compares the password hash via the wired `PasswordEncoder` — and
  throws Spring Security's own `BadCredentialsException` on mismatch. No custom
  credential-comparison code lives in the controller.
- **Token is generated only after `authenticate()` succeeds** — never issued for an invalid
  credential pair, and the signing cost is never paid on the failure path.
- **`me(Authentication authentication)`** — Spring MVC injects the current `Authentication`
  directly as a method parameter rather than pulling it manually from
  `SecurityContextHolder`. `authentication.getName()` returns the `UserDetails` username,
  which `CustomUserDetailsService` set to the user's email. This endpoint exists purely to
  prove the full chain (filter → context → controller) works end to end — it's a throwaway
  verification endpoint, not a real feature.

---

## `controller/GlobalExceptionHandler.java`

- **`@ControllerAdvice` + `@ExceptionHandler(BadCredentialsException.class)`**, not
  `@ResponseStatus` on the exception class (the pattern `EmailAlreadyRegisteredException`
  uses) — because `BadCredentialsException` is Spring Security's own class; you can't
  annotate a class you don't own. `@ControllerAdvice` is the standard way to map a status
  code onto a third-party exception.
- Returns a generic `"Invalid email or password"` body rather than saying which part was
  wrong — avoids enabling user enumeration (e.g. distinguishing "no such email" from "wrong
  password").

---

## `application.yml`

- **No `jwt.secret` anymore** — RS256 replaced the HS256 shared secret. The private key is a
  gitignored file (`auth-service/src/main/resources/keys/private_key.pem`) rather than an env
  var; see the "Token signing/verification split" section above.
- **DB credentials (`DB_USERNAME` / `DB_PASSWORD`) do carry defaults** (currently
  `callback_admin` / `callback_password123`) — acceptable for a local-only Postgres instance
  where dev convenience outweighs the near-zero risk, unlike the JWT secret.

---

## Pre-existing files (unchanged by the auth/JWT work)

`User.java`, `UserRepository.java`, `UserService.java`, `RegisterRequest.java`,
`RegisterResponse.java`, `EmailAlreadyRegisteredException.java`, `HealthController.java`,
`AuthServiceApplication.java` — used as-is. `UserService.register()` is what
`AuthController.register()` calls; `CustomUserDetailsService` reads from the same
`UserRepository`.

---

## Verified manually (2026-08-17)

Full flow tested against a local Postgres instance:

| Scenario | Expected | Result |
|---|---|---|
| `POST /auth/register` | 201 | ✅ |
| `POST /auth/login` (correct credentials) | 200 + token + expiresAt | ✅ |
| `GET /auth/me` (valid token) | 200, body = email | ✅ |
| `GET /auth/me` (no token) | 401 | ✅ |
| `GET /auth/me` (garbage token) | 401 | ✅ |
| `POST /auth/login` (wrong password) | 401, body = "Invalid email or password" | ✅ |

---

## Keeping this document current

When you change something structural — a new endpoint, a new security rule, a new bean, a
changed default — update the relevant section above in the same commit/PR. Treat drift
between this file and the code as a bug.