# jd-resume-service — Architecture & Design Notes

This document explains what exists in `jd-resume-service`, why each piece is built the way
it is, and the reasoning behind the non-obvious decisions. Update this file whenever the
architecture changes — new endpoints, new extraction formats, changed conventions — so it
stays a reliable map of the service instead of going stale.

## What this service is

A pure storage-and-extraction microservice. It has no knowledge of scoring, compatibility,
or questions — it just accepts job descriptions (as raw text) and resumes (as uploaded PDF/DOCX
files), persists them, extracts plain text out of the files, and hands that text back to whoever
asks (with an ownership check). `compatibility-service` is the main downstream consumer: it
fetches JD/resume text over HTTP from here to feed its Groq-based scoring pipeline. This is why
the text-retrieval endpoints return `text/plain` — the simplest possible contract for a caller
that just wants a blob to embed/score, no JSON parsing needed.

Runs on port `8082` (see the service port map in the root README/`compatibility-service`'s doc:
auth-service 8081, jd-resume-service 8082, compatibility-service 8083, question-service 8084,
voice-orchestrator 8085).

## Request flow

```
POST /jd  (auth required)
    ──▶ JobDescriptionController.create(JobDescriptionRequest)
          └─▶ JobDescriptionService.create(rawText, role, company, ownerEmail)
                └─▶ JobDescriptionRepository.save() → Postgres "job_descriptions" (callback_jdresume DB)
        ◀── 201 JobDescriptionResponse{id, role, company, createdAt}

GET /jd/{id}/text  (auth required, must own the JD)
    ──▶ JobDescriptionController.text(id)
          └─▶ JobDescriptionService.findOwned(id, currentEmail())
                ├─ not found            → JobDescriptionNotFoundException (404)
                └─ found, wrong owner   → JobDescriptionNotFoundException (404, SAME exception)
        ◀── 200, body = rawText (text/plain)

POST /resumes  (multipart, auth required)
    ──▶ ResumeController.upload(MultipartFile)
          └─▶ ResumeService.upload(file, currentEmail())
                1. validate(file): non-empty, ≤5MB, contentType ∈ {pdf, docx}
                     └─ violation → InvalidFileException (400)
                2. FileStorageService.store(file, ownerEmail)
                     └─▶ LocalFileStorageService: UUID_sanitizedName → ./data/resumes/
                         (IOException → FileStorageException, 500)
                3. TextExtractionService.extractText(file)
                     └─▶ PdfBoxPoiTextExtractionService:
                           PDF  → PDFTextStripper
                           DOCX → XWPFWordExtractor
                           other/failure → null (best-effort, never blocks the upload)
                4. ResumeRepository.save() → Postgres "resumes" table
        ◀── 201 ResumeResponse{id, originalFilename, contentType, fileSizeBytes, uploadedAt}
            (storageKey deliberately excluded from the response — internal detail)

GET /resumes/{id}/text  (auth required, must own the resume)
    ──▶ same ownership pattern as JD text → 200 text/plain or 404
```

All four "real" endpoints (`POST/GET /jd`, `POST/GET /resumes`, plus the two `/text` routes) sit
behind `common-security`'s `JwtAuthenticationFilter`; only `/health` and `/error` are public.
There's no login/registration here — this service trusts whatever bearer token auth-service
issued and never talks to auth-service directly.

---

## Ownership model (not roles)

There is no role/authority concept anywhere in this service. The only authorization axis is
**ownership**, enforced in the service layer (`JobDescriptionService.findOwned`,
`ResumeService.findOwned`), not in the security filter chain:

```java
if (!entity.getOwnerEmail().equals(callerEmail)) {
    throw new JobDescriptionNotFoundException(...); // NOT a 403
}
```

Both services throw the **same** not-found exception whether the record doesn't exist at all or
exists but belongs to someone else. This is deliberate — a 403 vs 404 split would leak the
existence of another user's records to anyone probing IDs. `ownerEmail` is populated straight
from `SecurityContextHolder`'s principal (the email `JwtAuthenticationFilter` set as the JWT
subject), so ownership is tied to the same identity auth-service issued the token for — no
separate user table or foreign key back to auth-service's Postgres instance is needed.

