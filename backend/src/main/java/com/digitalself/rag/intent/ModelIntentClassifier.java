package com.digitalself.rag.intent;

import com.digitalself.ai.AIService;
import com.digitalself.config.IntentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Classifies a question by asking the model for a single label.
 *
 * <p>Runs before retrieval, so it must be cheap: it emits one word and can use a
 * smaller model than the one answering (see
 * {@code digitalself.intent.model}).
 *
 * <p>Every failure path lands somewhere safe. An unreachable model, an
 * unintelligible answer or a low-confidence guess all fall back to first-person
 * heuristics, and anything still ambiguous goes to the strictest branch — the
 * one that refuses rather than speculates.
 */
@Component
public class ModelIntentClassifier implements IntentClassifier {

    private static final Logger log = LoggerFactory.getLogger(ModelIntentClassifier.class);

    private static final String SYSTEM_PROMPT = """
            You classify a question into exactly one category. Reply with the category name
            and nothing else — no explanation, no punctuation, no preamble.

            PERSONAL_MEMORY
              Asks about the user's own life, history, work, opinions, or people they know.
              Answerable only from their stored personal records.
              "What did I say about my Java project?"
              "What did I discuss with John last year?"
              "When did I start learning Spanish?"

            GENERAL_KNOWLEDGE
              Ordinary facts about the world. Nothing to do with this user specifically.
              "What is the chemical formula of water?"
              "How does a hash map work?"
              "Who wrote Hamlet?"

            HYBRID
              Needs both the user's own situation and general knowledge to answer well.
              "Based on my project architecture, should I use pgvector?"
              "What did I decide about my database, and is that a good approach?"
              "Given my experience, what should I learn next?"

            CURRENT_INFORMATION
              Needs live data from outside — weather, news, prices, today's events.
              "What's the weather today?"
              "What happened in the news this morning?"

            CASUAL_CONVERSATION
              Greetings, thanks, pleasantries. No information is being requested.
              "Hello"
              "Thanks, that's helpful"

            Rule: if the question contains "I", "my", "we" or "our" referring to the user,
            it is almost never GENERAL_KNOWLEDGE. Choose PERSONAL_MEMORY or HYBRID.
            """;

    /** First-person markers as whole words, so "I" does not match "ice". */
    private static final Pattern FIRST_PERSON = Pattern.compile(
            "\\b(i|i'm|i've|my|mine|me|myself|we|we're|our|ours)\\b", Pattern.CASE_INSENSITIVE);

    /** Openers that, absent first-person markers, indicate a factual lookup. */
    private static final Pattern FACTUAL_OPENER = Pattern.compile(
            "^(what|who|when|where|why|how)\\s+(is|are|was|were|does|do|did|can|should)\\b",
            Pattern.CASE_INSENSITIVE);

    private final AIService aiService;
    private final IntentProperties properties;

    public ModelIntentClassifier(AIService aiService, IntentProperties properties) {
        this.aiService = aiService;
        this.properties = properties;
    }

    @Override
    public IntentDecision classify(String question) {
        if (!properties.isEnabled()) {
            return IntentDecision.disabled();
        }
        if (question == null || question.isBlank()) {
            return IntentDecision.heuristic(QuestionIntent.safestFallback());
        }

        String response;
        try {
            response = aiService.completeWith(properties.getModel(), SYSTEM_PROMPT, question, List.of());
        } catch (Exception e) {
            log.warn("Intent classification unavailable, falling back to heuristics: {}", e.toString());
            return IntentDecision.heuristic(heuristicIntent(question));
        }

        IntentDecision decision = parse(response);
        if (decision == null) {
            log.warn("Could not read an intent from the model's reply, falling back to heuristics.");
            return IntentDecision.heuristic(heuristicIntent(question));
        }
        if (decision.confidence() < properties.getMinConfidence()) {
            // A guess is not acted on. Retrieval is cheap; inventing a life is not.
            return IntentDecision.heuristic(heuristicIntent(question));
        }
        return decision;
    }

    /**
     * Confidence comes from how cleanly the model followed the instruction, not
     * from the model claiming a number — a self-reported score from a small model
     * would be invented precision.
     */
    private IntentDecision parse(String response) {
        if (response == null || response.isBlank()) {
            return null;
        }
        String cleaned = response.strip().toUpperCase(Locale.ROOT);

        for (QuestionIntent intent : QuestionIntent.values()) {
            if (cleaned.equals(intent.name())) {
                // Followed the instruction exactly.
                return IntentDecision.model(intent, 0.9);
            }
        }
        for (QuestionIntent intent : QuestionIntent.values()) {
            if (cleaned.contains(intent.name())) {
                // Right answer buried in prose: usable, but it ignored the format,
                // so treat the rest of its judgement as less reliable too.
                return IntentDecision.model(intent, 0.6);
            }
        }
        return null;
    }

    /**
     * Used when the model cannot be trusted or reached. Deliberately crude, and
     * biased towards the branch that cannot fabricate personal history.
     */
    private QuestionIntent heuristicIntent(String question) {
        if (FIRST_PERSON.matcher(question).find()) {
            return QuestionIntent.PERSONAL_MEMORY;
        }
        if (FACTUAL_OPENER.matcher(question.strip()).find()) {
            return QuestionIntent.GENERAL_KNOWLEDGE;
        }
        return QuestionIntent.safestFallback();
    }
}
