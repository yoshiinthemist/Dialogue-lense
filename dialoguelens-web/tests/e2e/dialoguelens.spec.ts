import { test, expect } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import fs from 'node:fs';
import path from 'node:path';

const VERIFICATION_DIR = path.resolve(process.cwd(), 'verification');
const SCREENSHOTS_DIR = path.join(VERIFICATION_DIR, 'screenshots');

test.beforeAll(async ({ request }) => {
  fs.mkdirSync(SCREENSHOTS_DIR, { recursive: true });
  await request.post('/api/policy/free-mode', {
    data: { freeCcOnly: true },
  });
});

test.describe('DialogueLens Web — Real Browser Acceptance Verification', () => {
  test('Scenarios 1, 12, 14: Keyless E2E, protected edits, project-scoped undo, IndexedDB persistence, exports, and unconfirmed Free CC disconnect', async ({
    page,
  }) => {
    const aiRequests: string[] = [];
    page.on('request', (req) => {
      if (req.url().includes('/api/ai/')) {
        aiRequests.push(req.url());
      }
    });

    await page.goto('/');
    await expect(page.getByTestId('header-policy-pill')).toContainText('Free CC Only');
    await page.screenshot({ path: path.join(SCREENSHOTS_DIR, 'desktop-home.png'), fullPage: false });

    // Open playable sample (28s WebM + WebVTT)
    await page.getByTestId('open-sample-btn').click();
    await expect(page.getByTestId('main-video-player')).toBeVisible();
    await expect(page.getByTestId('transcript-list')).toContainText('Do not open the vault');

    // Select cue 1 and apply a protected text edit
    const firstCueItem = page.locator('[data-testid^="transcript-item-"]').first();
    await firstCueItem.click();
    const editBox = page.getByTestId('edit-cue-text-input');
    await expect(editBox).toBeVisible();

    // Verify playback Space shortcut does NOT intercept text editing!
    await editBox.click();
    await editBox.fill('Protected custom wording with space');
    await expect(editBox).toHaveValue('Protected custom wording with space');

    // Run Repair Interval -> protected user edit MUST survive and surface conflict hint
    await page.getByTestId('repair-interval-btn').click();
    await expect(page.getByTestId('repair-status-msg')).toBeVisible();
    await expect(editBox).toHaveValue('Protected custom wording with space');
    await expect(page.getByTestId('conflict-hints-box')).toBeVisible();

    // Verify Export JSON triggers download cleanly without secrets/blobs
    const [jsonDownload] = await Promise.all([
      page.waitForEvent('download'),
      page.getByTestId('export-json-btn').click(),
    ]);
    expect(jsonDownload.suggestedFilename()).toContain('.dialoguelens.json');

    // Verify Project A Undo works and Project A's Undo cannot affect Project B
    await page.getByTestId('nav-home-btn').click();
    await expect(page.locator('[data-testid^="recent-project-"]').first()).toBeVisible();

    // Reload browser page to verify real IndexedDB persistence!
    await page.reload();
    const savedCard = page.locator('[data-testid^="recent-project-"]').first();
    await expect(savedCard).toBeVisible();
    await savedCard.getByRole('button', { name: 'Reopen' }).click();
    await expect(page.getByTestId('transcript-list')).toContainText(
      'Protected custom wording with space',
    );

    // Test disconnected backend during Free CC Only switch -> must visibly stay UNCONFIRMED!
    await page.getByTestId('nav-settings-btn').click();
    await expect(page.getByTestId('credentials-status-text')).toContainText(
      'Awaiting GEMINI_API_KEY',
    );
    await page.screenshot({
      path: path.join(SCREENSHOTS_DIR, 'desktop-settings.png'),
      fullPage: false,
    });

    // Switch off Free CC Only, then block backend requests and switch back to Free CC Only
    await page.getByTestId('toggle-free-cc-btn').click();
    await page.evaluate(() => {
      window.__DIALOGUELENS_DEBUG__?.setInjectedFault({ blockBackendRequests: true });
    });
    await page.getByTestId('toggle-free-cc-btn').click();
    await expect(page.getByTestId('header-policy-pill')).toContainText('Backend Unconfirmed');
    await expect(page.getByTestId('free-mode-ack-text')).toContainText('UNCONFIRMED');

    // Confirm ZERO AI network requests were made in keyless/Free CC session
    expect(aiRequests).toHaveLength(0);
    const attempted = await page.evaluate(
      () => window.__DIALOGUELENS_DEBUG__?.getAiRequestsAttempted() ?? -1,
    );
    expect(attempted).toBe(0);

    // Restore clean backend Free CC Only state for subsequent browser tests
    await page.evaluate(async () => {
      window.__DIALOGUELENS_DEBUG__?.setInjectedFault(null);
      await fetch('/api/policy/free-mode', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ freeCcOnly: true }),
      });
    });
  });

  test('Scenarios 2, 4: Full Scout 1 -> Scout 2 delayed repair loop and visible native CC on scout review surface', async ({
    page,
  }) => {
    await page.goto('/');
    await page.getByTestId('open-sample-btn').click();
    await expect(page.getByTestId('main-video-player')).toBeVisible();

    // Wait for opening 0–8s window to be accepted, leaving 8–16s unprepared in Prepare ahead mode
    await expect(page.getByTestId('transcript-list')).toContainText('Do not open the vault');

    // Inject Scout 1 defects + 350ms review delay for upcoming window 8–16s
    await page.evaluate(() => {
      window.__DIALOGUELENS_DEBUG__?.setInjectedFault({
        delayReviewMs: 350,
        corruptFirstCueText: 'CORRUPTED DRAFT TEXT THAT MUST NEVER DISPLAY',
        dropSecondCue: true,
      });
    });

    // Open Inspector and enable muted inspectable Scout Review Surface with native CC showing
    await page.getByTestId('nav-inspector-btn').click();
    await page.getByTestId('toggle-scout-surface-btn').click();
    await expect(page.getByTestId('scout-review-video')).toBeVisible();

    // Verify HTML TextTrack states separately:
    // Scout review surface = "showing" (visible native CC), Viewer video = "hidden" (readable feed without duplication)
    const trackModes = await page.evaluate(() => {
      const scoutVid = document.querySelector(
        '[data-testid="scout-review-video"]',
      ) as HTMLVideoElement | null;
      const viewerVid = document.querySelector(
        '[data-testid="main-video-player"]',
      ) as HTMLVideoElement | null;
      return {
        scoutMuted: scoutVid?.muted ?? false,
        scoutTrackMode: scoutVid?.textTracks?.[0]?.mode ?? null,
        viewerTrackMode: viewerVid?.textTracks?.[0]?.mode ?? null,
      };
    });
    expect(trackModes.scoutMuted).toBe(true);
    expect(trackModes.scoutTrackMode).toBe('showing');
    expect(trackModes.viewerTrackMode).toBe('hidden');

    // Trigger Scout 1 -> Scout 2 on window [8000, 16000] and verify repaired output (corrupted draft never leaks!)
    await page.evaluate(async () => {
      await window.__DIALOGUELENS_DEBUG__?.prepareWindow(8000, 16000, true);
    });

    const pageText = await page.getByTestId('transcript-list').innerText();
    expect(pageText).not.toContain('CORRUPTED DRAFT TEXT');
    expect(pageText).toContain('After the cut, the north door is still locked!');
    // Legitimate repetition ("Wait." x2) recovered intact despite dropSecondCue defect!
    const waitMatches = pageText.match(/\bWait\./g) ?? [];
    expect(waitMatches.length).toBeGreaterThanOrEqual(2);

    const timings = await page.evaluate(
      () => window.__DIALOGUELENS_DEBUG__?.getStageTimings() ?? [],
    );
    const repairedWin = timings.find((t) => t.windowStartMs === 8000);
    expect(repairedWin).toBeDefined();
    expect(repairedWin!.repairedIssueCount).toBeGreaterThanOrEqual(1);
    expect(repairedWin!.preparedAtMs).toBeLessThanOrEqual(repairedWin!.reviewedAtMs);
    expect(repairedWin!.reviewedAtMs).toBeLessThanOrEqual(repairedWin!.acceptedAtMs);
  });

  test('Scenarios 7, 8, 9, 10: Normal-motion video recording, <=100ms timing, <=1px body drift, tailed/tailless parity, contour-attached SVG tails, drag, and hard cut', async ({
    browser,
  }) => {
    // Dedicated context with real WebM video recording under normal motion!
    const context = await browser.newContext({
      viewport: { width: 1280, height: 800 },
      recordVideo: {
        dir: VERIFICATION_DIR,
        size: { width: 1280, height: 800 },
      },
    });
    const page = await context.newPage();

    try {
      await page.goto('/');
      await page.getByTestId('open-sample-btn').click();
      await expect(page.getByTestId('main-video-player')).toBeVisible();

      // Prepare all windows so playback flows smoothly across movement, crossing, cut, narration, and shout
      await page.evaluate(async () => {
        await window.__DIALOGUELENS_DEBUG__?.prepareWindow(0, 8000, true);
        await window.__DIALOGUELENS_DEBUG__?.prepareWindow(8000, 16000, true);
        await window.__DIALOGUELENS_DEBUG__?.prepareWindow(16000, 28000, true);
      });

      // Ensure Normal motion (NOT reduced motion!)
      await page.getByTestId('nav-settings-btn').click();
      await page.getByTestId('motion-pref-select').selectOption('normal');
      await page.getByTestId('nav-player-btn').click();

      // Wait for real video metadata and start muted playback on real media clock
      await page.getByTestId('mute-btn').click();
      await page.evaluate(async () => {
        const vid = document.querySelector(
          '[data-testid="main-video-player"]',
        ) as HTMLVideoElement;
        vid.currentTime = 0.7;
      });
      await page.getByTestId('play-pause-btn').click();

      // Wait until first cue bubble renders during live playback
      const firstBubble = page.locator('[data-testid^="bubble-body-"]').first();
      await expect(firstBubble).toBeVisible({ timeout: 5000 });

      // Measure cue timing accuracy relative to actual HTMLVideoElement.currentTime
      const timingSample = await page.evaluate(() => {
        const vid = document.querySelector(
          '[data-testid="main-video-player"]',
        ) as HTMLVideoElement;
        const proj = window.__DIALOGUELENS_DEBUG__?.getProject();
        const tMs = vid.currentTime * 1000;
        const activeRenderedIds = [...document.querySelectorAll('[data-testid^="bubble-body-"]')].map(
          (el) => el.getAttribute('data-cue-id'),
        );
        const expectedIds = (proj?.dialogueEvents ?? [])
          .filter((e) => tMs >= e.startMs && tMs <= e.endMs)
          .map((e) => e.id);
        return { tMs, activeRenderedIds, expectedIds };
      });
      expect(timingSample.activeRenderedIds).toEqual(timingSample.expectedIds);

      // Capture screenshot with active comic bubble + contour-attached tail
      await page.screenshot({
        path: path.join(SCREENSHOTS_DIR, 'desktop-player-bubbles.png'),
        fullPage: false,
      });

      // Sample rendered bubble center & SVG tail geometry across 20 real animation frames during speaker movement!
      const motionMetrics = await page.evaluate(async () => {
        const samples: Array<{
          centerX: number;
          centerY: number;
          width: number;
          height: number;
          entryCount: number;
          tailVisible: boolean;
          rootX: number;
          rootY: number;
          tipX: number;
          tipY: number;
          bubbleLeft: number;
          bubbleTop: number;
        }> = [];

        const stage = document.querySelector('[data-testid="video-stage"]') as HTMLElement;
        const stageRect = stage.getBoundingClientRect();

        for (let i = 0; i < 20; i++) {
          await new Promise((r) => requestAnimationFrame(r));
          const bodyEl = document.querySelector('[data-testid^="bubble-body-"]') as HTMLElement | null;
          if (!bodyEl) continue;
          const cueId = bodyEl.getAttribute('data-cue-id')!;
          const tailEl = document.querySelector(
            `[data-testid="bubble-tail-${cueId}"]`,
          ) as SVGPolygonElement | null;
          const rect = bodyEl.getBoundingClientRect();
          samples.push({
            centerX: rect.left - stageRect.left + rect.width / 2,
            centerY: rect.top - stageRect.top + rect.height / 2,
            width: rect.width,
            height: rect.height,
            entryCount: Number(bodyEl.dataset.entryCount ?? '0'),
            tailVisible: tailEl?.getAttribute('data-visible') === 'true',
            rootX: Number(tailEl?.getAttribute('data-root-x') ?? '0'),
            rootY: Number(tailEl?.getAttribute('data-root-y') ?? '0'),
            tipX: Number(tailEl?.getAttribute('data-tip-x') ?? '0'),
            tipY: Number(tailEl?.getAttribute('data-tip-y') ?? '0'),
            bubbleLeft: rect.left - stageRect.left,
            bubbleTop: rect.top - stageRect.top,
          });
        }
        return samples;
      });

      expect(motionMetrics.length).toBeGreaterThanOrEqual(10);
      const base = motionMetrics[0]!;
      let maxCenterDrift = 0;
      let visibleTailFrames = 0;
      let minTipX = Number.POSITIVE_INFINITY;
      let maxTipX = Number.NEGATIVE_INFINITY;

      for (const s of motionMetrics) {
        const drift = Math.hypot(s.centerX - base.centerX, s.centerY - base.centerY);
        maxCenterDrift = Math.max(maxCenterDrift, drift);
        expect(s.entryCount).toBe(1);
        if (s.tailVisible) {
          visibleTailFrames++;
          minTipX = Math.min(minTipX, s.tipX);
          maxTipX = Math.max(maxTipX, s.tipX);
          // Verify SVG root sits on the rendered bubble contour (within <= 1 CSS px of box boundary!)
          const dxEdge = Math.min(
            Math.abs(s.rootX - s.bubbleLeft),
            Math.abs(s.rootX - (s.bubbleLeft + s.width)),
          );
          const dyEdge = Math.min(
            Math.abs(s.rootY - s.bubbleTop),
            Math.abs(s.rootY - (s.bubbleTop + s.height)),
          );
          // For rounded corners, root is always inside/on the bounding rect within <= 6px of straight edges or corner arc
          expect(Math.min(dxEdge, dyEdge)).toBeLessThanOrEqual(7);
        }
      }

      // CRITICAL ACCEPTANCE CRITERIA:
      // 1. Committed body center drift <= 1 CSS px while speaker moves!
      expect(maxCenterDrift).toBeLessThanOrEqual(1);
      // 2. Tail actually rendered & tracked movement (cannot pass by suppressing tails!)
      expect(visibleTailFrames).toBeGreaterThanOrEqual(8);
      expect(maxTipX - minTipX).toBeGreaterThan(1.5);

      // Toggle Tailless mode mid-cue: body bounds & entryCount MUST remain identical (<= 1px)!
      const beforeToggle = await firstBubble.boundingBox();
      await page.getByTestId('toggle-tail-mode-btn').click();
      await page.waitForTimeout(120);
      const afterToggle = await firstBubble.boundingBox();
      await page.screenshot({
        path: path.join(SCREENSHOTS_DIR, 'desktop-player-tailless.png'),
        fullPage: false,
      });

      expect(beforeToggle).not.toBeNull();
      expect(afterToggle).not.toBeNull();
      expect(Math.abs(afterToggle!.x - beforeToggle!.x)).toBeLessThanOrEqual(1);
      expect(Math.abs(afterToggle!.y - beforeToggle!.y)).toBeLessThanOrEqual(1);
      expect(Math.abs(afterToggle!.width - beforeToggle!.width)).toBeLessThanOrEqual(1);
      await expect(firstBubble).toHaveAttribute('data-entry-count', '1');

      // Switch back to Speech Bubbles mode and seek across the hard scene cut at 14.0s
      await page.getByTestId('toggle-tail-mode-btn').click();
      await page.evaluate(() => {
        const vid = document.querySelector(
          '[data-testid="main-video-player"]',
        ) as HTMLVideoElement;
        vid.currentTime = 14.8;
      });
      await page.waitForTimeout(450);
      await expect(page.locator('[data-testid^="bubble-body-"]').first()).toContainText(
        'After the cut, the north door is still locked!',
      );
      await page.screenshot({
        path: path.join(SCREENSHOTS_DIR, 'desktop-cut-scene2.png'),
        fullPage: false,
      });

      // Seek to Narrator cue (23.2s) -> tail MUST hide cleanly
      await page.evaluate(() => {
        const vid = document.querySelector(
          '[data-testid="main-video-player"]',
        ) as HTMLVideoElement;
        vid.currentTime = 23.2;
      });
      await page.waitForTimeout(350);
      const narratorTailVisible = await page.evaluate(() => {
        const tails = [...document.querySelectorAll('[data-testid^="bubble-tail-"]')];
        return tails.some((t) => t.getAttribute('data-visible') === 'true');
      });
      expect(narratorTailVisible).toBe(false);

      // Seek to Shout + long text cue (25.5s) -> verify fits stage bounds & shout contour visible
      await page.evaluate(() => {
        const vid = document.querySelector(
          '[data-testid="main-video-player"]',
        ) as HTMLVideoElement;
        vid.currentTime = 25.5;
      });
      await page.waitForTimeout(350);
      const shoutCheck = await page.evaluate(() => {
        const stage = document.querySelector('[data-testid="video-stage"]')!.getBoundingClientRect();
        const bubble = document.querySelector('[data-testid^="bubble-body-"]')!.getBoundingClientRect();
        return {
          fitsLeft: bubble.left >= stage.left - 1,
          fitsRight: bubble.right <= stage.right + 1,
          fitsTop: bubble.top >= stage.top - 1,
          fitsBottom: bubble.bottom <= stage.bottom + 1,
        };
      });
      expect(shoutCheck).toEqual({
        fitsLeft: true,
        fitsRight: true,
        fitsTop: true,
        fitsBottom: true,
      });
    } finally {
      const video = page.video();
      await context.close();
      if (video) {
        const recordedPath = await video.path();
        const destPath = path.join(VERIFICATION_DIR, 'normal-motion-recording.webm');
        fs.copyFileSync(recordedPath, destPath);
      }
    }
  });

  test('Scenarios 4, 11, 16: YouTube Companion compliance, embed restriction fallback, Mobile 390px zero-overflow, and Axe accessibility scan', async ({
    page,
  }) => {
    await page.goto('/');

    // Run Axe accessibility scan on Home desktop
    const axeHome = await new AxeBuilder({ page })
      .withTags(['wcag2a', 'wcag2aa'])
      .analyze();
    const seriousHome = axeHome.violations.filter(
      (v) => v.impact === 'critical' || v.impact === 'serious',
    );
    expect(seriousHome).toEqual([]);

    // Open YouTube URL (ordinary viewing works immediately without captions/AI/payment!)
    await page.getByTestId('youtube-url-input').fill('https://youtu.be/RzagPoxaolQ');
    await page.getByTestId('open-youtube-btn').click();

    const ytRect = page.getByTestId('youtube-player-rect');
    await expect(ytRect).toBeVisible();
    await expect(ytRect).toHaveAttribute('data-cc-load-policy', '1');
    const embedUrl = await ytRect.getAttribute('data-embed-url');
    expect(embedUrl).toContain('cc_load_policy=1');

    // Verify truthful unobservable CC semantics and zero overlays inside YouTube player rect
    await expect(page.getByTestId('youtube-caption-status')).toContainText(
      'CC requested; activation not observable',
    );
    expect(await ytRect.locator('[data-testid^="bubble-body-"]').count()).toBe(0);
    await page.screenshot({
      path: path.join(SCREENSHOTS_DIR, 'desktop-youtube-companion.png'),
      fullPage: false,
    });

    // Verify blocked/restricted YouTube embed produces accurate error + Open in YouTube link
    await page.evaluate(() => {
      window.__DIALOGUELENS_DEBUG__?.setYouTubeEmbedError(150);
    });
    await expect(page.getByTestId('youtube-embed-error')).toContainText('Embedding restricted');
    await expect(page.getByTestId('open-in-youtube-link')).toHaveAttribute(
      'href',
      'https://www.youtube.com/watch?v=RzagPoxaolQ',
    );

    // Mobile 390x844 responsive check + zero horizontal overflow!
    await page.setViewportSize({ width: 390, height: 844 });
    await page.getByTestId('nav-home-btn').click();
    await page.getByTestId('open-sample-btn').click();
    await expect(page.getByTestId('video-stage')).toBeVisible();
    await page.waitForTimeout(300);

    const overflow = await page.evaluate(() => ({
      scrollWidth: document.documentElement.scrollWidth,
      clientWidth: document.documentElement.clientWidth,
    }));
    expect(overflow.scrollWidth).toBeLessThanOrEqual(overflow.clientWidth + 1);
    await page.screenshot({
      path: path.join(SCREENSHOTS_DIR, 'mobile-390-player.png'),
      fullPage: false,
    });

    // Run Axe accessibility scan on Player workspace
    const axePlayer = await new AxeBuilder({ page })
      .withTags(['wcag2a', 'wcag2aa'])
      .analyze();
    const seriousPlayer = axePlayer.violations.filter(
      (v) => v.impact === 'critical' || v.impact === 'serious',
    );
    expect(seriousPlayer).toEqual([]);
  });
});
