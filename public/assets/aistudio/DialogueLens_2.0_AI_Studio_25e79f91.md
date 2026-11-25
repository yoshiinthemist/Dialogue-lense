# DialogueLens 2.0 — Master Implementation Specification

**For Google AI Studio · Native Android / Kotlin / Jetpack Compose**

Act as a senior Android software architect, real-time rendering engineer, asynchronous systems engineer, media synchronization specialist, computer vision specialist, cinematic interaction designer, consumer product designer, accessibility specialist, and senior Jetpack Compose engineer.

Implement this specification in the existing DialogueLens application. This is a coordinated technological and visual upgrade, not an invitation to replace the existing engine with a mockup or a simpler implementation.

## Release objectives

1. Eliminate persistent speech balloon flickering and unjustified back-and-forth motion.
2. Establish reliable playback synchronization, seeking, buffering, and automatic resumption.
3. Implement an adaptive Sentinel workforce that responds to Heal Beacons anywhere in the current media timeline.
4. Proactively inspect and repair YouTube chapter boundaries and suspicious editorial transitions by default.
5. Deliver a coherent, cinematic, accessible consumer UI that conceals technical complexity while preserving professional tools.

**Preserve:** existing application identity and signing configurations, hierarchical contextual awareness, transcript coverage ledger, versioned caches, media adapters, translation and transcription, speaker attribution, live tracking, character memory, user corrections, Heal Beacon history, saved projects, preferences, editing, and exports. Maintain backward compatibility and protect previously verified dialogue.

**Baseline:** The previous independent source review examined version 27 (main commit `03d3e7f0ea4334f52b49e0ce06b36782ccb40ddc`). Confirm the actual current implementation before making changes. Some issues described below are source-supported hypotheses rather than device-reproduced defects. Reuse existing functionality where it already satisfies the requirements. All numerical thresholds are proposed initial settings unless specifically identified as existing behavior; measure and calibrate them. Do not claim tests passed unless actually executed.

---

# PART I — Unified operating architecture

## 1. Four-agent operating model

- **Viewer — Experience:** Plays media, displays prepared dialogue, accepts interactions, maintains synchronization and a smooth viewing experience. Avoid unnecessary analytical work.
- **Scout — Anticipation:** Processes upcoming dialogue, identifies speakers, translates, establishes context, maintains continuous verified coverage ahead of playback, and immediately reprioritizes analysis when the Viewer seeks into an unprepared region.
- **Sentinel 1: Responder — Correction:** Responds to user-created Heal Beacons; investigates missing dialogue, incorrect attribution, timing and spatial positioning; validates repairs; assists urgent recovery when playback readiness is threatened.
- **Sentinel 2: Patrol — Prevention:** Proactively investigates named YouTube chapters and suspicious editorial transitions; identifies contextual inconsistencies; generates automatic Heal Beacons when justified; attempts to prevent defects before the Viewer reaches them.
- **Playback Readiness Controller — Coordination:** Centrally determines whether playback can begin or continue, whether a seek destination is ready, whether buffering is necessary, when automatic resumption is appropriate, which work has priority, and when routine Sentinel activity must suspend or resume.

These are logical roles, not necessarily four independent video players. No subsystem may independently make contradictory playback decisions. The Viewer watches; the Scout anticipates; the Responder corrects; the Patrol prevents; the controller coordinates.

## 2. Adaptive Sentinel workforce

Implement one primary on-demand Sentinel worker capable of both reactive and proactive responsibilities. Activate a second specialized Sentinel only when multiple independent tasks exist, the first worker is occupied, Scout coverage is sufficient, device/network/AI resources permit it, and work can proceed without conflicting writes. With one worker, combine both roles using a priority queue. **Chapter patrol is enabled by default regardless of worker count.**

Reuse authorized media access, cached observations, existing transcripts and analysis services. Do not maintain unnecessary continuous video player instances. Idle workers when no useful work remains. Do not trade playback performance for repair throughput.

---

# PART II — Playback readiness and timing intelligence

## 3. Single source of truth for readiness

Use `TranscriptCoverageLedger` as the authoritative record of dialogue coverage. Distinguish physical Scout position, furthest analyzed timestamp, contiguous verified dialogue, verified silence, available but unverified cues, failed or partially recovered intervals, media transport buffering, subtitle preparation buffering, speaker attribution readiness, and spatial positioning readiness.

Calculate continuous verified coverage beginning at the actual current or requested playback position, stopping at the first unresolved gap. Do not substitute overall completion percentage, distant verified intervals, Scout playback position, or a nearby subtitle for this measure. Verified silence counts as prepared; an empty or failed result does not prove silence. Do not require confident speaker or spatial attribution to present otherwise valid dialogue.

## 4. Adaptive buffer thresholds

Existing settings observed in the reviewed version include: target physical Scout lead 7 seconds; opening analysis window 35 seconds; rolling windows 40 seconds; minimum startup wait 3 seconds; maximum startup wait 18–42 seconds depending on duration; synchronization tolerance 2.4 seconds; playback ticker about 16 ms. Review these rather than assume they are optimal.

Initial targets, expressed in **real viewing time**:

| Threshold | Initial target | Meaning |
|---|---:|---|
| Critical | 2 seconds | Prioritize immediate recovery; enter buffering if further dialogue is required. |
| Minimum resume | 7 seconds | Continuous verified coverage required for automatic resumption after dialogue buffering. |
| Preferred | 15 seconds | Maintain whenever resources permit. |
| Comfortable | 30 seconds | Extra resilience when processing capacity is available. |

