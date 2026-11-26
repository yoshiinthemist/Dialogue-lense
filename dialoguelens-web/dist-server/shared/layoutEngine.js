export const DEFAULT_SAFE_AREA = {
    top: 14,
    right: 14,
    bottom: 58, // Clear bottom control bar / subtitles safe area
    left: 14,
};
/**
 * Single authoritative content-to-stage coordinate transform.
 * Matches CSS `object-fit: contain` inside the video stage across portrait 9:16,
 * ultrawide 21:9, letterboxing, resize, and fullscreen.
 * Always bumps `measurementSeq` even if the next dimensions equal the previous ones.
 */
export function computeContentViewportRect(stageWidth, stageHeight, videoNaturalWidth, videoNaturalHeight, measurementSeq) {
    const safeStageW = Math.max(1, stageWidth);
    const safeStageH = Math.max(1, stageHeight);
    const safeVidW = Math.max(1, videoNaturalWidth || 16);
    const safeVidH = Math.max(1, videoNaturalHeight || 9);
    const stageAspect = safeStageW / safeStageH;
    const videoAspect = safeVidW / safeVidH;
    let contentWidth;
    let contentHeight;
    let contentX;
    let contentY;
    if (videoAspect > stageAspect) {
        contentWidth = safeStageW;
        contentHeight = safeStageW / videoAspect;
        contentX = 0;
        contentY = (safeStageH - contentHeight) / 2;
    }
    else {
        contentHeight = safeStageH;
        contentWidth = safeStageH * videoAspect;
        contentX = (safeStageW - contentWidth) / 2;
        contentY = 0;
    }
    return {
        stageWidth: safeStageW,
        stageHeight: safeStageH,
        contentX,
        contentY,
        contentWidth,
        contentHeight,
        scaleX: contentWidth / safeVidW,
        scaleY: contentHeight / safeVidH,
        measurementSeq,
    };
}
export function normalizedToStagePoint(point, viewport) {
    return {
        x: viewport.contentX + point.x * viewport.contentWidth,
        y: viewport.contentY + point.y * viewport.contentHeight,
    };
}
export function stageToNormalizedPoint(x, y, viewport) {
    return {
        x: clamp((x - viewport.contentX) / Math.max(1, viewport.contentWidth), 0, 1),
        y: clamp((y - viewport.contentY) / Math.max(1, viewport.contentHeight), 0, 1),
    };
}
export function clamp(val, min, max) {
    if (min > max)
        return (min + max) / 2;
    return Math.max(min, Math.min(max, val));
}
export function normalizeAnglePi(rad) {
    let a = rad;
    while (a > Math.PI)
        a -= Math.PI * 2;
    while (a <= -Math.PI)
        a += Math.PI * 2;
    return a;
}
/**
 * Shortest signed angular delta from `fromRad` to `toRad` in (-π, π].
 * Guarantees smooth crossing across the ±π discontinuity without spinning 360°.
 */
export function shortestAngleDelta(fromRad, toRad) {
    return normalizeAnglePi(toRad - fromRad);
}
/**
 * Computes the exact ray-to-contour intersection point for a rounded rectangle
 * centered at `(cx, cy)` with dimensions `(w, h)` and corner radius `r`.
 * Ray starts at `(cx, cy)` in direction `(cos(angle), sin(angle))`.
 */
