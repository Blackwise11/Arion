/*
 * Arion — an AI companion mod that REQUIRES Baritone.
 *
 * Arion is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package arion.voice.svc;

import arion.config.ArionConfig;
import arion.voice.SttEngine;
import arion.voice.TtsEngine;
import de.maxhenkel.voicechat.api.ForgeVoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatClientApi;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.audiochannel.ClientStaticAudioChannel;
import de.maxhenkel.voicechat.api.events.ClientSoundEvent;
import de.maxhenkel.voicechat.api.events.ClientVoicechatConnectionEvent;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import net.minecraft.client.Minecraft;

import java.util.UUID;

/**
 * Arion's Simple Voice Chat plugin — the voice front-end. Only ever loaded by SVC
 * itself (via the {@code @ForgeVoicechatPlugin} annotation) when SVC is installed, so
 * this class may reference SVC types directly.
 *
 * Two flows, both into/out of the SAME command path as typed chat:
 *  - STT: our mic frames ({@link ClientSoundEvent#getRawAudio}) → VAD utterance slice
 *    → {@link SttEngine} → text → {@code Arion.controller().onPlayerCommand(...)}.
 *  - TTS: {@link #speak} synthesizes reply text via {@link TtsEngine} → PCM → a
 *    static audio channel that plays back to the local player.
 *
 * If no STT/TTS key is configured, both flows no-op and Arion stays in text mode
 * (the graceful fallback the plan requires).
 */
@ForgeVoicechatPlugin
public final class SvcVoiceBridge implements VoicechatPlugin {

    private static SvcVoiceBridge instance;

    private final ArionConfig config;
    private final SttEngine stt;
    private final TtsEngine tts;
    private final SttEngine.Vad vad = new SttEngine.Vad();

    private VoicechatClientApi clientApi;
    private ClientStaticAudioChannel speakChannel;
    private boolean sttBusy = false;

    /** SVC instantiates plugins via a no-arg constructor when it discovers the annotation. */
    public SvcVoiceBridge() {
        this.config = arion.Arion.config();
        this.stt = new SttEngine(config);
        this.tts = new TtsEngine(config);
        instance = this;
    }

    @Override
    public String getPluginId() {
        return "arion";
    }

    @Override
    public void initialize(VoicechatApi api) {
        // client API arrives via the connection event; nothing to do here
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(ClientVoicechatConnectionEvent.class, this::onConnection);
        registration.registerEvent(ClientSoundEvent.class, this::onMic);
    }

    private void onConnection(ClientVoicechatConnectionEvent event) {
        clientApi = event.getVoicechat();
        if (!event.isConnected()) {
            speakChannel = null;
        }
    }

    // ------------------------------------------------------------------ STT

    private void onMic(ClientSoundEvent event) {
        if (!stt.available()) return;              // no engine → text-only fallback
        short[] frame = event.getRawAudio();
        if (frame == null || frame.length == 0) return;

        short[] utterance = vad.feed(frame);
        if (utterance != null) {
            transcribeAndDispatch(utterance);
        }
    }

    private void transcribeAndDispatch(short[] utterance) {
        if (sttBusy) return;                       // one utterance at a time
        sttBusy = true;
        Minecraft mc = Minecraft.getInstance();
        stt.transcribe(utterance).thenAccept(text -> {
            sttBusy = false;
            if (text.isBlank()) return;
            // marshal back to the client thread and feed the SAME path as typed chat
            mc.execute(() -> {
                var baritone = arion.Arion.baritone();
                var controller = arion.Arion.controller();
                if (baritone != null && controller != null) {
                    controller.onPlayerCommand(baritone, text);
                }
            });
        });
    }

    // ------------------------------------------------------------------ TTS

    /**
     * Speak a reply line to the local player through SVC. Called by the controller
     * for any assistant message. No-ops (returning) when TTS isn't configured.
     */
    public static void speak(String text) {
        SvcVoiceBridge self = instance;
        if (self == null || !self.tts.available()) return;
        self.doSpeak(text);
    }

    private void doSpeak(String text) {
        tts.synthesize(text).thenAccept(pcm24 -> {
            if (pcm24.length == 0) return;
            short[] pcm16 = TtsEngine.downsample24to16(pcm24);
            Minecraft.getInstance().execute(() -> playToLocalPlayer(pcm16));
        });
    }

    private void playToLocalPlayer(short[] pcm) {
        if (clientApi == null) return;
        try {
            if (speakChannel == null || speakChannel.getId() == null) {
                speakChannel = clientApi.createStaticAudioChannel(UUID.randomUUID());
            }
            if (speakChannel != null) {
                speakChannel.play(pcm);
            }
        } catch (Throwable ignored) {
            // playback is best-effort; never let it crash the game
        }
    }
}