Neither controller null-checks `getAuthentication()` before calling `currentEmail()` — an NPE
here is only impossible because `SecurityConfig` requires `.authenticated()` on every route these
controllers serve; if that config ever loosens, `currentEmail()` becomes the weak point.

---

## File upload, storage, and text extraction

### `service/FileStorageService.java` / `LocalFileStorageService.java`
- `FileStorageService` is an interface with exactly one implementation, injected by type (no
  qualifier needed — nothing else in the context satisfies it). `load()` and `delete()` exist on
  both but are **unused** — there's no download-raw-file or delete-resume endpoint yet; only
  metadata and extracted text are ever served back.
- Storage key = `UUID.randomUUID() + "_" + sanitize(originalFilename)`, where `sanitize()` maps
  `null` → `"unnamed"` and otherwise strips everything outside `[a-zA-Z0-9._-]`. Guards against
  path traversal and filesystem-illegal characters from a client-controlled filename, while the
  UUID prefix guarantees no collision even for two uploads of the same original filename.
- Root directory is `./data/resumes`, relative to the process's working directory, created via
  `Files.createDirectories()` in the constructor. This path is gitignored
  (`jd-resume-service/data/`, under the comment `### Local file storage (jd-resume-service
  dev-only uploads) ###`) — it's a dev-local disk store, not meant to be portable or committed;
  a real deployment would swap this implementation for something backed by object storage
  without touching any caller of the `FileStorageService` interface.

### `service/PdfBoxPoiTextExtractionService.java`
- PDF → PDFBox's `PDDocument.load()` + `PDFTextStripper().getText()`.
- DOCX → POI's `XWPFDocument` + `XWPFWordExtractor().getText()`. `poi-ooxml` is required
  specifically for `XWPFDocument` (Word 2007+/OOXML format) — plain `poi` alone only reads the
  legacy binary `.doc` format.
- Any other content type, or any exception during parsing (corrupt file, unsupported internal
  structure), is caught broadly and returns `null` rather than propagating. The interface javadoc
  states this explicitly: *"extraction failures must never block an upload."* A resume with
  `extractedText == null` is a valid, successfully-stored record — extraction is a best-effort
  enhancement, not a precondition for storage.
- Upload triggers two independent reads of the file bytes: one `Files.copy(file.getInputStream(),
  ...)` for storage, one fresh `file.getInputStream()` call for extraction. `MultipartFile`
  supports repeated `getInputStream()` calls (backed by memory or a temp file depending on the
  servlet multipart resolver), so this is safe but means extraction reads the original upload
  stream, not the just-written disk copy.

### Two independent file-size limits (not a bug)
- `application.yml`: `spring.servlet.multipart.max-file-size/max-request-size: 10MB` — a
  **server-level** ceiling; Tomcat/the servlet container rejects anything larger before it ever
  reaches the controller.
- `ResumeService`'s own validation: **5MB** — the actual product-level policy, checked in code
  after the request has already been accepted by the container.

These are two independent layers on purpose: the 10MB value is an infrastructure safety margin,
the 5MB value is the business rule. Don't "fix" the apparent mismatch by making them equal —
narrowing the servlet-level limit down to the business limit would remove the margin needed to
return a clean `InvalidFileException` (400, with a real message) instead of a raw container-level
`MultipartException` for files just over the product limit.

---

## JPA entities

### `model/JobDescription.java` (table `job_descriptions`)
`id: UUID` (generated) · `ownerEmail` · `rawText` (`TEXT`, not null) · `role` (not null) ·
`company` (nullable — *"not every JD names the company"*) · `createdAt: Instant` (set once at
construction). No setters — immutable after creation from the entity's own API.

