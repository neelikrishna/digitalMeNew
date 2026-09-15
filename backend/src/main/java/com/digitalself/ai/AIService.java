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
}
