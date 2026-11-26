import React from 'react';
import type { BackendPolicyState, ProjectSnapshot } from '../../shared/types.js';
import type { ReadinessSnapshot } from '../../shared/playbackReadiness.js';
import type { ScoutStageTimingRecord } from '../controllers/sessionController.js';

export interface InspectorDrawerProps {
  project: ProjectSnapshot;
  readiness: ReadinessSnapshot;
  policy: BackendPolicyState;
  scoutLogs: string[];
  stageTimings: ScoutStageTimingRecord[];
  showScoutSurface: boolean;
  onToggleScoutSurface: () => void;
  onClose: () => void;
}

export const InspectorDrawer: React.FC<InspectorDrawerProps> = ({
  project,
  readiness,
  policy,
  scoutLogs,
  stageTimings,
  showScoutSurface,
  onToggleScoutSurface,
  onClose,
}) => {
  const caps = project.source.capabilities;

  return (
    <section
      className="card"
      style={{ marginTop: 16 }}
      aria-label="Developer diagnostics inspector"
      data-testid="inspector-drawer"
    >
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12 }}>
        <h3 style={{ margin: 0 }}>Optional Diagnostics Inspector</h3>
        <div style={{ display: 'flex', gap: 8 }}>
          {project.source.kind !== 'youtube' && (
            <button
              type="button"
              className={`btn ${showScoutSurface ? 'btn-active' : ''}`}
              data-testid="toggle-scout-surface-btn"
              onClick={onToggleScoutSurface}
            >
              {showScoutSurface ? 'Hide Scout Review Surface' : 'Show Scout Review Surface (Native CC)'}
            </button>
          )}
          <button type="button" className="btn" onClick={onClose}>
            Close Inspector
          </button>
        </div>
      </div>

      <div className="home-grid">
        <div>
          <h4 style={{ margin: '0 0 6px' }}>Source Capabilities ({project.source.kind})</h4>
          <ul style={{ margin: 0, paddingLeft: 18, fontSize: 13 }}>
            <li>Playback: <code>{caps.playback}</code></li>
            <li>Caption activation: <code>{caps.captionActivation}</code></li>
            <li>Readable captions: <code>{caps.readableCaptionEvidence}</code></li>
            <li>Audio extraction: <code>{caps.audioExtraction}</code></li>
            <li>Frame inspection: <code>{caps.frameInspection}</code></li>
            <li>Seeking: <code>{caps.seeking}</code></li>
            <li>Permitted overlays: <code>{caps.permittedOverlayPlacement}</code></li>
          </ul>
          {caps.notes.map((n, i) => (
            <p key={i} style={{ fontSize: 12, color: 'var(--text-secondary)', margin: '6px 0 0' }}>
              {n}
            </p>
          ))}
        </div>

        <div>
          <h4 style={{ margin: '0 0 6px' }}>Readiness &amp; Policy State</h4>
          <ul style={{ margin: 0, paddingLeft: 18, fontSize: 13 }}>
            <li>Readiness phase: <code>{readiness.phase}</code></li>
            <li>Required lead: <code>{readiness.requiredLeadMs} ms</code></li>
            <li>Observed latency: <code>{readiness.observedProcessingMs} ms</code></li>
            <li>Content revision: <code>{project.contentRevision}</code></li>
            <li>Evidence revision: <code>{project.evidenceRevision}</code></li>
            <li>Backend policy rev: <code>{policy.policyRevision}</code></li>
            <li>Coverage intervals: <code>{project.coverage.length}</code></li>
          </ul>
        </div>

        <div>
          <h4 style={{ margin: '0 0 6px' }}>Scout Stage Timings ({stageTimings.length})</h4>
          {stageTimings.length === 0 ? (
            <p style={{ fontSize: 12, color: 'var(--text-secondary)', margin: 0 }}>
              No interval completions recorded yet.
            </p>
          ) : (
            <ul style={{ margin: 0, paddingLeft: 18, fontSize: 12 }}>
              {stageTimings.slice(-6).map((t, i) => (
                <li key={i}>
                  {(t.windowStartMs / 1000).toFixed(0)}s–{(t.windowEndMs / 1000).toFixed(0)}s ·
                  repaired: {t.repairedIssueCount} · displayed:{' '}
                  {t.firstDisplayedAtMs ? 'yes' : 'pending'}
                </li>
              ))}
            </ul>
          )}
        </div>
      </div>

      <div style={{ marginTop: 12 }}>
        <h4 style={{ margin: '0 0 6px' }}>Recent Scout Pipeline Log</h4>
        <div
          style={{
            maxHeight: 140,
            overflowY: 'auto',
            fontFamily: 'monospace',
            fontSize: 12,
            background: '#0f172a',
            color: '#e2e8f0',
            padding: 10,
            borderRadius: 8,
          }}
        >
          {scoutLogs.length === 0 ? (
            <div>No scout events recorded yet.</div>
          ) : (
            scoutLogs.slice(-20).map((line, i) => <div key={i}>{line}</div>)
          )}
        </div>
      </div>
    </section>
  );
};
