/*
 * Arion — an AI companion mod that REQUIRES Baritone.
 *
 * Arion is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package arion.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * All user-facing settings for Arion, persisted per-client in
 * {@code config/arion-client.toml}. API keys live here too (BYOK): they are
 * stored locally on the player's machine, never hardcoded, never logged,
 * and sent only to the provider the player configured.
 */
public final class ArionConfig {

    public final ModConfigSpec spec;

    // ---- brain (planner) ----
    public final ModConfigSpec.ConfigValue<String> plannerProvider;
    public final ModConfigSpec.ConfigValue<String> plannerModel;
    public final ModConfigSpec.ConfigValue<String> plannerApiKey;
    public final ModConfigSpec.ConfigValue<String> plannerBaseUrl;

    // ---- reflex (fast, small) ----
    public final ModConfigSpec.ConfigValue<String> reflexProvider;
    public final ModConfigSpec.ConfigValue<String> reflexModel;
    public final ModConfigSpec.ConfigValue<String> reflexApiKey;
    public final ModConfigSpec.ConfigValue<String> reflexBaseUrl;

    // ---- behavior ----
    public final ModConfigSpec.ConfigValue<String> chatPrefix;
    public final ModConfigSpec.BooleanValue enabled;
    public final ModConfigSpec.BooleanValue debugMode;
    public final ModConfigSpec.IntValue maxContextTurns;
    public final ModConfigSpec.IntValue plannerTimeoutSeconds;
    public final ModConfigSpec.BooleanValue mineCollectDrops;
    public final ModConfigSpec.IntValue mineDropLoiterMs;

    // ---- survival ----
    public final ModConfigSpec.DoubleValue eatBelowFood;
    public final ModConfigSpec.BooleanValue autoResumeAfterDeath;

    // ---- combat ----
    public final ModConfigSpec.DoubleValue combatRetreatHealth;
    public final ModConfigSpec.IntValue combatMaxAttackers;
    public final ModConfigSpec.DoubleValue combatReach;
    public final ModConfigSpec.BooleanValue combatHumanizeAim;
    public final ModConfigSpec.IntValue combatSearchTimeoutSeconds;
    public final ModConfigSpec.IntValue combatMaxDurationSeconds;
    public final ModConfigSpec.BooleanValue vesselAutoDefend;
    public final ModConfigSpec.BooleanValue autoShield;
    public final ModConfigSpec.DoubleValue shieldRange;
    public final ModConfigSpec.IntValue shieldHoldTicks;

    // ---- features (extended action verbs) ----
    public final ModConfigSpec.BooleanValue extendedVerbs;
    public final ModConfigSpec.IntValue farmDefaultRange;
    public final ModConfigSpec.IntValue clearMaxVolume;

    // ---- vessel (fake player) ----
    public final ModConfigSpec.BooleanValue vesselEnabled;
    public final ModConfigSpec.BooleanValue vesselBaritoneOnServers;
    public final ModConfigSpec.BooleanValue vesselCombat;

    // ---- crafting (M3) ----
    public final ModConfigSpec.BooleanValue craftEnabled;
    public final ModConfigSpec.BooleanValue craftPlaceStations;

    // ---- laya (System 1 decision engine) ----
    public final ModConfigSpec.BooleanValue layaEnabled;
    public final ModConfigSpec.ConfigValue<String> layaBaseUrl;
    public final ModConfigSpec.IntValue layaTimeoutSeconds;
    public final ModConfigSpec.IntValue layaIntervalTicks;
    public final ModConfigSpec.DoubleValue layaMinConfidence;

    // ---- voice (STT / TTS) ----
    public final ModConfigSpec.BooleanValue voiceEnabled;
    public final ModConfigSpec.ConfigValue<String> sttProvider;   // groq | openai
    public final ModConfigSpec.ConfigValue<String> sttApiKey;
    public final ModConfigSpec.ConfigValue<String> sttBaseUrl;
    public final ModConfigSpec.ConfigValue<String> sttModel;
    public final ModConfigSpec.ConfigValue<String> ttsProvider;   // openai
    public final ModConfigSpec.ConfigValue<String> ttsApiKey;
    public final ModConfigSpec.ConfigValue<String> ttsBaseUrl;
    public final ModConfigSpec.ConfigValue<String> ttsModel;
    public final ModConfigSpec.ConfigValue<String> ttsVoice;

