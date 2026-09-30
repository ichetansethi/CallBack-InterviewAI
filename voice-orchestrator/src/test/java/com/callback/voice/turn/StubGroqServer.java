package com.callback.voice.turn;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A local stand-in for Groq's OpenAI-compatible /v1/chat/completions, replaying a scripted queue of
 * responses — for behaviour a real model can't be made to produce on demand (a 429, a tool call that
 * breaks the rules). Tool calls are answered with whatever argument names the request's tool schema
 * declares, as a real model does, and those names are recorded (they guard the parent pom's
 * -parameters flag: without it they degrade to arg0/arg1).
 */
final class StubGroqServer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpServer server;
    private final Queue<Response> script = new ConcurrentLinkedQueue<>();
    private final AtomicInteger requests = new AtomicInteger();
    private final List<String> toolArgNames = new CopyOnWriteArrayList<>();

    /** action/responseText are set only for a tool call; its arguments are built when served. */
    record Response(int status, String retryAfter, String body, String action, String responseText) {
        Response(int status, String retryAfter, String body) {
            this(status, retryAfter, body, null, null);
        }

        static Response rateLimited(String retryAfter) {
            return new Response(429, retryAfter, """
                    {"error":{"message":"Rate limit reached for model on tokens per minute (TPM)","type":"tokens","code":"rate_limit_exceeded"}}""");
        }

        static Response toolCall(String action, String responseText) {
            return new Response(200, null, """
                    {"id":"chatcmpl-1","object":"chat.completion","created":1,"model":"openai/gpt-oss-120b",
                     "choices":[{"index":0,"finish_reason":"tool_calls","message":{"role":"assistant","content":null,
                       "tool_calls":[{"id":"call_1","type":"function","function":{"name":"submitTurnDecision","arguments":ARGUMENTS}}]}}],
                     "usage":{"prompt_tokens":10,"completion_tokens":10,"total_tokens":20}}""", action, responseText);
        }

        /** The assistant message Spring AI requests after it has executed the tool. */
        static Response finalMessage() {
            return new Response(200, null, """
                    {"id":"chatcmpl-2","object":"chat.completion","created":1,"model":"openai/gpt-oss-120b",
                     "choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant","content":"recorded"}}],
                     "usage":{"prompt_tokens":10,"completion_tokens":1,"total_tokens":11}}""");
        }
    }

    StubGroqServer() {
        try {
            server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.createContext("/v1/chat/completions", exchange -> {
            requests.incrementAndGet();
            JsonNode request = MAPPER.readTree(exchange.getRequestBody().readAllBytes());
            Response next = script.poll();
            if (next == null) {
                next = new Response(500, null, "{\"error\":{\"message\":\"stub script exhausted\"}}");
            }
            String body = next.body();
            if (next.action() != null) {
                List<String> names = new ArrayList<>();
                request.at("/tools/0/function/parameters/properties").fieldNames().forEachRemaining(names::add);
                toolArgNames.clear();
                toolArgNames.addAll(names);
                // Tool arguments are a JSON document carried as a JSON string: serialize twice.
                String arguments = MAPPER.writeValueAsString(java.util.Map.of(
                        names.get(0), next.action(), names.get(1), next.responseText()));
                body = body.replace("ARGUMENTS", MAPPER.writeValueAsString(arguments));
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            if (next.retryAfter() != null) {
                exchange.getResponseHeaders().add("Retry-After", next.retryAfter());
            }
            exchange.sendResponseHeaders(next.status(), bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    String baseUrl() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    /** Queues a tool call plus the final message Spring AI asks for after executing it. */
    void thenDecision(String action, String responseText) {
        script.add(Response.toolCall(action, responseText));
        script.add(Response.finalMessage());
    }

    void then(Response response) {
        script.add(response);
    }

    void reset() {
        script.clear();
        requests.set(0);
        toolArgNames.clear();
    }

    int requests() {
        return requests.get();
    }

    List<String> toolArgNames() {
        return toolArgNames;
    }

    void stop() {
        server.stop(0);
    }
}