Adapt to actual coverage, playback speed, processing throughput, network latency, and remaining duration. Use different entry and exit thresholds to avoid pause/resume oscillation. Calibrate with real tests, not arbitrary waits.

## 5. Scale with playback speed

Convert real viewing time to required media coverage using `requiredMediaMs = targetWallTimeMs * playbackSpeed`, capped by remaining known media duration. Example starting values:

| Speed | Critical media seconds | Minimum resume media seconds | Preferred media seconds |
|---:|---:|---:|---:|
| 0.5× | 1 | 3.5 | 7.5 |
| 1× | 2 | 7 | 15 |
| 1.5× | 3 | 10.5 | 22.5 |
| 2× | 4 | 14 | 30 |

Reevaluate immediately on speed changes. Never wait for coverage past the known end. Treat unknown-duration media explicitly. A physical seven-second lead is not enough to establish readiness at every speed.

## 6. Explicit playback states

Introduce a coherent state machine or equivalent structured state contract: preparing initial dialogue, ready, playing, user paused, seeking, waiting for seek acknowledgement, buffering media, buffering dialogue, recovering missing dialogue, playing with limited dialogue availability, processing unavailable, playback ended.

Preserve user playback intent independently from actual player state. An intentional pause, an editing pause, a media stall and an automatic subtitle buffering pause are different. A completed background task must not automatically resume playback. A routine Sentinel repair must not pause the Viewer. Define and test valid state transitions, including cancellation and supersession.

## 7. Seeking and jump-ahead behavior

When the user seeks:

1. Capture the user's intended playing/paused state and requested destination.
2. Create a new seek generation/session identifier; invalidate stale spatial observations.
3. Evaluate continuous coverage at the destination and prioritize its analysis.
4. Issue the appropriate media seek, waiting for reliable acknowledgement of the actual destination.
5. Resume or remain paused according to readiness, media transport, and current user intent.

**Prepared destination:** Seek without unnecessary buffering or repeating startup. Preserve play/pause intent and revalidate the destination's visual context.

**Unprepared destination:** Immediately hold the Viewer at the destination and enter dialogue buffering if playback is intended. Prioritize local coverage; defer routine Patrol and use Responder assistance where appropriate. Resume only after minimum continuous coverage becomes available. Do not briefly play into an unprepared region while waiting for a later callback to notice the gap.

**Paused seek:** Remain paused when preparation finishes; allow preparation in the background.

**New seek during buffering:** Replace the old destination. Never resume at an obsolete timestamp because an earlier request completed. Reject stale callbacks and inappropriate analysis commits. Do not conflate the pending seek target with the last acknowledged media position.

## 8. Immediate destination reprioritization

The current ledger can prioritize the playhead when workers become available; existing requests may still occupy every worker. Reserve immediate capacity for an unprepared seek destination, reprioritize queued work, defer distant analysis, suspend routine patrol, and cancel obsolete work only when the benefit outweighs the cost. Preserve useful completed or nearly completed work. Do not duplicate requests. Share interval reservations across Scout and Sentinel tasks so obsolete work cannot monopolize capacity.

## 9. Reliable automatic resumption

Require all of the following: acknowledged current seek destination (where applicable), valid session, continuing user intent to play, minimum contiguous verified dialogue coverage, media player able to resume, and no unrelated user pause or editing interaction. Explicitly issue the appropriate play command exactly once for that buffering episode. Clearing a flag alone is not sufficient.

Prevent obsolete resume commands, repeated play/pause oscillation, resume after manual pause, playback at a stale position, resumption based on one cue, and resumption due to unrelated distant analysis completion.

## 10. Failure, timeout, and user override

Never remain indefinitely trapped in a spinner. Differentiate processing delay, connectivity interruptions, failed analysis, quota exhaustion, unavailable captions, unsupported access and verified silence.

After a bounded, reasonable wait, offer appropriate actions: keep preparing, retry, continue with available subtitles, attach a subtitle file, use available native captions, or return to the previous position. A timeout does not establish readiness. Watch Now / Continue Anyway is a deliberate user-authorized degraded mode; explain possible missing dialogue.

Review the observed 45-second media-time catch-up override. Replace broad suppression with a bounded, explicitly authorized bypass scoped to the affected episode or region so unrelated future failures are not hidden. Reset appropriately on a new seek or session. If analysis ends unsuccessfully, resolve the wait state explicitly; do not clear it while leaving the media player unexpectedly paused.

## 11. Startup preparation

Review Smart Pre-Roll and the cinema introduction. Do not use elapsed wait time, number of completed windows, or physical Scout position as the primary readiness criterion. Use verified contiguous coverage from the requested starting position, including verified silence. Start immediately when cached/authoritative coverage is adequate. Silent openings must not require actual dialogue to be present.

For unprepared media, display a calm preparation state until readiness or an explicit user choice. Provide bounded timeout and recovery options. Preserve restored viewing position; do not automatically rewind resumed projects to zero.

## 12. Cross-platform buffering

Apply consistent readiness principles to YouTube, local video, direct streams and supported embedded web players, adapting to actual provider capabilities. The reviewed YouTube implementation has catch-up behavior not equivalently present in ordinary web streams. Do not exclude short videos (the observed guard bypasses videos <=30 seconds); handle unknown duration, unsupported seeking, inaccessible audio, media restrictions and DRM honestly. Never bypass access controls. If adequate analysis or synchronization is unavailable, communicate the limitation and provide permitted fallback options.

