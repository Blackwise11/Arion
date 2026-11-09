/*
 * Arion — an AI companion mod that REQUIRES Baritone.
 *
 * Arion is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package arion.laya;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Async client for the local Laya System 1 decision engine
 * ({@code laya_http_server.py}, default {@code http://127.0.0.1:8765}).
 *
 * <p>Contract (see the server and the Laya MCP tool):
 * <pre>
 *   POST /decide {"state": "...", "questions": {id: {type, instructions, criteria}}}
 *     -> {"answers": {id: {...}}, "usage": {...}, "latency_ms": 31.2}
 *   GET  /health
 *     -> {"ok": true, "model": "...", "cuda": true, "device": "..."}
 * </pre>
 *
 * <p><b>Threading:</b> every method returns immediately with a
 * {@link CompletableFuture}; the HTTP exchange runs on dedicated daemon threads.
 * Network calls must never run on the Minecraft client/server tick thread.
 * Callers are responsible for marshalling results back onto the client thread
 * and for terminating chains with {@code .exceptionally(...)}.
 *
 * <p>No API key is involved: the model runs locally.
 */
public final class LayaClient {

    /** One typed question: {@code choice} (criteria map), {@code score} (ordered list), or {@code noul} (boolean). */
    public record Question(String type, String instructions, JsonElement criteria) {
        public static Question choice(String instructions, Map<String, String> criteria) {
            JsonObject o = new JsonObject();
            criteria.forEach(o::addProperty);
            return new Question("choice", instructions, o);
        }

        public static Question score(String instructions, List<String> criteria) {
            JsonArray a = new JsonArray();
            criteria.forEach(a::add);
            return new Question("score", instructions, a);
        }

        public static Question noul(String instructions) {
            return new Question("noul", instructions, null);
        }
    }

    /** One typed answer. Unused fields stay null; {@link #raw} carries the server's full object. */
    public record Answer(String id, String type, String choice, Integer score, Double noul,
                         double confidence, JsonObject raw) {

        /** Case-insensitive match against a choice answer. */
        public boolean is(String candidate) {
            return candidate != null && choice != null && choice.equalsIgnoreCase(candidate);
        }

        /** Probability-style check for a noul (boolean) answer. */
        public boolean atLeast(double probability) {
            return noul != null && noul >= probability;
        }

        /** "combat (0.91)" / "score=2 (0.74)" / "yes (0.83)" — for debug lines. */
        public String describe() {
            if ("choice".equalsIgnoreCase(type)) {
                return choice + " (" + fmt(confidence) + ")";
            }
            if ("score".equalsIgnoreCase(type)) {
                return "score=" + (score == null ? "?" : score) + " (" + fmt(confidence) + ")";
            }
            double p = noul == null ? 0.0 : noul;
            return (p >= 0.5 ? "yes" : "no") + " p=" + fmt(p);
        }

        private static String fmt(double d) {
            return String.format(Locale.ROOT, "%.2f", d);
        }
    }

    /** A completed decision: typed answers plus server-side and wall-clock latency. */
    public record Result(Map<String, Answer> answers, double latencyMs, long roundTripMs) {
        public Answer get(String id) {
            return answers.get(id);
        }

