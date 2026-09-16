# compatibility-service — Architecture & Design Notes

This document explains what exists in `compatibility-service`, why each piece is built the way
it is, and the reasoning behind the non-obvious decisions. Update this file whenever the
architecture changes — new endpoints, new reliability fixes, changed conventions — so it stays a
reliable map of the service instead of going stale.

## What this service is

Given a job description and a resume (both already stored in `jd-resume-service`), this service
scores how well the resume matches the JD and produces targeted, evidence-grounded suggestions
for closing any gaps. It owns no file storage and no user data beyond its own analysis records —
it fetches JD/resume text over HTTP from jd-resume-service, indexes the resume into a per-resume
RAG index, extracts structured requirements from the JD via an LLM, judges each requirement
against retrieved evidence, and persists the resulting score + suggestions. `question-service`
is a downstream consumer of this service's `GET /compatibility/{id}` (added specifically for it —
see `bb32797`).

Runs on port `8083`, its own Postgres instance on port `5433` (database `callback_compatibility`
— distinct from every other service's DB, per this system's one-database-per-service pattern).

## Request flow

```
POST /compatibility/analyze  (Authorization: Bearer <caller-jwt>)
      │
      ▼
JwtAuthenticationFilter (common-security) — populates SecurityContext principal = email
      │
      ▼
SecurityFilterChain.anyRequest().authenticated() check
      │
      ▼
CompatibilityController.analyze(AnalyzeRequest{jdId, resumeId}, HttpServletRequest)
      │   extracts raw Authorization header + currentEmail() from SecurityContext
      ▼
CompatibilityAnalysisService.analyze(jdId, resumeId, ownerEmail, authorizationHeader)
      │
      ├─▶ JdResumeServiceClient.fetchJobDescriptionText(jdId, authHeader)
      │        RestClient GET http://localhost:8082/jd/{jdId}/text   (Authorization forwarded verbatim)
      │        ⇒ jdText
      │
      ├─▶ JdResumeServiceClient.fetchResumeText(resumeId, authHeader)
      │        RestClient GET http://localhost:8082/resumes/{resumeId}/text
      │        ⇒ resumeText
      │
      ├─▶ RagRetrievalService.indexResumeIfAbsent(resumeId, resumeText)
      │        isIndexed()? similaritySearch(topK=1, similarityThresholdAll(),
      │                        filter "resumeId == '<id>'") — probe query "resume content"
      │        if not indexed:
      │           TokenTextSplitter(chunkSize=40, minChunkSizeChars=20, minChunkLengthToEmbed=5,
      │                              maxNumChunks=500, keepSeparator=true).split(resumeText, {resumeId})
      │           vectorStore.add(chunks)  → embedded via Ollama (nomic-embed-text) → pgvector "vector_store"
      │
      ├─▶ RequirementExtractor.extract(jdText)
      │        ChatClient (Groq via OpenAI-compatible client)
      │          .prompt().system(...).user("JOB DESCRIPTION:\n" + jdText)
      │          .call().entity(RequirementList.class)   ← structured-output extraction, not tool calling
      │        ⇒ List<String> requirements
      │
      ├─ for each requirement:
      │     RagRetrievalService.retrieveEvidence(resumeId, requirement, topK=3)
      │        similaritySearch(query=requirement, topK=3, filter "resumeId == '<id>'")
      │        — NOT filtered by similarity score
      │     ⇒ RequirementEvidence(requirement, evidenceChunks)
      │
      ├─▶ CompatibilityScorer.score(jdText, List<RequirementEvidence>)
      │      per requirement: judgeRequirement() → 3x self-consistency vote (see below)
      │      collectScores(): one more model call → ScoreBreakdown{skillsOverlap, experienceMatch,
      │                                                              keywordCoverage, semanticSimilarity}
      │
      ├─▶ new CompatibilityAnalysis(ownerEmail, jdId, resumeId, 4 scores) + one AnalysisSuggestion
      │       per requirement judged unsupported
      ├─▶ CompatibilityAnalysisRepository.save(analysis)   — JPA, cascades AnalysisSuggestion rows
      │
      ▼
201 Created + AnalyzeResponse{id, jdId, resumeId, 4 scores, suggestions[], createdAt}
```