export function intersectRoundedRectContour(cx, cy, w, h, r, angleRad) {
    const halfW = Math.max(1, w / 2);
    const halfH = Math.max(1, h / 2);
    const cr = clamp(r, 0, Math.min(halfW, halfH));
    const dx = Math.cos(angleRad);
    const dy = Math.sin(angleRad);
    const innerHalfW = Math.max(0, halfW - cr);
    const innerHalfH = Math.max(0, halfH - cr);
    // First intersect outer bounding box
    const tx = Math.abs(dx) > 1e-8 ? halfW / Math.abs(dx) : Number.POSITIVE_INFINITY;
    const ty = Math.abs(dy) > 1e-8 ? halfH / Math.abs(dy) : Number.POSITIVE_INFINITY;
    const tBox = Math.min(tx, ty);
    const px = dx * tBox;
    const py = dy * tBox;
    // If intersection lies on a straight edge segment, return immediately
    if (Math.abs(px) <= innerHalfW + 1e-6 || Math.abs(py) <= innerHalfH + 1e-6 || cr <= 0.5) {
        return { x: cx + px, y: cy + py };
    }
    // Otherwise intersect the corner quarter-circle centered at (sx * innerHalfW, sy * innerHalfH)
    const sx = Math.sign(dx) || 1;
    const sy = Math.sign(dy) || 1;
    const ccx = sx * innerHalfW;
    const ccy = sy * innerHalfH;
    // Solve |t*(dx, dy) - (ccx, ccy)|^2 = cr^2
    const b = -2 * (dx * ccx + dy * ccy);
    const c = ccx * ccx + ccy * ccy - cr * cr;
    const disc = Math.max(0, b * b - 4 * c);
    const t = (-b + Math.sqrt(disc)) / 2;
    return { x: cx + dx * t, y: cy + dy * t };
}
/**
 * Generates deterministic starburst polygon vertices for `shout` bubbles
 * in local coordinates centered at (0,0), plus exact ray-polygon intersection
 * so shout tails attach strictly within <= 1 CSS px of the actual rendered contour.
 */
export function buildShoutContourPoints(w, h, spikes = 16) {
    const rxOuter = Math.max(16, w / 2 + 6);
    const ryOuter = Math.max(12, h / 2 + 6);
    const rxInner = Math.max(12, w / 2 - 2);
    const ryInner = Math.max(10, h / 2 - 2);
    const pts = [];
    const total = spikes * 2;
    for (let i = 0; i < total; i++) {
        const a = (i / total) * Math.PI * 2;
        const isOuter = i % 2 === 0;
        pts.push({
            x: Math.cos(a) * (isOuter ? rxOuter : rxInner),
            y: Math.sin(a) * (isOuter ? ryOuter : ryInner),
        });
    }
    return pts;
}
export function intersectShoutContour(cx, cy, w, h, angleRad) {
    const pts = buildShoutContourPoints(w, h);
    const dx = Math.cos(angleRad);
    const dy = Math.sin(angleRad);
    let bestT = Number.POSITIVE_INFINITY;
    for (let i = 0; i < pts.length; i++) {
        const p1 = pts[i];
        const p2 = pts[(i + 1) % pts.length];
        const ex = p2.x - p1.x;
        const ey = p2.y - p1.y;
        const denom = dx * ey - dy * ex;
        if (Math.abs(denom) < 1e-8)
            continue;
        const t = (p1.x * ey - p1.y * ex) / denom;
        const u = (p1.x * dy - p1.y * dx) / denom;
        if (t > 0 && u >= -1e-6 && u <= 1 + 1e-6 && t < bestT) {
            bestT = t;
        }
    }
    if (!Number.isFinite(bestT)) {
        return intersectRoundedRectContour(cx, cy, w, h, 14, angleRad);
    }
    return { x: cx + dx * bestT, y: cy + dy * bestT };
}
export function intersectBubbleContour(body, angleRad) {
    if (body.styleVariant === 'shout') {
        return intersectShoutContour(body.centerX, body.centerY, body.width, body.height, angleRad);
    }
    return intersectRoundedRectContour(body.centerX, body.centerY, body.width, body.height, body.borderRadius, angleRad);
}
/**
 * Computes the expected clearanced tail tip point from a speaker's face observation.
 * Applies mouth clearance radially toward the bubble center and bounds maximum reach.
 */