---

# PART III — Proactive Sentinel patrol

## 13. Default YouTube chapter patrol

The user observed that named YouTube chapter transitions appear especially vulnerable to contextual/spatial errors. Treat this observation as a high-priority hypothesis to validate.

On loading a YouTube video, retrieve available chapter metadata; identify explicit boundaries; build a prioritized inspection queue; independently inspect upcoming boundaries; detect anomalies; create internal Heal Beacons for genuine defects; apply validated repairs before the Viewer arrives where feasible. Patrol operates even if no manual Beacons exist.

Initial window: about **10 seconds before and 15 seconds after** a boundary. Adapt to dialogue continuity, scene complexity, evidence, resources and duration; clamp to the actual media and merge queued overlapping inspection work when sensible.

At every transition inspect dialogue completeness and synchronization, speaker identity continuity, changes in participants, stale context/coordinates, invalid anchors, character color inheritance, echoed previous-chapter dialogue, missing new-chapter dialogue, off-screen attribution, bubble stability, unusual confidence shifts, and contiguous readiness through the transition. Preserve video-level knowledge while revalidating chapter and scene knowledge.

## 14. Suspicious scene breaks

Differentiate explicit chapters, major editorial transitions, normal camera cuts, reaction shots, B-roll, title cards, participant changes, temporary detector failures, and real playback discontinuities. Explicit chapters automatically enter the queue. Other cuts receive inexpensive local validation first; escalate only when evidence warrants it. Do not pay for cloud analysis at every camera cut. For media without chapters, use supported metadata or reliably inferred editorial transitions. Never fabricate chapter information.

## 15. Heal Beacons independent of playback

A Beacon identifies a media timestamp, not Viewer or Scout location. Repair marks behind/ahead of the Viewer, in the present scene or another chapter, in verified/unverified regions, restored from saved projects, or generated by Patrol. Users may continue watching after marking a moment. Do not rewind, pause or reposition the Viewer for a repair, or unnecessarily reposition the Scout. Acquire extra authorized media access only as needed and release it afterward.

## 16. Unified scheduling priorities

1. Critical playback recovery: unprepared seek destinations, current missing dialogue blocking playback, imminent exhaustion of verified lead.
2. User-directed repair: manually placed Beacons and explicit retries.
3. Preventive maintenance: upcoming YouTube chapter boundaries, suspicious upcoming editorial transitions and automatic Beacons.
4. Background maintenance: distant chapters, older unresolved regions and noncritical visual refinements.

Manual Beacons always outrank routine patrol; critical playback may temporarily outrank ordinary repairs. Prefer upcoming transitions by proximity and time required to inspect. Prevent starvation, merge overlapping queued requests, and never expand the immutable window of a running repair behind its worker's back. Queue uncovered portions separately. Bound retries, prevent duplicate requests and overlapping destructive writes, and avoid excessive AI usage.

## 17. Agent cooperation during buffering

Normal: Scout maintains contiguous coverage; Responder handles marks; Patrol checks transitions. During an unbuffered seek or critical shortage: Viewer waits, Scout prioritizes the destination, Responder assists urgent recovery when useful, routine Patrol suspends. Restore normal work after readiness recovers and resources permit. Share awareness of verified lead, reservations, upcoming boundaries, AI quotas, network throughput, device resources and completed analysis. Patrol must never be the reason playback runs out of prepared dialogue.

---

# PART IV — Safe healing and contextual integrity

## 18. Transactionally safe repairs

**Healing must never destroy healthy dialogue.** Review `HealBeaconEngine`: a partial nonempty result can potentially replace an entire window in the reviewed code.

Capture original affected state; analyze the exact requested region; validate timestamps and actual coverage; compare candidate repairs with existing dialogue; preserve unaffected cues, authoritative sources and user locks; retain valid cues in unverified portions; apply only justified changes; maintain undo history.

For incomplete repairs, safely merge independently verified improvements or retain the original dialogue and mark unresolved portions. Never replace a whole window merely because some cues exist. Never infer silence from an empty result. Reject obsolete project/session results, dismissed Beacons, superseded attempts, conflicting repairs or intervening user edits. Use immutable requests, session IDs, repair revisions, interval reservations and serialized atomic commits. Maintain accurate coverage and cache state across repair, rollback and restart.

## 19. Sentinel specializations

- Dialogue recovery: missing, duplicated, mistranscribed or incorrectly timed cues.
- Speaker correction: questionable assignments while protecting established identities and locks. Never automatically merge distinct people solely on matching display names.
- Spatial correction: invalid coordinates, tails, excessive movement and stale anchors.
- Context correction: chapter transitions, reaction shots, B-roll, cuts and inconsistent memory.
- Timing correction: premature appearance, delayed disappearance, overlapping cues, short interjections, media/presentation drift, seek discontinuities and misaligned audio capture intervals.
- Integrity verification: validate proposed changes before application. Prefer inexpensive deterministic local repairs, escalating to AI only when necessary and allowed. Local re-attribution is not verified text recovery.

## 20. Hierarchical memory

Build on the existing three-layer model:

- Video memory: persistent identities, confirmed names, colors, voice traits, relationships and user corrections.
- Chapter memory: active participants, conversational context, subject, speaker sequence and environmental changes.
- Scene memory: faces, mouths, spatial anchors, framing, obstacles and immediate positions.

A scene tracking failure must not corrupt video-level knowledge. A chapter change must not erase established characters. Revalidate location independently of identity.

---

