package com.digitalself.rag.intent;

/**
 * What kind of question was asked, which decides whether personal memory is
 * searched at all.
 *
 * <p>The ordering of the constants is meaningful: it runs from the branch that
 * can least invent personal history to the branch that is least constrained.
 * {@link #safestFallback()} relies on it.
 */
public enum QuestionIntent {

    /**
     * About the owner's own life, history, people or preferences. Answered
     * strictly from retrieved memories; nothing retrieved means the model is
     * never invoked and the system says so.
     */
    PERSONAL_MEMORY,

    /**
     * Needs both: the owner's own context and the model's general knowledge.
     * Both may be used, but the answer must say which part came from where.
     */
    HYBRID,

    /**
     * Ordinary world knowledge — "what is the chemical formula of water". No
     * personal memory is searched, and the answer is marked as general knowledge
     * so it is never mistaken for something the owner said.
     */
    GENERAL_KNOWLEDGE,

    /**
     * Needs information from outside the machine — today's weather, the news.
     * This system has no internet access by design, so these are answered
     * honestly rather than guessed at.
     */
    CURRENT_INFORMATION,

    /**
     * Greetings, thanks, small talk. No retrieval, short reply.
     */
    CASUAL_CONVERSATION;

    /**
     * Whether answering this requires searching personal memory.
     */
    public boolean requiresRetrieval() {
        return this == PERSONAL_MEMORY || this == HYBRID;
    }

    /**
     * Where to land when classification is unreliable.
     *
     * <p>{@code PERSONAL_MEMORY} is the strictest branch: it refuses rather than
     * speculates. Misrouting a general question there produces an unhelpful
     * answer, which is recoverable by asking again. Misrouting a personal
     * question to {@code GENERAL_KNOWLEDGE} invites the model to invent someone's
     * history, which is not.
     */
    public static QuestionIntent safestFallback() {
        return PERSONAL_MEMORY;
    }
}
