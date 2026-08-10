/*
 * Arion — an AI companion mod that REQUIRES Baritone.
 *
 * Arion is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package arion.provider;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Registry of every provider Arion knows how to talk to. Model flexibility is
 * the point: a player picks a provider id, supplies their own key if needed,
 * and it just works.
 */
public final class ProviderRegistry {

    private final Map<String, AiProvider> providers = new LinkedHashMap<>();

    public ProviderRegistry() {
        register(new OllamaProvider());
        register(new OpenAiProvider());                                                        // openai
        register(new OpenAiProvider("openai-compatible", "OpenAI-compatible", ""));            // any /v1 endpoint
        register(new OpenAiProvider("groq", "Groq", "https://api.groq.com/openai"));
        register(new OpenAiProvider("openrouter", "OpenRouter", "https://openrouter.ai/api"));
        register(new OpenAiProvider("together", "Together", "https://api.together.xyz"));
    }

    public void register(AiProvider p) {
        providers.put(p.id(), p);
    }

    public Optional<AiProvider> byId(String id) {
        return Optional.ofNullable(providers.get(id));
    }

    /** Fallback: unknown id → ollama (local, no key) so a typo never hard-breaks. */
    public AiProvider byIdOrDefault(String id) {
        return providers.getOrDefault(id, providers.get("ollama"));
    }

    public Collection<AiProvider> all() {
        return providers.values();
    }
}
