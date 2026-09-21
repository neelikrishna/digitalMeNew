# Beyond JARVIS

A companion to [assistant-experience.md](assistant-experience.md), which covers
*how* to make the assistant feel live. This covers what it should be able to do
that JARVIS cannot — and why the copy-JARVIS target, taken literally, sells this
project short.

Status: **design, not approved, nothing built.**

---

## 1. Where the JARVIS framing runs out

JARVIS is a butler with a superb API. He is *external* to Tony Stark: he manages
the suit, the calendar, the lab. His value is competence and availability.

That is worth having, and [assistant-experience.md](assistant-experience.md)
describes how to get it. But it is not the interesting part of what is being
built here, and aiming only at it would waste the actual asset.

The asset is this: **a structured, versioned, decades-deep record of one
person's life, with provenance on every claim.** Siri, Alexa and ChatGPT do not
have that and structurally cannot — they are amnesiac by design, and the ones
that do remember keep it on someone else's servers. A JARVIS clone is a nicer
interface to a model. This can be something no product can currently be.

Five capabilities follow from the archive rather than from the voice. None needs
a better model. All of them get *better the longer the system runs*, which is the
opposite of how assistants normally age.

---

## 2. Memory that consolidates instead of accumulating

**The problem this solves is real and arriving.** Retrieval quality degrades as
volume grows. Ten thousand memories of ordinary days will bury the twenty that
matter, and "design for millions" (development rule 14) makes that certain, not
hypothetical. Adding more retrieval cleverness fights the symptom.

Human memory does not work by accumulation. It consolidates: a thousand commutes
become "I took the 7:40 train for four years", and the individual mornings fade
while the shape of them survives.

**The schema already anticipates this.** `EPISODIC` ("this happened") and
`SEMANTIC` ("this is true of me") are distinct memory types. Nothing currently
moves anything between them. Consolidation is precisely that bridge:

```
47 episodic memories mentioning the 7:40 train, 2019-2023
        │  (periodic background pass, clustering on embeddings + time)
        ▼
1 semantic memory: "Commuted by train, roughly 2019 to 2023."
        │
        └── links to all 47 originals, which are archived, never deleted
```

**Why this is safe here specifically:** the hard problem with consolidation is
that summarising is lossy, and lossy is unacceptable for a memory system. This
project already has the machinery to make it safe — versioning, supersession,
archive-never-delete, provenance, and links. The originals stay, marked
`ARCHIVED`, reachable from the summary. If a summary is wrong, the evidence for
it is one hop away, and the existing correction flow applies unchanged.

**Limitation:** consolidation must never run on `sensitive` memories — they are
encrypted and unembeddable, and a summary would leak what the encryption
protects. It should also stay out of `EMOTIONAL` memories entirely: reducing
grief or a turning point to a tidy generalisation is exactly the wrong outcome.
Consolidate the mundane; leave the significant alone.

---

## 3. Showing you yourself

Retrieval answers *what did I do*. An archive of this shape can answer something
no assistant can: **what am I like, and what has changed.**

Concrete queries that are impossible without longitudinal data:

- "You have written about learning Spanish in 2019, 2021 and 2024. Each time in
  January, never after March."
- "You have not mentioned your brother in two years. Before that, monthly."
- "You describe your job positively in morning entries and negatively in evening
  ones."
- "Every project you finished, you started alone. Every one you abandoned
  started with someone else."

These are patterns across time, not facts in a document. They come from
aggregation over `event_date`, `type`, `people`, tags and embeddings — data
already stored.

**Why this is the strongest idea in this document:** it is genuinely useful,
impossible for any competitor, needs no new model capability, and gets richer
every year the archive grows. It is also the thing a person cannot do for
themselves — nobody can see their own decade at a glance.

**Limitation, and it is a serious one.** This turns the system from a tool into
something that makes claims about you. A wrong pattern is not a wrong fact; it
is an unflattering misreading of a life. Every insight must show its working —
the memories it drew on, the period covered, and what it excluded — and must be
rejectable, with rejections remembered so it does not resurface the same
misreading. It should never editorialise; "you abandoned three projects" is
observation, "you struggle to finish things" is judgement, and the second is not
its place.

---

## 4. Noticing that you have changed

People change their minds, and a memory system that only accumulates will hold
both the old and new belief with equal confidence, forever.

