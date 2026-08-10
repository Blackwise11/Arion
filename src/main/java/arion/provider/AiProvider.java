/*
 * Arion — an AI companion mod that REQUIRES Baritone.
 *
 * Arion is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package arion.provider;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * A single LLM backend. Implementations must be non-blocking: {@link #complete}
 * returns a future and never touches the client thread.
 *
 * Security contract (BYOK): the API key is supplied by the player, stored only
 * in the local client config, never logged, and sent only to this provider's
 * endpoint. Implementations must not write the key to any log or file.
 */
public interface AiProvider {

    /** Stable id used in config, e.g. "ollama", "openai". */
    String id();

    /** Human-readable name shown in the settings GUI. */
    String displayName();

    /** Whether this provider needs an API key to function. */
    boolean requiresApiKey();

    /**
     * Send a conversation and get the assistant's next message.
     *
     * @param request the request (model, messages, key, endpoint)
     * @return a future completing with the assistant's reply text
     */
    CompletableFuture<String> complete(AiRequest request);

    /** Quick connectivity check for the settings GUI "test connection" button. */
    CompletableFuture<Boolean> testConnection(AiRequest request);

    /** One chat message in a conversation. */
    record Message(String role, String content) {
        public static Message system(String content)  { return new Message("system", content); }
        public static Message user(String content)    { return new Message("user", content); }
        public static Message assistant(String content){ return new Message("assistant", content); }
    }

    /**
     * Everything a provider call needs. The key may be empty for providers that
     * don't require one (e.g. local Ollama).
     */
    record AiRequest(
            String baseUrl,
            String model,
            String apiKey,      // may be "" — never log this
            List<Message> messages,
            int timeoutSeconds
    ) {}
}