`GET /compatibility/{id}` is the short path: `CompatibilityAnalysisService.getById()` →
`findOwned()` (lookup + ownership check) → `toResponse()`. Both endpoints require a valid bearer
token; only `/health` and `/error` are public.

### Ownership pattern

```java
if (!analysis.getOwnerEmail().equals(callerEmail)) {
    // deliberately the SAME exception as "doesn't exist" — mirrors
    // JobDescriptionService.findOwned in jd-resume-service, to avoid leaking existence
    // of another user's analysis.
    throw new CompatibilityAnalysisNotFoundException(id);
}
```
Same not-found-for-both-cases convention used across this system's ownership checks (see
jd-resume-service/ARCHITECTURE.md). Added in `bb32797` specifically so question-service — which
calls `GET /compatibility/{id}` on a caller's behalf — gets the same non-leaking 404 behavior.

### Two identities forwarded per request, on purpose

`CompatibilityController.analyze()` pulls **both** the SecurityContext principal (`currentEmail()`,
used to stamp `ownerEmail` on the locally-created `CompatibilityAnalysis` row) **and** the raw
`Authorization` header off `HttpServletRequest` (forwarded verbatim, including the literal
`Bearer ` prefix, to jd-resume-service). The email is for local ownership bookkeeping; the header
is what lets jd-resume-service run its own ownership check against the same caller. This service
never re-derives or re-checks ownership of the upstream JD/resume itself — it relays identity and
translates upstream failures into local equivalents (jd-resume-service's 404-for-not-owned
becomes `UpstreamNotFoundException` here too).

---

## `client/JdResumeServiceClient.java` + `JdResumeServiceClientConfig.java`

```java
@Bean
public RestClient jdResumeServiceRestClient(RestClient.Builder builder,
                                             @Value("${jd-resume-service.base-url}") String baseUrl) {
    return builder.baseUrl(baseUrl).build();
}
```

- Plain synchronous `RestClient` (not `WebClient`) — this service is a servlet/MVC app; nothing
  in it is reactive, so a blocking client is the natural fit and matches how Spring AI's own
  internal Ollama/Groq calls behave (also synchronous). No explicit connect/read timeout is
  configured — relies on the underlying `ClientHttpRequestFactory`'s defaults.
- Base URL comes from `jd-resume-service.base-url` (`http://localhost:8082`) — a single
  configured URL, no service discovery. Fine for a fixed local/dev topology; would need
  revisiting for a real multi-instance deployment.
- Error translation is the interesting part — every upstream HTTP failure is mapped to a specific
  local exception rather than propagating raw HTTP client exceptions:

| Upstream response | Local exception | Status |
|---|---|---|
| 404 | `UpstreamNotFoundException` | 404 |
| 401 | `UpstreamUnauthorizedException` | 401 |
| any other 4xx/5xx | `UpstreamServiceException` (message includes the actual upstream status) | 502 |
| network/connection failure | `UpstreamServiceException` | 502 |

  This means a caller of `/compatibility/analyze` sees jd-resume-service's own 404/401 semantics
  pass straight through, while anything else that goes wrong reaching jd-resume-service (it's
  down, times out, 500s) surfaces uniformly as a 502 rather than leaking an internal stack trace
  or a confusing unrelated status code.
- There is no client pointed at question-service from this module — the calling direction is the
  other way (question-service holds a `CompatibilityClient` pointed at
  `compatibility-service.base-url: http://localhost:8083`, forwarding the caller's bearer token
  the same way this service forwards it to jd-resume-service). See
  question-service/ARCHITECTURE.md.

---

## Reliability mechanisms (`scoring/CompatibilityScorer.java`)

This class embodies most of the reliability work referenced in project history — bounded
retries, a hard per-call timeout, self-consistency voting, and rate-limit backoff — all aimed at
one problem: LLM tool-calling is not reliable enough to trust on the first attempt, especially
against a rate-limited free-tier API.

