package com.digitalself.rag.intent;

/**
 * Decides what kind of question was asked, before any retrieval happens.
 *
 * <p>An interface rather than a concrete class because classification is the
 * one place in the retrieval path most likely to be replaced: a fine-tuned
 * classifier, an embedding-similarity approach, or simple rules could each beat
 * a prompted model depending on the hardware available.
 */
public interface IntentClassifier {

    IntentDecision classify(String question);
}
