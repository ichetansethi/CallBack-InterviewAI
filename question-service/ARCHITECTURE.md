# question-service — Architecture & Design Notes

This document explains what exists in `question-service`, why each piece is built the way it is,
and the reasoning behind the non-obvious decisions. Update this file whenever the architecture
changes — new endpoints, new reliability fixes, changed conventions — so it stays a reliable map
of the service instead of going stale.

## What this service is

Given a job description (and, optionally, an existing compatibility analysis), this service
generates a fixed set of 7 interview questions — 3 technical, 2 behavioral, 2 role-specific —
grounded in the JD text, weighted toward probing any gaps the compatibility analysis surfaced.
It owns no file storage of its own: it fetches JD text from jd-resume-service and (optionally) a
compatibility analysis from compatibility-service, both over HTTP with the caller's own bearer
token forwarded, and persists only the generated question sets. It calls both of those services;
neither of them calls back into this one — `question-service` sits at the top of the call graph
among the servlet-based services (see the commit message "added Question Service and connected
it with compatibility service, Groq and updated dependencies").

Runs on port `8084`, its own Postgres database (`callback_question`, same instance/port `5432` as
jd-resume-service's default but a distinct schema — see per-service DB pattern in the other
services' docs).

The entire module landed in a single commit (`2bf8320`) — created whole-cloth rather than
iterated across multiple commits, which is why there's no earlier history to trace for individual
classes.

## Request flow

```
POST /questions/generate  (Authorization: Bearer <caller-jwt>)
      │
      ▼
QuestionSetController.generate(@Valid QuestionGenerateRequest, @RequestHeader Authorization)
      │   ownerEmail = SecurityContextHolder...getAuthentication().getName()
      │   bearerToken = the raw header value, re-read directly (not derived from the principal)
      ▼
QuestionSetService.generate(request, ownerEmail, bearerToken)
      │
      ├─▶ JdResumeClient.getJdText(jdId, bearerToken)
      │        RestClient GET http://localhost:8082/jd/{jdId}/text  (Authorization forwarded verbatim)
      │        ⇒ jdText
      │        404/401/other → UpstreamNotFoundException / UpstreamUnauthorizedException / UpstreamServiceException(502)
      │
      ├─▶ (only if request.compatibilityAnalysisId() != null)
      │     CompatibilityClient.getAnalysis(compatibilityAnalysisId, bearerToken)
      │          RestClient GET http://localhost:8083/compatibility/{id}  (Authorization forwarded)
      │          ⇒ CompatibilityAnalysisResponse
      │          .uncoveredRequirements() → List<String> of jdRequirement values from its suggestions
      │     else: uncoveredRequirements = List.of()
      │
      ├─▶ QuestionGenerationService.generate(jdText, uncoveredRequirements)
      │        (see "Reliability mechanisms" below — bounded retries, hard timeout, rate-limit backoff,
      │         single-tool-per-exchange tool calling against Groq)
      │        ⇒ QuestionBatch{questions: List<GeneratedQuestion>}  (exactly 7: 3 technical, 2 behavioral, 2 role-specific)
      │
      ├─▶ new QuestionSet(ownerEmail, jdId, compatibilityAnalysisId) → QuestionSetRepository.save()
      ├─▶ generated.questions() mapped to InterviewQuestion rows → InterviewQuestionRepository.saveAll()
      │
      ▼
201 CREATED + QuestionSetResponse{id, jdId, createdAt, questions[]}
```

`GET /questions/{id}` and `GET /questions` are the short paths: `QuestionSetService.findOwned()`
(lookup + ownership check, same not-found-for-both-cases pattern used across this system) and
`findByOwnerEmail()` respectively.

### Two identities pulled per request, same pattern as compatibility-service

`QuestionSetController.generate()` reads the JWT-derived principal (`currentEmail()`, used to
stamp `ownerEmail` on the persisted `QuestionSet`) and separately re-reads the raw
`Authorization` header via `@RequestHeader` (forwarded byte-for-byte to both upstream services).
This service never re-derives or re-checks ownership of the JD or the compatibility analysis
itself — it relays the caller's identity and lets jd-resume-service / compatibility-service run
their own ownership checks, translating their failures into the equivalent local exceptions
(their "404 for not-owned" becomes this service's "404 for not-owned" too).

### Ownership pattern

```java
// deliberately the SAME exception as "doesn't exist" — mirrors
// CompatibilityAnalysisService.findOwned, to avoid leaking existence of another user's question set.
```
`QuestionSetService.findOwned()` throws `QuestionSetNotFoundException` (404) whether the id
doesn't exist at all or exists but belongs to someone else — consistent with every other
ownership check in this system (jd-resume-service, compatibility-service).

---

## Client classes — HTTP calls to jd-resume-service and compatibility-service

Both follow an identical, deliberately un-consolidated shape: a `@Configuration` class producing
one qualified `RestClient` bean, and a `@Component` client class that calls it and maps upstream
HTTP failures onto this system's shared exception vocabulary.

```java
@Bean
public RestClient jdResumeServiceRestClient(RestClient.Builder builder,
                                             @Value("${jd-resume-service.base-url}") String baseUrl) {
    return builder.baseUrl(baseUrl).build();
}
```
(and the equivalent `compatibilityServiceRestClient` bean, from `${compatibility-service.base-url}`)

| Upstream response | Local exception | Status |
|---|---|---|
| 404 | `UpstreamNotFoundException` | 404 |
| 401 | `UpstreamUnauthorizedException` | 401 |
| other HTTP error, or a connection/transport failure | `UpstreamServiceException` | 502 |

`JdResumeClient.getJdText(jdId, bearerToken)` → `GET /jd/{jdId}/text`.
`CompatibilityClient.getAnalysis(analysisId, bearerToken)` → `GET /compatibility/{id}`.

Both are plain synchronous `RestClient`s (not `WebClient`) — this is a servlet/MVC service, so a
blocking client is the natural fit, matching the pattern used by compatibility-service for its
own jd-resume-service client.

**One-way dependency, confirmed by grep across every other module**: question-service calls
compatibility-service and jd-resume-service; neither of those, nor voice-orchestrator, ever calls
question-service. (voice-orchestrator's `TurnDecisionService` has a comment referencing
question-service's `QuestionGenerationService` as a *design-pattern precedent* — bounded retries
plus a hard timeout — not an actual HTTP call.)

**DTO duplication, not a shared library**: `CompatibilityAnalysisResponse`/`SuggestionResponse`
here are hand-maintained, field-for-field copies of compatibility-service's own
`AnalyzeResponse`/`SuggestionResponse`. There is no shared DTO module between the two services —
they're coupled only by the JSON wire shape, which means a field rename on either side has to be
made in both places by hand. `CompatibilityAnalysisResponse.uncoveredRequirements()` is a derived
helper method local to this copy:
```java
// The JD requirements this analysis found unsupported by the resume — not the resume-tailoring
// advice text on each suggestion, which is a separate concern.
public List<String> uncoveredRequirements() {
    return suggestions().stream().map(SuggestionResponse::jdRequirement).toList();
}
```

---

## AI generation internals (`generation` package)

### `QuestionGenerationService`

- `TECHNICAL_COUNT=3`, `BEHAVIORAL_COUNT=2`, `ROLE_SPECIFIC_COUNT=2`, `TOTAL_QUESTIONS=7` — a
  fixed interview shape, not configurable per request.
- **`MAX_ATTEMPTS = 4`** — bounded retry loop around the entire prompt+tool-call cycle, retrying
  on *any* `RuntimeException`: a missing tool call, an empty question batch, or the 60s-timeout
  exception below.
- **`MODEL_CALL_TIMEOUT = Duration.ofSeconds(60)`** — enforced via a dedicated cached daemon-thread
  executor (`"question-generation-model-call"`) and `Future.get(timeout)`; on timeout,
  `future.cancel(true)` and throw `IllegalStateException("Model call exceeded 60s (likely a
  runaway tool-call loop)")`. Same defense-in-depth rationale as compatibility-service's
  `CompatibilityScorer.MODEL_CALL_TIMEOUT` and voice-orchestrator's `TurnDecisionService` — a
  Spring AI tool-execution loop has no built-in round cap of its own.
- **`QuestionBatchRecorder`** — exposes exactly one `@Tool`-annotated method,
  `submitQuestions(List<GeneratedQuestion>)`. A fresh instance is created per attempt (not
  reused across retries), and the system prompt instructs the model to *"Respond only via a tool
  call, never in plain text: call submitQuestions exactly once with the complete set."* Same
  single-tool-per-exchange discipline used by compatibility-service's `JudgmentRecorder`/
  `ScoreOnlyRecorder` and voice-orchestrator's `TurnDecisionRecorder`.
- **Two independent, deliberately separate retry mechanisms**, not one:
  1. `RATE_LIMIT_RETRY_TEMPLATE` (`RetryTemplate.builder().maxAttempts(4)
     .retryOn(GroqRateLimitException.class).customBackoff(new RateLimitBackOffPolicy()).build()`)
     wraps only the raw model call inside `callModel()`, and retries **only**
     `GroqRateLimitException` — a 429 from Groq.
  2. The outer `for (attempt = 1..MAX_ATTEMPTS)` loop in `generate()` retries the *entire*
     prompt+tool-call cycle for any other failure (bad/missing tool call, timeout), backing off a
     flat, non-exponential 300ms between attempts. The Javadoc explains why this backoff is
     deliberately dumb: *"asking again can plausibly get a different, correct answer, so backoff
     time isn't the fix"* — unlike a rate limit, a malformed tool call isn't a function of how
     long you wait before retrying.
- `buildSystemPrompt()`/`buildUserPrompt()` are `static` and package-private rather than
  `private`, specifically so `QuestionGenerationServiceTest`'s stress test exercises the exact
  production prompt-building code rather than a hand-duplicated copy that could silently drift
  from it.
- System prompt (exact, parameterized by the fixed counts above): instructs the model to generate
  the exact split of technical/behavioral/role-specific questions, to ground every question and
  rationale in the JD text and never invent requirements not present in it, and — when uncovered
  requirements are supplied — to weight at least 1–2 questions toward directly probing those
  specific gaps while the rest cover the JD more broadly.
- User prompt renders the JD text plus either the uncovered-requirements list (each rendered as
  `"- " + requirement`) or the literal string `"(none — no compatibility analysis was requested,
  or the resume covers everything)"` when there are none.

### `ChatModelErrorHandlingConfig`

A `@Configuration` producing a `ResponseErrorHandler` bean that **replaces** Spring AI's default
(possible because the default is `@ConditionalOnMissingBean`). The only behavioral difference: on
an HTTP 429, it reads the `Retry-After` header — confirmed via a live probe against real Groq
behavior to only ever be a plain integer-seconds value, never an HTTP-date — and throws
`GroqRateLimitException(message, retryAfterDuration)` instead of a generic classification. Every
other status code gets identical treatment to Spring AI's own default handler: other 4xx →
`NonTransientAiException`, 5xx → `TransientAiException`.

### `RateLimitBackOffPolicy`

Implements Spring Retry's `BackOffPolicy`. `FALLBACK_DELAYS = {2s, 4s, 8s}`. On each `backOff()`
call it reads the last throwable from `RetrySynchronizationManager`; if it's a
`GroqRateLimitException` carrying a non-null `retryAfter()`, it sleeps exactly that duration —
otherwise it falls back to the fixed schedule above. The class comment is explicit that this is
*not* a general-purpose backoff policy: it assumes the last throwable is always
`GroqRateLimitException`, because the owning `RetryTemplate` only ever retries that exception
type.

This rate-limit handling predates the compatibility-service scorer's own backoff mechanism in
spirit — both follow the same "prove it against the real model behavior, don't assume" approach
referenced in [[local_model_reliability_findings]] — but the two services implement it
independently rather than sharing a library, consistent with this system's general preference for
small, duplicated, service-local reliability code over a shared abstraction.

---

## Security config

`config/SecurityConfig.java` — same shape as compatibility-service's and jd-resume-service's:

```java
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtAuthenticationFilter jwtAuthenticationFilter) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(handling -> handling.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/error").permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
```

This closes a gap this document previously flagged: until this config was added, the module
declared `spring-boot-starter-security` but had no `SecurityFilterChain` bean, so
`common-security`'s `JwtAuthenticationFilter` was discovered as a plain Spring bean and
auto-registered into the servlet container's filter chain via `FilterRegistrationBean` — a
different mechanism from, and with no guaranteed ordering relative to, Spring Security's own
`FilterChainProxy`. `QuestionSetController`'s `currentEmail()`/`SecurityContextHolder` calls were
already written as if a JWT-populated `Authentication` was guaranteed present; this config is
what actually guarantees it, the same way it does in every sibling servlet service.

**Only `/error` is permitted, not `/health`** — unlike compatibility-service and
jd-resume-service, this module has no `HealthController`/`/health` endpoint to open up. Add it to
the `permitAll()` list here if one is ever added.

`QuestionServiceApplication`'s `@SpringBootApplication(scanBasePackages = "com.callback")` is what
makes `JwtAuthenticationFilter` (living in `com.callback.security.jwt`, a sibling package to
`com.callback.question`) visible to this config's `@Bean` method parameter in the first place —
same requirement as every other consumer of `common-security`.

**Still untested at the HTTP layer**: no `@WebMvcTest`/`@SpringBootTest(webEnvironment =
RANDOM_PORT)` exists for `QuestionSetController`. `QuestionGenerationServiceTest` and
`QuestionSetServiceTest` both boot with `webEnvironment = NONE`, deliberately isolating the
generation/service layers from the web layer per [[feedback_isolated_pipeline_stages]]'s "prove
each stage before building the next" approach — the side effect is that the controller, `@Valid`
request validation, and this security wiring are exercised only by manual/production traffic, not
by the automated suite. Worth adding a thin `@WebMvcTest` (or a full-stack test hitting
`/questions/**` with a real/forged JWT) to lock in that this config actually rejects unauthenticated
requests with 401, not just that it compiles.

---

## Exceptions

No `@ControllerAdvice`/global exception handler exists — every exception carries its own
`@ResponseStatus`.

| Exception | Status | Thrown when |
|---|---|---|
| `QuestionSetNotFoundException` | 404 | missing, or exists but owned by someone else |
| `UpstreamNotFoundException` | 404 | jd-resume-service or compatibility-service returned 404 |
| `UpstreamUnauthorizedException` | 401 | either upstream rejected the forwarded token |
| `UpstreamServiceException` | 502 | any other upstream error, or a network/connection failure |

`GroqRateLimitException` (in the `generation` package, not `exception`) is a separate, unannotated
`RuntimeException` — purely an internal control-flow signal for `RATE_LIMIT_RETRY_TEMPLATE`. It's
never meant to escape to an HTTP response; if it somehow exhausted all retries and propagated, it
would surface as an unhandled 500, since it carries no `@ResponseStatus`.

---

## Persistence model

- **`QuestionSet`** (table `QuestionSet`): `id (UUID)`, `ownerEmail`, `jdId`,
  `compatibilityAnalysisId` (nullable — tailoring against a compatibility analysis is optional),
  `createdAt`. Mutable JavaBean style (getters/setters), unlike this system's DTOs (records).
- **`InterviewQuestion`** (table `InterviewQuestion`): `id (UUID)`, `@ManyToOne QuestionSet`,
  `category` (plain `String` — `"technical" | "behavioral" | "role-specific"`, not an enum),
  `questionText` (`TEXT`), `rationale` (`TEXT`), `orderIndex` (`int`).
- `QuestionSetRepository` — one derived query, `findByOwnerEmail`.
- `InterviewQuestionRepository` — one derived query,
  `findByQuestionSetIdOrderByOrderIndexAsc`.
- No Flyway/Liquibase — `ddl-auto: update`, same as every other service in this system.

**Known loose end**: `orderIndex` is declared on `InterviewQuestion` but never explicitly set
anywhere in `QuestionSetService` — every persisted row gets the JPA default of `0`. The
`ORDER BY orderIndex ASC` query on the repository therefore doesn't currently guarantee anything
beyond whatever incidental ordering falls out of `saveAll()` and the list order returned by
`.map()`. If question ordering ever needs to be guaranteed (e.g. displaying questions in the order
the model generated them), `orderIndex` needs to actually be populated from the list index during
the save step.

---

## `pom.xml` — dependencies

- `spring-ai-bom` (version `1.0.9`, same as compatibility-service) imported for
  `dependencyManagement`.
- `spring-ai-starter-model-ollama` / `spring-ai-starter-model-openai` / `spring-ai-vector-store` —
  present with the same pom comments as compatibility-service ("Ollama: used for local embeddings
  only... chat is routed to Groq"). **Notably, nothing in this service's actual source code uses
  `VectorStore`, `SearchRequest`, `Document`, or calls an embedding model at all** — no vector
  store bean, no RAG, no `@Autowired VectorStore` anywhere. This dependency (and the matching
  Ollama embedding keys in `application.yml`) appear to be inherited boilerplate carried over from
  compatibility-service's setup rather than something this service exercises. Question generation
  runs entirely off the JD text and the (optional) uncovered-requirements list — no retrieval
  step. If embeddings/vector search are never going to be used here, these dependencies and config
  keys are candidates for removal; if a future retrieval-augmented question style is planned,
  document that intent here when it lands.
- `spring-boot-starter-security` — declared, but see the security gap above; there's no
  `SecurityConfig` consuming it.
- No `spring-retry` dependency is declared explicitly — `RetryTemplate` and
  `RetrySynchronizationManager` (used by `QuestionGenerationService`/`RateLimitBackOffPolicy`) are
  pulled in transitively, almost certainly via `spring-ai-retry` (the module whose default
  `ResponseErrorHandler` bean `ChatModelErrorHandlingConfig` overrides).

---

## `application.yml` — every key

```yaml
spring:
  application:
    name: question-service
  datasource:
    url: jdbc:postgresql://localhost:5432/callback_question
    username: ${DB_USERNAME:callback_admin}
    password: ${DB_PASSWORD:callback_password123}
  jpa:
    hibernate:
      ddl-auto: update
    open-in-view: false
  ai:
    model:
      chat: openai
      embedding: ollama
    ollama:
      base-url: http://localhost:11434
      embedding:
        model: nomic-embed-text
      init:
        pull-model-strategy: never
    openai:
      base-url: https://api.groq.com/openai
      api-key: ${GROQ_API_KEY}
      chat:
        options:
          model: openai/gpt-oss-120b
          temperature: 0.2
server:
  port: 8084
jd-resume-service:
  base-url: http://localhost:8082
compatibility-service:
  base-url: http://localhost:8083
```

- `spring.ai.model.chat: openai` / `embedding: ollama` — the same dual-starter selector mechanism
  compatibility-service uses to let both Spring AI starters coexist without a duplicate-bean
  conflict, even though (per the note above) the embedding half is currently unused here.
- `GROQ_API_KEY` — env var only, no literal fallback, same convention as every other Groq-backed
  service in this system.
- `jd-resume-service.base-url` and `compatibility-service.base-url` are both configured — this is
  the only service in the system with two outbound base URLs. There is no `auth-service.base-url`
  anywhere — this service never calls auth-service directly; JWT verification happens locally via
  `common-security`.
- No `/health` endpoint or controller exists in this module, unlike compatibility-service's
  dedicated `HealthController`.

---

## Testing — what's verified, and what isn't

| Test class | What it proves | Real dependencies |
|---|---|---|
| `CompatibilityClientTest` | Token forwarding to compatibility-service; 401/404 map to the right local exceptions | `MockRestServiceServer` (no network) |
| `JdResumeClientTest` | Same shape for jd-resume-service | `MockRestServiceServer` |
| `ChatModelErrorHandlingConfigTest` | 429+Retry-After → duration captured; 429 with no/unparseable header → `null` (not a failure); other 4xx → `NonTransientAiException`; 5xx → `TransientAiException` | Mocked `ClientHttpResponse` |
| `RateLimitBackOffPolicyTest` | Real sleep timing: `Retry-After` honored; fallback schedule otherwise | Real `Thread.sleep`, no network |
| `QuestionGenerationServiceTest` | A 5-trial reliability stress test directly against the production prompt builders (tolerating at most one flaky miss out of 5); end-to-end generation produces a grounded, non-empty batch; gap-tailoring — at least one question/rationale mentions the specific uncovered requirement supplied | **Real Groq API, real model** |
| `QuestionSetServiceTest` | Full `generate()` flow against real Groq + real Postgres, with `JdResumeClient`/`CompatibilityClient` mocked (their token-forwarding is proven separately); JD-only path verifies `compatibilityClient` is never called; gap-tailoring path verifies the right analysis id is persisted and fetched | **Real Groq + real Postgres**, upstream HTTP mocked |

No `@WebMvcTest`/`@SpringBootTest(webEnvironment = RANDOM_PORT)` exists for
`QuestionSetController` — the HTTP endpoints, `@Valid` request validation, and the security
wiring question above are all currently untested at the HTTP layer.

---

## Keeping this document current

When you change something structural — a new endpoint, a fix to the security wiring gap, a
reliability change, or if the currently-unused vector-store dependency starts being exercised —
update the relevant section above in the same commit/PR. Treat drift between this file and the
code as a bug.
