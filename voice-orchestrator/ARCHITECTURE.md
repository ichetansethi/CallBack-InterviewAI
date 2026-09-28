# voice-orchestrator — Architecture & Design Notes

This document explains what exists in `voice-orchestrator`, why each piece is built the way it
is, and the reasoning behind the non-obvious decisions. Update this file whenever the
architecture changes — a new endpoint, a new pipeline stage, a security fix — so it stays a
reliable map of the service instead of going stale.

## What this service is

A real-time voice interview pipeline. A client opens one WebSocket connection
(`/voice/session?token=<jwt>&questionSetId=<uuid>`), streams raw PCM audio chunks up, and this
service transcribes each utterance (local whisper.cpp), decides the interviewer's next move via an
LLM tool call (Groq, through Spring AI's OpenAI-compatible client) — grounded in a prepared
question list fetched once from question-service at connect time — synthesizes the response as
speech (local Piper), and streams the resulting audio back — sentence by sentence, so playback can
start before the whole response has finished synthesizing. Session/conversation state (the running
transcript, plus the question list and progress through it) lives in Redis for the life of the
connection plus a 2-hour TTL.

**This is not a proxy to OpenAI's realtime voice API.** Despite the pom comment and the
`spring-ai-starter-model-openai` dependency name, the "OpenAI-compatible client" here is pointed
at Groq and used purely for a text-only tool-call decision — the actual speech-to-text and
text-to-speech work is done by two unrelated local HTTP servers (whisper.cpp and Piper) that have
nothing to do with Spring AI.

Runs on port `8085`. Landed whole in a single commit (`79eff1a`, "Added Voice Orchestrator and
updated common security to accommodate ReactiveJwtAuthenticationFilter") that also introduced
`common-security`'s `ReactiveJwtAuthenticationFilter` and a new `docker-compose.yml` running
`redis:7-alpine` on port `6379`.

There is **no controller package, no `SecurityConfig`, and no REST endpoint** in this module —
the WebSocket handler is the only entry point.

## Why reactive/WebFlux, not servlet

Every other service in this system is `spring-boot-starter-web` (servlet/MVC). This one is
`spring-boot-starter-webflux`, because a voice pipeline needs to stream binary WebSocket frames
in both directions — receiving audio while simultaneously sending synthesized speech back — without
blocking a servlet thread per connection for the duration of an interview. This single choice is
what forced `common-security` to grow a second filter (`ReactiveJwtAuthenticationFilter`) — see
common-security/ARCHITECTURE.md for the full story of why a servlet `Filter` can't be reused here
(it can't even be loaded without `jakarta.servlet-api`, and its `ThreadLocal`-based
`SecurityContextHolder` is unsafe under WebFlux's non-blocking, thread-hopping execution model).

## Connection / auth flow

```
Client → WebSocket upgrade GET /voice/session?token=<JWT>&questionSetId=<uuid>
      │
      ▼
WebSocketConfig's SimpleUrlHandlerMapping (order=-1, so it's matched ahead of any future
@RestController mapping) routes to VoiceWebSocketHandler
      │
      ▼
VoiceWebSocketHandler.handle(session)
      1. token = query param "token"
         if missing or !jwtValidator.isValid(token) → session.close(POLICY_VIOLATION)
      2. questionSetId = query param "questionSetId", parsed as a UUID
         if missing or not a valid UUID → session.close(POLICY_VIOLATION)
      3. ownerEmail = jwtValidator.extractEmail(token)
      4. sessionId = ownerEmail + "::" + questionSetId   — see "Session identity" below, this
         replaced a client-supplied sessionId query param
      5. questionServiceClient.getQuestionSet(questionSetId, "Bearer " + token)
         — GET to question-service (see "Question-service integration" below); a 404 (question
           set doesn't exist OR isn't owned by this token — question-service deliberately returns
           the same 404 for both) → session.close(POLICY_VIOLATION); any other failure (401, 5xx,
           timeout, connection refused) → session.close(SERVER_ERROR), logged
      6. sessionRepository.loadOrCreate(sessionId, ownerEmail, fetchedQuestions)
         — Redis GET, or create+save with the fetched question list if absent. On a genuine resume
           (session already existed), the freshly-fetched question list is discarded in favor of
           what's already persisted — see InterviewSessionState's javadoc for why.
      7. send text ack frame: {"sessionId":"<id>"}
      8. session.receive().concatMap(handleIncomingAudioChunk) — per-connection audio pipeline begins
```

**This handler authenticates itself independently of `ReactiveJwtAuthenticationFilter`** — it
injects `JwtValidator` directly and never reads `ReactiveSecurityContextHolder`. See "Security
wiring" below for why the global filter still runs on every request (including this handshake)
but currently has no consumer in this service.

### Session identity — no longer a client-supplied id

Originally a client could pass `?sessionId=<uuid>` to resume a specific prior interview, or omit
it to get a freshly minted one. That's gone: **the resumable identity of an interview is now
`(ownerEmail, questionSetId)`**, computed server-side as `sessionId = ownerEmail + "::" +
questionSetId`. Reconnecting with the same token and the same `questionSetId` always lands on the
same Redis-stored session — there's no separate id for the client to track, store, or pass back.

This is deliberately different from a raw ownership check on a client-chosen id (the old
`state.ownerEmail().equals(ownerEmail)` comparison is gone because it's now structurally
impossible to violate): since the key itself embeds `ownerEmail`, a different user can never even
compute the same key, let alone collide with someone else's session, regardless of whether they
guess or observe a `questionSetId`. The actual authorization boundary is enforced by
question-service's own ownership check on every connect (step 5 above) — this service doesn't
independently re-verify anything beyond trusting that response, which is a deliberate delegation
of that check, not an oversight.

One consequence worth knowing: this ties one `questionSetId` to exactly one interview attempt per
candidate. There's no way to represent "retake this same question set" — that would need its own
distinct identifier (e.g. an attempt id), not implied by anything built here.

### Rejection close code: `POLICY_VIOLATION` (1008), not `NOT_ACCEPTABLE` (1003)

All four handshake-rejection paths above originally used `CloseStatus.NOT_ACCEPTABLE`, which maps
to WebSocket close code **1003**. Found wrong during live testing (see "Testing" below): RFC 6455
defines 1003 as *"received a type of data it cannot accept"* — the wire-level frame type (e.g.
binary vs. text), not a business-logic rejection like a bad JWT, a malformed UUID, or "you don't
own this resource." Spring's own `POLICY_VIOLATION` constant (code **1008**) is RFC 6455's actual
catch-all for *"received a message that violates its policy"* and is the correct fit here — fixed
to use that instead. (`CloseStatus.NOT_ACCEPTABLE`'s name is arguably a Spring naming footgun: it
evokes HTTP 406, a completely different axis — content negotiation, not auth/authorization — which
is likely what led to reaching for it originally.)

This still doesn't reject at the most standard layer: a real HTTP 401/403 during the Upgrade
request itself (before the WebSocket handshake completes) would be more universally actionable by
proxies, load balancers, and browser devtools than any WS close code, which requires the client to
inspect `close.code`/`close.reason` explicitly. Doing that in Spring WebFlux would mean rejecting
earlier — a `WebFilter`/handshake interceptor ahead of `VoiceWebSocketHandler` — rather than
accepting the upgrade and closing after the fact. Deliberately not done here; flagged as a possible
future improvement, not a bug.

### Message protocol
- **Inbound**: binary frames only, expected to be 16 kHz / 16-bit signed little-endian mono PCM.
  Any non-binary frame is silently ignored — *"anything else (e.g. a future text control message)
  is ignored for now."*
- **Outbound**: one text ack frame on connect (`{"sessionId":"..."}`), then binary audio frames
  (one per synthesized sentence, streamed as Piper returns them), or — on a turn-processing
  failure — a text error frame: `{"type":"error","message":"Sorry, something went wrong — could
  you repeat that?"}`.

---

## Question-service integration

`QuestionServiceClient` (a small `WebClient` wrapper, same shape as `WhisperClient`/`PiperClient`)
calls question-service's `GET /questions/{id}` once per connection, during the handshake — see
"Connection / auth flow" above. This is what finally gives `TurnDecisionService`'s `"advance"`
action a real question to advance to; previously (see "Turn decision" below) there was no prepared
question list anywhere in this service, which was a likely reason the model never chose `advance`
in testing — it had nothing concrete to advance toward.

- **DTOs** (`QuestionSetDto`, `InterviewQuestionDto`) mirror question-service's own
  `QuestionSetResponse`/`InterviewQuestionResponse` field-for-field (`id`, `jdId`, `createdAt`,
  `questions[]` of `{category, questionText, rationale}`). Array order is the only ordering
  signal — question-service sorts server-side by its own `orderIndex` before responding, but
  doesn't expose that index in the response; this service just trusts array order.
- **Ownership is delegated, not re-checked here.** question-service's own `QuestionSetService`
  enforces that the question set belongs to the caller (matching the JWT subject), returning 404
  for both "doesn't exist" and "exists but isn't yours" — deliberately the same response for both,
  to avoid leaking which is which (same pattern as compatibility-service's own `findOwned`). Only
  that 404 is folded into `QuestionSetNotFoundException` here; a 401 (bad/expired forwarded token)
  is deliberately left as a distinct, generically-logged failure rather than being mislabeled as
  "not found" — collapsing them would hide a real auth-forwarding bug behind a misleading message.
- **Timeout**: 3s connect / 15s response, same reasoning and same mechanism
  (`ReactorClientHttpConnector` + `reactor.netty.http.client.HttpClient`) as
  `WhisperClientConfig`/`PiperClientConfig` — this call happens before anything else in the
  connection, so a hang here is worse than a hang mid-conversation: the candidate never even gets
  an ack.
- **Live-verified** (see "Testing" below) — real question sets fetched from a running
  question-service correctly drive `"advance"`/`"end"` decisions end-to-end.

### Two real bugs found and fixed by this live-testing pass

Neither was caught by compilation or unit tests; both were found the moment a real connection was
attempted.

1. **`pom.xml` had a stray full `compile`-scope dependency on `question-service`** (added in the
   same commit as this integration), even though this service only ever uses its own local
   `QuestionSetDto`/`InterviewQuestionDto`/`QuestionServiceClient` and never imports
   `com.callback.question.*`. Because `VoiceOrchestratorApplication` scans the widened
   `com.callback` base package (see "Security wiring" below), this pulled question-service's JPA
   entities and repositories onto voice-orchestrator's classpath and made Spring try to configure a
   SQL `DataSource` for a service that has none — `APPLICATION FAILED TO START` with "Failed to
   determine a suitable driver class" on every boot. Fixed by deleting the dependency entirely;
   the "no compile-time dependency between them" claim in "`pom.xml` — dependencies" above is now
   actually true again, not just documented as the intent.
2. **question-service's `GET /questions/{id}` 500'd on every call**, not just cross-owner ones:
   `QuestionSetController.get(@PathVariable UUID id)` never named its path variable, and this
   service's build has no `-parameters` compiler flag, so Spring couldn't resolve the binding
   (`IllegalArgumentException: Name for argument of type [java.util.UUID] not specified...`). Every
   other `@PathVariable` in this codebase (`JobDescriptionController`, `ResumeController`) names it
   explicitly (`@PathVariable("id")`); this one didn't. This is the exact call
   `QuestionServiceClient` makes on every voice-orchestrator handshake, so until it was fixed, no
   question-set-backed session could ever be created at all — not a cross-user edge case, a total
   block on this entire feature. Fixed by adding the explicit name in
   `question-service/.../QuestionSetController.java`.

---

## The per-utterance pipeline

Per WebSocket connection, two pieces of connection-scoped mutable state are constructed fresh
(neither is a Spring singleton): a `UtteranceBuffer` (wrapping the shared, stateless
`VoiceActivityDetector` bean) and an `AtomicReference<InterviewSessionState>` pointing at the
latest Redis-persisted state. All audio chunks for one connection are processed sequentially via
`concatMap`, so there's exactly one writer per session and no concurrent-write races within it —
no locking or multi-instance session-sharing logic exists or is needed under that assumption.

```
binary frame arrives
   │
   ▼
UtteranceBuffer.append(chunk)
   accumulates raw PCM bytes; computes chunkMs from byte length @ 16kHz/16-bit
   VoiceActivityDetector.isSilent(chunk) → tracks running speechMs / silenceMs
   returns true (flush) only once speechMs >= 300ms AND silenceMs >= 700ms
      (guards against flushing on leading silence before the candidate has said anything)
   │  (false → wait for more chunks)
   ▼ (true)
UtteranceBuffer.flushAsWav()
   WavEncoder.encode(pcmBytes, 16000, 1, 16) — hand-rolled 44-byte RIFF/WAVE/fmt/data header,
   no external audio library needed. Resets buffer state for the next utterance.
   │
   ▼
WhisperClient.transcribe(wavBytes)
   multipart POST {whisper.base-url}/inference (whisper.cpp server mode), field "file",
   filename "utterance.wav", content-type audio/wav
   → WhisperTranscriptionResponse{text} — mirrors whisper.cpp's {"text": "..."} response shape
   │
   ▼
processTurn(session, transcript, stateRef)
   ├─▶ TurnDecisionService.decideNextTurn(transcript, currentState) → TurnDecision{action, responseText}
   │        (Groq tool call — see "Turn decision" below)
   ├─▶ TextToSpeechStreamer.speak(session, decision.responseText())
   │        splits into sentences, synthesizes + streams each in order (see "TTS streaming" below)
   ├─▶ ONLY after speech has been fully handed to the WebSocket:
   │      sessionRepository.save(current.withTurn(new TurnRecord(transcript, action, responseText)))
   │      stateRef.set(saved)
   └─▶ if action == "end": session.close(CloseStatus.NORMAL) — see "Reaching a clean end state" below
```

### Why the save happens after the speech is sent, not before

Explicit comment in `processTurn`: *"Commit only after the response has actually been synthesized
and sent — not before attempting delivery. That way a failure partway through (e.g. Piper is
down) leaves the session at its prior turn instead of silently advancing past a question the
candidate never heard."* This is not a delivery guarantee — there's no client-side ack, so it
can't confirm the candidate actually *heard* the audio, only that this service successfully
handed it to the WebSocket transport. The comment is explicit that closing that gap is "real added
complexity... deliberately not built here."

### Failure isolation per utterance

The whole whisper → decide → speak → save chain is wrapped in `.onErrorResume` per utterance. On
any failure: log, send the fallback error text frame; if *that* send also fails, log and swallow
rather than propagate — one bad utterance never tears down the whole connection. Because the save
step never ran, the candidate's next utterance is effectively answering the same question again
(turn state wasn't advanced), which is the intended recovery behavior given the "commit after
delivery" rule above.

---

## Audio processing details

### `VoiceActivityDetector`
Energy-based VAD: RMS amplitude of signed 16-bit LE PCM samples, `SILENCE_RMS_THRESHOLD = 500`.
Explicitly documented as *"the simplest viable version — a proper ML VAD model is a later upgrade,
not a blocker for a working pipeline."*

### `UtteranceBuffer`
`MIN_SPEECH_MS_BEFORE_FLUSH = 300`, `SILENCE_HOLD_MS = 1500` — an utterance only flushes once it
has accumulated real speech and then held silence for a sustained period, avoiding both
premature flushing on brief pauses and flushing on pure background silence.

**`SILENCE_HOLD_MS` was originally 700ms; live end-to-end testing found this too short.** A
candidate pausing ~1.2s mid-sentence to think (a normal pause, not a stopping point) was flushed
as end-of-utterance: the pipeline transcribed the fragment before the pause, the interviewer
generated and spoke a response to that half-formed sentence, and the rest of the candidate's
answer became a second, disconnected turn. Confirmed via `redis-cli` on the session state:
`turnCount` incremented twice for what was semantically one answer, with two fragmentary
transcripts instead of one coherent one. Raised to 1500ms, which comfortably exceeds a natural
thinking pause; a regression test
(`UtteranceBufferTest.doesNotFlagEndOfUtteranceOnAMidThoughtPauseUnderTheHoldThreshold`) pins this.
**Trade-off, stated plainly**: this adds up to ~800ms of latency to every genuine end-of-turn
detection — see "Real end-to-end latency" below, where median measured latency was already ~1.9s
before this change, so the true end-to-end number is now higher. A fixed energy-based silence
threshold fundamentally can't distinguish "still thinking" from "done talking" — raising the
number trades false positives (premature cutoffs) for slower turn-taking, it doesn't eliminate the
underlying ambiguity. A semantic/ML VAD (or a pause-fillers-aware heuristic) is the real fix, not
attempted here.

### `WavEncoder`
Prepends a standard 44-byte PCM WAV header (RIFF/WAVE/fmt /data chunks) to raw PCM bytes — no
external audio library dependency for this. Every header field (RIFF size, format markers,
channel count, sample rate, bits-per-sample, data size) is unit-tested byte-for-byte.

---

## Turn decision (`turn` package) — tool-calling against Groq, not a realtime voice API

`TurnDecisionService`:

```java
public TurnDecisionService(ChatClient.Builder chatClientBuilder) {
    this.chatClient = chatClientBuilder.build();
}
```

- `decideNextTurn(transcript, state)` wraps a **blocking** `ChatClient.call()` exchange in
  `Mono.fromCallable(...).subscribeOn(Schedulers.boundedElastic())` — explicit comment: *"this
  class is used from voice-orchestrator's reactive WebSocket pipeline and must never block a
  Netty event loop thread."* This is the one place a blocking Spring AI call is deliberately
  shifted off the reactive thread pool.
- **`MAX_ATTEMPTS = 4`**, rate-limit-aware backoff between attempts (`backoffBeforeRetry`): ~12s if
  the failure message contains `"429"`/`"rate_limit"`, ~300ms otherwise. Originally a flat 300ms
  regardless of failure type — a gap relative to `CompatibilityScorer.backoffBeforeRetry` in
  compatibility-service, which already had this distinction (see
  [[local_model_reliability_findings]]: *"Rate limits interact badly with retry-without-backoff...
  immediately retrying a 429 just re-hits the same limit"*). Brought into parity so a burst of
  Groq free-tier rate limiting doesn't burn through all 4 attempts in under a second.
- **`MODEL_CALL_TIMEOUT = Duration.ofSeconds(30)`**, enforced via a dedicated cached daemon-thread
  executor (`"turn-decision-model-call"`) and `Future.get(timeout)`. The class's own comment
  cross-references `CompatibilityScorer.MODEL_CALL_TIMEOUT` in compatibility-service directly —
  this guards against the same failure mode: a runaway tool-call repetition loop hanging the
  exchange indefinitely, which Spring AI's own tool-execution loop has no built-in cap against.
- **Single-tool-per-exchange discipline**: the system prompt instructs *"Respond only via a tool
  call, never in plain text: call submitTurnDecision exactly once."* `TurnDecisionRecorder`
  exposes exactly one `@Tool`-annotated method, `submitTurnDecision(action, responseText)` — a
  fresh instance is created per attempt, never reused across retries. This deliberately duplicates
  (rather than shares as a library) the same discipline used by compatibility-service's
  `CompatibilityScorer` and question-service's `QuestionGenerationService` — see
  [[local_model_reliability_findings]].
- Failure modes that trigger a retry: the model doesn't call the tool at all
  (`IllegalStateException("Model did not call submitTurnDecision")`), calls it with a blank
  `responseText`, or returns an `action` outside `{follow_up, advance, end}` (case-insensitive) —
  this last check was added alongside the question-service integration below, since `action` now
  drives real business logic (advancing `currentQuestionIndex`), not just prose the model happens
  to produce. After `MAX_ATTEMPTS` is exhausted: `IllegalStateException("Model failed to produce a
  valid turn decision after 4 attempts", lastFailure)`, which propagates up through the reactive
  chain and is caught by `VoiceWebSocketHandler`'s `.onErrorResume` (the fallback error-message
  path).

### Question-set-aware prompting

`buildTurnPrompt` now includes, in addition to the exchange number (see "Reaching a clean end
state" below): the current prepared question's category and text, and — if there is one — the
*next* prepared question's category and text, or an explicit "this is the last prepared question,
end instead of advancing" instruction if there isn't. This directly addresses a finding from live
testing: `"advance"` previously had no concrete question to advance to anywhere in the prompt, and
the model was observed to never choose it. Giving it a real next question, and explicitly telling
it when there's nothing left, fixes that — **re-verified live** (see "Testing" below): across a
7-question set, the model chose `"advance"` repeatedly, each time asking exactly the next prepared
question's text, and correctly chose `"end"` (not another `"advance"`) once positioned on the last
prepared question.

`InterviewSessionState.withTurn` clamps `currentQuestionIndex` at the last valid index regardless
of what the model returns, as a deterministic safety net — if the model says `"advance"` while
already on the last question despite being told not to, the index simply doesn't move past the end
(and the exchange-count cap still applies as a second, independent path to `"end"`). This is a
different mechanism from the exchange-count guidance: running out of prepared questions is an
objective fact this service can enforce in code, not something worth leaving purely to the model's
judgment.
- `TurnDecision{action, responseText}` — `action` is one of `"follow_up"`, `"advance"`, or `"end"`
  per the tool parameter's description; this is validated only by the prompt instruction, not
  enforced in code as an enum.
- The prompt renders the full turn history (`state.history()`) as alternating `"Candidate: ..."` /
  `"Interviewer: ..."` lines, plus the latest transcript, as the user message — so every decision
  is made with the full conversation-so-far in context, not just the latest utterance.

## Reaching a clean end state

Originally there was no way for an interview to end on its own: `action` only ever meant
`"follow_up"` or `"advance"`, no question-set concept existed anywhere in this service (no call to
question-service), and no code path ever closed the connection based on interview progress — it
just stayed open until the client disconnected or an unhandled error occurred. Confirmed as a real
gap via live testing, not assumed.

Fixed by adding a third action, `"end"`: the system prompt instructs the model to end the
interview with a closing remark once it's covered enough ground. `VoiceWebSocketHandler.processTurn`
speaks that closing remark and saves the final turn exactly as any other turn, then — only after
both have succeeded — `closeIfInterviewEnded` calls `session.close(CloseStatus.NORMAL)`. Closing
after speech has been sent (not before) keeps the same "commit after delivery" discipline the rest
of `processTurn` already follows.

**Verified live** (three separate sessions): the model only chose `"end"` when the candidate gave
an explicit verbal cue ("I don't have anything else to add" / "that's everything from my side") —
never spontaneously from context alone in an initial test. Each time, the WebSocket closed with
code **1000 (normal closure)**, confirmed by forcing a subsequent send into the closed socket and
observing `ConnectionClosedOK: received 1000 (OK)` client-side.

### Why it didn't end on its own at first, and the fix for that

The original prompt told the model "roughly 5-8 substantive exchanges is typically enough" in
prose, but never told it the actual exchange number — `buildTurnPrompt` only rendered the
conversation as unstructured `"Candidate: ... \nInterviewer: ..."` text. For the model to know it
was on exchange 8, it would have had to accurately count conversational pairs embedded in that
prose itself, which is unreliable for a model tuned for fast, low-temperature tool-calling. Across
8 organic turns in testing, it never did — every decision was `"follow_up"` until an explicit
candidate cue forced `"end"`.

Fixed by making the exchange number an explicit fact instead of something to infer:
`TurnDecisionService.buildTurnPrompt` now computes `currentExchangeNumber = state.turnCount() + 1`
and puts it as the first line of the user message (`CURRENT EXCHANGE NUMBER: %d`). The system
prompt references two named constants instead of vague prose: `MIN_EXCHANGES_BEFORE_END = 6` (the
model is told never to end before this exchange, no matter how confident it feels) and
`TARGET_MAX_EXCHANGES = 8` (the model is told to end by this exchange regardless). Between 6 and
8, it's told to end as soon as it has a well-rounded picture rather than continuing to probe. This
is still prompt-level guidance, not a hard enforcement in code — nothing forces `action = "end"` at
exchange 8 if the model disregards the instruction, so a runaway interview that ignores the prompt
is still theoretically possible. **Re-verified live** (see "Testing" below): with no explicit
candidate cue at all, the model chose `"end"` unprompted exactly at exchange 6
(`MIN_EXCHANGES_BEFORE_END`), with `currentQuestionIndex` still only at 3 of 6 — confirming the
exchange-count path really is independent of, and can preempt, the question-list-exhaustion path.

**Deliberately not built**: a hard code-level cap (e.g. force `action = "end"` once
`state.turnCount()` reaches some ceiling regardless of what the model returns) would make this
fully bulletproof, but that's a separate, more invasive change — not implied by "tell the model the
turn count," and not added without a deliberate decision to trade model judgment for a hard
guarantee.

**What this deliberately doesn't do**: there is still no question-set exhaustion concept — this
service has no integration with question-service and doesn't track a target number of questions.
The model decides when to end purely from the conversation so far, the same way it already decides
`"advance"` without any actual prepared question list. If a hard question-count cap or
question-service integration is wanted later, that's a separate, larger feature, not implied by
this fix.

---

## TTS streaming (`tts` package)

- **`SentenceSplitter`** — splits a complete string into sentences via a lookbehind regex on
  terminal punctuation (`.?!`). This is the path actually used today, since `TurnDecisionService`
  returns one complete `responseText` string, not a token stream.
- **`TextToSpeechStreamer.speak(session, text)`** — splits into sentences, then
  `.concatMap(sentence -> piperClient.synthesize(sentence).flatMap(bytes -> session.send(...)))`.
  Deliberately `concatMap`, not `flatMap` — the class javadoc is explicit: sentences must reach
  the client **in order** (`flatMap` gives no ordering guarantee across concurrently-synthesizing
  sentences of different latency), and `WebSocketSession.send()` must never be invoked
  concurrently with itself on the same session. This is the mechanism that lets sentence 1's audio
  start playing while sentence 2 is still being synthesized, rather than waiting for the entire
  response before speaking anything.
- **`SentenceBoundaryBuffer`** and **`TextToSpeechStreamer.speakStream()`** — built but currently
  **unused**. `SentenceBoundaryBuffer` buffers a `Flux<String>` of token-by-token text deltas into
  complete sentences (flushing any trailing partial sentence when the source completes), which
  would let the pipeline start synthesizing speech before the LLM has finished generating its full
  response. This is forward-looking infrastructure for if/when `TurnDecisionService` streams its
  output token-by-token instead of returning a single blocking string — not dead code to remove,
  but not on the active code path today.

---

## HTTP clients to Whisper and Piper — buffer size and timeouts

`WhisperClientConfig`/`PiperClientConfig` each build their `WebClient` with explicit
`ReactorClientHttpConnector`-backed settings, not WebFlux's defaults. Both gaps below were found
via live end-to-end testing against real local whisper.cpp/Piper servers, not by inspection.

- **Response buffer size (`PiperClientConfig` only)**: WebFlux's default in-memory buffer cap for
  a fully-buffered response body (`bodyToMono(byte[].class)`, as `PiperClient.synthesize` uses) is
  256KB. A single synthesized sentence's raw audio can exceed that — hit in testing as
  `DataBufferLimitException: Exceeded limit on max bytes to buffer : 262144`, which `processTurn`'s
  `onErrorResume` treated identically to Piper being down (fallback message, turn not committed) —
  except Piper was actually healthy the whole time. Raised to 10MB via
  `ExchangeStrategies.builder().codecs(...).maxInMemorySize(...)`, comfortably past any realistic
  one-sentence clip.
- **Response/connect timeouts (both clients)**: neither `WebClient` had any timeout configured
  before. A backend that's fully down (nothing listening on the port) fails the connection
  quickly — confirmed for both whisper.cpp-down and Piper-down, fallback message delivered in
  roughly 1-2s. But a backend that's *alive and unresponsive* (process hung, not crashed) had no
  timeout guard at all and could have stalled a turn indefinitely with no fallback ever firing —
  the same class of failure as `TurnDecisionService.MODEL_CALL_TIMEOUT` guards against for the
  Groq call, just previously unguarded on this side of the pipeline. Both clients now set a 3s
  connect timeout and a 15s response timeout via a `reactor.netty.http.client.HttpClient`, generous
  headroom over real local inference/synthesis time for one utterance or one sentence.

---

## Redis: session/conversation state store (not pub/sub, not a cache)

`RedisConfig` defines exactly one bean — a `ReactiveRedisTemplate<String, InterviewSessionState>`
with a plain `StringRedisSerializer` for keys and a `Jackson2JsonRedisSerializer` (reusing the
app's own auto-configured `ObjectMapper`) for values.

`InterviewSessionRepository`:
- Key format: `"voice:session:" + sessionId`, where `sessionId` is now `ownerEmail + "::" +
  questionSetId` (see "Session identity" above) — no longer a client-supplied or randomly
  generated UUID.
- `TTL = Duration.ofHours(2)`, **refreshed on every save** (a sliding TTL, not fixed-from-creation)
  — comment: *"Bounds how long an abandoned session lingers in Redis; refreshed on every save so
  an active interview never expires mid-conversation."*
- `loadOrCreate(sessionId, ownerEmail, questions)` — `GET`, and if empty, creates and saves a
  fresh `InterviewSessionState.newSession(...)` seeded with the question list fetched from
  question-service for this connection. If a session already exists, the passed-in `questions` are
  discarded — the persisted list wins, so an interview's questions can't silently change mid-way
  even if question-service's own data changes later.

Redis here **is** the store of the interview transcript for the life of the connection (and up to
2 hours after) — not a cache in front of some other database. `InterviewSessionState.history` is
a `List<TurnRecord>`; `TurnRecord{transcript, action, responseText}` is one full turn: what the
candidate said, what the interviewer decided to do, and what it said back.
`InterviewSessionState.questions` is the prepared question list (fetched once, at creation, from
question-service) and `currentQuestionIndex` is this service's own progress cursor into it —
question-service itself has no "current question" concept, only a flat ordered list.
`InterviewSessionState.withTurn(...)` returns a new immutable state (copy-on-write of the history
list) with an incremented `turnCount`, `currentQuestionIndex` advanced (and clamped) if the turn's
action was `"advance"`, and bumped `updatedAt` — no in-place mutation of persisted state.

### The known, documented persistence gap

`SessionHistoryPersister.onConnectionEnded(state)` is invoked from
`VoiceWebSocketHandler.handle(session)`'s `.doFinally(...)` — firing on normal completion, error,
or cancellation alike — and today only logs:

```
"Connection ended for session {} after {} turn(s); transcript remains in Redis only —
 persistence to session-history-service is not implemented yet."
```

This is intentional scaffolding, not an oversight: `session-history-service` doesn't exist yet as
a module. The full transcript isn't lost — it lives in Redis for the 2-hour TTL — but it isn't
durably persisted past that window. The module's own `CLAUDE.md` says explicitly to delete this
class once real persistence lands.

---

## Security wiring — the whole `ReactiveJwtAuthenticationFilter` story, from this service's side

This is the most structurally distinctive fact about this module relative to every servlet
sibling service, and relative to the auth-service template this documentation style is based on:
**there is no `SecurityConfig`, no `@EnableWebFluxSecurity`, and no `SecurityWebFilterChain` bean
anywhere in this module — and none is needed.**

`common-security`'s `ReactiveJwtAuthenticationFilter implements WebFilter`. Spring Boot's reactive
web autoconfiguration collects every `WebFilter` bean in the application context and splices it
into the request-handling pipeline automatically, regardless of whether Spring Security's own
filter-chain machinery is present. Since `VoiceOrchestratorApplication` uses the same
`@SpringBootApplication(scanBasePackages = "com.callback")` widened scan every sibling service
uses (with a comment explaining that `JwtAuthenticationFilter` and `ReactiveJwtAuthenticationFilter`
are each conditionally guarded so only the one matching this app's actual reactive stack is ever
instantiated — see common-security/ARCHITECTURE.md), `ReactiveJwtAuthenticationFilter` is
auto-detected and runs on every request this service handles, including the WebSocket handshake
GET to `/voice/session` — with **zero explicit wiring required**.

### But the filter has no consumer here today

`VoiceWebSocketHandler` never reads `ReactiveSecurityContextHolder.getContext()`. Instead, it
performs its own independent `jwtValidator.isValid(token)` / `extractEmail(token)` calls directly
against the `token` query parameter (see "Connection / auth flow" above). Combined with the
filter's own unconditional pass-through behavior when no valid token is present (*"let it through
unauthenticated; reject downstream if needed"* — see common-security/ARCHITECTURE.md), this means:
**the global filter genuinely runs on every request but currently has no functional effect in
this service.** It populates the Reactor-context-scoped security identity; nothing downstream
reads it. This is intentional-but-currently-redundant plumbing, not a bug — it exists so that any
future plain reactive REST endpoint this service might grow gets request authentication "for
free," matching common-security's design goal used by every other consumer. Doing the WebSocket
handshake's own auth check by hand, independent of the filter, sidesteps a genuinely awkward
corner of Spring WebFlux: a `WebSocketHandler.handle()` method doesn't naturally inherit the
Reactor context the way a controller method invoked mid-`WebFilter`-chain does, so wiring the two
together cleanly is nontrivial — the handler doing its own check is the pragmatic choice given
that, not an oversight.

**The actual authorization-relevant checks for this service's one real endpoint are entirely
in `VoiceWebSocketHandler`**: token validity, and (since the session-identity change in "Session
identity" above) trusting question-service's own per-`questionSetId` ownership check on every
connect — there's no separate in-Redis ownership comparison anymore, because the
`ownerEmail + "::" + questionSetId` key makes a cross-owner collision structurally impossible
rather than something to check for after the fact.

---

## `pom.xml` — dependencies

- `common-security` — pulls in `JwtValidator`, `RsaPublicKeyLoader`, and (crucially, this being
  the one WebFlux consumer) `ReactiveJwtAuthenticationFilter`. The inert `JwtAuthenticationFilter`
  (servlet) also ships transitively but is guarded off by `@ConditionalOnClass(Filter.class)` —
  it never loads, since `jakarta.servlet-api` isn't on this service's runtime classpath.
- `spring-boot-starter-webflux` — the whole app's reactive foundation.
- `spring-boot-starter-data-redis-reactive` — the reactive Lettuce client backing
  `ReactiveRedisTemplate`.
- `spring-ai-starter-model-openai` — pom comment: *"OpenAI-compatible client, pointed at Groq's
  endpoint for chat/tool-calling"* — the same trick compatibility-service and question-service use
  to talk to Groq (which has no dedicated Spring AI starter of its own).
- `reactor-test` (test scope) — backs the `StepVerifier`-based tests for `SentenceBoundaryBuffer`.
- **No `spring-boot-starter-security` and no `spring-security-config`/`spring-security-webflux`
  starter anywhere on this module's own dependency graph.** Every Spring Security primitive it
  uses (`UsernamePasswordAuthenticationToken`, `ReactiveSecurityContextHolder`, `WebFilter`)
  arrives transitively through `common-security`'s own (non-`provided`) `spring-security-web`
  dependency — this service never chose a security starter itself.
- **No new dependency was needed for `QuestionServiceClient`.** It's a plain `WebClient` built the
  same way `WhisperClient`/`PiperClient` are, using `reactor-netty-http` (for the connect/response
  timeout wiring) that already arrives transitively via `spring-boot-starter-webflux`. voice-
  orchestrator and question-service are separate deployable services communicating over HTTP —
  there is no compile-time dependency between them, and `QuestionSetNotFoundException` here is
  this service's own local class, not question-service's internal one of the same idea.

---

## `application.yml` — every key

```yaml
spring:
  application:
    name: voice-orchestrator
  data:
    redis:
      host: localhost
      port: 6379
  ai:
    openai:
      base-url: https://api.groq.com/openai
      api-key: ${GROQ_API_KEY}
      chat:
        options:
          model: openai/gpt-oss-120b
          temperature: 0.2
server:
  port: 8085
whisper:
  base-url: http://localhost:8090
piper:
  base-url: http://localhost:8091
services:
  question-service:
    base-url: http://localhost:8084
```

- `spring.data.redis.host`/`port` — targets the `redis:7-alpine` container added by
  `docker-compose.yml` in the same commit (container name `callback-redis`, port-mapped
  `6379:6379`). No password configured — an unauthenticated local Redis, acceptable for local dev
  only.
- `spring.ai.openai.*` — same Groq-via-OpenAI-compatible-client pattern as every other Groq-backed
  service here: `api-key` from `${GROQ_API_KEY}` only (no literal fallback), model
  `openai/gpt-oss-120b`, low temperature (`0.2`) appropriate for a structured tool-call decision
  rather than creative text.
- `whisper.base-url` / `piper.base-url` — plain custom properties, injected via `@Value` into
  `WhisperClientConfig`/`PiperClientConfig`'s respective `WebClient` beans. These point at local
  server-mode instances of whisper.cpp and Piper — infrastructure entirely outside Spring AI's
  model abstractions.
- `services.question-service.base-url` — same `@Value`-into-`WebClient` pattern, injected into
  `QuestionServiceClient`. Port `8084` matches question-service's own `application.yml`.
- No `spring.data.redis.password`, no Redis SSL/timeout tuning, no actuator/management
  configuration — deliberately minimal, dev-focused config.

---

## Testing — what's verified, and the real gap

Only pure-logic, dependency-free classes are unit tested. Nothing integration-level exists: no
`@SpringBootTest`, no embedded/Testcontainers Redis, no WireMock stand-in for Whisper/Piper/Groq,
and no reactive WebSocket integration test of `VoiceWebSocketHandler` itself.

| Test class | Coverage |
|---|---|
| `UtteranceBufferTest` | Silence-only audio never flags end-of-utterance; sufficient speech + silence does; `flushAsWav` produces a valid WAV header and resets buffer state so a post-flush append doesn't immediately re-trigger |
| `VoiceActivityDetectorTest` | Zero-sample and empty-buffer chunks are silent; a strongly alternating chunk is not; boundary behavior around the RMS threshold |
| `WavEncoderTest` | Every header field verified byte-for-byte; PCM payload appended unchanged after the header |
| `SentenceBoundaryBufferTest` | Streaming deltas emit a sentence exactly on terminal punctuation; a never-terminated trailing fragment flushes on source completion; empty source produces nothing |
| `SentenceSplitterTest` | Splits on terminal punctuation; punctuation-less input stays one "sentence"; blank input → empty list |
| `InterviewSessionStateTest` | `"advance"` moves `currentQuestionIndex` forward; `"follow_up"` leaves it unchanged; repeated `"advance"` past the last question clamps rather than going out of bounds; an empty question list leaves the index at 0 |

No automated test exists for `TurnDecisionService`/`TurnDecisionRecorder` (would require mocking
`ChatClient`), `InterviewSessionRepository` (would need embedded Redis or a fake
`ReactiveRedisTemplate`), `WhisperClient`/`PiperClient`/`QuestionServiceClient` (would need
WireMock), the WebSocket handler itself (would need a reactive WebSocket test client), or either
JWT filter in common-security. That gap is still real — everything below was verified by hand,
once, against a real local stack, not by a repeatable test suite.

### Manually verified, end-to-end (real whisper.cpp, Piper, Redis, Groq — this session only)

| Scenario | Result |
|---|---|
| Multi-turn happy path: connect, several turns, transcript accuracy (via `redis-cli`), audible TTS | Pass |
| Missing / invalid / expired token on handshake → rejected | Pass (WS close code 1008 — see "Rejection close code" note below) |
| Valid token for a different user, same `sessionId` → rejected | Superseded — see the question-set-aware row below. The old client-supplied `sessionId` no longer exists as a concept at all (see "Session identity"). |
| **Question-service integration, full live pass (this session)**: real question set fetched at handshake; `"advance"` moves `currentQuestionIndex` through it with the exact next question's text asked each time; reaching the last prepared question turns an `"advance"`-equivalent decision into `"end"` with a real closing remark (not another question), clean WS close code 1000; exchange-count-based `"end"` (exchange 6) still fires on its own, independent of the question list, even mid-list; reconnecting with the same token + `questionSetId` after a full voice-orchestrator process kill+restart resumes the same Redis session (`turnCount`, `currentQuestionIndex`, `history` all intact); missing `questionSetId` → WS close 1008 "questionSetId required"; malformed (non-UUID) `questionSetId` → WS close 1008 "questionSetId must be a UUID"; a different user's token with someone else's real `questionSetId` → WS close 1008 "question set not found or not owned" | **Pass, all sub-scenarios** — see "Question-set-aware prompting" and "Reaching a clean end state" above for details. Required fixing two real bugs first — see "Two real bugs found and fixed by this live-testing pass" above — without which no question-set-backed session could be created at all. |
| Piper down → fallback error frame, turn not committed | Pass |
| whisper.cpp down → fallback error frame, turn not committed, no hang | Pass |
| Kill + restart voice-orchestrator mid-session, reconnect same `sessionId` | Pass — history and `turnCount` survived in Redis, next turn showed continuity |
| Two concurrent sessions (different users) | Pass — separate Redis keys, correct `ownerEmail` each, no cross-contamination |
| Short single-word utterance ("Yes") | Pass — VAD flushes correctly, one turn |
| Mid-sentence thinking pause (~1.2s) | **Failed before the `SILENCE_HOLD_MS` fix** — utterance split into two turns. Fixed and re-verified: 1200ms pause (under the new 1500ms threshold) → 1 turn, no split; 1800ms pause (over threshold) → 2 turns, split still occurs as intended |
| Real end-to-end turn latency (candidate stops talking → audio starts) | Pre-fix (`SILENCE_HOLD_MS=700`): median ~1.9s, range 1.8-2.4s (9 samples). Post-fix (`SILENCE_HOLD_MS=1500`): median ~3.1s, range 2.5-4.5s (6 samples) — confirms the ~800ms-plus latency cost of the pause fix is real |
| Clean interview end state | Was entirely missing (no `"end"` action existed); added and verified live across 3 sessions — model chose `"end"` on an explicit candidate cue each time, WebSocket closed with code 1000 (normal closure) each time. The later turn-count-in-prompt change (see "Reaching a clean end state") has **not yet** been re-verified live |

This table is a point-in-time record of one manual pass, not a substitute for the automated tests
this section still lacks — update or re-verify it the next time this pipeline is tested live.

---

## Things worth keeping in mind when changing this service

1. **Naming vs. reality**: the pom dependency and comments say "OpenAI," the config points at
   Groq, and the actual voice work (STT/TTS) is done by two local servers unrelated to either —
   don't assume "OpenAI" anywhere in this module means audio.
2. **No `SecurityConfig`, and none is needed** — the reactive `WebFilter` self-registers. Don't
   add a `SecurityWebFilterChain` here reflexively just because every servlet sibling has a
   `SecurityConfig`; the pattern genuinely differs for WebFlux.
3. **`ReactiveJwtAuthenticationFilter` has no consumer in this service today** — the actual
   authorization logic lives entirely in `VoiceWebSocketHandler`. If a future plain REST endpoint
   is added to this service, that's the point where the filter's Reactor-context identity would
   finally need to be read (via `ReactiveSecurityContextHolder.getContext()` in that endpoint's
   handling chain).
4. **Streaming TTS/turn-decision infrastructure exists but is dormant** — `SentenceBoundaryBuffer`
   and `speakStream()` are ready for when `TurnDecisionService` produces token-by-token output;
   the current code path is the non-streaming `speak()`/`SentenceSplitter` combination.
5. **`SessionHistoryPersister` is a placeholder** slated for deletion once `session-history-service`
   exists — don't build durability logic into it; build the real service instead.
6. **The interview end condition now has two independent paths, one prompt-driven and one
   code-enforced.** The model can choose `"end"` on its own judgment, guided by an explicit
   exchange number in the prompt (`MIN_EXCHANGES_BEFORE_END = 6`, `TARGET_MAX_EXCHANGES = 8` in
   `TurnDecisionService`) — that part is still prompt-level guidance the model could disregard.
   Separately, `InterviewSessionState.withTurn` clamps `currentQuestionIndex` at the last question
   regardless of what the model says, which is a hard, deterministic guarantee for "ran out of
   prepared questions" specifically. **Both paths re-verified live** since the question-service
   integration landed — see "Testing" above: the exchange-count path fired unprompted mid-list, and
   the question-list-exhaustion path correctly produced `"end"` once positioned on the last
   question.
7. **Session identity changed from a client-supplied id to `ownerEmail + "::" + questionSetId`.**
   There is no more `sessionId` query param — a WS connection now requires `questionSetId`
   instead, and question-service must be reachable during every handshake (see "Question-service
   integration"). Don't reintroduce a free-standing client-chosen `sessionId` without also
   reconciling how it'd interact with this identity scheme. **Live-verified**: missing/malformed
   `questionSetId` and a different owner's real `questionSetId` are all cleanly rejected (WS close
   1003) — see "Testing" above.
8. **`SILENCE_HOLD_MS` (1500ms) is a deliberately imperfect trade-off**, not a solved problem — see
   "Audio processing details" above. Don't casually lower it back toward 700ms without re-running
   the mid-thought-pause test that caught the original bug.

---

## Keeping this document current

When you change something structural — a new endpoint, a security fix, a new pipeline stage, or
if `session-history-service` lands and this service starts calling it — update the relevant
section above in the same commit/PR. Treat drift between this file and the code as a bug.
