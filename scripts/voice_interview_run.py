#!/usr/bin/env python3
"""Scripted spoken interview through the gateway (ws://localhost:8080/voice/session).

Usage: scripts/.venv/bin/python scripts/voice_interview_run.py strong|vague|fail

Registers a throwaway user, inserts a 7-question set owned by it straight into callback_question
(no Groq spend), speaks each answer with macOS `say` at 16 kHz, and after every exchange reads the
committed turn from Redis. Prints one line per exchange and checks the expectations for the mode:

  strong: all 7 questions get asked and the interview ends after the last one.
  vague:  every question gets exactly two follow-ups and then moves on; ends at exchange 22.
  fail:   one turn only, for a broken model key: expects the fallback error frame, the socket still
          open and the Redis session still there (nothing committed).

Cleans up its user, question set, Redis key and stored session at the end.
"""
import asyncio
import json
import subprocess
import sys
import tempfile
import time
import urllib.request
import uuid
from pathlib import Path

import websockets

GATEWAY = "http://localhost:8080"
WS = "ws://localhost:8080/voice/session"
CHUNK_BYTES = 3200  # 100 ms of 16 kHz 16-bit mono
SILENCE = b"\x00" * 16000 * 2 * 3  # 3 s, above the 1.5 s hold
REPLY_QUIET_S = 6.0
TURN_TIMEOUT_S = 90.0
RATE_LIMIT_WAIT_S = 30.0
MAX_EXCHANGES = 30

QUESTIONS = [
    ("technical", "How do you decide how many partitions a Kafka topic needs?"),
    ("technical", "How would you tune a slow PostgreSQL query?"),
    ("technical", "How do you version a public REST API?"),
    ("behavioral", "Tell me about an on-call incident you handled."),
    ("behavioral", "Describe a disagreement with a product manager."),
    ("role-specific", "How would you design a new payments microservice?"),
    ("role-specific", "Which alerts would you set up for a Spring Boot service?"),
]

# One strong answer per question, and an elaboration to use if the model still asks a follow-up.
STRONG = [
    ("I size partitions from target throughput divided by per-consumer throughput. For a topic at 50 megabytes a "
     "second with consumers doing 10 each, that is five, and I double it for headroom because adding partitions "
     "later breaks key ordering.",
     "To add to that, I also keep the total under the broker limits, since each partition costs memory and "
     "slows leader election, and I load test with realistic keys."),
    ("I start with EXPLAIN ANALYZE to find where the time goes. Last quarter a report did a sequential scan over "
     "40 million rows; a composite index on account id and created at cut it from 12 seconds to 80 milliseconds, "
     "at the cost of slower writes.",
     "To add, I check row estimates against actuals and run ANALYZE when statistics are stale, then confirm "
     "with a before and after benchmark."),
    ("I put the version in the URL path, like v1 and v2, because it is visible and easy to route. I only bump the "
     "major version for breaking changes, and I keep the old one for six months with a deprecation header.",
     "To add, additive changes like new optional fields never need a new version, and contract tests guard "
     "the old version."),
    ("Our payment queue backed up at 2 a.m. because a consumer was stuck on a poison message. I paged the team, "
     "moved the message to a dead letter topic, and restarted the consumer, which cleared 90 thousand messages in "
     "ten minutes. Afterwards we added a retry limit and an alert on consumer lag.",
     "To add, the postmortem found we had no dead letter topic at all, so that became the follow-up action."),
    ("A product manager wanted to ship a feature without the idempotency checks. I showed the double-charge risk "
     "with numbers from our logs, proposed shipping behind a flag with the checks, and we agreed on a one week "
     "delay. It shipped cleanly.",
     "To add, I made sure we agreed on the tradeoff in writing, so there was no surprise later."),
    ("I would split it into an orders API and a ledger service, with the ledger as the source of truth and an "
     "idempotency key on every request. Payments would be asynchronous through Kafka, using the outbox pattern so "
     "events are never lost.",
     "To add, I would use a reconciliation job against the provider every night, and keep card data out of our "
     "systems entirely."),
    ("I would alert on p99 latency, error rate, consumer lag and connection pool saturation, each with a runbook "
     "link. I page only on symptoms users feel, and send the rest to a ticket queue.",
     "To add, I would also alert on JVM heap after garbage collection, because a rising floor shows a leak early."),
]

VAGUE = [
    "I would just try to do my best and follow best practices, it really depends on the situation.",
    "Honestly I would look into it and see what makes sense, there are a lot of factors involved.",
    "I think it is mostly about being careful and making sure everything is done properly.",
    "Well, you know, I would use my experience and figure it out as I go along.",
    "I would keep it simple and do what is generally recommended in cases like this.",
]


