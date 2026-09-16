# common-security — Architecture & Design Notes

This document explains what exists in `common-security`, why each piece is built the way it is,
and the reasoning behind the non-obvious decisions. Update this file whenever the architecture
changes — a new filter, a new conditional guard, a new consumer's wiring pattern — so it stays a
reliable map of the module instead of going stale.

## What this module is

> From `pom.xml`'s own `<description>`: "Shared JWT verification library. Ships only the RS256
> public key: any service that depends on this module can verify tokens issued by auth-service,
> but none of them gain the ability to issue tokens."

It is deliberately thin: **4 main classes, 1 bundled resource, zero tests, zero Spring Boot
auto-configuration.** There is no `spring.factories`/`AutoConfiguration.imports` anywhere in this
module — nothing here registers itself the way a real Spring Boot starter would. Every consumer
wires this module in by hand: add the Maven dependency, widen `@SpringBootApplication`'s
component scan to `com.callback`, and (for a servlet service) build your own `SecurityConfig`
that wires the filter into your `SecurityFilterChain`. See "How consuming services wire this up"
below — this contract is followed with varying degrees of completeness across the five consumers,
which is itself worth documenting rather than glossing over.

Full class list:

```
common-security/src/main/java/com/callback/security/jwt/
  RsaPublicKeyLoader.java             — loads the bundled public key from the jar
  JwtValidator.java                    — signature verification + claim extraction (shared by both filters)
  JwtAuthenticationFilter.java         — servlet Filter, for MVC/servlet consumers
  ReactiveJwtAuthenticationFilter.java — WebFilter, for WebFlux consumers (voice-orchestrator)
common-security/src/main/resources/keys/public_key.pem
```

No test directory exists under this module — a real gap, called out explicitly at the end of this
document, given the module is security-critical and shared by every other service in the system.

---

## Token signing/verification split (recap — full story lives in auth-service/ARCHITECTURE.md)

Tokens are RS256. The private key lives only in auth-service (gitignored, never here); this
module ships only the public key, bundled directly in its jar
(`src/main/resources/keys/public_key.pem`) so any consumer gets verification "for free" the
moment it adds the Maven dependency — no configuration, no key distribution step. See
auth-service/ARCHITECTURE.md's "Token signing/verification split" section for the full
issuance-side rationale; this document covers the verification side and how it's shared.

### `RsaPublicKeyLoader.java`
- Final utility class, private constructor, one static method `loadFromClasspath()`.
- Reads `/keys/public_key.pem` via `getResourceAsStream(...)` — from this jar's own bundled
  resource, not an externally configured path.
- Strips PEM armor (`-----BEGIN/END (.*)-----`, then all whitespace), Base64-decodes, and builds
  a `PublicKey` via `KeyFactory.getInstance("RSA")` + `X509EncodedKeySpec`.
- Throws `IllegalStateException` wrapping any `IOException`/`NoSuchAlgorithmException`/
  `InvalidKeySpecException` — fails fast and loud at bean construction time.
- Not a Spring bean itself — a static loader invoked once by `JwtValidator`'s constructor.

### `JwtValidator.java`
- `@Component`, **unconditional** — no `@ConditionalOnClass`/`@ConditionalOnWebApplication`
  guard, because verification logic itself has no dependency on which web stack (servlet,
  reactive, or none) the consuming service runs. This is the single point of truth for "is this
  token valid, and whose is it" — both `JwtAuthenticationFilter` and `ReactiveJwtAuthenticationFilter`
  delegate to the exact same instance of this class; only the transport-layer wrapper around it
  differs between the two.
- `isValid(token)` catches `JwtException | IllegalArgumentException` and returns `false` rather
  than propagating — required because this runs on every request via a filter and can't turn a
  garbage `Authorization` header (or, for the reactive filter, a garbage query param) into a 500.
- `extractEmail(token)` / `extractExpiration(token)` verify the signature before reading any
  claim (`Jwts.parser().verifyWith(publicKey).build().parseSignedClaims(token)`) — a
  forged/tampered token throws rather than silently handing back an attacker-chosen subject.

---

## Two filters, two web stacks — why both exist side by side

