# Memory Engine

## 1. What this component owns

The Memory Engine is the boundary between "raw input" (something you typed, said, uploaded, or a conversation turn) and "structured, retrievable, versioned personal memory." Nothing enters `memories` without passing through it.

## 2. Memory types and how they differ operationally

| Type | Example | Distinguishing behavior |
|---|---|---|
| Episodic | "On 12 June 2026, I..." | Requires/benefits from `event_date`; strongly linked to `events`, `people`, `locations` |
| Semantic | "I prefer Java for backend work" | No event date; treated as a standing belief, superseded rather than dated |
| Preference | Preferred language/DB/etc. | Structured key/value in `preferences`, *also* mirrored as a semantic memory for retrieval context |
| Procedural | "When building Spring Boot apps, I usually..." | Retrieved specifically when the assistant is asked "how do I/how do you usually..." |
| Emotional/Reflective | Private reflections | Defaults to `privacy_level = private` **and to encrypted-at-rest**: title, content and version history are encrypted, and the memory is excluded from text and semantic search. Excluded from any future legacy-readable export unless explicitly changed |
| Relationship | Connections between people/events | Lives primarily in `relationships`/`memory_links`, not free text |
| Project | Project context | Linked to `projects`; retrieved when a question concerns a specific project |

## 3. Lifecycle

```text
Captured → Processed → Classified → Stored → Linked → Indexed → Retrieved → Updated → Archived
```

- **Captured:** raw input preserved verbatim (original text, transcript, or file) — this is never discarded, even after structuring, so you can always see exactly what you originally said.
- **Processed:** the AI layer extracts candidate facts, dates, people, places (see [ai-architecture.md](ai-architecture.md) for the extraction prompt/pipeline).
- **Classified:** assigned a `type`, tentative `confidence`, and tentative links to existing `people`/`events`/`projects` (via name/entity matching against what already exists).
- **Stored:** written as a new `memories` row (or a new `memory_versions` row if it updates an existing memory).
- **Linked:** `memory_links` edges created to related memories; join rows created for `people`/`events`/`locations`/`projects`.
- **Indexed:** embedding generated and written to `embeddings`.
- **Retrieved:** surfaced by RAG when relevant to a question.
- **Updated/Archived:** either superseded by a correction, or explicitly archived by you.

## 4. Confidence and confirmation

Every candidate memory produced by the AI carries a `confidence` score. Below a configurable threshold, or whenever the extraction would **modify an existing memory** (as opposed to creating a new one), the system asks for confirmation before writing:

```text
"I found an existing person named John. Should I add this event to John's profile?"
[Yes]  [No]  [Create New Person]
```

High-confidence *new* memories (no ambiguity, no existing entity to conflict with) can be stored without interrupting you, but are always tagged `source = ai_inference` so you can review/prune them later — nothing is hidden about how a memory was derived.

## 5. Corrections vs. new memories

A correction ("The date was 2019, not 2020") is detected as referring to an *existing* memory (via conversational context or explicit reference) and produces:
- a new `memory_versions` row with `change_reason = user_correction`,
- the prior version flipped to `superseded` (never deleted),
- the parent `memories` row's `current_version_id` updated.

This is the mechanism behind Section 14 of the spec: the system always knows both "what I currently believe" and "what I used to believe, and why it changed."

## 6. Provenance labels (Section 23 of the spec)

Every retrieved memory is tagged with exactly one of:
```text
user_stated | ai_inferred | corrected | uncertain
```
and every RAG answer built from memories carries these labels through to the response, so the assistant can say "you told me X" versus "I inferred X from what you said" versus "I don't have a memory of that" — and never blur the three.

## 7. Building the knowledge graph

`memory_links`, `relationships`, and the join tables between `memories`/`events`/`people`/`locations` are the graph edges. The graph is not a separate storage engine (no Neo4j) — Postgres recursive CTEs are sufficient at this scale, and it keeps the graph transactionally consistent with the memories it describes. If graph queries become a real bottleneck at very large scale, this is an isolated `GraphQueryService` to swap out, not a schema rewrite.