- **`MAX_ATTEMPTS = 4`** — every individual model exchange (each of the 3 per-requirement
  judgment votes, and the one scoring call) gets its own independent 4-attempt retry budget. This
  is *not* a total cap across the whole analysis — a JD with 10 requirements can make up to
  `10 × 3 × 4 = 120` judgment attempts in the worst case, plus 4 for scoring.
- **`MODEL_CALL_TIMEOUT = Duration.ofSeconds(30)`**, enforced by `callWithTimeout()`: submits the
  model call to a dedicated `ExecutorService` (cached thread pool, daemon threads named
  `compatibility-scorer-model-call`) and calls `future.get(30, SECONDS)`, cancelling the future
  and throwing on timeout. The class javadoc explains this guards against a degenerate tool-call
  repetition loop that a smaller local model could fall into, since Spring AI's own tool-execution
  loop has no built-in round cap. The model has since moved to Groq's `openai/gpt-oss-120b`, but
  the timeout stays as defense-in-depth — a cheap guarantee against a hang regardless of which
  model is behind the client.
- **`backoffBeforeRetry(failure)`** — sleeps **12000ms** if the failure text contains `"429"` or
  `"rate_limit"` (case-insensitive), else **300ms**. This is specifically tuned for Groq's
  free-tier rate limit (30 requests/minute): a short backoff for a transient/unrelated failure,
  a much longer one so a retry loop doesn't immediately re-hit the same rate-limit window.
- **`JUDGMENT_VOTES = 3`** — self-consistency voting per requirement. `judgeRequirement()` calls
  `judgeRequirementOnce()` three independent times and takes a majority vote
  (`supportedVotes * 2 >= votes.size()`). If all 3 votes exhaust their retries and fail outright,
  the requirement defaults to `supported = true` with a warning logged — a deliberate fail-open
  choice so a flaky model run doesn't spuriously flag every requirement as a gap. When the
  majority says "not supported," the suggestion text comes from the first non-blank unsupported
  vote, falling back to a generated `"Add resume content that specifically addresses: " +
  requirement` if none of the votes produced usable text.
- **Tool-contract enforcement**: a `Judgment(supported=false, suggestion="")` result is treated as
  a *failed attempt* (throws, triggering a retry) rather than accepted at face value — the
  contract the tool is documented to uphold (a non-blank suggestion whenever a requirement is
  unsupported) is enforced in code, not just trusted from the model's output.
- **Single-tool-per-exchange design**: `JudgmentRecorder` exposes only `recordJudgment`;
  `ScoreOnlyRecorder` exposes only `submitScores`. Each is instantiated fresh
  (`new JudgmentRecorder()` / `new ScoreOnlyRecorder()`) per call and passed to its own isolated
  `chatClient.prompt()...call()` — never both tools in the same exchange, never multiple judgment
  calls accumulating in one exchange. The javadoc is explicit about why: *"reliability of a final
  tool call was observed to degrade sharply the more prior tool calls accumulated in the same
  exchange."*
- **No embedding-similarity threshold used as a pass/fail gate.** `retrieveEvidence()` applies no
  `similarityThreshold` — only the indexing-check probe query does, and only to answer "has
  anything been indexed at all." `CompatibilityAnalysisService.analyze()` has an explicit comment
  on this: score alone doesn't reliably separate "covered" from "not covered," so the scorer reads
  the raw retrieved text itself and judges from that, rather than trusting a numeric similarity
  cutoff. `CompatibilityAnalysisServiceTest` specifically regression-tests the failure mode this
  guards against: a short resume's top-K retrieval always returns *something*, even for
  requirements it doesn't actually cover, so treating "empty evidence" as the gap signal fails —
  the LLM judge, not retrieval, is what has to catch the gap.
- **Per-requirement, one-at-a-time judgment**, not a single batched "judge this whole list" call —
  the javadoc notes batched judgment was observed to sometimes flag a well-evidenced requirement
  as a gap while missing the actual gap elsewhere in the same batch.
- **Suggestion "deduplication"** is mentioned in project history as one of the reliability fixes,
  but there is no explicit dedup code (no `distinct()`/set-based check) anywhere in
  `CompatibilityScorer` or `CompatibilityAnalysisService`. It falls out of the design rather than
  being enforced separately: `judgeRequirement()` runs exactly once per already-deduplicated
  extracted requirement, so at most one suggestion can ever exist per requirement. Don't look for
  a dedicated dedup class — there isn't one, and none is needed given this shape.