export function computeExpectedTailTip(body, observation, viewport) {
    const mouthStage = normalizedToStagePoint(observation.mouth, viewport);
    const vx = mouthStage.x - body.centerX;
    const vy = mouthStage.y - body.centerY;
    const dist = Math.hypot(vx, vy);
    const angleRad = Math.atan2(vy, vx);
    const root = intersectBubbleContour(body, angleRad);
    const rootToMouth = Math.hypot(mouthStage.x - root.x, mouthStage.y - root.y);
    // Mouth clearance proportional to face box height (min 8px, max 24px)
    const faceHeightPx = observation.bbox.height * viewport.contentHeight;
    const mouthClearancePx = clamp(faceHeightPx * 0.16, 8, 22);
    // Bounded tail reach (max 42% of displayed video width, min 12px)
    const maxReachPx = Math.max(48, viewport.contentWidth * 0.42);
    const targetReach = clamp(rootToMouth - mouthClearancePx, 10, maxReachPx);
    if (dist < 1e-4) {
        return { tipX: root.x, tipY: root.y + 12, angleRad: Math.PI / 2 };
    }
    const ux = (mouthStage.x - root.x) / Math.max(1e-4, rootToMouth);
    const uy = (mouthStage.y - root.y) / Math.max(1e-4, rootToMouth);
    return {
        tipX: root.x + ux * targetReach,
        tipY: root.y + uy * targetReach,
        angleRad,
    };
}
export function hashCueText(event) {
    return `${event.originalText}|${event.translatedText ?? ''}|${event.styleVariant}`;
}
export function clampBodyCenterToSafeArea(centerX, centerY, width, height, viewport, safeArea = DEFAULT_SAFE_AREA) {
    const minLeft = viewport.contentX + safeArea.left;
    const maxLeft = Math.max(minLeft, viewport.contentX + viewport.contentWidth - safeArea.right - width);
    const minTop = viewport.contentY + safeArea.top;
    const maxTop = Math.max(minTop, viewport.contentY + viewport.contentHeight - safeArea.bottom - height);
    const left = clamp(centerX - width / 2, minLeft, maxLeft);
    const top = clamp(centerY - height / 2, minTop, maxTop);
    return {
        centerX: left + width / 2,
        centerY: top + height / 2,
        left,
        top,
    };
}
function rectsOverlapArea(a, b) {
    const ox = Math.max(0, Math.min(a.left + a.width, b.left + b.width) - Math.max(a.left, b.left));
    const oy = Math.max(0, Math.min(a.top + a.height, b.top + b.height) - Math.max(a.top, b.top));
    return ox * oy;
}
/**
 * Stable Bubble Body + Independent Active Tail Coordinator.
 *
 * Guarantees:
 * - Tailed and tailless modes share the EXACT same committed body anchor, bounds, cue identity, and entryCount.
 * - Unchanged cue content & viewport produces 0px (<= 1 CSS px) body center drift while target moves normally.
 * - Text updates reflow controlled bounds in place without blanking or replaying entrance.
 * - Tails use time-based smoothing with unwrapped ±π angles, exact contour roots (<= 1 CSS px),
 *   mouth clearance, crossing-face identity lock, distinct observationSeq grace, and instant cut/seek reset.
 */
