# DialogueLens: latest-push check against the code review

**Checked:** November 25, 2026  
**Previous review:** `d65fae9bdeca4c9aae3d245434ee670b10c2070b` — version 30  
**Latest main checked:** [`5b3324398c5ce4ebcb4f5e82b109bbb720448428`](https://github.com/yoshiinthemist/Dialogue-lense/commit/5b3324398c5ce4ebcb4f5e82b109bbb720448428) — version 31  
**Relationship:** Latest commit is the direct child of the reviewed commit. GitHub reports 19 changed files, 3,299 additions, and 179 deletions. Pushed November 25 at 10:52 a.m. Pacific.

## Revised assessment

This push makes substantive improvements. The ordinary bubble renderer no longer runs its competing emergency placement solver, native captions can be retained as reference evidence without replacing the visible track, caption gaps are no longer automatically classified as silence, and the new tests include the actual bubble component.

The old review must be narrowed accordingly. It would be inaccurate to say there is still no release pipeline or no renderer test.

**Release verdict remains: not ready to approve.** Several earlier correctness defects are untouched, and the new pipeline has important handoff and verification defects. The priority is targeted integration repair, while preserving the best-working bubble behavior from Josh's first large prompt. The exact historical prompt-to-commit mapping remains unresolved.

“Fixed” below means the specific source path changed appropriately. It does not mean an Android device or build has been validated.

## What should be credited

### The ordinary renderer now follows the layout engine

[SpeechBalloonView](https://github.com/yoshiinthemist/Dialogue-lense/blob/5b3324398c5ce4ebcb4f5e82b109bbb720448428/app/src/main/java/com/example/ui/player/SpeechBalloonView.kt#L229) removes the extra emergency overlap solver. It clamps the engine's chosen position within the viewport and reports the animated rendered position. [VideoPlayerStage](https://github.com/yoshiinthemist/Dialogue-lense/blob/5b3324398c5ce4ebcb4f5e82b109bbb720448428/app/src/main/java/com/example/ui/player/VideoPlayerStage.kt#L897) keys ordinary bubbles using the new persistent cue key.

This directly addresses an important source of competing movements. Stable keys also help preserve component identity through text revisions. Fixed Zones and Reading Comfort still have separate stability issues described below.

### Caption absence and confirmed silence are separated

[markAuthoritativeTrack](https://github.com/yoshiinthemist/Dialogue-lense/blob/5b3324398c5ce4ebcb4f5e82b109bbb720448428/app/src/main/java/com/example/domain/analysis/TranscriptCoverageLedger.kt#L332) now records uncued gaps and the trailing region as CAPTION_GAP with verified=false and silenceConfirmed=false. The old test claiming an entire minute was verified from one four-second cue has been revised.

The ledger and readiness model now distinguish released/playable coverage from independently verified coverage. That is useful: playback can continue with a clearly labeled fallback without pretending that every interval has been checked.

### Reference-caption collection is meaningfully connected

[Timed-text ingress](https://github.com/yoshiinthemist/Dialogue-lense/blob/5b3324398c5ce4ebcb4f5e82b109bbb720448428/app/src/main/java/com/example/ui/state/DialogueLensViewModel.kt#L2550) retains reference captions even when active-track replacement is forbidden. [YouTube reference fetch](https://github.com/yoshiinthemist/Dialogue-lense/blob/5b3324398c5ce4ebcb4f5e82b109bbb720448428/app/src/main/java/com/example/ui/state/DialogueLensViewModel.kt#L6525) separately obtains reference tracks. The new data models retain origin, language, translation metadata, and revision fields.

The repair/release machinery now exists in [ScoutReleasePipeline](https://github.com/yoshiinthemist/Dialogue-lense/blob/5b3324398c5ce4ebcb4f5e82b109bbb720448428/app/src/main/java/com/example/domain/pipeline/ScoutReleasePipeline.kt#L64), and the main YouTube window path calls it. This is more than a renamed status. Its integration and evidence requirements still need tightening.

### Chapter-only transitions and tests improve

[Context handling](https://github.com/yoshiinthemist/Dialogue-lense/blob/5b3324398c5ce4ebcb4f5e82b109bbb720448428/app/src/main/java/com/example/domain/analysis/HierarchicalContextEngine.kt#L187) no longer increments the scene epoch merely because a chapter marker changes. The live-frame side-memory reset also drops chapter ID from its reset key.

The repository now contains 143 JVM test methods, up from 129, plus one instrumented device test. The additions include 13 pipeline tests and one Robolectric test that actually instantiates SpeechBalloonView. These are checked-in tests, not observed passing results.

## Status of the original 14 main findings

| Original finding | Updated status | Latest evidence / remaining work |
| --- | --- | --- |
| 1. Speaker correction spreads through null identities | Open; unchanged | [Correction propagation](https://github.com/yoshiinthemist/Dialogue-lense/blob/5b3324398c5ce4ebcb4f5e82b109bbb720448428/app/src/main/java/com/example/domain/attribution/SpeakerAttributionEngine.kt#L536) still accepts equality of missing IDs. Require positive identity evidence. |
| 2. Free CC Only misses web-audio requests | Open | [Captured audio](https://github.com/yoshiinthemist/Dialogue-lense/blob/5b3324398c5ce4ebcb4f5e82b109bbb720448428/app/src/main/java/com/example/ui/state/DialogueLensViewModel.kt#L2230) still lacks the zero-cloud/circuit guard and launches independent provider work. |
| 3. Competing bubble-placement authorities | Partly fixed | The ordinary renderer's competing solver is removed. Fixed Zones/Reading Comfort still bypass the central commitment policy; render integration needs testing. |
| 4. Collision solver caps measured bubble size | Open | Fixed-zone and scout-anchor paths still shrink measured dimensions before solving. Match actual render geometry. |
| 5. Old video-frame callbacks after seeks | Open | [Snapshot receiver](https://github.com/yoshiinthemist/Dialogue-lense/blob/5b3324398c5ce4ebcb4f5e82b109bbb720448428/app/src/main/java/com/example/ui/state/DialogueLensViewModel.kt#L1461) still does not reject an old project/seek generation before mutating context and live faces. |
| 6. Caption-track gaps called verified silence | Fixed in that specific track-marking path | [Gap classification](https://github.com/yoshiinthemist/Dialogue-lense/blob/5b3324398c5ce4ebcb4f5e82b109bbb720448428/app/src/main/java/com/example/domain/analysis/TranscriptCoverageLedger.kt#L368) now distinguishes gaps. Gap repair eligibility remains incomplete, and a different new silence handoff defect exists below. |
| 7. Verification lacks a true reviewed release | Partly implemented | New draft/reviewer/store classes and YouTube calls exist. Evidence quality, complete coverage, production publication, and gate coverage remain deficient. |
| 8. Partial results bypass retry ceiling | Open | [Retry selection](https://github.com/yoshiinthemist/Dialogue-lense/blob/5b3324398c5ce4ebcb4f5e82b109bbb720448428/app/src/main/java/com/example/domain/analysis/TranscriptCoverageLedger.kt#L709) still limits FAILED only; PARTIAL_RECOVERY can remain eligible after the budget. |
| 9. Owned request timeouts bypass recovery | Open; implementation unchanged | TranslationAiService, ScoutHeartbeatService, and ResilientYouTubeSegmentExecutor retain the previous cancellation/timeout path. |
| 10. Local preparation silently stops at three minutes | Open; implementation unchanged | AudioAnalyzer and SubtitlePreGenerator retain the 180,000 ms default and incomplete-outcome handling. |
| 11. WebView evidence/spending boundary | Open; implementation unchanged | WebStreamPlayer and YouTubeEmbeddedPlayer retain the bridge validation gaps. New reference ingestion increases the importance of authenticating source identity. |
| 12. Saved AI project skips coverage/resume | Open | Ordinary load still gates/starts acquisition only when no real subtitles exist ([loadProject](https://github.com/yoshiinthemist/Dialogue-lense/blob/5b3324398c5ce4ebcb4f5e82b109bbb720448428/app/src/main/java/com/example/ui/state/DialogueLensViewModel.kt#L5512)). |
| 13. Failed translation marked analyzed | Open | [Translation result handling](https://github.com/yoshiinthemist/Dialogue-lense/blob/5b3324398c5ce4ebcb4f5e82b109bbb720448428/app/src/main/java/com/example/ui/state/DialogueLensViewModel.kt#L4571) still marks every requested cue analyzed even when no usable line was returned. |
| 14. Progress save overwritten by ordinary save | Open | [Progress persistence](https://github.com/yoshiinthemist/Dialogue-lense/blob/5b3324398c5ce4ebcb4f5e82b109bbb720448428/app/src/main/java/com/example/ui/state/DialogueLensViewModel.kt#L8619) writes a copy without updating authoritative project state; [ordinary saves](https://github.com/yoshiinthemist/Dialogue-lense/blob/5b3324398c5ce4ebcb4f5e82b109bbb720448428/app/src/main/java/com/example/ui/state/DialogueLensViewModel.kt#L8084) can subsequently write old progress. |

## New pipeline defects that matter most

### P1 — Recovered dialogue can be discarded and recorded as silence

In the producer-silence branch, [the ViewModel](https://github.com/yoshiinthemist/Dialogue-lense/blob/5b3324398c5ce4ebcb4f5e82b109bbb720448428/app/src/main/java/com/example/ui/state/DialogueLensViewModel.kt#L6814) asks Scout 2 to review an empty interval. If reference captions contain dialogue, the reviewer correctly returns recovered events.

The caller assigns only the review outcome. It does not copy the recovered events into workingProj. The next [coverage branch](https://github.com/yoshiinthemist/Dialogue-lense/blob/5b3324398c5ce4ebcb4f5e82b109bbb720448428/app/src/main/java/com/example/ui/state/DialogueLensViewModel.kt#L6866) records VERIFIED_SILENCE for every review result except SUSPECTED_MISSING_DIALOGUE. REPAIRED_FROM_REFERENCE therefore takes the silence branch.

Finally, [updateProject and cache persistence](https://github.com/yoshiinthemist/Dialogue-lense/blob/5b3324398c5ce4ebcb4f5e82b109bbb720448428/app/src/main/java/com/example/ui/state/DialogueLensViewModel.kt#L6947) publish/save workingProj, which still lacks the recovered words. The separate release store can contain the repair while playback and the saved cache omit it.

**Required repair:** Publish the reviewed events, project state, and ledger outcome together. A dialogue repair must become visible dialogue, never verified silence.

**Acceptance case:** Scout 1 returns silence; native reference contains a sentence in that interval. The sentence appears in playback/cache, coverage reports dialogue or explicitly unverified fallback, and no silence flag remains.

### P1 — One matching cue can verify an entire interval

[Per-event comparison](https://github.com/yoshiinthemist/Dialogue-lense/blob/5b3324398c5ce4ebcb4f5e82b109bbb720448428/app/src/main/java/com/example/domain/pipeline/ScoutReleasePipeline.kt#L285) counts matching/repaired cues. [Interval classification](https://github.com/yoshiinthemist/Dialogue-lense/blob/5b3324398c5ce4ebcb4f5e82b109bbb720448428/app/src/main/java/com/example/domain/pipeline/ScoutReleasePipeline.kt#L348) promotes the whole interval when either count is greater than zero.

A batch containing one matching cue and several unsupported cues can therefore be labeled independently verified. A source-language repair can likewise promote a batch while retaining a target-language translation whose correctness was never checked. The repair counter also increments before checking whether an authored reference matches either language: an unrelated-language branch can leave the event unchanged but still report it repaired and independently verified.

The [token score](https://github.com/yoshiinthemist/Dialogue-lense/blob/5b3324398c5ce4ebcb4f5e82b109bbb720448428/app/src/main/java/com/example/domain/pipeline/ScoutReleasePipeline.kt#L569) also divides the intersection of word sets by the smaller set. For example:

- Draft: “I did send it.”
- Reference: “I did not send it.”
- Tokens of length at least two: {did, send, it} versus {did, not, send, it}.
- Score: 3 / 3 = 1.0, exceeding the 0.58 match threshold.

This is a direct calculation from the implementation, not a Kotlin execution result. It shows why word-set overlap cannot establish semantic equivalence.

**Required repair:** Evaluate and retain outcomes per cue/subinterval. Unchecked text remains unchecked. Treat similarity as triage evidence; handle negation, quantities, entities, language, and timing before claiming agreement. Keep transcript correctness and translation correctness separate.

**Acceptance cases:** One correct cue plus one unsupported cue never yields a fully verified interval. A negation change fails equivalence. Source-text agreement does not automatically verify its translation.

### P1 — Translation safeguards have two bypasses

The [empty-draft recovery branch](https://github.com/yoshiinthemist/Dialogue-lense/blob/5b3324398c5ce4ebcb4f5e82b109bbb720448428/app/src/main/java/com/example/domain/pipeline/ScoutReleasePipeline.kt#L189) copies reference text directly into both sourceText and translatedText before the language checks below it. A Japanese reference can therefore populate an English display field with Japanese text.

In the [nonempty branch](https://github.com/yoshiinthemist/Dialogue-lense/blob/5b3324398c5ce4ebcb4f5e82b109bbb720448428/app/src/main/java/com/example/domain/pipeline/ScoutReleasePipeline.kt#L271), sourceLanguage=Auto-Detect makes isTranslationRequested false. That permits comparisons/replacements against translatedText even when the reference language differs from the requested display language.

The new translation test covers an explicitly selected Japanese source and a nonempty draft. It does not cover either bypass.

**Required repair:** Apply the same language contract to every recovery path. Preserve reference/source text, request translation when required, and label missing translation explicitly.

### P1 — Playback still has multiple publication paths

The new store is not yet the sole source of displayed dialogue. [updateProject](https://github.com/yoshiinthemist/Dialogue-lense/blob/5b3324398c5ce4ebcb4f5e82b109bbb720448428/app/src/main/java/com/example/ui/state/DialogueLensViewModel.kt#L8084) still publishes a supplied project directly; normal rendering reads project.dialogueEvents.

The captured-web-audio path still assigns/updates events and marks them verified directly, bypassing the new review function. Deadline fallbacks update the store and ledger, but the ticker does not copy released events into the visible project.

**Required repair:** Route all relevant generated-dialogue mutations through one publication contract, or explicitly synchronize the store and project in a single reviewed operation. A successful store commit must not be only diagnostic data.

### P1/P2 — Request identity is captured too late, and review reservations are advisory

[The main window callback](https://github.com/yoshiinthemist/Dialogue-lense/blob/5b3324398c5ce4ebcb4f5e82b109bbb720448428/app/src/main/java/com/example/ui/state/DialogueLensViewModel.kt#L6781) passes the current UI seekGeneration when the result is processed, rather than the seek generation captured when its asynchronous work began. The new gate can accept old work bearing a freshly assigned generation.

[The review helper](https://github.com/yoshiinthemist/Dialogue-lense/blob/5b3324398c5ce4ebcb4f5e82b109bbb720448428/app/src/main/java/com/example/ui/state/DialogueLensViewModel.kt#L405) enqueues/claims a required-review task, then reviews its current draft regardless of whether a task was claimed or whether the claimed task belongs to that draft. Review is synchronous in this helper; the new queue does not yet control which pending draft is processed.

**Required repair:** Carry immutable request context from dispatch to commit. Process the exact claimed task/draft revision; keep required review eligible under low lead without bypassing task identity.

### P2 — Exact revision enforcement is incomplete

[stageDraft](https://github.com/yoshiinthemist/Dialogue-lense/blob/5b3324398c5ce4ebcb4f5e82b109bbb720448428/app/src/main/java/com/example/domain/pipeline/ScoutReleasePipeline.kt#L705) compares against currently pending drafts only. [commitReviewedRelease](https://github.com/yoshiinthemist/Dialogue-lense/blob/5b3324398c5ce4ebcb4f5e82b109bbb720448428/app/src/main/java/com/example/domain/pipeline/ScoutReleasePipeline.kt#L727) can accept an outcome when no matching pending draft exists; once a newer revision has been committed and removed from the pending map, an older same-context draft is not rejected against that already released revision.

The gate also does not compare its sourceRevision/referenceTrackId with current evidence. This violates the intended exact-revision contract. The current synchronous helper limits some out-of-order opportunities; this is not a claim that an asynchronous old review was observed overwriting a newer one.

**Required repair:** Bind outcomes to an exact staged draft/evidence revision and reject stale revisions relative to both pending and already released work.

## Bubble stability: remaining scope

The original ordinary-renderer override is fixed. These narrower issues remain:

- Fixed Zones can relocate on a single obstruction without the central commitment gate; Reading Comfort recomputes neutral placement. Both still need the same stability policy.
- Measured width/height can still be capped below the rendered size.
- The preview reviewer creates a fresh engine per cue, uses current viewer faces for all three preview times, and omits measured bubble dimensions. It does not establish the geometry or face evidence of a future interval.
- validatePresentation returns placementStable=true even after changing placement mode without re-evaluating the result. Its conservative repair can retain faceTrackId, which the layout engine may still use to attach the cue spatially.
- Dropout grace counts computeLayout calls, rather than distinct detector observations. The faster playback tick can consume this grace before a new captured frame arrives.
- Tracker failure versus valid empty observations and stale masked-pixel history remain unresolved.
- Clearing measured-size caches can conflict with the renderer's retained reporting deadband, leaving an active cue on estimates until a sufficiently large size change occurs.
- New telemetry detects certain large two-step reversals, but can miss gradual multi-frame oscillation. Zero counters alone do not establish stable playback.
- The ordinary speech ownership fix does not remove the separate SFX placement solver.

Sources: [preview evaluation](https://github.com/yoshiinthemist/Dialogue-lense/blob/5b3324398c5ce4ebcb4f5e82b109bbb720448428/app/src/main/java/com/example/domain/layout/BalloonLayoutEngine.kt#L141), [presentation review](https://github.com/yoshiinthemist/Dialogue-lense/blob/5b3324398c5ce4ebcb4f5e82b109bbb720448428/app/src/main/java/com/example/domain/pipeline/ScoutReleasePipeline.kt#L490), [fixed-zone placement](https://github.com/yoshiinthemist/Dialogue-lense/blob/5b3324398c5ce4ebcb4f5e82b109bbb720448428/app/src/main/java/com/example/domain/layout/BalloonLayoutEngine.kt#L459), [Reading Comfort](https://github.com/yoshiinthemist/Dialogue-lense/blob/5b3324398c5ce4ebcb4f5e82b109bbb720448428/app/src/main/java/com/example/domain/layout/BalloonLayoutEngine.kt#L528), [dropout handling](https://github.com/yoshiinthemist/Dialogue-lense/blob/5b3324398c5ce4ebcb4f5e82b109bbb720448428/app/src/main/java/com/example/domain/layout/BalloonLayoutEngine.kt#L959).

**Preservation requirement:** Keep the prior successful bubble behavior as the comparison standard. Test ordinary balloons, Fixed Zones, and Reading Comfort separately. Do not replace working speaker-linked presentation with a blanket neutral or fixed layout to make a test pass.

## Other earlier notes still apply

Unchanged implementations retain the API 24–25 storage compatibility risk, premature audio-capture started flag, MediaRecorder clip/timeline assumptions, low-sample-rate conversion defect, HLS partial-input handling, audio timestamp bounds, and anti-echo overreach.

The fetcher's earlier rolling-prefix normalizer is also unchanged; the new reviewer's rolling merge is a separate later operation. New stable cue keys improve component identity, but do not establish correct source-caption normalization.

Imported caption gaps are now honestly labeled, but uploaded-file intervals remain excluded from the gap scheduler and loaded-track locks still constrain repair. The distinction between respecting a selected source and investigating missing dialogue remains important.

Reference ingestion additionally needs source-identity checks: the observed timed-text callback stamps data with the current project ID and token without validating that the observed URL/callback originated from that video. A late callback can therefore be admitted under a new session's identity.

## Tests and validation limits

The new renderer test uses actual SpeechBalloonView, fixed supplied rectangles, and reducedMotion=true. It checks persistent identity and the absence of the removed face-driven renderer jumps. It does not test default spring animation, engine-to-measurement feedback, or PixelCopy/face-tracker integration.

The new pipeline tests are useful isolated tests. They miss the ViewModel's recovered-dialogue publication bug. The readiness fallback assertion evaluates a controller still in its loading state without starting a user-play request, so that assertion does not prove that playback starts correctly on fallback.

Validation performed here:

- Compared the direct parent and latest commit; inspected all 19 changed files and affected callers.
- Materialized changed files and verified their Git blob hashes.
- Rechecked selected unchanged implementations behind the old findings.
- Counted 143 JVM test methods plus one instrumented test.
- Queried GitHub evidence for this exact latest commit: zero Actions runs, zero check runs, and zero commit-status records returned.
- Rechecked main after the comparison; it still pointed to the same version 31 commit.

No Android build, Kotlin tests, device playback, or paid provider calls were run. The environment still lacks the relevant Gradle/Kotlin/Android runner and the repository still lacks a complete wrapper bootstrap. GitHub results do not establish what AI Studio may have built elsewhere.

## Next repair order

1. Repair the silence-to-dialogue handoff and make released content the content playback actually receives.
2. Make verification cue-specific and language-aware; close Auto-Detect and empty-draft translation bypasses.
3. Enforce request context, exact draft/evidence revisions, cloud policy, timeouts, and retry budgets at shared boundaries.
4. Finish stability enforcement and measured geometry across display modes, preserving the earlier successful bubble behavior.
5. Add ViewModel-level integration cases for the above and run normal-motion device playback before approving a release.

The push deserves credit for specific corrections. Its release pipeline must now satisfy its contract throughout production callers, rather than only inside the new helper classes.