### Empirically-driven model swap

`CompatibilityScorerTest`'s class doc records a real precision ceiling found while this scorer
ran against a local 3B model (llama3.2:3b): it produced false-positive gap flags on well-evidenced
requirements (e.g. "Kubernetes/AWS operations" flagged as unsupported despite clear evidence in
the resume). After moving the chat model to Groq's `openai/gpt-oss-120b`, the same test case was
re-run empirically (2026-09-10) and showed zero false positives. This is the concrete motivation
behind [[local_model_reliability_findings]] and behind chat being routed to Groq rather than
Ollama in this service (see below).

---

## RAG / vector store (`rag/RagRetrievalService.java`)

- Each resume is indexed **once** and reused across every future analysis against it — indexing
  is idempotent via `isIndexed()`, a `topK=1`, `similarityThresholdAll()` probe search scoped to
  that resume's chunks.
- `RESUME_ID_KEY = "resumeId"` metadata tag on every chunk; every search applies
  `filterExpression("resumeId == '<uuid>'")`. The class javadoc is explicit about why this
  matters: an unscoped search over the shared `vector_store` table would leak evidence chunks
  across candidates — one resume's content could surface as "evidence" for a different resume's
  analysis.
- `TokenTextSplitter` config: `chunkSize=40`, `minChunkSizeChars=20`, `minChunkLengthToEmbed=5`,
  `maxNumChunks=500`, `keepSeparator=true`.