        /** Debug summary, e.g. {@code action=combat (0.91) threat=2 (0.60) replan=no p=0.10}. */
        public String summarize() {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, Answer> e : answers.entrySet()) {
                if (!sb.isEmpty()) sb.append(' ');
                sb.append(e.getKey()).append('=').append(e.getValue().describe());
            }
            return sb.toString();
        }
    }

    /** {@code GET /health} result. */
    public record Health(boolean ok, String model, boolean cuda, String device) {
        public String describe() {
            return (ok ? "ready" : "model not loaded")
                    + (cuda ? " on " + device : " on CPU")
                    + (model == null || model.isBlank() ? "" : " (" + model + ")");
        }
    }

    private static final Gson GSON = new Gson();

    private final String baseUrl;
    private final Duration requestTimeout;
    private final HttpClient http;
    private final ExecutorService executor;

    public LayaClient(String baseUrl, int timeoutSeconds) {
        this.baseUrl = normalizeBase(baseUrl);
        this.requestTimeout = Duration.ofSeconds(Math.max(1, timeoutSeconds));
        this.executor = Executors.newFixedThreadPool(2, daemonFactory());
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .executor(executor)
                .build();
    }

    public String baseUrl() {
        return baseUrl;
    }

    /**
     * Ask Laya one multi-question decision. Never blocks; the returned future completes on a
     * Laya worker thread (callers must hop back to the client thread).
     *
     * @param state     the situation text (sub-goal + perception + vessel status)
     * @param questions ordered question id → definition
     */
    public CompletableFuture<Result> decide(String state, Map<String, Question> questions) {
        JsonObject body = new JsonObject();
        body.addProperty("state", state == null ? "" : state);
        JsonObject qs = new JsonObject();
        for (Map.Entry<String, Question> e : questions.entrySet()) {
            Question q = e.getValue();
            JsonObject qo = new JsonObject();
            qo.addProperty("type", q.type());
            qo.addProperty("instructions", q.instructions());
            if (q.criteria() != null) qo.add("criteria", q.criteria());
            qs.add(e.getKey(), qo);
        }
        body.add("questions", qs);

        long started = System.currentTimeMillis();
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/decide"))
                .timeout(requestTimeout)
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)))
                .build();

        return http.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenApply(resp -> {
                    long rtt = System.currentTimeMillis() - started;
                    if (resp.statusCode() != 200) {
                        throw new IllegalStateException("Laya HTTP " + resp.statusCode() + ": " + brief(resp.body()));
                    }
                    return parseResult(resp.body(), rtt);
                });
    }

    /** Ping the local server (and report whether the model finished loading). */
    public CompletableFuture<Health> health() {
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/health"))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        return http.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenApply(resp -> {
                    if (resp.statusCode() != 200) {
                        throw new IllegalStateException("Laya /health HTTP " + resp.statusCode());
                    }
                    JsonObject o = JsonParser.parseString(resp.body()).getAsJsonObject();
                    return new Health(
                            bool(o, "ok"),
                            str(o, "model"),
                            bool(o, "cuda"),
                            str(o, "device"));
                });
    }

    /** Release the worker threads (safe to call once; a new client starts fresh threads). */
    public void close() {
        executor.shutdownNow();
    }

    // ------------------------------------------------------------------ parsing

    private static Result parseResult(String json, long rtt) {
        JsonObject root;
        try {
            root = JsonParser.parseString(json).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IllegalStateException("Laya returned invalid JSON: " + brief(json), e);
        }
        Map<String, Answer> answers = new LinkedHashMap<>();
        JsonElement answersEl = root.get("answers");
        if (answersEl != null && answersEl.isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : answersEl.getAsJsonObject().entrySet()) {
                if (!e.getValue().isJsonObject()) continue;
                JsonObject a = e.getValue().getAsJsonObject();
                answers.put(e.getKey(), new Answer(
                        e.getKey(),
                        str(a, "type"),
                        str(a, "choice"),
                        a.has("score") && a.get("score").isJsonPrimitive()
                                ? (int) Math.round(a.get("score").getAsDouble()) : null,
                        a.has("noul") && a.get("noul").isJsonPrimitive()
                                ? a.get("noul").getAsDouble() : null,
                        a.has("confidence") && a.get("confidence").isJsonPrimitive()
                                ? a.get("confidence").getAsDouble() : 0.0,
                        a));
            }
        }
        double latency = root.has("latency_ms") && root.get("latency_ms").isJsonPrimitive()
                ? root.get("latency_ms").getAsDouble() : -1.0;
        // preserve server order so debug summaries are stable
        return new Result(java.util.Collections.unmodifiableMap(new LinkedHashMap<>(answers)), latency, rtt);
    }

    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }

    private static boolean bool(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsBoolean();
    }

    private static String brief(String s) {
        if (s == null) return "";
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() <= 200 ? t : t.substring(0, 200) + "…";
    }

    private static String normalizeBase(String url) {
        String u = (url == null || url.isBlank()) ? "http://127.0.0.1:8765" : url.trim();
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        return u;
    }

    private static ThreadFactory daemonFactory() {
        AtomicInteger n = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, "arion-laya-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }
}