def http(method, path, body=None, token=None):
    req = urllib.request.Request(GATEWAY + path, method=method, data=json.dumps(body).encode() if body else None)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.loads(r.read() or b"{}")


def sh(*args, input_text=None):
    return subprocess.run(args, check=True, capture_output=True, text=True, input=input_text).stdout


def psql(db, sql):
    return sh("docker", "exec", "-i", "callback-postgres", "psql", "-U", "callback_admin", "-d", db, "-v",
              "ON_ERROR_STOP=1", "-q", input_text=sql)


def speak_pcm(text, workdir):
    aiff, pcm = Path(workdir) / "u.aiff", Path(workdir) / "u.pcm"
    sh("say", "-v", "Samantha", "-o", str(aiff), text)
    sh("ffmpeg", "-y", "-loglevel", "error", "-i", str(aiff), "-ar", "16000", "-ac", "1", "-f", "s16le", str(pcm))
    return pcm.read_bytes()


def redis_state(key):
    raw = sh("docker", "exec", "callback-redis", "redis-cli", "GET", key).strip()
    return json.loads(raw) if raw else None


async def exchange(ws, pcm):
    """Send one utterance plus trailing silence; return (audio frames, error text or None)."""
    audio = pcm + SILENCE
    for i in range(0, len(audio), CHUNK_BYTES):
        await ws.send(audio[i:i + CHUNK_BYTES])
        await asyncio.sleep(0.005)
    frames, error, last = 0, None, None
    start = time.monotonic()
    while time.monotonic() - start < TURN_TIMEOUT_S:
        wait = REPLY_QUIET_S if last else TURN_TIMEOUT_S - (time.monotonic() - start)
        try:
            msg = await asyncio.wait_for(ws.recv(), timeout=wait)
        except asyncio.TimeoutError:
            break
        if isinstance(msg, bytes):
            frames += 1
            last = time.monotonic()
        else:
            data = json.loads(msg)
            if data.get("type") == "error":
                return frames, data.get("message")
    return frames, error


async def run(mode):
    suffix = uuid.uuid4().hex[:8]
    email, password = f"harness-{mode}-{suffix}@example.com", "harness-pass-123"
    qset = str(uuid.uuid4())
    http("POST", "/auth/register", {"email": email, "password": password})
    token = http("POST", "/auth/login", {"email": email, "password": password})["token"]
    rows = "".join(
        f"INSERT INTO interview_question (id, category, order_index, question_text, rationale, question_set_id) "
        f"VALUES ('{uuid.uuid4()}', '{c}', {i}, '{q}', 'harness', '{qset}');\n" for i, (c, q) in enumerate(QUESTIONS))
    psql("callback_question", f"INSERT INTO question_set (id, created_at, owner_email) "
                              f"VALUES ('{qset}', now(), '{email}');\n{rows}")
    key = f"voice:session:{email}::{qset}"
    workdir = tempfile.mkdtemp(prefix="voice-harness-")
    log, errors, closed = [], 0, None
    try:
        if mode == "fail":
            return await run_fail(token, qset, key, workdir)
        async with websockets.connect(f"{WS}?token={token}&questionSetId={qset}", max_size=8 * 1024 * 1024) as ws:
            print("ack:", await asyncio.wait_for(ws.recv(), 10))
            vague_i, last_idx, ups = 0, 0, 0
            for n in range(1, MAX_EXCHANGES + 1):
                if n == 1:
                    text = "Hi, I'm ready to start."
                elif mode == "strong":
                    a, b = STRONG[last_idx]
                    text = b if ups else a
                else:
                    text = VAGUE[vague_i % len(VAGUE)]
                    vague_i += 1
                pcm = speak_pcm(text, workdir)
                while True:
                    try:
                        frames, err = await exchange(ws, pcm)
                    except websockets.ConnectionClosed as e:
                        closed = e.rcvd.code if e.rcvd else None
                        frames, err = 0, None
                        break
                    if err is None:
                        break
                    errors += 1
                    print(f"  [{n}] error frame ({err!r}); waiting {RATE_LIMIT_WAIT_S:.0f}s and resending")
                    await asyncio.sleep(RATE_LIMIT_WAIT_S)
                state = redis_state(key)
                if closed is not None or state is None or len(state["history"]) < n:
                    closed = closed if closed is not None else "gone"
                    log.append((n, last_idx + 1, "end", "(session closed; interview ended)"))
                    print(f"{n:>3} Q{last_idx + 1} end        socket closed ({closed}), frames={frames}")
                    break
                turn = state["history"][-1]
                last_idx, ups = state["currentQuestionIndex"], state["followUpCountForCurrentQuestion"]
                log.append((n, last_idx + 1, turn["action"], turn["responseText"]))
                print(f"{n:>3} Q{last_idx + 1} {turn['action']:<9} {turn['responseText'][:100]!r}")
            else:
                print("hit MAX_EXCHANGES without the session ending")
        return report(mode, log, errors, closed)
    finally:
        cleanup(email, qset, key)