### `model/Resume.java` (table `resumes`)
`id: UUID` (generated) · `ownerEmail` · `originalFilename` · `storageKey` (*"reference into
storage — not the file itself"*) · `contentType` · `fileSizeBytes` · `uploadedAt: Instant` ·
`extractedText` (`TEXT`, nullable — *"best-effort text extraction; null if unsupported type or
extraction failed"*). `extractedText` is the one mutable field (`setExtractedText()`), because
extraction happens as a second step after the entity is constructed, inside
`ResumeService.upload()`.

Both repositories are plain `JpaRepository<Entity, UUID>` with one derived query each —
`findByOwnerEmail(String)`. No pagination, no soft-delete, no custom `@Query`.

`ddl-auto: update` — Hibernate auto-migrates schema at boot; no Flyway/Liquibase in this service,
same as auth-service.

---

## Security config

Same shape as auth-service's `SecurityConfig`, narrower public surface:

```java
.csrf(AbstractHttpConfigurer::disable)
.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
.exceptionHandling(handling -> handling.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
.authorizeHttpRequests(auth -> auth
        .requestMatchers("/health", "/error").permitAll()
        .anyRequest().authenticated())
.addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
```

Only `/health` and `/error` are public — unlike auth-service, this service has no
registration/login endpoints of its own; every other route requires a bearer token issued
elsewhere. `JdResumeServiceApplication` uses `@SpringBootApplication(scanBasePackages =
"com.callback")` for the same reason documented in auth-service: `JwtAuthenticationFilter`/
`JwtValidator` live in `com.callback.security.jwt`, a sibling package to `com.callback.jd`, which
the default scan-below-the-app-package behavior would miss.

**This service is the reason `common-security`'s `JwtAuthenticationFilter` is conditioned on
`@ConditionalOnClass(Filter.class)` rather than `@ConditionalOnWebApplication(SERVLET)`.** This
service's own test (`ResumeTextExtractionTest`, see below) runs a `@SpringBootTest(webEnvironment
= NONE)` context that has `jakarta.servlet-api` on the classpath but isn't classified by Spring
as a "servlet web application" in that mode — `@ConditionalOnWebApplication(SERVLET)` would have
silently skipped registering the filter bean there. Since `common-security` is shared across
servlet services (this one, auth-service, compatibility-service, question-service) and a reactive
one (voice-orchestrator), the weaker `@ConditionalOnClass(Filter.class)` guard is what lets it
register correctly in every context that can actually load a servlet `Filter` type, including a
`NONE`-web-environment test context. See `common-security/ARCHITECTURE.md` for the full story.

---

## Exceptions

No `@ControllerAdvice` in this module — every exception it throws is its own class, so
`@ResponseStatus` directly on the class is enough (unlike auth-service, which needs
`@ControllerAdvice` specifically to attach a status to Spring Security's own
`BadCredentialsException`, a class it doesn't own).

| Exception | Status | Thrown by |
|---|---|---|
| `JobDescriptionNotFoundException` | 404 | `JobDescriptionService.findOwned()` — missing OR not-owned |
| `ResumeNotFoundException` | 404 | `ResumeService.findOwned()` — missing OR not-owned |
| `InvalidFileException` | 400 | `ResumeService.validate()` — empty, oversized, wrong content type |
| `FileStorageException` | 500 | `LocalFileStorageService` — wraps `IOException`/`MalformedURLException` |

---

## `pom.xml` — dependencies

- **`common-security`** — `JwtAuthenticationFilter` + `JwtValidator` + bundled public key, same
  as every other servlet-based service in this system.
- **`pdfbox` 2.0.31`** and **`poi`/`poi-ooxml` 5.2.5`** — versions pinned locally as properties
  (`pdfbox.version`, `poi.version`) because the parent BOM (`spring-boot-dependencies`) doesn't
  manage either. `poi-ooxml` is required alongside plain `poi` specifically for OOXML/DOCX
  support — bare `poi` only covers the legacy binary `.doc` format.
- **`h2`** (test scope) — in-memory Postgres-compatible DB backing `ResumeTextExtractionTest`.
- No Lombok, no OpenAPI/Swagger, no separate DTO-validation-only starter beyond
  `spring-boot-starter-validation` (powers `@NotBlank` on `JobDescriptionRequest`).

---

## `application.yml`

```yaml
spring:
  application:
    name: jd-resume-service
  datasource:
    url: jdbc:postgresql://localhost:5432/callback_jdresume
    username: ${DB_USERNAME:callback_admin}
    password: ${DB_PASSWORD:callback_password123}
  jpa:
    hibernate:
      ddl-auto: update
    open-in-view: false
  servlet:
    multipart:
      max-file-size: 10MB
      max-request-size: 10MB
server:
  port: 8082
```

- **Own Postgres database** (`callback_jdresume`) — separate from auth-service's DB. Each service
  owns its own schema; no shared database across services in this system.
- **`open-in-view: false`** — no lazy-loading outside a transaction is possible from controllers.
  Consistent with the code: all entity access happens inside `@Service` methods, fully mapped to
  DTOs before reaching the controller layer.
- DB credential defaults mirror auth-service's — acceptable for a local-only dev Postgres
  instance, same rationale documented there.

---

## Testing: `ResumeTextExtractionTest`

The only test class, backed by `src/test/resources/application-test.yml`
(`jdbc:h2:mem:jdresume;DB_CLOSE_DELAY=-1;MODE=PostgreSQL`, `ddl-auto: create-drop`,
`hibernate.dialect: H2Dialect`). `MODE=PostgreSQL` makes H2 emulate Postgres dialect quirks so
tests don't silently diverge from prod SQL behavior; `DB_CLOSE_DELAY=-1` keeps the in-memory DB
alive for the whole test run instead of dropping it the instant a connection closes.

Loads the **full** Spring context (`@SpringBootTest(webEnvironment = NONE)`) but drives requests
through a **standalone** `MockMvc` (`MockMvcBuilders.standaloneSetup(resumeController)`), with
authentication faked directly via `SecurityContextHolder` rather than going through
`JwtAuthenticationFilter`/`JwtValidator`. The test class javadoc states this explicitly: *"the
JWT filter chain is bypassed via standalone MockMvc since ownership, not authentication, is what
this endpoint adds."* JWT verification is common-security's concern and is tested there; this
test scopes itself to controller → service → repository → storage → extraction.

Three tests, all passing:

| Test | Proves |
|---|---|
| `ownerCanReadExtractedTextFromRealPdf` | Real PDF fixture (`fixtures/sample-resume.pdf`) → PDFBox extraction round-trips real text |
| `ownerCanReadExtractedTextFromRealDocx` | In-memory-built real POI `XWPFDocument` → POI extraction round-trips real text |
| `nonOwnerGetsNotFoundForText` | Upload as owner, re-authenticate as a different email → `GET /resumes/{id}/text` → 404 |

`@AfterEach` calls `SecurityContextHolder.clearContext()` — required because standalone MockMvc
doesn't clear the security context per-request the way the full filter chain would on real HTTP
traffic.

---

## Pre-existing / cross-module notes

- `data/resumes/` on disk holds real output from manual end-to-end test sessions (UUID-prefixed
  PDFs/DOCX files) — gitignored, not sample data to be curated or committed.
- `GET /jd/{id}/text` and `GET /resumes/{id}/text` were both added together in a single earlier
  commit ("updating jd-resume-service for resume text extraction"), alongside the PDF/DOCX
  extraction service, the H2 test profile, and `ResumeTextExtractionTest`. A later, unrelated
  commit's message text ("Reapply...") describes *why* the JD-text endpoint exists (so
  compatibility-service has something to fetch) but touches zero files in this module — don't
  read that commit's message as a literal diff description if cross-referencing git history here.
- `compatibility-service` is the actual consumer of both `/text` endpoints; it forwards the
  caller's own bearer token when fetching, so the ownership check here still applies to whichever
  user's JD/resume is being fetched on their behalf.

---

## Keeping this document current

When you change something structural — a new endpoint, a new extraction format, a new storage
backend, a changed security rule — update the relevant section above in the same commit/PR. Treat
drift between this file and the code as a bug.