export class BalloonPresentationEngine {
    committedBodies = new Map();
    activeTails = new Map();
    /** Tracks consecutive distinct missing observation sequences per cue (repeated layout frames don't burn grace). */
    missTracker = new Map();
    lastSceneId = null;
    lastMediaTimeMs = null;
    /**
     * Immediately clears incompatible tail/animation state on hard scene cuts or seeks
     * without dragging bodies across the previous scene's faces.
     */
    invalidateForCutOrSeek(reason, preserveValidBodyAnchors = true) {
        this.activeTails.clear();
        this.missTracker.clear();
        if (!preserveValidBodyAnchors || reason === 'seek') {
            this.committedBodies.clear();
        }
    }
    resetAll() {
        this.committedBodies.clear();
        this.activeTails.clear();
        this.missTracker.clear();
        this.lastSceneId = null;
        this.lastMediaTimeMs = null;
    }
    getCommittedBody(cueId) {
        return this.committedBodies.get(cueId);
    }
    /**
     * Resolves or preserves a stable committed bubble body for an active cue.
     * Deliberately ignores `displayMode` (bubbles vs tailless) so switching tails
     * NEVER moves, re-wraps, or replays the body!
     */
    resolveBubbleBody(input) {
        const safeArea = input.safeArea ?? DEFAULT_SAFE_AREA;
        const { event, measuredWidth, measuredHeight, viewport, observations, otherCommittedBodies, nowMonotonicMs } = input;
        const width = Math.max(60, Math.min(measuredWidth, viewport.contentWidth - safeArea.left - safeArea.right));
        const height = Math.max(36, Math.min(measuredHeight, viewport.contentHeight - safeArea.top - safeArea.bottom));
        const borderRadius = event.styleVariant === 'narration' ? 10 : event.styleVariant === 'shout' ? 8 : 18;
        const textHash = hashCueText(event);
        const existing = this.committedBodies.get(event.id);
        // Explicit manual anchor always wins and stays strictly pinned in content coordinates
        if (event.manualAnchor) {
            const targetStage = normalizedToStagePoint(event.manualAnchor, viewport);
            const clamped = clampBodyCenterToSafeArea(targetStage.x, targetStage.y, width, height, viewport, safeArea);
            const body = {
                cueId: event.id,
                speakerId: event.speakerId,
                sceneId: event.sceneId,
                centerX: clamped.centerX,
                centerY: clamped.centerY,
                width,
                height,
                left: clamped.left,
                top: clamped.top,
                borderRadius,
                styleVariant: event.styleVariant,
                isManualAnchor: true,
                entryCount: existing ? existing.entryCount : 1,
                committedAtMs: existing ? existing.committedAtMs : nowMonotonicMs,
                textHash,
                viewportSeq: viewport.measurementSeq,
            };
            this.committedBodies.set(event.id, body);
            return body;
        }
        // If a committed body already exists for this cue:
        // Keep its committed center LOCKED unless viewport changed, text resized beyond safe area,
        // or another bubble persistently overlaps it! Normal speaker motion NEVER moves the body!
        if (existing) {
            const sameViewport = existing.viewportSeq === viewport.measurementSeq;
            const sameText = existing.textHash === textHash;
            const sizeChanged = Math.abs(existing.width - width) > 0.5 || Math.abs(existing.height - height) > 0.5;
            if (sameViewport && sameText && !sizeChanged && !existing.isManualAnchor) {
                // Zero drift guarantee: return exact committed coordinates
                return existing;
            }
            // Controlled reflow / viewport rescale preserving committed center position
            const clamped = clampBodyCenterToSafeArea(existing.centerX, existing.centerY, width, height, viewport, safeArea);
            const updated = {
                ...existing,
                sceneId: event.sceneId,
                centerX: clamped.centerX,
                centerY: clamped.centerY,
                width,
                height,
                left: clamped.left,
                top: clamped.top,
                borderRadius,
                styleVariant: event.styleVariant,
                isManualAnchor: false,
                textHash,
                viewportSeq: viewport.measurementSeq,
            };
            this.committedBodies.set(event.id, updated);
            return updated;
        }
        // First-time placement for this cue: pick a readable non-overlapping candidate
        const matchedObs = findSupportedSpeakerObservation(event, observations);
        const candidates = [];
        if (event.speakerRole === 'narrator') {
            candidates.push({
                x: viewport.contentX + viewport.contentWidth * 0.5,
                y: viewport.contentY + safeArea.top + height / 2 + 6,
            });
        }
        else if (matchedObs) {
            const faceCenter = normalizedToStagePoint({
                x: matchedObs.bbox.x + matchedObs.bbox.width / 2,
                y: matchedObs.bbox.y,
            }, viewport);
            const faceHeightPx = matchedObs.bbox.height * viewport.contentHeight;
            // Primary: comfortably above speaker head, slightly offset toward center
            const offsetDir = matchedObs.mouth.x > 0.5 ? -1 : 1;
            candidates.push({ x: faceCenter.x + offsetDir * 24, y: faceCenter.y - height / 2 - 22 }, { x: faceCenter.x + offsetDir * (width * 0.45), y: faceCenter.y + faceHeightPx * 0.1 }, { x: faceCenter.x - offsetDir * (width * 0.45), y: faceCenter.y - height / 2 - 18 }, { x: viewport.contentX + viewport.contentWidth * 0.5, y: viewport.contentY + safeArea.top + height / 2 + 10 });
        }
        else {
            // Neutral upper-center/bottom-safe reading placement for unknown/offscreen cues
            candidates.push({
                x: viewport.contentX + viewport.contentWidth * 0.5,
                y: viewport.contentY + viewport.contentHeight * 0.18,
            }, {
                x: viewport.contentX + viewport.contentWidth * 0.5,
                y: viewport.contentY + viewport.contentHeight * 0.76,
            });
        }
        let bestCandidate = clampBodyCenterToSafeArea(candidates[0].x, candidates[0].y, width, height, viewport, safeArea);
        let bestPenalty = Number.POSITIVE_INFINITY;
        for (const cand of candidates) {
            const clamped = clampBodyCenterToSafeArea(cand.x, cand.y, width, height, viewport, safeArea);
            let penalty = 0;
            // Penalize overlapping other active bubbles
            for (const other of otherCommittedBodies) {
                if (other.cueId === event.id)
                    continue;
                penalty += rectsOverlapArea({ left: clamped.left, top: clamped.top, width, height }, { left: other.left, top: other.top, width: other.width, height: other.height }) * 10;
            }
            // Penalize covering any visible speaker face
            for (const obs of observations) {
                const tl = normalizedToStagePoint({ x: obs.bbox.x, y: obs.bbox.y }, viewport);
                const obsRect = {
                    left: tl.x,
                    top: tl.y,
                    width: obs.bbox.width * viewport.contentWidth,
                    height: obs.bbox.height * viewport.contentHeight,
                };
                penalty += rectsOverlapArea({ left: clamped.left, top: clamped.top, width, height }, obsRect) * 5;
            }
            if (penalty < bestPenalty) {
                bestPenalty = penalty;
                bestCandidate = clamped;
            }
        }
        const committed = {
            cueId: event.id,
            speakerId: event.speakerId,
            sceneId: event.sceneId,
            centerX: bestCandidate.centerX,
            centerY: bestCandidate.centerY,
            width,
            height,
            left: bestCandidate.left,
            top: bestCandidate.top,
            borderRadius,
            styleVariant: event.styleVariant,
            isManualAnchor: false,
            entryCount: 1,
            committedAtMs: nowMonotonicMs,
            textHash,
            viewportSeq: viewport.measurementSeq,
        };
        this.committedBodies.set(event.id, committed);
        return committed;
    }
    /**
     * Computes independent active SVG tail geometry anchored strictly to the rendered contour.
     * Respects:
     * - crossing-face identity lock (requires matching `linkedVisualTrackId`)
     * - cut/seek immediate invalidation
     * - distinct `observationSeq` grace (re-rendering the same missing seq does NOT burn grace)
     * - time-based smoothing with unwrapped ±π angle continuity (<= 150ms response)
     */
    resolveTailGeometry(input) {
        const { event, body, observations, viewport, currentObservationSeq, mediaTimeMs, nowMonotonicMs, reducedMotion, taillessMode, } = input;
        // Detect hard seek or scene cut and invalidate incompatible tail state immediately
        if (this.lastMediaTimeMs !== null &&
            Math.abs(mediaTimeMs - this.lastMediaTimeMs) > 750) {
            this.activeTails.clear();
            this.missTracker.clear();
        }
        if (this.lastSceneId !== null && this.lastSceneId !== event.sceneId) {
            this.activeTails.clear();
            this.missTracker.clear();
        }
        this.lastMediaTimeMs = mediaTimeMs;
        this.lastSceneId = event.sceneId;
        const hiddenResult = (reason) => ({
            cueId: event.id,
            visible: false,
            reason,
            rootX: body.centerX,
            rootY: body.centerY,
            baseLeftX: body.centerX,
            baseLeftY: body.centerY,
            baseRightX: body.centerX,
            baseRightY: body.centerY,
            tipX: body.centerX,
            tipY: body.centerY,
            angleRad: 0,
            lastObservationSeq: currentObservationSeq,
            lastUpdateMonotonicMs: nowMonotonicMs,
        });
        if (taillessMode) {
            return hiddenResult('Tailless mode enabled');
        }
        if (event.speakerRole === 'unknown' ||
            event.speakerRole === 'narrator' ||
            event.speakerRole === 'offscreen') {
            this.activeTails.delete(event.id);
            return hiddenResult(`No tail for ${event.speakerRole} cue`);
        }
        const matchedObs = findSupportedSpeakerObservation(event, observations);
        const prevTail = this.activeTails.get(event.id);
        if (!matchedObs || matchedObs.occluded || matchedObs.confidence < 0.35) {
            // Distinct observation sequence grace: repeated renders of the same sequence do NOT burn grace!
            const tracker = this.missTracker.get(event.id) ?? {
                lastProcessedSeq: -1,
                consecutiveMisses: 0,
            };
            if (currentObservationSeq !== tracker.lastProcessedSeq) {
                tracker.lastProcessedSeq = currentObservationSeq;
                tracker.consecutiveMisses += 1;
                this.missTracker.set(event.id, tracker);
            }
            // Allow up to 2 distinct missing observation frames before deliberately hiding tail
            if (prevTail && tracker.consecutiveMisses <= 2 && prevTail.trackId === event.linkedVisualTrackId) {
                // Re-anchor root strictly to current body contour (e.g. if body was dragged during grace)
                const recomputed = buildTailPolygonFromAngleAndTip(body, prevTail.angleRad, prevTail.tipX, prevTail.tipY, prevTail.trackId, currentObservationSeq, nowMonotonicMs);
                return recomputed;
            }
            this.activeTails.delete(event.id);
            return hiddenResult('Speaker visual track missing, offscreen, or occluded');
        }
        // Valid fresh speaker observation found — reset miss grace
        this.missTracker.set(event.id, {
            lastProcessedSeq: currentObservationSeq,
            consecutiveMisses: 0,
        });
        const target = computeExpectedTailTip(body, matchedObs, viewport);
        let smoothedAngle = target.angleRad;
        let smoothedTipX = target.tipX;
        let smoothedTipY = target.tipY;
        if (prevTail && prevTail.visible && prevTail.trackId === matchedObs.trackId) {
            const dtMs = clamp(nowMonotonicMs - prevTail.lastUpdateMonotonicMs, 0, 120);
            // Fast critically damped exponential smoothing (tau ~ 28ms => >95% converged well under 100ms,
            // guaranteeing <= 150ms presentation lag while staying silky smooth across ±π)
            const alpha = reducedMotion ? 1 : 1 - Math.exp(-dtMs / 26);
            const deltaAngle = shortestAngleDelta(prevTail.angleRad, target.angleRad);
            smoothedAngle = normalizeAnglePi(prevTail.angleRad + deltaAngle * alpha);
            smoothedTipX = prevTail.tipX + (target.tipX - prevTail.tipX) * alpha;
            smoothedTipY = prevTail.tipY + (target.tipY - prevTail.tipY) * alpha;
        }
        const tail = buildTailPolygonFromAngleAndTip(body, smoothedAngle, smoothedTipX, smoothedTipY, matchedObs.trackId, currentObservationSeq, nowMonotonicMs);
        this.activeTails.set(event.id, tail);
        return tail;
    }
    pruneInactiveCues(activeCueIds) {
        for (const id of [...this.committedBodies.keys()]) {
            if (!activeCueIds.has(id)) {
                this.committedBodies.delete(id);
            }
        }
        for (const id of [...this.activeTails.keys()]) {
            if (!activeCueIds.has(id)) {
                this.activeTails.delete(id);
            }
        }
        for (const id of [...this.missTracker.keys()]) {
            if (!activeCueIds.has(id)) {
                this.missTracker.delete(id);
            }
        }
    }
}
/**
 * Finds the speaker observation matching the cue's positive visual track association
 * inside the current scene. NEVER falls back to nearest-face heuristics when faces cross!
 */
