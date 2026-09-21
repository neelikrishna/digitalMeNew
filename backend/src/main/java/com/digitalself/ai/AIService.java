package com.digitalself.ai;

import java.util.List;

/**
 * Abstraction over whatever local model actually answers a prompt. No
 * business logic should depend on Ollama directly — only on this interface —
 * so the model or inference backend can be swapped by changing one bean.
 * See docs/ai-architecture.md Section 1.
 */
public interface AIService {

    String complete(String systemPrompt, String userPrompt, List<Message> history);

    /**
     * Same, but asking a named model rather than the configured default.
     *
     * <p>Exists for work that is not the main answer — intent classification in
     * particular, which only has to emit one word and should not pay the latency
     * of the large model doing the answering.
     *
     * <p>Defaults to ignoring the override, so a provider that cannot switch
     * models at request time needs no extra code and simply uses its default.
     *
     * @param model provider-specific model name; blank or null means the default
     */
    default String completeWith(String model, String systemPrompt, String userPrompt, List<Message> history) {
        return complete(systemPrompt, userPrompt, history);
    }
}
