/*
 * Arion — an AI companion mod that REQUIRES Baritone.
 *
 * Arion is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package arion.memory;

import arion.provider.AiProvider.Message;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Short-term conversational memory: the rolling window of turns Arion sends to
 * the LLM so it has context. Bounded so prompts stay small for local models.
 *
 * Not persisted across restarts in this iteration (world-memory persistence is
 * a later phase); the goal here is a correct, bounded conversation window.
 */
public final class ArionMemory {

    private final ModConfigSpec.IntValue maxTurnsConfig;
    private final Deque<Message> turns = new ArrayDeque<>();

    public ArionMemory(ModConfigSpec.IntValue maxTurnsConfig) {
        this.maxTurnsConfig = maxTurnsConfig;
    }

    /** Record a user turn. */
    public synchronized void userSaid(String content) {
        add(Message.user(content));
    }

    /** Record an assistant turn. */
    public synchronized void assistantSaid(String content) {
        add(Message.assistant(content));
    }

    private void add(Message m) {
        int maxTurns = Math.max(1, maxTurnsConfig.get());
        turns.addLast(m);
        while (turns.size() > maxTurns * 2) { // *2 because a turn = user + assistant
            turns.pollFirst();
        }
    }

    /** A snapshot of the recent conversation, oldest first. */
    public synchronized List<Message> recent() {
        return new ArrayList<>(turns);
    }

    public synchronized void clear() {
        turns.clear();
    }
}