The project's founding rule is *never silently overwrite a memory* — and the
correction flow already implements it. What is missing is **noticing that a
correction is warranted without being told.**

```
New input:  "I've come round to Python for backend work."
Existing:   "I prefer Java for backend development."   (SEMANTIC, 2024)

        → not a contradiction to resolve silently
        → not two facts to store side by side
        → a question worth asking:

  "You told me in 2024 you preferred Java for backend work. Has that
   changed, or is this specific to something?"
     [Changed — supersede it]  [Both true, different contexts]  [Leave it]
```

This is the existing confidence-gated confirmation flow, pointed at a new
trigger: semantic similarity to an existing memory combined with a
contradictory claim. The machinery — proposals, review, supersession, version
history — exists and is tested.

**Limitation:** contradiction detection with a small local model will be noisy,
and a system that constantly asks "did you change your mind?" becomes something
you stop reading. It should trigger only on `SEMANTIC` and `PREFERENCE`
memories — standing beliefs, where change is meaningful — never on episodic
ones, where two different accounts of a day are normal rather than conflicting.

---

## 5. Knowing what is happening now

Everything built so far is retrospective. The assistant knows your past
perfectly and your present not at all. That gap is the real difference between
an archive that answers questions and something that feels present.

JARVIS knows what Tony is working on right now. That is not memory; it is
**working context** — a small, current, expiring layer distinct from long-term
memory:

| | Long-term memory | Working context |
|---|---|---|
| Lifespan | permanent | hours |
| Volume | millions | dozens |
| Written by | deliberate capture, review | passively, continuously |
| Storage | encrypted, versioned, permanent | in-memory, expiring |
| Promoted? | — | only if you say "remember this" |

**The architecture already anticipates this**: conversation history is
deliberately separate from long-term memory, and a message only becomes a memory
when explicitly promoted. Working context is that idea extended past the current
conversation — the last few hours of what you have said, asked, uploaded.

**This is the highest-risk item here**, and it should be built last and narrowly.
Ambient capture is how personal systems become surveillance of their owner. The
rule should be that working context is never written to disk, never embedded,
and never consulted for anything but the current session — and the moment it
would be genuinely useful to persist, that is a decision to bring back for
explicit approval, not a default to slide into.

---

## 6. Sounding like you without borrowing your certainty

The stated goal includes communication style — the system should eventually
sound like its owner.

**The failure mode is specific and worth naming.** A system that mimics your
phrasing while holding incomplete memory will sound exactly like you being
confidently wrong. That is strictly worse than something that sounds like a
machine, because the voice itself is a claim to authority. It matters far more
in Phase 8, where someone who knew you may be hearing it after you are gone.

The mitigation is already half-built: personality is architecturally separate
from memory, and every retrieved memory carries a provenance label. What is
missing is making certainty *audible*:

- `stated by owner` → speak it plainly, in their voice.
- `inferred by AI` → mark it aloud. "I think, though you never said it directly…"
- consolidated summary (§2) → say it is a summary and offer the originals.
- pattern (§3) → say what it is based on, always.

Style is borrowable. Certainty is not.

---

## 7. What this changes about the build order

Nothing in [assistant-experience.md](assistant-experience.md) §7 is wrong —
streaming, routing and personality still come first, and still need no new
software. This adds where the work goes afterwards.

| | Capability | Depends on | Risk |
|---|---|---|---|
| 1 | **Change detection** (§4) | extraction + proposals, both built | Low — reuses a tested flow |
| 2 | **Self-insight** (§3) | aggregation over existing columns | Medium — must show its working and be rejectable |
| 3 | **Consolidation** (§2) | clustering over embeddings; **needs pgvector** | Medium — lossy by nature; mitigated by keeping originals |
| 4 | **Calibrated voice** (§6) | personality (Phase 6) + provenance, which exists | Low |
| 5 | **Working context** (§5) | nothing technically | **High** — build last, narrowly, or not at all |

Change detection is the best first move: it is small, it reuses a flow that is
already built and tested, and it makes the system feel like it is paying
attention rather than filing.

---

## 8. The honest summary

A JARVIS clone is a good interface on someone else's model. It would be a
pleasant thing to have and it would not be yours in any deep sense.

What the archive makes possible is different: something that has watched one
life closely enough to notice things about it, that improves for as long as it
runs, and that no company could build for you because none of them have the
data — and shouldn't.

The voice is worth building. But the voice is the *interface*. The archive is
the product.
