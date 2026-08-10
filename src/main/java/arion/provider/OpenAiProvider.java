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
 * OpenAI and any OpenAI-compatible backend (Together, Groq, OpenRouter, a
 * self-hosted vLLM, etc.). Uses the player's own key — BYOK. The key is sent
 * only as a Bearer token to the configured base URL and is never logged.
 */
public final class OpenAiProvider implements AiProvider {

    private final String id;
    private final String displayName;
    private final String defaultBaseUrl;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** The canonical OpenAI provider. */
    public OpenAiProvider() {
        this("openai", "OpenAI", "https://api.openai.com");
    }

    /** An OpenAI-compatible provider with a custom default base URL. */
    public OpenAiProvider(String id, String displayName, String defaultBaseUrl) {
        this.id = id;
        this.displayName = displayName;
        this.defaultBaseUrl = defaultBaseUrl;
    }

    @Override public String id() { return id; }
    @Override public String displayName() { return displayName; }
    @Override public boolean requiresApiKey() { return true; }

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
                .header("Authorization", "Bearer " + req.apiKey())
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();

        return http.sendAsync(httpReq, HttpResponse.BodyHandlers.ofString())
                .thenApply(resp -> {
                    if (resp.statusCode() / 100 != 2) {
                        throw new RuntimeException(id + " HTTP " + resp.statusCode() + ": " + abbreviate(resp.body()));
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
        // cheapest authenticated probe: list models. Use a generous timeout — some relays
        // (self-hosted GLM proxies, vLLM, etc.) take several seconds even for /v1/models, which
        // would otherwise trip a tight timeout and report a misleading "couldn't reach".
        String url = normalize(req.baseUrl()) + "/v1/models";
        HttpRequest httpReq = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + req.apiKey())
                .GET()
                .build();
        return http.sendAsync(httpReq, HttpResponse.BodyHandlers.ofString())
                .thenApply(r -> {
                    boolean ok = r.statusCode() / 100 == 2;
                    if (!ok) {
                        arion.Arion.LOGGER.warn("[Arion] connection test to {} returned HTTP {} : {}",
                                url, r.statusCode(), abbreviate(r.body()));
                    }
                    return ok;
                })
                .exceptionally(t -> {
                    Throwable c = t.getCause() != null ? t.getCause() : t;
                    arion.Arion.LOGGER.warn("[Arion] connection test to {} failed: {}", url, c.toString());
                    return false;
                });
    }

    private String normalize(String base) {
        if (base == null || base.isBlank()) return defaultBaseUrl;
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    private static String abbreviate(String s) {
        return s == null ? "" : (s.length() <= 200 ? s : s.substring(0, 200) + "…");
    }
}
