/*
 * Arion — an AI companion mod that REQUIRES Baritone.
 *
 * Arion is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package arion.provider;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Local Ollama backend. No API key required; talks to the OpenAI-compatible
 * /v1/chat/completions endpoint Ollama exposes on localhost:11434.
 */
public final class OllamaProvider implements AiProvider {

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Override public String id() { return "ollama"; }
    @Override public String displayName() { return "Ollama (local)"; }
    @Override public boolean requiresApiKey() { return false; }

    @Override
    public CompletableFuture<String> complete(AiRequest req) {
        String url = normalize(req.baseUrl()) + "/v1/chat/completions";

        JsonObject body = new JsonObject();
        body.addProperty("model", req.model());
        body.addProperty("stream", false);
        JsonArray msgs = new JsonArray();
        for (Message m : req.messages()) {
            JsonObject o = new JsonObject();
            o.addProperty("role", m.role());
            o.addProperty("content", m.content());
            msgs.add(o);
        }
        body.add("messages", msgs);

        HttpRequest httpReq = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(req.timeoutSeconds()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();

        return http.sendAsync(httpReq, HttpResponse.BodyHandlers.ofString())
                .thenApply(resp -> {
                    if (resp.statusCode() / 100 != 2) {
                        throw new RuntimeException("Ollama HTTP " + resp.statusCode() + ": " + abbreviate(resp.body()));
                    }
                    JsonObject json = JsonParser.parseString(resp.body()).getAsJsonObject();
                    return json.getAsJsonArray("choices")
                            .get(0).getAsJsonObject()
                            .getAsJsonObject("message")
                            .get("content").getAsString();
                });
    }

    @Override
    public CompletableFuture<Boolean> testConnection(AiRequest req) {
        String url = normalize(req.baseUrl()) + "/api/tags";
        HttpRequest httpReq = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        return http.sendAsync(httpReq, HttpResponse.BodyHandlers.ofString())
                .thenApply(r -> r.statusCode() / 100 == 2)
                .exceptionally(t -> false);
    }

    private static String normalize(String base) {
        if (base == null || base.isBlank()) return "http://localhost:11434";
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    private static String abbreviate(String s) {
        return s == null ? "" : (s.length() <= 200 ? s : s.substring(0, 200) + "…");
    }
}
