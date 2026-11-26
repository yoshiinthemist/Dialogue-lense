import {
  BalloonPresentationEngine,
  buildShoutContourPoints,
  computeContentViewportRect,
  stageToNormalizedPoint,
} from '../../shared/layoutEngine.js';
import type {
  CommittedBubbleBody,
  ContentViewportRect,
  DialogueEvent,
  DisplayMode,
  FaceObservation,
  NormalizedPoint,
  TailGeometry,
} from '../../shared/types.js';

export interface PresentationFrameResult {
  viewport: ContentViewportRect;
  bodies: CommittedBubbleBody[];
  tails: TailGeometry[];
  mediaTimeMs: number;
  observationSeq: number;
}

/**
 * Single authoritative Presentation Controller.
 * Owns actual measured DOM bubble bounds, stable body placement, and independent SVG tails
 * driven directly inside rAF without pushing every tracking frame through React state!
 */
export class PresentationController {
  private readonly engine = new BalloonPresentationEngine();
  private viewportSeq = 0;
  private lastViewport: ContentViewportRect = computeContentViewportRect(640, 360, 640, 360, 0);
  private lastBodies = new Map<string, CommittedBubbleBody>();
  private lastTails = new Map<string, TailGeometry>();
  private renderedEntryCounts = new Map<string, number>();

  /**
   * Invalidates and acknowledges new measurements EVEN IF the new dimensions equal
   * the previous dimensions (e.g. orientation/fullscreen round-trip).
   */
  updateViewport(
    stageWidth: number,
    stageHeight: number,
    videoNaturalWidth: number,
    videoNaturalHeight: number,
  ): ContentViewportRect {
    this.viewportSeq += 1;
    this.lastViewport = computeContentViewportRect(
      stageWidth,
      stageHeight,
      videoNaturalWidth,
      videoNaturalHeight,
      this.viewportSeq,
    );
    return this.lastViewport;
  }

  getViewport(): ContentViewportRect {
    return this.lastViewport;
  }

  invalidateForCutOrSeek(reason: 'cut' | 'seek'): void {
    this.engine.invalidateForCutOrSeek(reason, reason === 'cut');
  }

  resetAll(): void {
    this.engine.resetAll();
    this.lastBodies.clear();
    this.lastTails.clear();
    this.renderedEntryCounts.clear();
  }

  getSnapshot(): {
    viewport: ContentViewportRect;
    bodies: CommittedBubbleBody[];
    tails: TailGeometry[];
  } {
    return {
      viewport: this.lastViewport,
      bodies: [...this.lastBodies.values()],
      tails: [...this.lastTails.values()],
    };
  }

  convertStageDragToNormalizedAnchor(stageCenterX: number, stageCenterY: number): NormalizedPoint {
    return stageToNormalizedPoint(stageCenterX, stageCenterY, this.lastViewport);
  }

