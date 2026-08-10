/*
 * Arion — an AI companion mod that REQUIRES Baritone.
 *
 * Arion is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package arion.voice;

import arion.config.ArionConfig;
import com.google.gson.JsonObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Text-to-speech: turns the LLM's reply text into 16-bit mono PCM via an
 * OpenAI-compatible speech endpoint ({@code POST /v1/audio/speech}, PCM format),
 * ready to feed straight into a Simple Voice Chat audio channel.
 *
 * Runs off the client thread; the key is the player's own (BYOK), sent only to
 * the configured provider and never logged.
 */
public final class TtsEngine {

    private final ArionConfig config;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public TtsEngine(ArionConfig config) {
        this.config = config;
    }

    /** True if a TTS engine is configured enough to attempt synthesis. */
    public boolean available() {
        return config.voiceEnabled.get() && !config.ttsApiKey.get().isBlank();
    }

    /**
     * Synthesize text into 24kHz 16-bit mono PCM shorts (OpenAI `pcm` response
     * format), or an empty array on any failure (caller falls back to text).
     */
    public CompletableFuture<short[]> synthesize(String text) {
        if (!available() || text == null || text.isBlank()) {
            return CompletableFuture.completedFuture(new short[0]);
        }
        String base = config.ttsBaseUrl.get().isBlank()
                ? "https://api.openai.com"
                : trimSlash(config.ttsBaseUrl.get());
        String url = base + "/v1/audio/speech";

        JsonObject body = new JsonObject();
        body.addProperty("model", config.ttsModel.get());
        body.addProperty("voice", config.ttsVoice.get());
        body.addProperty("input", text);
        body.addProperty("response_format", "pcm"); // raw 24kHz 16-bit mono LE

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + config.ttsApiKey.get())
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();

        return http.sendAsync(req, HttpResponse.BodyHandlers.ofByteArray())
                .thenApply(resp -> {
                    if (resp.statusCode() / 100 != 2) return new short[0];
                    return bytesToShorts(resp.body());
                })
                .exceptionally(t -> new short[0]);
    }

    /** 24kHz → 16kHz downsample so it matches SVC's playback rate. */
    public static short[] downsample24to16(short[] in) {
        // 24000 → 16000 = keep 2 of every 3 samples
        int outLen = (int) ((long) in.length * 16000 / 24000);
        short[] out = new short[outLen];
        for (int i = 0; i < outLen; i++) {
            out[i] = in[(int) ((long) i * 24000 / 16000)];
        }
        return out;
    }

    static short[] bytesToShorts(byte[] bytes) {
        int n = bytes.length / 2;
        short[] out = new short[n];
        for (int i = 0; i < n; i++) {
            out[i] = (short) ((bytes[i * 2] & 0xFF) | (bytes[i * 2 + 1] << 8));
        }
        return out;
    }

    private static String trimSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