- Backing store: `spring-ai-starter-vector-store-pgvector`, table `vector_store`,
  `dimensions=768` (matches `nomic-embed-text`'s output size), `index-type=hnsw`,
  `distance-type=cosine-distance`.

---

## Spring AI: hybrid Groq (chat) + Ollama (embeddings)

Both `spring-ai-starter-model-ollama` and `spring-ai-starter-model-openai` are on the classpath
at once — the pom's inline comments state the split directly: *"Ollama: used for local embeddings
only (nomic-embed-text) — chat is routed to Groq"* and *"OpenAI-compatible client, pointed at
Groq's endpoint for chat/tool-calling."* Groq has no dedicated Spring AI starter, so the
OpenAI-compatible client is repurposed by overriding `base-url`.

Two `application.yml` selector keys are what let both starters coexist without a duplicate-bean
conflict:

```yaml
spring.ai.model.chat: openai        # ChatModel bean backed by the OpenAI-compatible starter
spring.ai.model.embedding: ollama   # EmbeddingModel bean backed by the Ollama starter
```

```yaml
spring.ai.ollama.base-url: http://localhost:11434
spring.ai.ollama.embedding.model: nomic-embed-text
spring.ai.ollama.init.pull-model-strategy: never   # don't auto-pull the model on startup

spring.ai.openai.base-url: https://api.groq.com/openai   # no trailing /v1 — client appends /v1/chat/completions itself
spring.ai.openai.api-key: ${GROQ_API_KEY}                # env var only, no default — startup fails without it
spring.ai.openai.chat.options.model: openai/gpt-oss-120b
spring.ai.openai.chat.options.temperature: 0.2

spring.ai.vectorstore.pgvector.initialize-schema: true
spring.ai.vectorstore.pgvector.dimensions: 768
spring.ai.vectorstore.pgvector.index-type: hnsw
spring.ai.vectorstore.pgvector.distance-type: cosine-distance
```

`GROQ_API_KEY` deliberately has no default (`${GROQ_API_KEY}`, not `${GROQ_API_KEY:default}`) —
unlike the DB credentials below, which do carry dev defaults. Secrets never get a literal
fallback in this system; local-only infrastructure credentials do.

---

## Security config

Same shape as auth-service's and jd-resume-service's `SecurityConfig`:

```java
.csrf(AbstractHttpConfigurer::disable)
.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
.exceptionHandling(handling -> handling.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
.authorizeHttpRequests(auth -> auth
        .requestMatchers("/health", "/error").permitAll()
        .anyRequest().authenticated())
.addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
```

`CompatibilityServiceApplication` uses `@SpringBootApplication(scanBasePackages = "com.callback")`
for the same reason as every other servlet consumer of common-security — `JwtAuthenticationFilter`
lives in `com.callback.security.jwt`, a sibling package to `com.callback.compatibility`, which the
default scan would miss.

No `@ControllerAdvice`/`GlobalExceptionHandler` exists here — every exception this service throws
carries its own `@ResponseStatus`, so Spring's default mapping suffices without a central advice
class.

| Exception | Status | Thrown when |
|---|---|---|
| `CompatibilityAnalysisNotFoundException` | 404 | `GET /compatibility/{id}` — missing OR owned by someone else |
| `UpstreamNotFoundException` | 404 | jd-resume-service returned 404 for the JD or resume fetch |
| `UpstreamUnauthorizedException` | 401 | jd-resume-service rejected the forwarded token |
| `UpstreamServiceException` | 502 | any other upstream error, or a network failure reaching jd-resume-service |

---

## `pom.xml` — dependencies

- `spring-ai-bom` (`spring-ai.version = 1.0.9`) imported in `dependencyManagement` — pins every
  Spring AI artifact version repo-wide-consistently for this module.
- `common-security` — shared JWT verification, same as every servlet service in this system.
- `spring-ai-starter-model-ollama` / `spring-ai-starter-model-openai` / `spring-ai-vector-store` /
  `spring-ai-starter-vector-store-pgvector` — see the hybrid-model section above.
- `spring-boot-starter-data-jpa` + `postgresql` (runtime) — persistence for
  `CompatibilityAnalysis`/`AnalysisSuggestion`, a separate JPA concern from the pgvector store
  even though both live in the same Postgres instance.
- No `WebClient` dependency anywhere — `RestClient` (blocking) is the only HTTP client type used,
  consistent with this being a fully synchronous/servlet service.

---

## `application.yml` — full key list

```yaml
spring:
  application:
    name: compatibility-service
  datasource:
    url: jdbc:postgresql://localhost:5433/callback_compatibility
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
    vectorstore:
      pgvector:
        initialize-schema: true
        dimensions: 768
        index-type: hnsw
        distance-type: cosine-distance
server:
  port: 8083
jd-resume-service:
  base-url: http://localhost:8082
```

Note the datasource port is **5433**, distinct from every other service's `5432` — this service's
Postgres instance/schema (`callback_compatibility`) is fully separate infrastructure, not just a
separate database name on a shared instance.

---

## Tests — what's actually verified

No separate "verified manually" log exists for this service (unlike auth-service); the automated
test suite is the closest equivalent, and several tests hit real dependencies rather than mocks:

| Test class | What it proves | Real dependencies |
|---|---|---|
| `SpringAiSmokeTest` | Chat and embedding beans wire up and respond at all; embedding dimension matches config | Real Groq + real local Ollama |
| `JdResumeServiceClientTest` | Bearer token forwarded verbatim on both text-fetch paths; upstream 401/404 map to the right local exceptions | `MockRestServiceServer` (no network) |
| `RagRetrievalTest` | Retrieval discriminates topically (a backend query doesn't surface an unrelated "barista" chunk and vice versa); `indexResumeIfAbsent` is a true no-op on re-index | Real pgvector + real Ollama embeddings |
| `CompatibilityScorerTest` | Well-covered JD produces in-range scores with non-blank suggestions; a genuine gap (e.g. Terraform/Ansible with zero evidence) reliably produces a suggestion naming that requirement; documents the llama3.2:3b → Groq precision improvement (see above) | Real Groq chat calls |
| `CompatibilityAnalysisServiceTest` | Full pipeline (jd-resume-service mocked, RAG + scorer real) surfaces a grounded suggestion for a genuine gap even against a short resume, where naive empty-evidence-as-signal would fail | jd-resume-service mocked via `@MockitoBean`; pgvector + Groq real |

---

## Keeping this document current

When you change something structural — a new endpoint, a new reliability mechanism, a model
swap, a changed security rule — update the relevant section above in the same commit/PR. Treat
drift between this file and the code as a bug.