# PART V — Critical visual stability

## 21. Eliminate permanent balloon flickering

The user reports persistent back-and-forth flickering during normal playback. Treat this as release-blocking. Instrument the whole rendering path, reproduce under controlled conditions, and fix underlying causes rather than simply lengthening animations. Investigate these source-supported hypotheses:

**A. Competing layout calls.** The ticker uses `computeContextAwareLayout`, but certain YouTube/web callbacks call `BalloonLayoutEngine.computeLayout` without equivalent scene context. Alternating scene IDs may invalidate placement history. Audit every call. Establish one authoritative layout coordinator that receives presentation time, project identity, chapter, scene epoch, current faces, evidence validity, style and committed layout state. No competing layout decisions from callbacks.

**B. Competing positioning engines.** `BalloonLayoutEngine` chooses normalized positions; `SpeechBalloonView` independently repositions in pixels based on measured text and collisions. Investigate A-B-A oscillation between these stages. Feed real measured text dimensions into the authoritative placement process; prevent the renderer from repeatedly undoing logical decisions.

**C. Compose identity.** Key all bubbles and SFX overlays by stable project/event identity; ordinary recomposition, list reorder, tracking updates or transcript refinements must not restart entrances or erase measurements. Preserve cue IDs when refining an existing utterance, including during repair. Avoid recreating visual identity for changed text, confidence or anchor.

**D. Live tracking.** Distinguish confirmed cuts, meaningful movement, detector dropout, stale observations, true absence of faces and changes caused by the app's overlays. Investigate whether PixelCopy captures the speech balloons as part of the underlying video, producing a feedback loop in luminance/cut detection. Use appropriate capture isolation or masking where needed. Reject observations from old seeks/projects. Do not repeatedly reset memory due to intermittent detection. Confirm these hypotheses with instrumentation; do not assume they are the only causes.

## 22. Stable balloon lifecycle

- Appearance: choose an appropriate initial location, display once at the correct cue onset.
- Stability: retain a safe, readable body position; allow the tail to follow modest verified motion independently.
- Relocation: only for meaningful collision, substantial speaker movement, genuine cut, new obstruction, material readability improvement or explicit correction.
- Disappearance: remove when the dialogue ends; do not repeatedly fade in/out during one uninterrupted cue. Preserve timing.

## 23. Position commitment and hysteresis

Commit a body position per active cue within its scene. Alternative locations must demonstrate meaningful improvement before displacing a safe position. Implement calibrated position holds, dead zones, collision tolerances, confidence hysteresis, consecutive observations, relocation cooldowns and deterministic candidate ordering. Prevent A-B-A oscillation from minor score changes. A critical obstruction may require immediate relocation; never lock a bubble over a face indefinitely just to avoid movement. Prefer readability and stability to instant mathematical optimization.

## 24. Separate body and tail

The body is a stable reading surface; the tail communicates speaker location. Smooth tail motion independently. When location becomes uncertain, keep the body and confirmed identity, remove/simplify an unreliable directional pointer, and restore it only when evidence recovers. Avoid rapid tail visibility toggling and whole-body repositioning due to temporary mouth landmark loss. Preserve a calm tailless option.

## 25. Stable presentation clock

Review `PlaybackClockAnchor`, `SubtitleTimelineMatcher`, remote player heartbeats and activation behavior. The reviewed version receives YouTube samples around every 50 ms, web samples around 80 ms, and interpolates around 16 ms. Investigate repeated backward corrections and stale samples.

Separate authoritative media position, presentation clock, seek commands/acknowledgements, real stalls, pauses and intentional discontinuities. During uninterrupted forward play use bounded drift correction to prevent tiny backward corrections crossing cue boundaries repeatedly. Reset for true seeks, discontinuities, speed changes and pauses. Stop interpolation during actual stalls and bound extrapolation when heartbeats cease. Do not hide genuine sync errors by arbitrarily extending subtitles.

## 26. Timing-related flicker

Audit cue boundary matching, overlaps, short events, backward corrections, remote delays, seeks, transcript updates, temporary stalls and speed changes. Preserve stable identity during continuing cues; do not remove/recreate visible events unnecessarily. Keep legitimate short interjections and simultaneous speakers. The goal is accurate, stable presentation, not artificially prolonged subtitles.

## 27. Rendering performance

Separate work by frequency:

- Playback frame: presentation interpolation, smooth tails, display committed positions.
- Live observation: face tracking, movement assessment and spatial validation.
- Cue or scene transition: initial placement, collision planning, reading order, context updates.
- Project change: timeline indexing, speaker ordinals, transcript reconciliation and cache invalidation.

Avoid full spatial optimization every ~16 ms, repeated full-transcript sorting, unchanged obstacle-map rebuilding and unnecessary temporary allocations. Profile long media, frame timing, memory and processing before/after. Preserve smooth playback and correct timing.

## 28. Repairs and rendering

Offscreen repairs must not reset unrelated visual state. Visible cue repairs should preserve event identity where possible, apply atomically, avoid entrance restarts and needless relocation, and respect locked text and position. Invalidate only affected state; do not globally clear smoothing or scene memory after local repairs. Diagnose recurring visual defects at their root rather than repeatedly healing individual symptoms.

---

# PART VI — Consumer experience and diagnostics

## 29. Simplified consumer experience

The workforce is invisible by default. **Pause. Mark. Heal. Continue.** Use concise labels: Preparing dialogue; Preparing dialogue at your new position; Ready to watch; Preparing upcoming dialogue; Healing this moment; Repair complete; Some dialogue unavailable; AI usage limit reached; Original dialogue restored. Routine patrol is silent. Keep worker counts, model names, queues and technical telemetry in Studio.