Four of the five consumer services (auth-service, compatibility-service, jd-resume-service,
question-service) are servlet/MVC applications built on `spring-boot-starter-web`.
voice-orchestrator is the exception — a fully reactive WebFlux application
(`spring-boot-starter-webflux`, no `spring-boot-starter-security` at all) built to stream binary
audio both ways over a WebSocket without blocking Netty's event-loop threads. A `Filter`
(`OncePerRequestFilter`) is a servlet-container concept; it cannot run inside a Netty/WebFlux
request pipeline, and even if it somehow could, its `SecurityContextHolder` is `ThreadLocal`-based
— fundamentally unsafe in a non-blocking runtime where the same request can hop across threads.
So a second, independent filter class was added — `ReactiveJwtAuthenticationFilter` — built on
WebFlux's own `WebFilter` SPI and Reactor's `Context`-scoped `ReactiveSecurityContextHolder`
instead of `ThreadLocal`. **Both filters call the exact same `JwtValidator` bean** for actual
verification; only the request/response transport wrapper and the mechanism for propagating
identity downstream differ.

### `JwtAuthenticationFilter.java` (servlet)

```java
@Component
@ConditionalOnClass(Filter.class)
public class JwtAuthenticationFilter extends OncePerRequestFilter { ... }
```

- Extends `OncePerRequestFilter` — guarantees exactly-once execution per request across
  forwards/includes.
- Missing/malformed `Authorization` header → pass through unauthenticated, no exception; deciding
  whether the request is *allowed to proceed* is left entirely to each consumer's own
  `SecurityFilterChain`.
- Only sets the `SecurityContext` if the token is valid **and**
  `SecurityContextHolder.getContext().getAuthentication() == null` — avoids clobbering something
  already set upstream.
- Principal is the raw email string, no DB lookup:
  `new UsernamePasswordAuthenticationToken(email, null, Collections.emptyList())`. See
  auth-service/ARCHITECTURE.md for the full stateless-verification trade-off this implies
  (a deleted user's still-unexpired token keeps authenticating until it naturally expires).
- Attaches `WebAuthenticationDetailsSource().buildDetails(request)` for future audit logging.

**Why `@ConditionalOnClass(Filter.class)` and not the more "obvious" `@ConditionalOnWebApplication(SERVLET)`:**
`@ConditionalOnWebApplication(SERVLET)` was tried first and looked more semantically direct, but
it broke `ResumeTextExtractionTest` in jd-resume-service — that test loads a full `@SpringBootTest`
with `webEnvironment = NONE`, and Spring doesn't classify a `NONE`-environment context as a
"servlet web application" even though `jakarta.servlet-api` is genuinely on that context's
classpath and the filter bean is genuinely needed there (the test authenticates by hand via
`SecurityContextHolder`, but other beans in that same context still expect the filter to exist).
`@ConditionalOnClass` sidesteps this: it's evaluated from ASM-read annotation metadata **before
the class is ever loaded**, so on a service with no `jakarta.servlet-api` on its runtime
classpath at all (voice-orchestrator), the JVM never attempts to load `OncePerRequestFilter`'s
supertype in the first place — no classloading crash, and no dependency on which "web application
type" Spring thinks the current context is. Class presence, not current web-application-type, is
what actually determines whether this bean can safely exist.

### `ReactiveJwtAuthenticationFilter.java` (WebFlux)

```java
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class ReactiveJwtAuthenticationFilter implements WebFilter { ... }
```

- Implements `WebFilter` (not `OncePerRequestFilter`) — any `WebFilter` bean in a WebFlux
  application context is automatically detected and spliced into the reactive request-handling
  pipeline by Spring Boot's reactive web autoconfiguration. **No `SecurityWebFilterChain` bean and
  no `spring-boot-starter-security` dependency are required for it to run** — this is exactly how
  it activates in voice-orchestrator today, which has no `SecurityConfig`/`@EnableWebFluxSecurity`
  at all (see voice-orchestrator/ARCHITECTURE.md).
- `extractToken(exchange)` checks the **`token` query parameter first**, falling back to the
  `Authorization: Bearer ` header second. Javadoc reasoning, written specifically with
  voice-orchestrator's `/voice/session` WebSocket endpoint in mind: *a WebSocket upgrade request
  starts as a plain HTTP GET that a browser cannot attach a custom header to, so the token has to
  travel as a query parameter instead; a plain reactive REST endpoint the service also exposes
  still authenticates via the Authorization header as normal.*
- On a valid token: builds the same shape of `UsernamePasswordAuthenticationToken(email, null,
  emptyList())` as the servlet filter, but propagates it via
  `chain.filter(exchange).contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication))`
  — Reactor **Context** propagation, not `ThreadLocal`. This is the WebFlux-native replacement for
  `SecurityContextHolder`, required because a non-blocking pipeline can hop across threads mid
  request.
- On no valid token: `return chain.filter(exchange);` unconditionally — the code comment states
  *"let it through unauthenticated; reject downstream if needed."* This is a real,
  currently-**unenforced** design point, not a settled trade-off: nothing downstream in
  voice-orchestrator currently reads the identity this filter sets up (see below), so as wired
  today there is no "reject downstream" actually happening at the WebFilter layer for this
  service.

**Why `@ConditionalOnWebApplication(REACTIVE)` here, but `@ConditionalOnClass` on the servlet
side — a deliberate divergence, not an inconsistency:** the "obvious" class-presence equivalent
for the reactive filter would be checking for a WebFlux-specific class (e.g.
`DispatcherHandler`), mirroring the servlet filter's approach. That doesn't work here: this
project's Spring AI starters (Ollama/OpenAI, used by compatibility-service, jd-resume-service,
and question-service — all three purely servlet/MVC apps) pull `spring-webflux` onto the
classpath **transitively**, for their own internal `WebClient` usage. This was confirmed directly
via `mvn dependency:tree` against question-service — a purely servlet service — which has
`spring-webflux` on its classpath despite being MVC-based end to end. A class-presence check
would therefore register `ReactiveJwtAuthenticationFilter` harmlessly-but-pointlessly in every
servlet service that happens to also call an AI model. `@ConditionalOnWebApplication(REACTIVE)`
avoids that entirely and was confirmed correct against a live servlet service (i.e., it does
*not* fire there). The known blind spot, mirroring the exact bug the servlet filter's own
Javadoc describes: a `@SpringBootTest(webEnvironment = NONE)` test in a *reactive* service that
needed this bean would find it missing. No such test exists yet anywhere in this repo — flagged
directly in the class's own Javadoc as "the first thing to revisit" if one is ever added.