  /**
   * Imperatively measures active HTML bubble nodes, resolves committed body positions &
   * independent SVG tails, and applies transform/SVG attributes directly to DOM elements.
   */
  renderFrameToDom(params: {
    activeEvents: DialogueEvent[];
    observations: FaceObservation[];
    observationSeq: number;
    mediaTimeMs: number;
    nowMonotonicMs: number;
    displayMode: DisplayMode;
    reducedMotion: boolean;
    bubbleNodes: Map<string, HTMLElement>;
    tailPathNodes: Map<string, SVGPolygonElement>;
    shoutBgNodes: Map<string, SVGPolygonElement>;
    speakerColors: Map<string, string>;
  }): PresentationFrameResult {
    const activeIds = new Set(params.activeEvents.map((e) => e.id));
    this.engine.pruneInactiveCues(activeIds);

    for (const id of [...this.lastBodies.keys()]) {
      if (!activeIds.has(id)) {
        this.lastBodies.delete(id);
        this.lastTails.delete(id);
      }
    }

    const bodies: CommittedBubbleBody[] = [];
    const tails: TailGeometry[] = [];
    const taillessMode = params.displayMode === 'tailless';

    for (const ev of params.activeEvents) {
      const node = params.bubbleNodes.get(ev.id);
      const measuredW = node ? Math.max(80, node.offsetWidth) : 220;
      const measuredH = node ? Math.max(40, node.offsetHeight) : 56;

      const body = this.engine.resolveBubbleBody({
        event: ev,
        measuredWidth: measuredW,
        measuredHeight: measuredH,
        viewport: this.lastViewport,
        observations: params.observations,
        otherCommittedBodies: bodies,
        nowMonotonicMs: params.nowMonotonicMs,
      });

      const prevEntryCount = this.renderedEntryCounts.get(ev.id) ?? 0;
      if (prevEntryCount === 0) {
        this.renderedEntryCounts.set(ev.id, 1);
      }

      const tail = this.engine.resolveTailGeometry({
        event: ev,
        body,
        observations: params.observations,
        viewport: this.lastViewport,
        currentObservationSeq: params.observationSeq,
        mediaTimeMs: params.mediaTimeMs,
        nowMonotonicMs: params.nowMonotonicMs,
        reducedMotion: params.reducedMotion,
        taillessMode,
      });

      bodies.push(body);
      tails.push(tail);
      this.lastBodies.set(ev.id, body);
      this.lastTails.set(ev.id, tail);

      // Apply exact pixel transform directly to the HTML bubble node (no React re-render!)
      if (node) {
        node.style.transform = `translate3d(${body.left.toFixed(2)}px, ${body.top.toFixed(2)}px, 0)`;
        node.dataset.centerX = body.centerX.toFixed(2);
        node.dataset.centerY = body.centerY.toFixed(2);
        node.dataset.entryCount = String(this.renderedEntryCounts.get(ev.id) ?? 1);
      }

      // Update shout starburst SVG contour if present
      const shoutNode = params.shoutBgNodes.get(ev.id);
      if (shoutNode && body.styleVariant === 'shout') {
        const pts = buildShoutContourPoints(body.width, body.height)
          .map((p) => `${(body.centerX + p.x).toFixed(2)},${(body.centerY + p.y).toFixed(2)}`)
          .join(' ');
        shoutNode.setAttribute('points', pts);
        shoutNode.style.display = '';
      } else if (shoutNode) {
        shoutNode.style.display = 'none';
      }

      // Apply exact SVG tail polygon points directly
      const tailNode = params.tailPathNodes.get(ev.id);
      if (tailNode) {
        if (tail.visible) {
          const pts = `${tail.baseLeftX.toFixed(2)},${tail.baseLeftY.toFixed(2)} ${tail.tipX.toFixed(2)},${tail.tipY.toFixed(2)} ${tail.baseRightX.toFixed(2)},${tail.baseRightY.toFixed(2)}`;
          tailNode.setAttribute('points', pts);
          tailNode.setAttribute('data-visible', 'true');
          tailNode.setAttribute('data-root-x', tail.rootX.toFixed(2));
          tailNode.setAttribute('data-root-y', tail.rootY.toFixed(2));
          tailNode.setAttribute('data-tip-x', tail.tipX.toFixed(2));
          tailNode.setAttribute('data-tip-y', tail.tipY.toFixed(2));
          tailNode.setAttribute('data-track-id', tail.trackId ?? '');
          tailNode.setAttribute('stroke', params.speakerColors.get(ev.speakerId) ?? '#2563EB');
          tailNode.style.display = '';
        } else {
          tailNode.setAttribute('data-visible', 'false');
          tailNode.style.display = 'none';
        }
      }
    }

    return {
      viewport: this.lastViewport,
      bodies,
      tails,
      mediaTimeMs: params.mediaTimeMs,
      observationSeq: params.observationSeq,
    };
  }
}