## 30. Clear buffering experience

Differentiate media transport buffering from dialogue preparation. Show a calm preparation indicator, requested position, appropriate Watch Now / Continue Anyway action, and recovery choices. No invented countdowns or percentages. Estimate waiting time only when supported by processing measurements. Remove the interface promptly when the controller legitimately resumes. Avoid duplicate overlays, competing progress bars and disruptive notifications.

## 31. Sentinel Inspector

In Studio show manual and automatic Beacons, known chapter boundaries, pending/completed inspections, anomalies, active/verified repairs, unresolved defects, repair history, undo, worker responsibilities, escalation reasons, reasons patrol is suspended, and resource consumption. Distinguish inspected from repaired. Do not repeat inspections simply to keep workers busy.

## 32. Playback timing diagnostics

Provide bounded, developer-accessible telemetry: requested and acknowledged seek position; seek latency; media and presentation clocks; correction magnitude; contiguous verified lead and physical Scout lead; preferred target; buffering start/end and reasons; user intent; prioritization latency; stale callbacks; auto-resume attempts; time waiting for dialogue versus media transport. Use this to objectively investigate timing without relying solely on user observation.

## 33. Visual stability diagnostics

Record optional bounded metrics: position changes per event, maximum frame-to-frame displacement, A-B-A relocations, scene epoch changes, layout frequency, tracking dropouts, tail visibility changes, activation/deactivation counts, identity changes, entrance animation restarts and reasons for moving. Make diagnostics accessible in Studio so persistent flicker can be investigated without a screenshot. Do not expose constantly changing telemetry during normal viewing.

---
# PART VII — Mandatory validation

## 34. Playback and buffering regression tests

Test all of the following, using deterministic fake clocks/players for state transitions and actual device playback where available:

- Fully cached and entirely unprepared videos; verified introductory silence; restored nonzero playback position.
- Forward/backward seeking into verified coverage and into unresolved gaps; paused seeking; seeking during buffering; rapid consecutive seeks; obsolete acknowledgements and stale callbacks.
- All Scout workers busy at seek time; partial destination coverage; a single cue followed by a gap; verified silence inside the required window.
- Failed processing; exhausted quota; network loss/recovery; actual media transport stalls; analysis pipeline terminating while the viewer is waiting.
- User pause during automatic buffering; explicit Watch Now; changes in playback speed; short videos under 30 seconds; near-end playback; unknown-duration media.

Assert that resumption requires current readiness and user intent, is issued exactly once, and never targets an obsolete seek. Measure actual seek-to-ready latency, rebuffering frequency, subtitle timing error and time spent waiting for transport versus dialogue.

## 35. Sentinel regression tests

Test multiple Beacons at different timestamps; earlier/later timeline repairs; manual priority over routine work; critical playback priority; overlapping queued requests; attempted expansion of active repair windows; late results for dismissed Beacons; partial success; quota exhaustion; restart during repair; project change; overlapping concurrent commits; user corrections during analysis; Scout falling behind; suspension/resumption of Patrol; operation with one and two workers. Assert that healthy dialogue and user edits remain intact, stale results are rejected, and work is bounded and idempotent.

## 36. Chapter transition regression tests

Test explicit named chapters, long chapters, short consecutive chapters, abrupt topic changes, introduced speakers, returning established speakers, title cards, promotions, embedded clips, reaction shots, B-roll, rapid editorial cuts, mid-sentence boundaries, unavailable chapter metadata, direct seeks into boundaries and gaps immediately after transitions. Verify default detection and inspection, persistent identity continuity, avoidance of redundant analysis, safe repair, truthful inspection status, and no unnecessary interruption of playback or impairment of Scout readiness.

## 37. Visual stability regression tests

Test long stationary dialogue, minor face-coordinate fluctuations, intermittent face detection, alternating candidate scores, live versus precomputed observations, remote heartbeats between rendering ticks, small backward corrections, simultaneous bubbles, changed text measurements, confidence fluctuations, genuine cuts, rapid chapter changes, seeking and speed changes, visible transcript refinement, repair during playback and offscreen chapter repair while unrelated dialogue remains visible.

The same uninterrupted cue must not unjustifiably restart its entrance animation, alternate between distant positions or lose identity. Establish measurable limits on displacement, avoidable relocations, activation restarts and tail toggles. Validate real device behavior, not just static layout output; compare against a baseline.

## 38. Cross-platform verification

Test supported YouTube, local files, direct streams and embedded web players; anime, live action, podcasts/interviews; portrait, landscape, phones, tablets and foldables. Verify seeking, subtitle preparation, Sentinel operation and visual stability for each. Test animated faces, off-screen dialogue and rapid cuts as first-class scenarios. Report actual capability limits; a media adapter alone does not establish compatibility. Respect platform restrictions and do not bypass DRM.

---

# PART VIII — Implementation and acceptance

## 39. Implementation methodology and release order

1. Audit existing functionality, playback timing, seek/buffering rules, Heal Beacon implementation and rendering. Inventory controls and establish regression baselines.
2. Reproduce and instrument flickering and timing defects.
3. Unify the presentation clock and layout coordinator; stabilize cue identity, committed positions and live observations.
4. Implement the readiness controller, contiguous coverage thresholds, reliable seek acknowledgement and automatic resumption.
5. Implement shared Scout/Sentinel scheduling and safe localized repair transactions; enable default chapter patrol; allow a second worker only if justified.
6. Integrate localized repairs with stable rendering and accurate coverage persistence.
7. Implement the visual design system, navigation, Watch, Library and Studio, wiring all controls to the real structured states described below.
8. Execute available unit, integration, regression, usability and actual device tests; compare measurements; address regressions.
9. Document remaining limitations and release readiness.

