# AI Architecture

## 1. Abstraction boundary

No business logic calls Ollama's HTTP API directly. Everything goes through two interfaces:

```java
interface AIService {
    String complete(String systemPrompt, String userPrompt, List<Message> history);
}

interface EmbeddingService {
    float[] embed(String text);
}
```

`OllamaAIService` and `OllamaEmbeddingService` are the only classes that know Ollama exists. Swapping models (or even swapping Ollama for another local runtime like llama.cpp server) means changing one Spring bean's configuration, not the memory or RAG code.

**Why this matters here specifically:** the spec explicitly asks to avoid coupling business logic to one model (Section 20) and to be able to change models later (Section 21) — this is the mechanism that guarantees that.

## 2. RAG pipeline

```text
User Question
     ↓
Query Understanding        -- lightweight: extract date range / person / project hints if present
     ↓
Hybrid Retrieval
     ├─ Vector search (pgvector cosine, HNSW index) over `embeddings`
     ├─ Keyword search (Postgres tsvector) over `memories.content`
     └─ Metadata filters (date range, person, tag, privacy_level ownership)
     ↓
Rank & merge (reciprocal rank fusion of the two result sets)
     ↓
Context Construction        -- top-N memories, each tagged with its provenance label
     ↓
Prompt Assembly = Personality Profile + Preferences + Retrieved Memories (labeled) + Conversation History
     ↓
Ollama (local model)
     ↓
Answer, with provenance preserved
```

## 3. Why hybrid retrieval, not vector-only

**Why needed:** pure semantic search misses exact-match queries ("what did John say on my birthday") that keyword/date filtering handles trivially, and pure keyword search misses paraphrased questions.

**Alternative:** vector-only search (simpler to build). Rejected because personal memory queries are frequently entity/date-anchored, where a `WHERE event_date BETWEEN ... AND person_id = ...` filter is both faster and more precise than similarity search alone.

**Recommendation:** combine both, as pgvector allows doing this in one SQL query — filter first, then rank by vector distance within the filtered set, when a filter is present; fall back to pure semantic + keyword fusion when it isn't.

## 4. Preventing hallucinated memories (Section 11 / 27)

This is a hard constraint, enforced structurally, not just by prompting:
- The system prompt explicitly instructs the model to answer *only* from the memories provided in context, and to say "I don't have a memory of that" when nothing relevant was retrieved.
- The backend inspects retrieval results before calling the model: if retrieval returns nothing above a similarity/relevance threshold, the backend can short-circuit and return "I don't have a memory of that" without invoking the model at all, removing the model's opportunity to fill the gap with invented detail.
- Every answer that does use memories cites which ones (memory IDs), so a claim is always traceable back to a stored, provenance-tagged row.

## 5. Personality layer, kept separate from factual memory

```text
PERSONALITY  → personality_traits table   ("what kind of person am I")
MEMORY       → memories table             ("what happened to me")
KNOWLEDGE    → semantic-type memories     ("what do I know")
PREFERENCES  → preferences table          ("what do I like")
VALUES       → personality_traits/goals   ("what principles do I follow")
```

The system prompt is composed, not hand-written per request:
```text
[Base assistant instructions + honesty/no-hallucination rules]
+ [Personality profile summary, derived from personality_traits]
+ [Relevant preferences]
+ [Retrieved, labeled memories]
+ [Recent conversation turns]
```
This keeps "how the assistant talks" (personality) decoupled from "what the assistant knows" (memory) — so personality can be refined (or reset) without touching the factual record, and vice versa.

## 6. Fine-tuning / LoRA — explicitly deferred

Per Section 12 of the spec, no fine-tuning in Phase 1-6. The personality/prompt-composition approach above is the entire mechanism until there's enough consistently-labeled personal data (large volume of your own conversational text with clear provenance) to make LoRA fine-tuning worthwhile — revisit only after Phase 6 data volume is assessed.

## 7. Embeddings model and chunking

- Local embedding model served via Ollama (e.g., a `nomic-embed-text`-class model); `embeddings.model_name` is stored per-row so re-embedding with a better model later is a background migration, not a breaking change.
- Long content (documents, transcripts) is chunked before embedding, with `embeddings.chunk_index` preserving order; retrieval can return either the matching chunk or its parent document depending on context size needs.