    public ArionConfig() {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();

        b.push("brain");
        plannerProvider = b.comment(
                        "Planner (big-picture) provider id. One of: ollama, openai, openai-compatible.",
                        "ollama = local model, no key needed. openai / openai-compatible = bring your own key.")
                .define("plannerProvider", "ollama");
        plannerModel = b.comment("Planner model name, e.g. llama3.2, qwen2.5:3b, gpt-4o-mini.")
                .define("plannerModel", "llama3.2");
        plannerApiKey = b.comment("API key for the planner provider (BYOK). Leave blank for ollama.",
                        "Stored locally in this config file only. Never logged, never sent anywhere but the provider.")
                .define("plannerApiKey", "");
        plannerBaseUrl = b.comment("Base URL for the planner provider. ollama default shown; openai-compatible can point anywhere.")
                .define("plannerBaseUrl", "http://localhost:11434");
        b.pop();

        b.push("reflex");
        reflexProvider = b.comment("Reflex (fast, command-routing) provider id. A small model is plenty here.")
                .define("reflexProvider", "ollama");
        reflexModel = b.comment("Reflex model name, e.g. qwen2.5:3b, phi3.5.")
                .define("reflexModel", "qwen2.5:3b");
        reflexApiKey = b.comment("API key for the reflex provider (BYOK).")
                .define("reflexApiKey", "");
        reflexBaseUrl = b.comment("Base URL for the reflex provider.")
                .define("reflexBaseUrl", "http://localhost:11434");
        b.pop();

        b.push("behavior");
        chatPrefix = b.comment("Chat prefix to talk to Arion. Type this, a space, then your message.")
                .define("chatPrefix", "ai ");
        enabled = b.comment("Master switch. When false, Arion ignores chat input and runs no jobs.")
                .define("enabled", true);
        debugMode = b.comment("Trace every pipeline step (chat intercept, routing, dispatch, LLM calls, combat states)",
                        "to chat and latest.log. Verbose — turn on only when something isn't working.")
                .define("debugMode", false);
        maxContextTurns = b.comment("How many recent conversation turns to send the LLM as memory.")
                .defineInRange("maxContextTurns", 12, 0, 100);
        plannerTimeoutSeconds = b.comment("How long to wait for a planner response before giving up (local LLMs are slow).")
                .defineInRange("plannerTimeoutSeconds", 30, 2, 300);
        mineCollectDrops = b.comment(
                        "While mining, make Baritone walk onto each mined block so its drops are collected",
                        "(sets Baritone cancelOnGoalInvalidation=false), and sweep nearby drops after the action.",
                        "Turn off to restore Baritone's faster mine-and-move-on behavior.")
                .define("mineCollectDrops", true);
        mineDropLoiterMs = b.comment(
                        "How long Baritone waits after breaking a block for its drop to appear (milliseconds).",
                        "Only applied when mineCollectDrops=true.")
                .defineInRange("mineDropLoiterMs", 500, 0, 5000);
        b.pop();

        b.push("survival");
        eatBelowFood = b.comment("Eat when food level drops below this (out of 20).")
                .defineInRange("eatBelowFood", 14.0, 0.0, 20.0);
        autoResumeAfterDeath = b.comment("After dying and respawning, try to walk back to the death point and resume.")
                .define("autoResumeAfterDeath", true);
        b.pop();

        b.push("combat");
        combatRetreatHealth = b.comment("Retreat when health drops below this (out of 20).")
                .defineInRange("combatRetreatHealth", 6.0, 0.0, 20.0);
        combatMaxAttackers = b.comment("If more than this many mobs are targeting us, back off.")
                .defineInRange("combatMaxAttackers", 3, 1, 20);
        combatReach = b.comment("Attack reach while in combat. Vanilla is 3.0; this is restored after combat ends.")
                .defineInRange("combatReach", 4.5, 3.0, 6.0);
        combatHumanizeAim = b.comment("Ease aim toward targets instead of snapping instantly, so it looks human.")
                .define("combatHumanizeAim", true);
        combatSearchTimeoutSeconds = b.comment("How long to keep wandering/searching for a hunt target before giving up.")
                .defineInRange("combatSearchTimeoutSeconds", 60, 5, 600);
        combatMaxDurationSeconds = b.comment("Hard cap on a single hunt. 0 = no cap. After this the hunt auto-stops.")
                .defineInRange("combatMaxDurationSeconds", 300, 0, 3600);
        vesselAutoDefend = b.comment(
                        "Hand the vessel's melee defence to Baritone's CombatProcess (AutoDefendMode.ALWAYS).",
                        "CombatProcess owns the real attacks and the shield reflex (combatUseShield, including",
                        "predictive melee blocks); it freezes the running task while it fights and resumes it.",
                        "Restored to the previous mode when the vessel's Baritone is unbound.")
                .define("vesselAutoDefend", true);
        autoShield = b.comment(
                        "Automatically raise an offhand shield when a projectile is predicted to hit (M4 threat engine).",
                        "Layer 0 reflex, no LLM. 'ai shield on|off' toggles it at runtime.")
                .define("autoShield", true);
        shieldRange = b.comment("Scan radius (blocks) for incoming projectiles. A full-charge arrow flies ~3 blocks/tick,",
                        "and vanilla needs blockDelayTicks (5) before the block is effective, so this must",
                        "cover well over 15 blocks of flight: 48 blocks ~= 16 ticks of lead.")
                .defineInRange("shieldRange", 48.0, 8.0, 96.0);
        shieldHoldTicks = b.comment("Keep the shield raised this many ticks after the last threat disappears.")
                .defineInRange("shieldHoldTicks", 10, 0, 60);
        b.pop();

        b.push("features");
        extendedVerbs = b.comment("Advertise the full action set to the LLM (farm, get, pickup, build, clear, relative goto).",
                        "Turn off for very small local models that choke on a longer prompt.")
                .define("extendedVerbs", true);
        farmDefaultRange = b.comment("Default radius (blocks) for the farm command when none is given.")
                .defineInRange("farmDefaultRange", 10, 1, 64);
        clearMaxVolume = b.comment("Safety cap on how many blocks the clear command may remove at once.")
                .defineInRange("clearMaxVolume", 10000, 1, 1000000);
        b.pop();

        b.push("vessel");
        vesselEnabled = b.comment("Enable the vessel ('ai spawn'). When false, vessel commands are off.",
                        "The vessel is a server-side fake player (single-player / integrated server) — a real body",
                        "that mobs target and that takes real damage. Brain/LLM/API keys stay client-side.")
                .define("vesselEnabled", true);
        vesselBaritoneOnServers = b.comment(
                        "Reserved for the Phase C pathfinding bridge. Currently no effect: the vessel is server-side",
                        "and movement is not yet wired. (Was: allow the legacy client-only vessel to borrow Baritone",
                        "on remote servers — that ghost model is retired in favour of the server-side body.)")
                .define("vesselBaritoneOnServers", false);
        vesselCombat = b.comment(
                        "Reserved for the Phase D server-side combat loop (real attack/break via the server).",
                        "Currently no effect on the server-side vessel. (Was: packet-safe body-block hunting for the",
                        "legacy client-only vessel — superseded by real server-side combat in Phase D.)")
                .define("vesselCombat", true);
        b.pop();

        b.push("crafting");
        craftEnabled = b.comment(
                        "Let Arion's macro plans craft and smelt items (M3). When false, craft/smelt steps re-plan.",
                        "The engine reads the server's real recipe manager, consumes the exact ingredients, and",
                        "places a crafting table/furnace when a 3x3 recipe or smelting needs one.")
                .define("craftEnabled", true);
        craftPlaceStations = b.comment(
                        "Allow placing a crafting table/furnace from the body's inventory when none is nearby.",
                        "Turn off to require an existing station within 4 blocks.")
                .define("craftPlaceStations", true);
        b.pop();

        b.push("laya");
        layaEnabled = b.comment(
                        "Enable the Laya System 1 loop. While a macro plan is running ('ai plan <goal>'),",
                        "Laya picks the atomic action (mine/combat/flee/eat/advance) every layaIntervalTicks.",
                        "Requires the local server: laya-project\\laya_http_server.py on port 8765.")
                .define("layaEnabled", true);
        layaBaseUrl = b.comment("Base URL of the local Laya HTTP server (POST /decide, GET /health).")
                .define("layaBaseUrl", "http://127.0.0.1:8765");
        layaTimeoutSeconds = b.comment(
                        "Give up on one Laya decision after this long; the body keeps doing its current action.",
                        "The model answers in ~30 ms once loaded, but the first request after startup can be slow.")
                .defineInRange("layaTimeoutSeconds", 10, 1, 60);
        layaIntervalTicks = b.comment(
                        "Minimum ticks between micro decisions while a macro plan is active (20 ticks = 1s).",
                        "Lower = more reactive but more GPU passes; the server serializes requests anyway.")
                .defineInRange("layaIntervalTicks", 10, 2, 200);
        layaMinConfidence = b.comment(
                        "Below this action confidence, Arion ignores Laya and performs the step's natural action.",
                        "Laya is a classifier; measured confidence on out-of-distribution states can be near random.",
                        "0.35 keeps decisive answers while preventing an 8%-confidence 'advance' from skipping a step.")
                .defineInRange("layaMinConfidence", 0.35, 0.0, 1.0);
        b.pop();

        b.push("voice");
        voiceEnabled = b.comment("Enable the voice front-end (requires Simple Voice Chat + an STT engine).")
                .define("voiceEnabled", true);
        sttProvider = b.comment("Speech-to-text provider: 'groq' (fast, free tier) or 'openai'.")
                .define("sttProvider", "groq");
        sttApiKey = b.comment("API key for the STT provider. Local only, never logged.")
                .define("sttApiKey", "");
        sttBaseUrl = b.comment("STT base URL override (leave blank to use the provider default).")
                .define("sttBaseUrl", "");
        sttModel = b.comment("STT model, e.g. whisper-large-v3-turbo (groq) or whisper-1 (openai).")
                .define("sttModel", "whisper-large-v3-turbo");
        ttsProvider = b.comment("Text-to-speech provider: 'openai' (or any OpenAI-compatible /audio/speech).")
                .define("ttsProvider", "openai");
        ttsApiKey = b.comment("API key for the TTS provider. Local only, never logged.")
                .define("ttsApiKey", "");
        ttsBaseUrl = b.comment("TTS base URL override (leave blank to use the provider default).")
                .define("ttsBaseUrl", "");
        ttsModel = b.comment("TTS model, e.g. tts-1 (openai).")
                .define("ttsModel", "tts-1");
        ttsVoice = b.comment("TTS voice name, e.g. alloy, echo, onyx (openai voices).")
                .define("ttsVoice", "alloy");
        b.pop();

        this.spec = b.build();
    }
}
