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
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Speech-to-text: turns a captured utterance (16kHz mono PCM shorts from Simple
 * Voice Chat) into text via an OpenAI-compatible Whisper endpoint
 * ({@code POST /v1/audio/transcriptions}). Works with OpenAI and Groq.
 *
 * Runs off the client thread; the key is the player's own (BYOK), sent only to
 * the configured provider and never logged.
 */
public final class SttEngine {

    private final ArionConfig config;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public SttEngine(ArionConfig config) {
        this.config = config;
    }

    /** True if an STT engine is configured enough to attempt a transcription. */
    public boolean available() {
        return config.voiceEnabled.get() && !config.sttApiKey.get().isBlank();
    }

    /** Transcribe a PCM utterance. Returns empty on any failure (caller falls back). */
    public CompletableFuture<String> transcribe(short[] pcm) {
        if (!available()) return CompletableFuture.completedFuture("");
        try {
            byte[] wav = toWav(pcm, 16000);
            return postTranscription(wav);
        } catch (Exception e) {
            return CompletableFuture.completedFuture("");
        }
    }

    private CompletableFuture<String> postTranscription(byte[] wav) {
        String base = config.sttBaseUrl.get().isBlank()
                ? defaultBase(config.sttProvider.get())
                : trimSlash(config.sttBaseUrl.get());
        String url = base + "/v1/audio/transcriptions";

        String boundary = "----arion" + UUID.randomUUID().toString().replace("-", "");
        byte[] body;
        try {
            body = buildMultipart(boundary, config.sttModel.get(), wav);
        } catch (IOException e) {
            return CompletableFuture.completedFuture("");
        }

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("Authorization", "Bearer " + config.sttApiKey.get())
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();

        return http.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenApply(resp -> {
                    if (resp.statusCode() / 100 != 2) return "";
                    try {
                        return JsonParser.parseString(resp.body())
                                .getAsJsonObject().get("text").getAsString().trim();
                    } catch (Exception e) {
                        return "";
                    }
                })
                .exceptionally(t -> "");
    }

    private static String defaultBase(String provider) {
        return switch (provider.toLowerCase()) {
            case "groq" -> "https://api.groq.com/openai";
            case "openai" -> "https://api.openai.com";
            default -> "https://api.openai.com";
        };
    }

    private static String trimSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    /** Minimal multipart/form-data body with a `model` field and a `file` (WAV). */
    private static byte[] buildMultipart(String boundary, String model, byte[] wav) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // model field
        out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Disposition: form-data; name=\"model\"\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write((model + "\r\n").getBytes(StandardCharsets.UTF_8));
        // file field
        out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Disposition: form-data; name=\"file\"; filename=\"audio.wav\"\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Type: audio/wav\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(wav);
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
        out.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    /** Wrap 16-bit mono PCM in a minimal WAV header so Whisper accepts it. */
    static byte[] toWav(short[] pcm, int sampleRate) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int dataLen = pcm.length * 2;
        // RIFF header
        out.write("RIFF".getBytes(StandardCharsets.US_ASCII));
        writeIntLE(out, 36 + dataLen);
        out.write("WAVE".getBytes(StandardCharsets.US_ASCII));
        // fmt chunk
        out.write("fmt ".getBytes(StandardCharsets.US_ASCII));
        writeIntLE(out, 16);            // PCM chunk size
        writeShortLE(out, (short) 1);   // PCM format
        writeShortLE(out, (short) 1);   // mono
        writeIntLE(out, sampleRate);
        writeIntLE(out, sampleRate * 2);// byte rate (16-bit mono)
        writeShortLE(out, (short) 2);   // block align
        writeShortLE(out, (short) 16);  // bits per sample
        // data chunk
        out.write("data".getBytes(StandardCharsets.US_ASCII));
        writeIntLE(out, dataLen);
        for (short s : pcm) writeShortLE(out, s);
        return out.toByteArray();
    }

    private static void writeIntLE(ByteArrayOutputStream out, int v) {
        out.write(v & 0xFF);
        out.write((v >> 8) & 0xFF);
        out.write((v >> 16) & 0xFF);
        out.write((v >> 24) & 0xFF);
    }

    private static void writeShortLE(ByteArrayOutputStream out, short v) {
        out.write(v & 0xFF);
        out.write((v >> 8) & 0xFF);
    }

    /**
     * Slice raw mic frames into utterances using simple energy-based voice-activity
     * detection: accumulate frames while there's sound, cut after a run of silence.
     */
    public static final class Vad {
        private static final int SAMPLE_RATE = 16000;
        private static final double ENERGY_THRESHOLD = 500.0;   // mean |sample| to count as speech
        private static final int SILENCE_FRAMES_TO_END = 12;    // ~240ms of silence ends an utterance
        private static final int MAX_UTTERANCE_FRAMES = SAMPLE_RATE / 960 * 20; // ~20s cap

        private final ArrayList<Short> buffer = new ArrayList<>();
        private int silenceFrames = 0;
        private boolean speaking = false;

        /** Feed one mic frame. Returns a complete utterance when VAD detects the end, else null. */
        public short[] feed(short[] frame) {
            double energy = meanAbs(frame);
            boolean voiced = energy > ENERGY_THRESHOLD;

            if (voiced) {
                speaking = true;
                silenceFrames = 0;
                for (short s : frame) buffer.add(s);
            } else if (speaking) {
                for (short s : frame) buffer.add(s);
                silenceFrames++;
            }

            boolean end = speaking && (silenceFrames >= SILENCE_FRAMES_TO_END
                    || buffer.size() >= MAX_UTTERANCE_FRAMES * frame.length);
            if (end) {
                short[] utterance = drain();
                return utterance.length > SAMPLE_RATE / 4 ? utterance : null; // ignore <0.25s blips
            }
            return null;
        }

        private short[] drain() {
            short[] out = new short[buffer.size()];
            for (int i = 0; i < out.length; i++) out[i] = buffer.get(i);
            buffer.clear();
            speaking = false;
            silenceFrames = 0;
            return out;
        }

        private static double meanAbs(short[] frame) {
            if (frame.length == 0) return 0;
            long sum = 0;
            for (short s : frame) sum += Math.abs(s);
            return (double) sum / frame.length;
        }
    }
}
