# DialogueLens — Google AI Studio Android implementation contract

**Version:** AIStudio-Android-2, November 26, 2026.  
**Purpose:** One self-contained specification for the next authorized implementation run in Google AI Studio, working on the existing Android project. This document is a proposed implementation contract, not a claim that the application has passed its tests.

## Scope and authority

Improve the existing native Android DialogueLens application. Preserve its application identity, useful controls, supported media sources, project data, and preferred bubble appearance. Keep the work in the Android project; a web implementation or web preview does not satisfy this delivery.

This document consolidates the Android repair requirements, source-first subtitle translation, and the rolling three-minute scout policy. Use its requirements and acceptance cases as the current product contract. Earlier prompts and audit reports are historical evidence when their wording differs. Later explicit user instructions take precedence. This document does not override platform access controls or authorize unrelated features.

The goal is one complete, accurate, stable viewing run: Scout 1 prepares upcoming dialogue using available original evidence; Scout 2 checks and repairs it continuously; the viewer receives accepted results through one publication path. Uncertainty must remain honest. Do not fabricate confidence to satisfy a completion target.

## Execution in AI Studio

**Reviewed baseline:** Android v33, [commit 2e3aed0c](https://github.com/yoshiinthemist/Dialogue-lense/commit/2e3aed0cb36dae027434f3bdf0c8da4d57b13c1a). Preserve its rolling-boundary policy, clipping helpers, idle outcomes, review backpressure, persisted scheduler state, and scrubber-preview handling. Finish their production integration; do not create another coordinator. The remaining targets include dispatch after seeks/decoding, clipped-window remainders, wake-up and restoration consistency, actual Pause behavior, the active YouTube CC path, and honest readiness. Its new helper tests are useful foundations; source inspection does not establish passing real-media behavior.

Inspect the Android project actually loaded in this workspace and record its source/build identity. Establish which editing, build, test, emulator, source-export, and signing facilities are available. Use the current implementation as the starting point; preserve fixes that already work. Continue unblocked work if a particular verification capability is unavailable, and record the resulting limitation precisely.

Create a concise requirement-to-production-path-and-test checklist using the IDs below. Proceed through implementation, integration, available verification, and correction of observed failures in the same run. Keep a small status record so interrupted work can resume from completed changes. Use existing libraries and official documentation where they solve the problem. Resolve routine implementation choices autonomously within scope.

Apply changes in dependency order: ownership and session validity; evidence and publication; scheduling and actual transport; rendering and persistence; integrated acceptance. This is an execution sequence, not permission to stop after the first stage.

## Required behavior

### R01 — One owner for each decision

Establish one authority for each of these responsibilities: work eligibility and claims; playback intent and actual transport; acceptance and publication of content; committed bubble-body placement.

Workers execute or explicitly requeue their exact claimed task. A worker cannot independently publish a competing dialogue revision, issue a contradictory transport decision, or silently mark unsupported work complete. Migrate production callers to the relevant authority; remove or delegate superseded decision paths. Adding another manager without connecting existing callers is insufficient.

Apply eligibility and generation checks to required review claims too. A rejected or unavailable claim must remain pending with a wake path; it cannot silently fall through to an unclaimed draft review.

### R02 — Define what is ready and what is verified

Track these concepts separately:

| Concept | Required meaning |
| --- | --- |
| Evidence acquired | Original captions, audio, or frames are available with identity and timing. |
| Draft prepared | A candidate interpretation exists; it has not necessarily passed review. |
| Review completed | The required checks reached supported outcomes or an honest unresolved outcome. |
| Accepted/playable | A revision is approved for presentation at its declared quality level, including a permitted fallback. |
| Verified | The particular quality dimension was actually checked against appropriate evidence. |
| Unavailable | No usable result exists after bounded recovery, with the reason retained. |

Keep source wording, translation, speaker identity, timing, and presentation support separate. Available audio, a nonblank translation, upstream confidence, or two scouts agreeing cannot alone verify those dimensions.

Measure contiguous playable coverage and contiguous verified coverage independently from the viewer position. A gap interrupts the corresponding lead. A good cue farther ahead does not certify intervening media. Verified silence requires affirmative evidence; absent captions do not establish silence.

Define **verified dialogue lead** as continuous accepted intervals whose source wording and timing were checked against admissible original evidence, whose translation was checked when required, and whose speaker attribution is supported. A required unchecked dimension, uncertain-identity fallback, or missing interval stops that aggregate lead. Record the actual wording-check method: caption alignment must not be labeled independent audio verification. Track presentation review and actual displayed-geometry validation separately; future dialogue coverage must not falsely claim that future rendered pixels were already validated. These distinctions must not prevent explicitly accepted fallback from contributing to playable lead.

### R03 — Native captions and source-first translation

Before Scout 1's source pass, discover available native captions, choose the appropriate track/language, enable CC where the supported integration permits it, and retain it. Check activation separately from access to timestamped original cues. Record off, loading, unavailable/blocked, enabled-but-inaccessible, and enabled-with-no-current-cue distinctly.

Trace the actual production YouTube Scout 1 evidence path. A dormant scout-only helper is insufficient. Verify activation through ready-time updates and mobile fallback, including active-track changes with an unchanged available-track count.

Bound setup and recovery. Recheck after relevant source/track changes or actual interruption; do not repeatedly toggle a healthy track. If activation or cue access is impossible, report the specific capability and use the fallback policy. Viewer overlay preferences must not disable the scout's evidence or force duplicate subtitles over bubbles.

Use this logical flow: original-language captions from accessible native/imported tracks or actual audio → stable source phrases → selected-language translation → reviewed, accepted dialogue. Reuse usable original captions; transcribe accessible audio for missing or disputed evidence under R11. Demonstrate the actual caption/audio input instead of treating a system caption overlay as an app-readable feed. Keep original references separate from generated dialogue; a translated track must not masquerade as the original.

Retain media/cue identity, source spans, original timestamps, actual coverage, track/authorship or ASR provenance, detected/selected language, and source revision. Resolve language per relevant segment, including language switches. Auto-Detect is unresolved, not a source/target match. For matching languages, use faithful source wording directly; readable segmentation does not authorize paraphrasing or an unnecessary translation call.

Distinguish provisional recognition from stable source phrases. Translate meaningful phrases with bounded neighboring context and established terminology; preserve source-span mappings and speaker boundaries when translated word order or cue lengths differ. During lookahead, settle drafts before viewer playback. For genuinely live sources already supported, measure and bound stabilization delay rather than waiting indefinitely or displaying every speculative update. A final recognition result is not independent verification.

Bind each translation to the exact source cue revisions, source/target languages, and relevant context/configuration. A substantive source correction invalidates its dependent translation and queues targeted repair. Missing/failed translation remains incomplete; copying foreign source text into a target field cannot count as translation. A supported provider may return transcript and translation together: preserve and validate both separately without requiring duplicate recognition, two providers, or two network calls. The combined response does not independently verify itself.

### R04 — Continuous and independent review

Scout 2 reviews each prepared section while Scout 1 continues. It must inspect original evidence rather than relying only on Scout 1's interpretation. A second model call is not inherently necessary for deterministic caption checks; an independent audio or translation claim requires the corresponding check to have occurred.

Reconcile in both directions: detect unsupported draft content and source-supported dialogue omitted from a nonempty draft. Empty-draft recovery must also publish recovered words instead of leaving a silence flag. Verify each supported cue/subinterval; one matching line cannot certify its neighbors or a whole interval.

Check source wording/timing against appropriate original-language evidence and translation meaning against that source, including negation, quantities, entities, idioms, and tone. Cross-language verification is not literal string equality. Preserve original text and translate recovered dialogue when required. Failed or omitted translation IDs remain incomplete and eligible for bounded recovery. Publish coherent accepted revisions; a source repair cannot leave an obsolete target translation approved for display.

### R05 — A hard rolling boundary

For automatic playback-attached scout analysis, the maximum submitted media endpoint is:

`min(known video end, acknowledged viewer position + 180,000 ms)`

If duration is unknown, use viewer position plus 180,000 ms until a reliable end becomes available. Anchor the boundary to the viewer, not a scout, wall-clock progress, or a scrubber preview. The 180 seconds are media time; at 2× speed they provide 90 seconds of viewing time.

All new automatic inference, review, repair, and patrol tasks share this eligibility boundary. Validate the actual submitted interval after chunking, overlap, and timestamp conversion. Split or defer a crossing interval; never round its end past the ceiling or mark an unprocessed remainder complete.

Revalidate at each actual provider invocation after decoding, suspension, and retry delays, including capture callbacks and model fallbacks. Retain the immutable task generation; earlier planner approval does not authorize a new request after the viewer seeks. Record completed interval extents so a clipped window's unfinished remainder becomes eligible when the horizon advances; a start timestamp alone cannot represent partial completion.

Fetching and caching a complete available caption track is allowed. Reusing valid cached evidence is allowed. Neither authorizes new full-video inference. Protect acceptance and revision checks for every producer, including inexpensive, offline, and cached paths.

The rolling boundary must not recreate the old defect where only the first three minutes of a source were processable. Source adapters must support later intervals. Speech at 04:30 becomes eligible when the viewer's moving window includes it. This task does not add an automatic prepare-to-end feature.

### R06 — Priority, pauses, idle, and wake-up

Prioritize current/imminent missing or incorrect dialogue, required review nearest the viewer, additional preparation within the boundary, then specifically identified remaining quality issues. Bound drafts awaiting review so Scout 1 cannot fill three minutes while Scout 2 falls behind. Reserve progress for required review even when the playable lead is low.

When the user pauses at 02:00, eligible scouts may continue within the fixed boundary ending at 05:00 while the app remains active. Finish required review and targeted repair, then idle. Do not keep regenerating clean sections. An unresolvable near-term gap must reach a bounded terminal outcome instead of monopolizing all workers.

Exhaustion is interval-specific. Before idling, consider all remaining eligible work. A short deferred fragment or one exhausted gap must not hide a later processable interval or required review; preserve its unresolved coverage while other work progresses.

Distinguish ready through the current horizon, waiting for evidence/capability, unresolved after exhausted recovery, and whole-video completion. A null next-task result must not permanently kill the scheduling loop or report the whole video complete.

Wake eligible work on meaningful viewer advancement, committed seek, preview end/cancellation, relevant new evidence/configuration, explicit retry, or restored capability. Wake-up delivery must survive an event arriving between the eligibility check and worker suspension. Use useful refill batches and distinct restart/stop thresholds to prevent request chatter. Timer ticks, status updates, and the scout's own publication must not endlessly trigger paid re-review. Checkpoint and recover after Android lifecycle interruption.

### R07 — One playback policy, applied to the actual player

Resolve explicit user intent, playable coverage, pending review, deadlines, and gap-scoped overrides in one policy. Apply its decisions to the real player for every supported source. Derive the transport icon, preparation overlay, and status from that same resolved state. A hold must actually pause media.

User Pause always prevents automatic resume. The Pause control must never invoke Watch Now/Continue. Those are separate actions. A Continue decision applies only to the accepted gap; a later gap is assessed again. Resume automatically only when the user's current intent permits it.

Use the same fallback policy for opening, seeks, catch-up, mid-playback gaps, and end tolerance. After a bounded preparation/review deadline, usable correctly timed captions or validated transcription may become explicitly unverified fallback. Unsupported identity uses a stable neutral presentation without an unjustified tail; missing translation remains explicit.

If no usable text exists after recovery, exit indefinite buffering into an honest unavailable/partial state with the existing Continue/Retry choices. Never silently force playback or display stale dialogue. Tune and document readiness thresholds and deadlines from measured behavior; the three-minute ceiling is not a requirement to fill the entire window before starting playback.

### R08 — Session, seek, revision, and protected edits

Use one activation contract for new, local, YouTube, web-media, saved, prepared, and resumed projects. Initialize intent, coverage, queues, release state, retries, tracking, measurements, and project-scoped Undo consistently.

Restore or reset the shared generation consistently across readiness, scheduler, release store, UI/player callbacks, and work requests. Never compare a restored scheduler generation against a freshly zeroed callback generation. Old callback identity must be captured at origin, not replaced with the current UI generation on receipt.

Capture immutable media/session identity, seek/configuration generation, task/draft revision, source-cue revision, and evidence identity at dispatch. Validate them at receiving boundaries before any live mutation. Do not relabel old frames or results with the new session's identity.

Scrubber drag previews do not dispatch destination work. A committed seek establishes a new generation and an explicit acknowledgement policy; old callbacks cannot move its boundary backward. Cancel or deprioritize obsolete queued work and request cancellation of unnecessary in-flight work when supported. Already dispatched calls may still finish: retain only compatible cached results, and prevent stale presentation, transport, or protected-content effects. Normal playback advancement must not cause cancellation/resubmission churn.

Publish text, attribution, quality/coverage consequences, and revision coherently through one acceptance boundary. Include cloud, cached, and offline translations and repairs. Display, persistence, and relevant exports consume accepted revisions. Validate outcomes against pending and already released revisions. Preserve user-protected text, timing, identity, and placement fields; obsolete translations cannot overwrite corrections.

### R09 — Speaker identity and shared body locking

Keep request-local A/B speaker slots local until evidence supports continuity. Metadata names remain suggestions. Separate identity, active speech, and screen-location confidence. Proximity, a tied side score, or a fixed mouth-activity value cannot establish identity. Propagate corrections only with positive, non-null identity/continuity evidence and provenance; unrelated null-track cues remain separate.

Share one committed, measured reading anchor between tailed and tailless bubbles. Preserve the preferred shapes, typography, colors, labels, padding, and useful mode controls. Ordinary speaker movement changes the supported tail, not the body. Presentation-only toggles retain position, measurement, and entry state, including Fixed Zones and Reading Comfort behavior.

Preserve stable cue/utterance identity across overlapping windows, rediscovery, translation, and correction. Track revisions and playback activation separately; updating the same utterance must not replay its entrance or reset its committed body anchor.

Allow controlled placement changes for user drag, genuine scene transition, relevant viewport/content-size change, or persistent body obstruction. Accepted text corrections remain visible and are remeasured when needed. Tail obstruction alone must not drag the body. Preserve a valid anchor or move directly to an appropriate new placement; avoid animation through obsolete scene geometry.

Bind tails to supported identity, track instance, scene, and observation generation. Use fresh observations, time-based smoothing, and one content-to-stage transform including letterboxing/orientation. Attach the root to the actual rendered body boundary continuously through all angles. Hide/reacquire uncertain, offscreen, or invalid tails; never transfer one to a nearby listener merely by proximity.

Define tail reach and clearance limits. Hide or safely reroute an invalid or obstructed connection without dragging the committed body.

Distinguish media time, capture timestamp/sequence, monotonic animation delta, and elapsed session deadlines. Repeated layouts are not new observations. Cuts/seeks invalidate old evidence and incompatible animation. Obtain acknowledged measurements after invalidation even when dimensions are unchanged; collision/masking bounds must cover actual rendered content. Manual bubble coordinates are not speaker-location evidence.

### R10 — Actual media and presentation evidence

Validate finite, ordered cue times against the actual supplied clip. Preserve source timing separately from reading duration and revalidate after splitting/pacing. Maintain raw rolling-caption windows separately from emitted suffixes; remove duplicate prefixes without deleting legitimate repeated speech or unrelated new lines.

Declare capture active only after successful recorder startup; reset and permit retry on failure/end/source replacement. Supply decodable clips with actual boundaries and generation. Re-anchor capture across seeks/rate changes; honor decoder format and correct upsampling/downsampling, including 8 kHz input. Missing or truncated stream segments yield actual partial coverage, never the requested interval by assumption.

Review future presentation using interval-appropriate observations and measured geometry. Current viewer faces cannot prove a future speaker position. Re-evaluate repaired placement and clear rejected attachments from neutral fallback. Distinguish detector failure from a successful no-face observation. Use adjacent valid pixels for cuts and invalidate masked history so newly exposed pixels do not create false transitions.

### R11 — Request policy and bounded recovery

Immediately before every provider attempt and retry, enforce current session, Free CC Only policy, quota circuit, and request/time/attempt budgets. Free CC Only makes zero AI-provider attempts, including queued capture work, retries, and cheaper-model fallbacks. Changing models does not authorize a request.

Switching to Free CC Only cancels undispatched provider work and requests cancellation of unnecessary in-flight calls where supported. Already dispatched calls may still finish; retain compatible evidence without further calls and apply current revision/acceptance checks before any publication. Do not claim the switch undoes a request already sent.

Apply finite budgets to partial recovery and failure alike. Session elapsed deadlines continue while playback is paused or buffering. Preserve salvageable cues and allow other eligible work to progress. An owned request timeout becomes a typed bounded outcome; actual parent cancellation propagates and cleans up in-progress state.

Treat a media URL, permitted destination-scoped request context, and descriptor revision as one input. Adopt richer valid same-URL descriptors. Validate WebView evidence at reception for origin/frame/session, payload size/rate/type, and time range; a navigation allowlist alone is insufficient. Invalid evidence cannot enter accepted content or trigger provider spending. Use synthetic credentials in tests.

### R12 — Persistence, recovery, and supported exports

Restore saved projects through the common activation contract and resume eligible missing-interval acquisition even when some cues already exist. Imported/native text locks protect existing content without suppressing gap diagnosis. Migrate obsolete verification labels without deleting valid text or user edits.

Merge progress and content saves through the project authority. Freshness follows accepted revision/order, including intentional backward seeks, rather than maximum playback time. Retain compatible evidence across cost/model switches; invalidate content-affecting source/language/glossary changes appropriately.

Keep supported exports consistent with accepted content and explicitly describe any visual-export limits. Preserve storage compatibility with the declared Android minimum SDK through a supported atomic writer/desugaring strategy. Preserve existing signing arrangements and application identity. Ensure the source can be built reproducibly through the available supported Android build workflow without distributing private keys.

### R13 — Honest, quiet status

Make healing acknowledgement transient. Then show the marked time and actual queued/checking/repaired/needs-attention state. Background work should not cover the picture with competing status messages. A genuine hold gets one consistent presentation. Put provider/model diagnostics in the inspector.

Every update/publication path must derive readiness from the same resolved playback, coverage, and scheduler inputs. Existing dialogue somewhere in the project does not establish that the current window is ready. Display the actual prepared span, including a shortened window near the end. Preserve the preferred visual design. If the exact earlier preferred build is unavailable, capture a current baseline and preserve the stated characteristics without claiming historical parity.

## Acceptance and evidence

Use production callers and independently authored expectations. Unit tests must not merely repeat implementation decisions. Distinguish source inspection, test creation, executed automated checks, emulator/device execution, and real-media playback. Each row below can require several fixtures; all listed assertions must be addressed. Missing tools, device access, credentials, or source access produce Blocked or Not run. Not applicable is reserved for a genuinely excluded or irrelevant product case with a specific justification; it cannot waive a required capability because this workspace cannot test it.

Observe actual provider invocations with controlled spies and actual player commands/UI actions. Permission Booleans, coordinator snapshots, unrelated locked-event constants, or a test fixture containing the YouTube URL cannot substitute for those assertions or the separate real-video run.

### Core regression matrix

| ID | Required scenario and observable result | Requirements |
| --- | --- | --- |
| T01 | Deplete playable lead during actual playback: media pauses and UI agrees. Press the actual Pause control during a hold; Pause survives review completion. Watch Now is separate and exempts only its gap. Opening/seek/mid-playback/end use one policy. | R01–02, R07 |
| T02 | Give a nonempty draft one correct cue while the original reference contains another omitted cue: recover the missing words or keep their subinterval unresolved. Empty/silence drafts with admissible speech recover visible text and coverage together. | R02–04 |
| T03 | Supply confidently wrong wording, negation, quantity, entity, translation, or identity despite audio being available: unsupported dimensions cannot become verified. Include foreign-language Auto-Detect, code switching, an idiom, unrelated reference language, and Japanese-to-English empty-draft recovery. Once languages resolve to match, preserve source wording without redundant translation requests; necessary combined recognition remains permitted. Blank target output remains incomplete. | R02–04, R09 |
| T04 | Correct protected text/timing/identity/placement while older cloud, offline, cached, or review results remain pending: no path overwrites the correction. Correct source wording or change target language mid-request: reject stale translations and repair affected targets, preserving the committed anchor subject to R09's measured geometry exceptions. A newer released revision rejects an older outcome. Display/save/export agree. | R03–04, R08–09, R12 |
| T05 | Pause at 02:00 while Scout 1 advances: boundary remains 05:00; an imminent gap outranks distant exploration; Scout 2 continues. Settled or exhausted work stops issuing requests and preserves Pause. | R05–07, R11 |
| T06 | Submit overlapping/whole-second-rounded chunks crossing a boundary, including a fractional playhead: actual submitted endpoints stay inside it. Clip a nominal 02:55–03:02 window at 03:00, then advance: 03:00–03:02 remains eligible in preloaded and generated paths. Near the end, tasks stop at actual duration. | R05, R10 |
| T07 | Resume to 02:20: boundary becomes 05:20 and useful work restarts. Check 0.5×, 1×, and 2× without changing the media-time ceiling. Ready-to-horizon does not mean whole-video complete. | R05–07 |
| T08 | Test actual preview drag/cancellation while paused: a waiting worker resumes eligible work without playing the viewer. Inject a wake just before suspension. Seek backward while decoding or awaiting a retry, then release it: no obsolete interval reaches a new provider call. Check captured-audio callbacks, already in-flight results, immutable callback identity, and compatible cache retention. | R05–06, R08, R11 |
| T09 | Cache a complete caption track without full-video inference. For media longer than three minutes, process speech at 04:30 once eligible. Reopen an incomplete project and seek into an unprepared interval: bounded acquisition resumes. | R03, R05, R12 |
| T10 | Leave a middle gap while later sections are accepted: continuous playable/verified lead reflects the gap at its actual quality level. Fallback may be playable without becoming verified. Exhausted recovery with no usable text exits buffering into an honest unavailable state. | R02, R06–07 |
| T11 | Repeated partial responses exhaust budgets and retain recovered cues. Pair an exhausted gap, or a first gap shorter than the minimum slice, with a later processable interval and required review: eligible work continues. Advance an injected elapsed clock while paused: deadline fires once. Timeout differs from parent cancellation; replacement cancels old work. | R06, R08, R11 |
| T12 | Switch to Free CC Only during decoding/capture and with retries queued: zero new AI attempts. Trip the quota circuit: later chunks/retries stop. Evidence/model changes do not cause unnecessary paid regeneration. | R11–12 |
| T13 | Load project B after editing/seeking A, then Undo or deliver late A frames/captions/results: B stays intact. Verify consistent activation for every supported source. Unsupported or mismatched claimed tasks cannot vanish as completed. | R01, R08 |
| T14 | Exercise the actual YouTube Scout 1 path: enable available CC and verify cue access separately. Ready-time updates, mobile fallback, viewer overlays, and active-track changes with unchanged track count preserve correct state. No-current-cue is not silence/failure; same-time corrections and inaccessible tracks retain honest outcomes. | R03–04 |
| T15 | Independent clips reuse slot A; two unrelated cues have null tracks; metadata names or tied geometry suggest a person: no unsupported identity or correction propagation. Valid linked continuity still propagates correctly. | R08–09 |
| T16 | Record normal motion, crossing speakers, tailed/tailless and presentation-mode toggles: body anchors and entry remain stable; supported tails follow. Rediscover/correct the same utterance without replaying entry. Sweep tail directions through a full circle and compare actual drawn attachment; excessive-distance or obstructed tails respect reach/clearance without dragging bodies. | R09 |
| T17 | Cut, seek, rotate, resize, or correct text: invalidate appropriate observations/measurements and transition coherently. Repeated layouts do not consume new-observation grace. Portrait/ultrawide coordinates, letterboxing, collisions, and capture masks match real displayed bounds. | R09–10 |
| T18 | Future-placement repair uses correct-time evidence and is reassessed. Neutral repair cannot restore a rejected tail. Detector failure is distinct from no faces; newly unmasked pixels do not create a false cut. | R09–10 |
| T19 | Test expanding/revised source captions, legitimate repeated speech, and translation spanning multiple cues: preserve source mappings and stable accepted display identity. Measure visible text replacements and time to stable output. A 151-character cue at 48,000–50,000 ms survives real pacing/splitting without inverted bounds. Partial translation output completes only accepted returned IDs. | R03–04, R09–10 |
| T20 | No-track-then-track capture retries successfully; transported clips decode; seeks/rate changes preserve timing. Sample-rate fixtures preserve duration/pitch. Missing/truncated streams and out-of-clip generated cues cannot certify full coverage. | R10 |
| T21 | Reject stale/unauthorized frames, origins, oversized payloads, and out-of-range evidence before mutation or spending. Preserve authorized embedded sources. A richer same-URL descriptor supersedes its older extraction context. | R08, R11 |
| T22 | Race progress/content saves in both orders, including backward seek. Through the real ViewModel, seek several times, save, reload, resume, and seek again: generations agree, acknowledged playback advances the boundary, and newly eligible work dispatches. Preserve content/resume position, migrate old verification claims, and check Undo/export consistency. | R08, R12 |
| T23 | Exercise saving/reloading on supported Android versions, including API 24/25/26 where declared supported and available. Run a clean supported build; record actual installation/update separately from compilation. | R12 |
| T24 | Mark a heal beacon, then transition through queue/review/repair/hold and content updates: acknowledgement expires; status, marked time, icon, and actual playback agree. Earlier/future cues cannot produce an unsupported current-ready label. Display actual remaining prepared duration without model-debug banners covering the video. | R07, R13 |

### Required real-video run

Use exactly: https://youtu.be/RzagPoxaolQ?is=IETdpKn91KSN9Ih_

Create a fresh project using the ordinary Android user flow. Establish caption/evidence availability and compare dialogue against accessible original audio and captions. Do not hard-code this video's words, speakers, cut times, or success states.

Complete a continuous 1× watch-through, then a separate pause/resume/seek/speed/repair interaction pass. Revisit 00:45, 01:59–02:01, and 02:29. Record actual motion and audio where available; screenshots alone cannot establish stable motion, correct speech, or a completed repair. Record real source-access limitations rather than substituting mock playback for this requirement.

Capture source/build identity, device/emulator configuration, dispatched intervals and boundary, contiguous playable and verified lead, end-to-end accepted-media throughput, relevant latency/retry data, idle provider-call count, and unexpected holds. Check actual body/tail output and cue timing against independent expectations. Interpret processing throughput relative to playback speed; do not count overlapping or repeatedly processed seconds as new completed coverage.

Also use a short authorized foreign-language fixture with independently checked source words and target meaning. Exercise native-caption reuse and missing-caption audio transcription, language detection, source correction, and translation publication. The required YouTube watch-through alone does not establish multilingual accuracy. Record visible replacements and stable-output latency alongside semantic correctness; settling text must not introduce unbounded holds or unsupported verification claims.

## Handoff and completion

Deliver `DialogueLens_<version>_<source-id>_handoff.zip` with the installable Android APK, matching source identity or complete source snapshot, `HANDOFF.md`, and `verification/RESULTS.md` plus indexed evidence from checks actually executed. Use the existing supported signing/build arrangements. Include version, application ID, build type, APK SHA-256, reproduction steps, and whether installation/updating was tested. Never attribute uncommitted build changes solely to a base commit.

Map R01–R13 and T01–T24 to implementation disposition and verification status separately. Use **Pass, Fail, Blocked, Not run, or Not applicable** with specific evidence/reasons. Tests being added, source inspection, compilation, emulator execution, and successful real-media behavior are different claims.

If AI Studio exposes only part of the required artifact or execution workflow, finish available changes and provide the exact export/build/testing continuation. A missing APK/source or unrun required real-video test remains an incomplete or unverified handoff. Do not invent an artifact or a successful test. Preserve existing release/publishing arrangements; this contract requires a reviewable build and does not itself request a public release.

## Execute

Read the entire contract, identify the loaded Android project and available verification facilities, and map the requirements to actual production paths. Inspect, implement, integrate, run the relevant checks, repair observed failures, and produce the evidenced handoff. Preserve all hard requirements while choosing routine internals and measured tuning values. If blocked, finish the unblocked work and leave a precise continuation record; do not replace the Android deliverable with another platform or claim complete verification.