Prioritize visual stability and playback readiness before expanding concurrent Sentinel processing. Make incremental, testable changes rather than an indiscriminate rewrite. Preserve identity, signing, projects, corrections, caches and preferences. Do not introduce mock processing, simulated AI behavior, fake status, or unconnected controls. Do not claim tests passed unless executed.

## 40. Technical acceptance criteria

- Prepared seeks do not wait unnecessarily; unprepared seeks buffer when appropriate; the destination is immediately prioritized.
- Playback resumes automatically only after seek acknowledgement and sufficient contiguous coverage, with continuing intent to play; paused viewers remain paused.
- Buffering cannot silently become stuck or falsely signal readiness; failed processing is never verified silence.
- Sentinels handle marks independently of playback; default YouTube chapter patrol works without manual marks; routine work does not compromise readiness.
- Partial repairs preserve healthy dialogue; stale repairs do not overwrite newer edits; speaker identity survives chapter changes.
- Bubbles keep stable identities and committed positions; persistent flicker and unjustified A-B-A motion are eliminated under tested conditions.
- Ordinary viewing is simple, comfortable and accurate; advanced functionality remains available.

Report confirmed root causes, implemented changes, state-machine behavior, seek/resume behavior, buffering and synchronization measurements, Sentinel scheduling, chapter patrol effectiveness, visual stability, resource consumption, tests executed, actual results and limitations.

---

# PART IX — DialogueLens 2.0: complete UI and experience redesign

The technological transformation requires an equally substantial UI transformation. Do not bolt new agent controls onto the current interface. Reconsider the full experience as a coherent premium media application. The technology should feel invisible until needed. Implement native Compose UI and connect it to the real engine; do not stop at mockups.

## 41. Product identity

Cinematic, calm, intelligent, approachable, precise, contemporary, dependable and restrained. Draw conceptual inspiration from premium streaming apps, professional film tools, Android interaction patterns and manga lettering, without copying branding. Create a distinctive visual identity centered on immersion, intelligent assistance and reading comfort.

## 42. Cohesive design system

Proposed initial palette:

| Token | Hex | Purpose |
|---|---|---|
| Cinema Obsidian | `#101419` | Primary background |
| Graphite | `#20272E` | Elevated surfaces |
| Soft Jade | `#BCE5CE` | Primary actions and verified readiness |
| Amethyst | `#CDB4F1` | Heal Beacons, Sentinel and repair interactions |
| Warm Amber | `#D5A36A` | Recoverable issues and attention |
| Warm off-white | Select accessible value | Primary text |
| Muted cool gray | Select accessible value | Secondary information |

Validate contrast and accessibility; never communicate meaningful states by color alone. Speaker colors are independent of interface colors.

Use existing bundled fonts where appropriate: Plus Jakarta Sans for primary UI, Lexend for accessible reading, Nunito for expressive balloon styles, JetBrains Mono only for technical diagnostics. Define reusable tokens for spacing, typography, color, corners, elevation, icon sizes, touch targets and motion. Avoid excessive cards, gradients, shadows, badges and decorative effects. The palette and type hierarchy are design proposals, not a tested optimum.

## 43. Three primary destinations

- **Watch:** ordinary viewing and media intake.
- **Library:** access, organization and resumption of media.
- **Studio:** editing, styling, healing, exports and diagnostics.

Use familiar Android navigation: compact bottom navigation, adaptive rail or expanded layout on large windows, no unnecessary app navigation in fullscreen. Preserve navigation state when switching destinations, and retain the current playback session where applicable.

## 44. Home screen

Central question: “What do you want to watch?” Clear hierarchy: branding; prominent link input; primary Start Watching action; equally discoverable local video option; most recent resumable media; recently opened items; primary navigation. Suggested brand line: “Every voice. Every story.”

Auto-detect supported sources. Remember language and appearance preferences. Hide Scout, Sentinel, tiers and file format configuration from the default workflow. Keep optional caption attachment, advanced import settings and an optional interactive demo discoverable. No developer-only test controls on the primary screen.

## 45. Library

Organize Continue Watching, Recently Watched, Saved Projects, Dialogue Prepared for Offline Viewing (when true), Generated Subtitles, and Videos Needing Attention. Display title, thumbnail when available, progress, last position, language, readiness and relevant unresolved repair count. Use understandable labels such as Ready to watch, Preparing dialogue, Partially prepared, Needs attention, Dialogue available offline. Cached text does not make online video playable offline. Offer resume, restart, inspect, export and remove with predictable interactions; hide raw project files/IDs.

## 46. Viewing screen

The video dominates. During playback keep persistent controls to a minimum; tap deliberately to reveal familiar controls without breaking balloon taps or embedded player interactions. Primary controls: play/pause, back/forward seek, scrubbing, fullscreen, volume/mute and subtitle presentation. Secondary controls belong in an overflow or contextual sheet. Hide persistent Scout PiP, worker indicators, cast chips, technical counters, banners and editing controls by default. Use predictable placement and accessible touch targets; provide alternatives to gestures. Respect provider interaction restrictions.

