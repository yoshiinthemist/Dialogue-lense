import React, { useState } from 'react';
import type {
  BackendPolicyState,
  FreeModeAckStatus,
  GlossaryEntry,
  ProjectSnapshot,
} from '../../shared/types.js';
import type { FaceTrackerDiagnostics } from '../adapters/faceTracker.js';
import type { SessionController } from '../controllers/sessionController.js';

export interface SettingsPanelProps {
  project: ProjectSnapshot;
  session: SessionController;
  policy: BackendPolicyState;
  freeModeAck: FreeModeAckStatus;
  trackerDiagnostics: FaceTrackerDiagnostics;
  liveTrackerEnabled: boolean;
  onToggleLiveTracker: (enabled: boolean) => Promise<void>;
}

export const SettingsPanel: React.FC<SettingsPanelProps> = ({
  project,
  session,
  policy,
  freeModeAck,
  trackerDiagnostics,
  liveTrackerEnabled,
  onToggleLiveTracker,
}) => {
  const { settings } = project;
  const [glossarySrc, setGlossarySrc] = useState('');
  const [glossaryDst, setGlossaryDst] = useState('');

  const addGlossaryTerm = (e: React.FormEvent) => {
    e.preventDefault();
    if (!glossarySrc.trim() || !glossaryDst.trim()) return;
    const next: GlossaryEntry[] = [
      ...settings.glossary,
      { sourceTerm: glossarySrc.trim(), preferredTranslation: glossaryDst.trim() },
    ];
    session.updateSettings({ glossary: next });
    setGlossarySrc('');
    setGlossaryDst('');
  };

  return (
    <section className="card" aria-label="Application settings" data-testid="settings-panel">
      <h2 style={{ margin: '0 0 12px' }}>Settings, Privacy &amp; Budgets</h2>

      {/* AI Policy & Free CC Only */}
      <div
        className="notice-box"
        style={{ marginBottom: 16 }}
        data-testid="policy-status-section"
      >
        <div style={{ display: 'flex', flexWrap: 'wrap', justifyContent: 'space-between', gap: 10 }}>
          <div>
            <strong>Mode &amp; Provider Boundary</strong>
            <div style={{ fontSize: 13, marginTop: 4 }}>
              Provider: {policy.providerName} · Scout 1: <code>{policy.scout1Model}</code> · Scout
              2: <code>{policy.scout2Model}</code>
            </div>
            <div style={{ fontSize: 13, marginTop: 4 }} data-testid="credentials-status-text">
              Server credentials:{' '}
              {policy.credentialsConfigured ? (
                <strong style={{ color: 'var(--success)' }}>Configured on server</strong>
              ) : (
                <strong style={{ color: 'var(--warning)' }}>
                  Awaiting GEMINI_API_KEY in server environment (captions-only works fully without it)
                </strong>
              )}
            </div>
            <div style={{ fontSize: 13, marginTop: 4 }} data-testid="free-mode-ack-text">
              Backend status:{' '}
              {freeModeAck === 'confirmed_free'
                ? `Free CC Only confirmed (policy rev ${policy.policyRevision}; zero AI requests permitted)`
                : freeModeAck === 'stopping_unconfirmed'
                  ? 'Stopping AI work — backend cancellation UNCONFIRMED (new local AI requests blocked; captions workflow remains available)'
                  : freeModeAck === 'credentials_unconfigured'
                    ? 'AI mode requested, awaiting server GEMINI_API_KEY'
                    : `AI mode enabled (${policy.sessionRequestsUsed}/${policy.sessionRequestBudget} requests used)`}
            </div>
          </div>

          <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
            <button
              type="button"
              className={`btn ${settings.freeCcOnly ? 'btn-primary' : ''}`}
              data-testid="toggle-free-cc-btn"
              onClick={() => void session.setFreeCcOnlyMode(!settings.freeCcOnly)}
            >
              {settings.freeCcOnly ? 'Free CC Only: ON (Switch to AI)' : 'AI Mode Requested (Switch to Free CC Only)'}
            </button>
          </div>
        </div>
      </div>

      {/* Upload Privacy Disclosure */}
      <div className="notice-box warn" style={{ marginBottom: 16 }}>
        <strong>Media Upload &amp; Privacy Disclosure</strong>
        <p style={{ margin: '6px 0 8px' }}>
          In Free CC Only mode, your local video and captions never leave your browser. Enabling
          server-side AI audio analysis uploads only the approved local media file to this
          application&apos;s own same-origin server for FFmpeg chunk extraction and deletes
          temporary uploads once the job completes or is cancelled.
        </p>
        <label style={{ display: 'flex', alignItems: 'center', gap: 8, fontWeight: 600 }}>
          <input
            type="checkbox"
            data-testid="upload-consent-checkbox"
            checked={settings.uploadConsentGranted}
            onChange={(e) => session.updateSettings({ uploadConsentGranted: e.target.checked })}
          />
          I approve uploading local media to the configured backend when AI mode is active
        </label>
      </div>

      <div className="home-grid">
        {/* Visual & Accessibility */}
        <div className="card">
          <h3 style={{ margin: '0 0 10px' }}>Readability &amp; Motion</h3>

          <div className="form-field">
            <label htmlFor="font-scale-input">
              Font scale ({Math.round(settings.fontSizeScale * 100)}%)
            </label>
            <input
              id="font-scale-input"
              data-testid="font-scale-input"
              type="range"
              min={0.85}
              max={1.6}
              step={0.05}
              value={settings.fontSizeScale}
              onChange={(e) => session.updateSettings({ fontSizeScale: Number(e.target.value) })}
            />
          </div>

          <div className="form-field">
            <label htmlFor="bubble-size-select">Bubble sizing</label>
            <select
              id="bubble-size-select"
              className="select-input"
              value={settings.bubbleSize}
              onChange={(e) =>
                session.updateSettings({
                  bubbleSize: e.target.value as 'compact' | 'medium' | 'large',
                })
              }
            >
              <option value="compact">Compact</option>
              <option value="medium">Medium (Default)</option>
              <option value="large">Large</option>
            </select>
          </div>

          <div className="form-field">
            <label htmlFor="motion-pref-select">Motion preference</label>
            <select
              id="motion-pref-select"
              data-testid="motion-pref-select"
              className="select-input"
              value={settings.motionPreference}
              onChange={(e) =>
                session.updateSettings({
                  motionPreference: e.target.value as 'system' | 'normal' | 'reduced',
                })
              }
            >
              <option value="system">Honor system prefers-reduced-motion</option>
              <option value="normal">Normal motion (smooth tails)</option>
              <option value="reduced">Reduced motion (instant snap)</option>
            </select>
          </div>

          <label style={{ display: 'flex', alignItems: 'center', gap: 8, minHeight: 44 }}>
            <input
              type="checkbox"
              data-testid="high-contrast-checkbox"
              checked={settings.highContrast}
              onChange={(e) => session.updateSettings({ highContrast: e.target.checked })}
            />
            High contrast surfaces and speech bubbles
          </label>
        </div>

        {/* Languages & Preparation */}
        <div className="card">
          <h3 style={{ margin: '0 0 10px' }}>Languages &amp; Preparation</h3>

          <div className="form-field">
            <label htmlFor="source-lang-select">Source language</label>
            <select
              id="source-lang-select"
              data-testid="source-lang-select"
              className="select-input"
              value={settings.sourceLanguage}
              onChange={(e) => session.updateSettings({ sourceLanguage: e.target.value })}
            >
              <option value="en">English (en)</option>
              <option value="es">Spanish (es)</option>
              <option value="fr">French (fr)</option>
              <option value="ja">Japanese (ja)</option>
              <option value="de">German (de)</option>
            </select>
          </div>

          <div className="form-field">
            <label htmlFor="target-lang-select">Target language</label>
            <select
              id="target-lang-select"
              data-testid="target-lang-select"
              className="select-input"
              value={settings.targetLanguage}
              onChange={(e) => session.updateSettings({ targetLanguage: e.target.value })}
            >
              <option value="en">English (en)</option>
              <option value="es">Spanish (es)</option>
              <option value="fr">French (fr)</option>
              <option value="ja">Japanese (ja)</option>
              <option value="de">German (de)</option>
            </select>
          </div>

          <div className="form-field">
            <label htmlFor="prep-mode-select">Preparation strategy</label>
            <select
              id="prep-mode-select"
              data-testid="prep-mode-select"
              className="select-input"
              value={settings.preparationMode}
              onChange={(e) =>
                session.updateSettings({
                  preparationMode: e.target.value as 'prepare_ahead' | 'prepare_fully',
                })
              }
            >
              <option value="prepare_ahead">Prepare ahead (start after opening window)</option>
              <option value="prepare_fully">Prepare fully (cover full clip first)</option>
            </select>
          </div>

          <div className="form-field">
            <label htmlFor="deadline-ms-input">Interval review deadline (ms)</label>
            <input
              id="deadline-ms-input"
              type="number"
              min={1500}
              max={60000}
              step={500}
              className="text-input"
              value={settings.budgets.intervalDeadlineMs}
              onChange={(e) =>
                session.updateSettings({
                  budgets: {
                    ...settings.budgets,
                    intervalDeadlineMs: Math.max(1500, Number(e.target.value) || 8000),
                  },
                })
              }
            />
          </div>
        </div>

        {/* On-Device Face Tracking & Budgets */}
        <div className="card">
          <h3 style={{ margin: '0 0 10px' }}>Local Face Tracker &amp; Budgets</h3>
          <p style={{ fontSize: 13, color: 'var(--text-secondary)', marginTop: 0 }}>
            {trackerDiagnostics.modelDownloadNotice}
          </p>
          <p style={{ fontSize: 12, color: 'var(--text-secondary)' }}>
            <strong>Limitations:</strong> {trackerDiagnostics.limitationsNotice}
          </p>
          <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 12 }}>
            <button
              type="button"
              className={`btn ${liveTrackerEnabled ? 'btn-primary' : ''}`}
              data-testid="toggle-live-tracker-btn"
              onClick={() => void onToggleLiveTracker(!liveTrackerEnabled)}
            >
              {liveTrackerEnabled ? 'Disable MediaPipe Tracker' : 'Enable MediaPipe Face Tracker'}
            </button>
            <span className="badge">Status: {trackerDiagnostics.status}</span>
          </div>
          {trackerDiagnostics.lastError && (
            <div className="notice-box error" style={{ marginBottom: 10 }}>
              {trackerDiagnostics.lastError}
            </div>
          )}

          <div style={{ fontSize: 13 }}>
            <strong>Request &amp; Attempt Budgets:</strong>
            <ul style={{ margin: '4px 0 0', paddingLeft: 18 }}>
              <li>
                Session requests: {policy.sessionRequestsUsed} / {policy.sessionRequestBudget}
              </li>
              <li>Max attempts per interval: {settings.budgets.maxAttemptsPerInterval}</li>
              <li>Circuit breaker: {policy.circuitOpen ? 'OPEN' : 'Closed (healthy)'}</li>
            </ul>
          </div>
        </div>
      </div>

      {/* Glossary */}
      <div className="card" style={{ marginTop: 16 }}>
        <h3 style={{ margin: '0 0 8px' }}>Translation Glossary ({settings.glossary.length})</h3>
        <form onSubmit={addGlossaryTerm} style={{ display: 'flex', flexWrap: 'wrap', gap: 8 }}>
          <input
            type="text"
            className="text-input"
            style={{ flex: 1, minWidth: 160 }}
            placeholder="Source term"
            value={glossarySrc}
            onChange={(e) => setGlossarySrc(e.target.value)}
          />
          <input
            type="text"
            className="text-input"
            style={{ flex: 1, minWidth: 160 }}
            placeholder="Preferred translation"
            value={glossaryDst}
            onChange={(e) => setGlossaryDst(e.target.value)}
          />
          <button type="submit" className="btn">
            Add Term
          </button>
        </form>
        {settings.glossary.length > 0 && (
          <ul style={{ margin: '10px 0 0', paddingLeft: 18, fontSize: 13 }}>
            {settings.glossary.map((g, idx) => (
              <li key={`${g.sourceTerm}-${idx}`}>
                <strong>{g.sourceTerm}</strong> → {g.preferredTranslation}{' '}
                <button
                  type="button"
                  className="btn"
                  style={{ minHeight: 30, padding: '0 8px', marginLeft: 8 }}
                  onClick={() =>
                    session.updateSettings({
                      glossary: settings.glossary.filter((_, i) => i !== idx),
                    })
                  }
                >
                  Remove
                </button>
              </li>
            ))}
          </ul>
        )}
      </div>
    </section>
  );
};
