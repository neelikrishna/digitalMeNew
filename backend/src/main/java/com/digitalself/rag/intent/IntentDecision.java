package com.digitalself.rag.intent;

/**
 * @param intent     how the question was classified
 * @param confidence 0–1. Low confidence is not an error: it records that the
 *                   classifier guessed, which is worth logging and worth showing
 *                   in the response so a strange answer can be explained.
 * @param source     what produced the decision — useful when the model is
 *                   unavailable and the heuristic stood in for it
 */
public record IntentDecision(QuestionIntent intent, double confidence, Source source) {

    public enum Source {
        /** The classification model answered. */
        MODEL,
        /** The model failed or was unintelligible; first-person heuristics decided. */
        HEURISTIC,
        /** Routing is switched off; everything is treated as a memory question. */
        DISABLED
    }

    public static IntentDecision model(QuestionIntent intent, double confidence) {
        return new IntentDecision(intent, confidence, Source.MODEL);
    }

    public static IntentDecision heuristic(QuestionIntent intent) {
        return new IntentDecision(intent, 0.4, Source.HEURISTIC);
    }

    public static IntentDecision disabled() {
        return new IntentDecision(QuestionIntent.PERSONAL_MEMORY, 1.0, Source.DISABLED);
    }

    public boolean requiresRetrieval() {
        return intent.requiresRetrieval();
    }
}