## 47. Playing, paused and editing experiences

Playing: prioritize video, dialogue, and minimal readiness information only when needed; auto-hide unnecessary controls. Intentional user pause: show Heal This Moment, replay a short section, appearance, characters and navigation. Do not show healing just because the media or dialogue is buffering. Editing: explicitly reveal relevant edit controls and indicate editing mode. Preserve user-pause versus automatic-pause semantics throughout.

## 48. Healing as a signature interaction

**Pause. Mark. Heal. Continue.** Show a prominent “Heal this moment” action when intentionally paused, using an amethyst accent and consistent icon. One tap creates a valid timestamp-specific Beacon and confirms: “Moment marked. You can keep watching.” No required diagnosis.

Optional problem hints: missing dialogue, wrong speaker, incorrect timing, unstable bubble, wrong positioning, something else. Default adaptive scope; Quick, Scene and Chapter through secondary controls. Do not rewind or interrupt playback for repair.

## 49. Heal Beacon visualization

Small timeline markers distinguish Marked, Healing, Repaired, Needs attention and Undone with shape, text/semantics and color. Keep manual marks discoverable in Watch; filter automatic discoveries to Studio rather than flooding the consumer timeline. Tap a Beacon for a contextual sheet with timestamp, status, scope, plain-language explanation and relevant retry/review/undo actions. Do not expose model identifiers or raw metrics in the sheet.

## 50. Two timeline experiences

**Watch timeline:** playback position, duration, subtle prepared coverage layer, relevant chapter boundaries, manually marked Beacons and significant unavailable regions. Keep scrubbing simple and provide accessible marker descriptions. Differentiate media transport buffer, physical Scout position, verified dialogue coverage and Viewer position. Do not merge gaps visually or use unnecessary competing progress bars.

**Studio timeline:** multi-track editor where space permits, waveform, dialogue cues, speaker tracks, verified speech/silence, unverified and failed intervals, chapters, manual and automatic Beacons, repair history, precise timestamps and editable boundaries.

## 51. Intelligent buffering UI

Wire directly to the controller. Show calm contextual preparation when a seek enters an unprepared region. Example:

> Preparing dialogue at 18:42
>
> Playback will resume automatically when dialogue is ready.

Only promise automatic resumption if current user intent is to play. Provide appropriate Watch Now / Continue Anyway choice, explaining possible missing dialogue. Restrained progress animation; meaningful determinate progress comes from coverage required at the destination, not the whole video. No invented countdown or percentage. Replace failed preparation with actions for network, quota or unsupported media; avoid infinite spinners. Permit cancel, pause, another seek or navigation. Remove UI promptly on valid resumption.

## 52. Unified readiness language

Use structured controller and ledger states, never parse human-readable status strings. Consumer labels include Preparing dialogue, Ready to watch, Preparing upcoming dialogue, Preparing your new playback position, Healing this moment, Repair complete, Some dialogue unavailable, Reconnecting, AI usage limit reached, Media restricted, Original dialogue restored. Readiness must reflect the actual playhead; failed analysis is not silence; a distant cue is not sufficient. Avoid rapidly changing labels from every worker event.

## 53. Invisible agents by default

Scout, Responder and Patrol operate automatically. No three separate progress monitors, routine inspection notifications or developer telemetry in normal viewing. Use one consolidated readiness indicator; interrupt only for meaningful user action. Advanced users can inspect agents in Studio.

## 54. Professional Studio workspace

Organize into discoverable sections with progressive disclosure, not all controls simultaneously:

- **Overview:** project, coverage, unresolved problems, chapter preparation and repair activity.
- **Timeline:** waveform, timing adjustment, dialogue editing, speaker assignment and spatial anchor editing.
- **Characters:** known and unidentified speakers, corrections, colors, glossary, relevant voice/context information.
- **Healing:** manual/automatic Beacons, active/completed/failed repairs, comparison, undo and retry.
- **Appearance:** styles, text size, contrast, opacity, stable positioning, motion sensitivity, reduced motion, labels and tails.
- **Export:** supported subtitle formats, project export, supported video export and translation options.
- **Diagnostics:** readiness, Scout, Sentinel, ledger, media capabilities, timing, stability and errors.

## 55. Sentinel Inspector design

Show current worker responsibilities, active regions, manual queue, upcoming/completed chapter inspections, anomalies, verified repairs, deferral reasons, resource restrictions and unresolved issues. Use a navigable chapter list/timeline. Clearly differentiate Not inspected, Inspection queued, Inspecting, Inspected without defects, Repair required, Repair in progress, Repair verified. Inspection does not imply repair. Let advanced users initiate appropriate inspections and retries.

## 56. Repair review

Explain meaningful validated changes: restored missing dialogue, corrected speaker, improved timing, removed invalid pointer or corrected stale chapter context. Offer concise before/after comparison where possible without replaying the entire video. Keep undo accessible and accurate. Do not claim success absent validation.

## 57. Consolidated subtitle controls

Primary options: Speech bubbles, Traditional subtitles, Subtitles off, Subtitle language, Appearance. Move specialized modes to advanced options. Keep text size, contrast, speaker colors, stable positioning and reduced motion accessible; provide representative live style preview. No unnecessary playback changes or media restart for style or mode changes. Preserve existing speaker corrections and contextual actions, including original dialogue, rename/reassign speaker, move position, translation notes.

## 58. Balloon visual identity

Provide coherent presets, migrating equivalent existing styles and preferences:

- **Cinema:** restrained live-action dialogue.
- **Manga:** expressive comic lettering and forms.
- **Minimal:** lightweight text labels with minimal ornament.
- **Reading Comfort:** stable high-legibility presentation, restrained movement and optional tailless positioning.

Do not multiply redundant presets. Ensure natural wrapping, padding, contrast, consistent size and clear association. Protect essential expressions, existing graphics and action. Prioritize committed-position and anti-flicker rules over decoration.

## 59. Motion design

Use shared duration/easing tokens and restrained transitions for controls, sheets, navigation, Beacon confirmation, repair completion and new cue entry. No constant pulsing, repeated entrances, flashing, gratuitous springs, or progress animation that competes with dialogue. Respect reduced motion throughout. Stable reading takes precedence over animation aesthetics.

## 60. Responsive phones, foldables and tablets

Compact portrait: video-centered, efficient controls, contextual bottom sheets, minimal persistent chrome. Landscape: maximize video, contextual overlaid controls and safe areas. Expanded/foldable/tablet: large unobstructed stage, optional adjacent panels, Studio timeline and preview side-by-side, adaptive rail, collapsible/resizable workspaces when useful. Avoid occluding hinges. Preserve playback position, cue identity, analysis and repairs during rotation, folding, unfolding, resizing and multi-window; do not recreate media sessions merely for dimension changes.

## 61. Accessibility and cognitive comfort

Make the experience usable by people with visual fatigue, migraines, attention difficulties or reading challenges without requiring a diagnosis. Stable text, scalable comfortable typography, strong contrast, restrained motion, predictable navigation, concise messages, non-color distinctions, adequate touch targets and screen-reader semantics. Respect system accessibility and provide accessible Reading Comfort configuration. Minimize unnecessary visual stimulation and density.

## 62. First-time onboarding

Short, optional and demonstration-based: choose a video; see dialogue beside a speaker; change presentation; mark an imperfect moment and continue. Do not teach Scout, Patrol, workers or coverage intervals to ordinary viewers. Introduce advanced functionality contextually in Studio. Target first-time recognition of how to begin viewing within ten seconds; validate with usability testing.

## 63. Real UI state contract

Every control and displayed status must connect to real behavior from Playback Readiness Controller, TranscriptCoverageLedger, Scout scheduler, Sentinel scheduler, Beacon lifecycle, media capabilities, user intent, project persistence and stable layout coordinator. Separate presentation from analysis state. No mock statuses, fake progress, decorative controls without actions or simulated repair. Recomposition must not trigger redundant analysis. Avoid media recreation when opening menus, changing settings or switching related workspaces.

## 64. UX validation

Test end-to-end first-time launch; YouTube, local and web intake; subtitle language and presentation; prepared and unprepared seeks; auto-resume; limited-dialogue viewing; pause and mark; watching during repair; review and undo; resume saved media; recover failure; inspect Patrol; edit and export in Studio; folding/unfolding and rotation. Assess interaction count, discoverability, navigation, reading comfort, visual stability, accessibility, interruptions, status comprehension and recovery from mistakes. Map all existing meaningful controls to their new locations. Implement native UI, not just designs.

## 65. DialogueLens 2.0 release criteria

The technical and visual systems must work as one coherent product. UI is not complete if readiness, timing, healing and rendering are disconnected; technology is not complete if ordinary users have to supervise it. Protect original identity, signing, projects, locks, caches and preferences. Report implemented screens, navigation, design system, old-to-new feature mapping, structured readiness integration, Beacon interaction, Inspector functionality, adaptive layouts, accessibility, actual usability/regression results, performance and limitations.

---

# Visual implementation reference — proposed screen compositions

These are design directions, not screenshots or measured app states. Use representative real data in the implementation, never hardcode these example values.

| Screen | Composition |
|---|---|
| Home | Obsidian background; clean wordmark; “Every voice. Every story.”; graphite link field; jade Start watching button; local video option; one prominent resume item with thumbnail and progress; Watch / Library / Studio navigation. |
| Paused Watch | Video dominates; stable warm-white speech balloon; unobtrusive readiness and chapter label; single scrubber with subtle coverage, chapter ticks and manual amethyst Beacon markers; familiar controls; prominent amethyst Heal this moment button; contextual subtitles, cast and style controls. |
| Dialogue buffering | Calm, restrained preparation indicator and requested timestamp; meaningful status of locating media and preparing required continuous dialogue; explicit Watch Now with limitation; automatic resume only when valid. |
| Studio | Overview of actual verified coverage and unresolved regions; Responder and Patrol status in the advanced workspace; heal history with retry/review/undo; navigable Timeline, Healing and Diagnostics. |

# Final product manifesto

The interface is the cinema. The dialogue is the story.

The Viewer enjoys the story. The Scout prepares upcoming dialogue. The Responder corrects reported imperfections. The Patrol investigates likely trouble spots before they become visible problems. The Playback Readiness Controller ensures that all these systems cooperate without compromising viewing.

Buffer according to verified dialogue availability, not elapsed waiting time, physical Scout position or the presence of a nearby subtitle. Heal without destroying healthy dialogue. A camera cut should make the app reconsider where someone is, not forget who they are. Speech balloons should feel anchored, intentional, readable and dependable, not chase characters, fight for space or flicker between competing positions.

Every technical system should make the experience feel less technical. Every visual element should earn its place. Every interaction should feel intentional. DialogueLens 2.0 must not merely contain more intelligence; it must require less effort from the person using it.

**Make the extraordinary technology feel ordinary to use.**