---

## `pom.xml` — the two `provided`-scope dependencies that make this possible

```xml
<!-- lets this module compile the servlet filter without forcing the servlet API onto a
     reactive-only consumer's runtime classpath -->
<dependency>
  <groupId>jakarta.servlet</groupId>
  <artifactId>jakarta.servlet-api</artifactId>
  <scope>provided</scope>
</dependency>

<!-- Compile-only, same pattern as jakarta.servlet-api above: lets this module compile
     ReactiveJwtAuthenticationFilter without forcing the reactive stack onto every
     consumer's runtime classpath. Only a service that already declares its own
     spring-boot-starter-webflux (like voice-orchestrator) actually gets it at runtime. -->
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-webflux</artifactId>
  <scope>provided</scope>
</dependency>

<!-- For @ConditionalOnWebApplication, guarding each filter so it only activates on the
     web stack it's written for — see JwtAuthenticationFilter / ReactiveJwtAuthenticationFilter. -->
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-autoconfigure</artifactId>
  <scope>provided</scope>
</dependency>
```

Both stack-specific dependencies are `provided` — this jar itself carries neither at runtime.
Each consumer supplies whichever stack it actually runs (`jakarta.servlet-api` transitively via
`spring-boot-starter-web`, or `spring-boot-starter-webflux` directly), and the two `@Conditional*`
guards ensure only the filter matching that stack is ever instantiated.

**`spring-security-web` is *not* `provided`** — it's a real, default-scope (compile) dependency,
which means every consumer gets it transitively at runtime, including voice-orchestrator, which
declares no `spring-boot-starter-security` of its own anywhere in its pom. voice-orchestrator's
access to `UsernamePasswordAuthenticationToken`, `ReactiveSecurityContextHolder`, and the
`WebFilter` interface comes entirely as a side effect of depending on `common-security` — not
from any Spring Security starter it chose itself. This is intentional (the module needs these
types to compile its own filters) but worth knowing when reasoning about voice-orchestrator's
dependency graph.

Also: `jjwt-api` (compile), `jjwt-impl`/`jjwt-jackson` (runtime) — same split rationale as
documented in auth-service/ARCHITECTURE.md (`jjwt-api` is the only JJWT artifact any application
code should compile against). `jjwt.version` (`0.12.6`) is pinned locally as a property, same
reason as elsewhere in this repo: the parent's `spring-boot-dependencies` BOM doesn't manage JJWT.

---

## How consuming services wire this up — verified per service, and where it diverges

Because there is no Spring Boot auto-configuration, every consumer must do the following
manually. This section documents what's actually true today across all five consumers, including
where the pattern is *not* followed consistently — these are real, current gaps worth tracking,
not hypothetical risks.

**1. Maven dependency** — every consumer's pom declares
`com.callback:common-security:${project.version}`. True for all five.