export function findSupportedSpeakerObservation(event, observations) {
    if (!event.linkedVisualTrackId)
        return undefined;
    return observations.find((obs) => obs.trackId === event.linkedVisualTrackId &&
        obs.sceneId === event.sceneId &&
        obs.mouth.x >= 0 &&
        obs.mouth.x <= 1 &&
        obs.mouth.y >= 0 &&
        obs.mouth.y <= 1);
}
function buildTailPolygonFromAngleAndTip(body, angleRad, tipX, tipY, trackId, observationSeq, nowMonotonicMs) {
    const root = intersectBubbleContour(body, angleRad);
    const spreadRad = body.styleVariant === 'shout' ? 0.18 : 0.22;
    const leftContour = intersectBubbleContour(body, angleRad - spreadRad);
    const rightContour = intersectBubbleContour(body, angleRad + spreadRad);
    return {
        cueId: body.cueId,
        visible: true,
        rootX: root.x,
        rootY: root.y,
        baseLeftX: leftContour.x,
        baseLeftY: leftContour.y,
        baseRightX: rightContour.x,
        baseRightY: rightContour.y,
        tipX,
        tipY,
        angleRad,
        trackId,
        lastObservationSeq: observationSeq,
        lastUpdateMonotonicMs: nowMonotonicMs,
    };
}
