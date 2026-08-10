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
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Voice front-end status for Arion, built on Simple Voice Chat (SVC).
 *
 * How it fits together:
 *  - The SVC plugin itself ({@link arion.voice.svc.SvcVoiceBridge}) is annotated
 *    {@code @ForgeVoicechatPlugin}; Simple Voice Chat discovers and instantiates
 *    it automatically when (and only when) SVC is installed. Arion never loads
 *    that class itself, so Arion still runs text-only without SVC.
 *  - This class is just a status probe + log line so the player knows whether
 *    voice is active.
 *  - STT/TTS are pluggable engines (Whisper/Groq/Deepgram/local). Arion does not
 *    bundle one; without an engine, voice degrades gracefully to text chat.
 */
public final class ArionVoice {

    private static final Logger LOGGER = LogManager.getLogger("Arion/Voice");

    private final ArionConfig config;

    public ArionVoice(ArionConfig config) {
        this.config = config;
    }

    /** True if Simple Voice Chat is on the classpath (and so voice can be active). */
    public static boolean isSvcPresent() {
        try {
            Class.forName("de.maxhenkel.voicechat.api.VoicechatPlugin");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /**
     * Speak a reply line through Simple Voice Chat, if SVC and a TTS engine are both
     * available. Safe to call unconditionally: a no-op when voice isn't set up, and it
     * never loads SVC classes when SVC is absent.
     */
    public static void speak(String text) {
        if (!isSvcPresent()) return;
        try {
            arion.voice.svc.SvcVoiceBridge.speak(text);
        } catch (Throwable ignored) {
            // voice playback is best-effort
        }
    }

    /** Log once at startup whether the voice front-end is live. */
    public void logStatus() {
        if (isSvcPresent()) {
            LOGGER.info("Simple Voice Chat detected — Arion voice front-end available " +
                    "(STT/TTS engine not bundled; voice input falls back to text until one is configured).");
        } else {
            LOGGER.info("Simple Voice Chat not found — Arion running text-only. " +
                    "Install Simple Voice Chat to enable the mic front-end.");
        }
    }
}