**2. `scanBasePackages = "com.callback"`** — every consumer's `@SpringBootApplication` main class
widens the default component scan, because this module's classes live in
`com.callback.security.jwt`, a sibling package to e.g. `com.callback.auth` or `com.callback.voice`
— the default (app-package-and-below) scan would otherwise miss them entirely. True for all five;
voice-orchestrator's main class carries an explicit comment justifying the broad scan given that
*two* JWT filter classes now share that package (the comment says both are guarded by
`@ConditionalOnWebApplication` — technically imprecise, since the servlet filter is actually
guarded by `@ConditionalOnClass`; a minor doc inaccuracy worth fixing if you're in that file).

**3. Servlet consumers must build their own `SecurityConfig`** — this module ships the filter
*bean*; each servlet service must still define its own `SecurityFilterChain`, call
`.addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)`, and set
its own `.authorizeHttpRequests(...)` allow/deny rules, CSRF/session policy, and 401 entry point.

| Service | `SecurityConfig` exists? | Notes |
|---|---|---|
| auth-service | Yes | Also defines `passwordEncoder()`/`authenticationManager()` beans for its own login flow — not part of common-security's contract. |
| jd-resume-service | Yes | Permits `/health`, `/error`; same shape otherwise. |
| compatibility-service | Yes | Permits `/health`, `/error`; same shape otherwise. |
| question-service | Yes | Permits only `/error` — this service has no `/health` endpoint. Added later than the others (this module previously had no `SecurityConfig` at all despite declaring `spring-boot-starter-security` — see question-service/ARCHITECTURE.md for that history). |
| voice-orchestrator | N/A (reactive) | Correctly needs none — see point 4. |

**4. Reactive consumers need no `SecurityConfig` at all, by construction** —
`ReactiveJwtAuthenticationFilter implements WebFilter`, and any `WebFilter` bean is auto-detected
by Spring's reactive web autoconfiguration with zero explicit wiring. voice-orchestrator has no
`SecurityWebFilterChain`, no `@EnableWebFluxSecurity`, and no `spring-boot-starter-security`
dependency at all — and needs none for the filter itself to run. **However**, as of today, nothing
downstream in voice-orchestrator actually reads the `Authentication`/principal this filter
populates (`VoiceWebSocketHandler` authenticates its one WebSocket endpoint independently, via
direct `JwtValidator.isValid()`/`extractEmail()` calls against the `token` query parameter —
see voice-orchestrator/ARCHITECTURE.md). Combined with the filter's own unconditional pass-through
on a missing/invalid token, this means the filter is currently inert plumbing in that service —
correctly wired, but with no present-day consumer and no enforcement point of its own. It exists
so that *any future* plain reactive REST endpoint on that service gets authentication "for free,"
matching this module's overall design goal — it just isn't exercised yet.

---

## Testing — a known, explicit gap

**There is no test directory under this module at all.** `JwtValidator`, `RsaPublicKeyLoader`,
`JwtAuthenticationFilter`, and `ReactiveJwtAuthenticationFilter` have zero automated test
coverage of their own — everything known about their correctness comes from downstream
consumers exercising them indirectly (auth-service's manually-verified table in its own
ARCHITECTURE.md; jd-resume-service's `ResumeTextExtractionTest`, which deliberately bypasses this
module's filter rather than testing it). Given this module is shared by every service in the
system and is the sole gate on request authentication for four of the five, this is worth
prioritizing — a unit test suite here (valid/expired/malformed/wrong-signature tokens against
`JwtValidator`; both filters' pass-through and context-population behavior in isolation) would
catch a regression here before it silently breaks authentication across every consumer at once.

---

## Git history

Only two commits touch this module's path:

- `95449a9` — the very first commit of the whole repo. `common-security` was extracted
  immediately at project inception (scaffolded alongside auth-service), rather than starting
  inside auth-service and being pulled out later. Original contents: `JwtAuthenticationFilter`,
  `JwtValidator`, `RsaPublicKeyLoader`, and the bundled public key — no conditional guards yet
  (there was only one web stack in the system at that point).
- `79eff1a` — "Added Voice Orchestrator and updated common security to accomodate
  ReactiveJwtAuthenticationFilter." Added both `@Conditional*` guards, added
  `ReactiveJwtAuthenticationFilter` as a new file, and added the two `provided`-scope pom
  dependencies (webflux + autoconfigure), all in the same commit that introduced
  voice-orchestrator as a sibling module. `JwtValidator.java` and `RsaPublicKeyLoader.java` were
  untouched by this commit — the verification core has never needed to change since the module's
  original extraction.

---

## Keeping this document current

When you change something structural — a new filter, a new conditional guard, a new consumer's
wiring pattern, a fix to the reactive-security gap in voice-orchestrator, or a real test suite —
update the relevant section above in the same commit/PR. Treat drift between this file and the
code as a bug.