async def run_fail(token, qset, key, workdir):
    ok = True

    def check(name, cond):
        nonlocal ok
        ok &= bool(cond)
        print(("PASS " if cond else "FAIL ") + name)

    async with websockets.connect(f"{WS}?token={token}&questionSetId={qset}", max_size=8 * 1024 * 1024) as ws:
        print("ack:", await asyncio.wait_for(ws.recv(), 10))
        start = time.monotonic()
        frames, err = await exchange(ws, speak_pcm("Hi, I'm ready to start.", workdir))
        print(f"reply after {time.monotonic() - start:.1f}s: audio frames={frames}, error frame={err!r}")
        check("candidate got the fallback message", err is not None and "repeat" in err.lower())
        check("no audio was sent", frames == 0)
        check("socket still open", ws.state.name == "OPEN")
        state = redis_state(key)
        check("session still in Redis", state is not None)
        check("no turn committed", state is not None and state["turnCount"] == 0)
        # A second utterance on the same socket still gets an answer (the fallback again), not a dead pipeline.
        frames, err = await exchange(ws, speak_pcm("Hello, are you there?", workdir))
        check("pipeline still alive for a second utterance (fallback again)", err is not None)
    return ok


def report(mode, log, errors, closed):
    ok = True

    def check(name, cond):
        nonlocal ok
        ok &= bool(cond)
        print(("PASS " if cond else "FAIL ") + name)

    actions = [a for _, _, a, _ in log]
    ended_at = len(log) if actions and actions[-1] == "end" else None
    check("interview ended by the model (action end, socket closed)", ended_at is not None)
    check("no end before the last question", all(a != "end" for _, q, a, _ in log[:-1]))
    if mode == "strong":
        asked = {q for _, q, _, _ in log}
        check(f"all seven questions asked (reached {sorted(asked)})", asked >= set(range(1, 8)))
        check("ended on the last question", log and log[-1][1] == 7)
    else:
        per_q = {}
        for _, q, a, _ in log:
            per_q.setdefault(q, []).append(a)
        # The log holds the state after each turn, so a question's run starts with the advance that
        # reached it (the opener for Q1) and then its follow-ups; Q7 also ends the interview.
        for q in range(1, 8):
            want = ["advance", "follow_up", "follow_up"] + (["end"] if q == 7 else [])
            check(f"Q{q}: {want} (got {per_q.get(q, [])})", per_q.get(q, []) == want)
        check(f"ended at exchange 22 (ended at {ended_at})", ended_at == 22)
    print(f"error frames (rate limits etc.): {errors}")
    return ok


def cleanup(email, qset, key):
    sh("docker", "exec", "callback-redis", "redis-cli", "DEL", key)
    for db, sql in [
        ("callback_question", f"DELETE FROM interview_question WHERE question_set_id='{qset}';"
                              f"DELETE FROM question_set WHERE id='{qset}';"),
        ("callback_session", f"DELETE FROM question_feedback WHERE summary_id IN (SELECT id FROM feedback_summary WHERE session_id IN (SELECT id FROM interview_session WHERE owner_email='{email}'));"
                             f"DELETE FROM feedback_summary WHERE session_id IN (SELECT id FROM interview_session WHERE owner_email='{email}');"
                             f"DELETE FROM transcript_turn WHERE session_id IN (SELECT id FROM interview_session WHERE owner_email='{email}');"
                             f"DELETE FROM interview_session WHERE owner_email='{email}';"),
        ("callback_auth", f"DELETE FROM users WHERE email='{email}';"),
    ]:
        try:
            psql(db, sql)
        except subprocess.CalledProcessError as e:
            print(f"cleanup of {db} failed (harmless, test rows left): {e.stderr.strip()[:200]}")


if __name__ == "__main__":
    if len(sys.argv) != 2 or sys.argv[1] not in ("strong", "vague", "fail"):
        sys.exit(__doc__)
    sys.exit(0 if asyncio.run(run(sys.argv[1])) else 1)
