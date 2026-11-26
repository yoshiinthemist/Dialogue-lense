import React, { useMemo, useState } from 'react';
import { selectEventText } from '../../shared/subtitleParser.js';
import {
  SPEAKER_PALETTE,
  type DialogueEvent,
  type ProjectSnapshot,
  type SpeakerProfile,
} from '../../shared/types.js';
import type { SessionController } from '../controllers/sessionController.js';

export interface TranscriptEditorPanelProps {
  project: ProjectSnapshot;
  session: SessionController;
  mediaTimeMs: number;
  selectedCueId: string | null;
  onSelectCue: (cueId: string | null) => void;
  onJumpToTime: (timeMs: number) => void;
  onExportJson: () => void;
  onExportVtt: () => void;
  onExportSrt: () => void;
}

export const TranscriptEditorPanel: React.FC<TranscriptEditorPanelProps> = ({
  project,
  session,
  mediaTimeMs,
  selectedCueId,
  onSelectCue,
  onJumpToTime,
  onExportJson,
  onExportVtt,
  onExportSrt,
}) => {
  const [searchQuery, setSearchQuery] = useState('');
  const [propagateSpeaker, setPropagateSpeaker] = useState(false);
  const [newSpeakerName, setNewSpeakerName] = useState('');
  const [newSpeakerColor, setNewSpeakerColor] = useState<string>(SPEAKER_PALETTE[0]);
  const [repairStatus, setRepairStatus] = useState<string | null>(null);

  const speakerMap = useMemo(
    () => new Map(project.speakers.map((s) => [s.id, s])),
    [project.speakers],
  );

  const filteredEvents = useMemo(() => {
    const q = searchQuery.trim().toLowerCase();
    if (!q) return project.dialogueEvents;
    return project.dialogueEvents.filter((ev) => {
      const spk = speakerMap.get(ev.speakerId)?.label.toLowerCase() ?? '';
      return (
        ev.originalText.toLowerCase().includes(q) ||
        (ev.translatedText?.toLowerCase().includes(q) ?? false) ||
        spk.includes(q)
      );
    });
  }, [project.dialogueEvents, searchQuery, speakerMap]);

  const selectedCue: DialogueEvent | null = useMemo(
    () => project.dialogueEvents.find((e) => e.id === selectedCueId) ?? null,
    [project.dialogueEvents, selectedCueId],
  );

  const handleAddSpeaker = (e: React.FormEvent) => {
    e.preventDefault();
    const label = newSpeakerName.trim();
    if (!label) return;
    const spk: SpeakerProfile = {
      id: `${project.id}:spk-user-${Date.now()}`,
      projectId: project.id,
      label,
      color: newSpeakerColor,
      role: 'person',
      isUserNamed: true,
      linkedTrackIds: [],
    };
    session.upsertSpeaker(spk);
    setNewSpeakerName('');
  };

  const handleRepairSelectedInterval = async () => {
    if (!selectedCue) return;
    setRepairStatus('Running Scout 1 → Scout 2 review on interval…');
    const winStart = Math.floor(selectedCue.startMs / 8000) * 8000;
    const winEnd = Math.max(selectedCue.endMs + 200, winStart + 8000);
    const ok = await session.prepareWindow(winStart, winEnd, true);
    setRepairStatus(
      ok
        ? 'Interval reviewed. Protected user edits were preserved (see conflict hints if any differed).'
        : 'Interval review did not supersede current revision.',
    );
  };

  return (
    <aside className="card" aria-label="Transcript and cue editor" data-testid="transcript-editor-panel">
      <div
        style={{
          display: 'flex',
          flexWrap: 'wrap',
          justifyContent: 'space-between',
          alignItems: 'center',
          gap: 8,
          marginBottom: 10,
        }}
      >
        <h2 style={{ margin: 0, fontSize: 17 }}>
          Transcript ({project.dialogueEvents.length} cues)
        </h2>
        <div style={{ display: 'flex', gap: 6 }}>
          <button
            type="button"
            className="btn"
            data-testid="undo-btn"
            disabled={!session.canUndo()}
            onClick={() => session.undo()}
          >
            Undo
          </button>
          <button
            type="button"
            className="btn"
            data-testid="redo-btn"
            disabled={!session.canRedo()}
            onClick={() => session.redo()}
          >
            Redo
          </button>
        </div>
      </div>

      <div className="form-field">
        <label htmlFor="transcript-search">Search cues or speakers</label>
        <input
          id="transcript-search"
          data-testid="transcript-search-input"
          type="search"
          className="text-input"
          placeholder="Filter dialogue…"
          value={searchQuery}
          onChange={(e) => setSearchQuery(e.target.value)}
        />
      </div>

      {/* Export bar */}
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6, marginBottom: 12 }}>
        <button type="button" className="btn" data-testid="export-json-btn" onClick={onExportJson}>
          Export JSON
        </button>
        <button type="button" className="btn" data-testid="export-vtt-btn" onClick={onExportVtt}>
          Export WebVTT
        </button>
        <button type="button" className="btn" data-testid="export-srt-btn" onClick={onExportSrt}>
          Export SRT
        </button>
      </div>

      {/* Selectable transcript list */}
      <div className="transcript-list" data-testid="transcript-list">
        {filteredEvents.length === 0 ? (
          <p style={{ margin: 0, fontSize: 13, color: 'var(--text-secondary)' }}>
            No dialogue cues match this filter yet.
          </p>
        ) : (
          filteredEvents.map((ev) => {
            const isActive = mediaTimeMs >= ev.startMs && mediaTimeMs <= ev.endMs;
            const isSelected = ev.id === selectedCueId;
            const spk = speakerMap.get(ev.speakerId);
            const isProtected =
              ev.protectedFields.text ||
              ev.protectedFields.timing ||
              ev.protectedFields.speaker ||
              ev.protectedFields.anchor;
            return (
              <div
                key={ev.id}
                role="button"
                tabIndex={0}
                data-testid={`transcript-item-${ev.id}`}
                className={`transcript-cue-item ${isActive ? 'active' : ''} ${isSelected ? 'selected' : ''}`}
                onClick={() => {
                  onSelectCue(ev.id);
                  onJumpToTime(ev.startMs + 80);
                }}
                onKeyDown={(e) => {
                  if (e.key === 'Enter' || e.key === ' ') {
                    e.preventDefault();
                    onSelectCue(ev.id);
                    onJumpToTime(ev.startMs + 80);
                  }
                }}
              >
                <div className="cue-meta-row">
                  <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}>
                    <span
                      className="bubble-speaker-dot"
                      style={{ background: spk?.color ?? '#475569' }}
                    />
                    <strong style={{ color: 'var(--text-primary)' }}>
                      {spk?.label ?? 'Unknown'}
                    </strong>
                    <span>
                      {(ev.startMs / 1000).toFixed(1)}s–{(ev.endMs / 1000).toFixed(1)}s
                    </span>
                  </span>
                  <span style={{ display: 'inline-flex', gap: 4 }}>
                    <span className="badge">Ref: {ev.quality.referenceAlignment}</span>
                    {isProtected && <span className="badge badge-protected">Protected</span>}
                    {ev.conflictHints.length > 0 && (
                      <span className="badge badge-conflict">
                        {ev.conflictHints.length} conflict
                      </span>
                    )}
                  </span>
                </div>
                <div style={{ fontSize: 14 }}>
                  {selectEventText(ev, project.settings.textDisplayMode)}
                </div>
              </div>
            );
          })
        )}
      </div>

      {/* Selected Cue Editor */}
      {selectedCue && (
        <div
          style={{
            marginTop: 14,
            paddingTop: 14,
            borderTop: '1px solid var(--border-subtle)',
          }}
          data-testid="cue-editor-form"
        >
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 8 }}>
            <h3 style={{ margin: 0, fontSize: 15 }}>Edit Selected Cue</h3>
            <button
              type="button"
              className="btn"
              onClick={() => onSelectCue(null)}
            >
              Close
            </button>
          </div>

          <div className="form-field">
            <label htmlFor="edit-cue-text">Original text (protected once edited)</label>
            <textarea
              id="edit-cue-text"
              data-testid="edit-cue-text-input"
              className="textarea-input"
              value={selectedCue.originalText}
              onChange={(e) =>
                session.editCue(selectedCue.id, { originalText: e.target.value })
              }
            />
          </div>

          <div className="form-field">
            <label htmlFor="edit-cue-translation">Optional translation</label>
            <input
              id="edit-cue-translation"
              data-testid="edit-cue-translation-input"
              className="text-input"
              value={selectedCue.translatedText ?? ''}
              onChange={(e) =>
                session.editCue(selectedCue.id, { translatedText: e.target.value })
              }
            />
          </div>

          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 8 }}>
            <div className="form-field">
              <label htmlFor="edit-cue-start">Start (ms)</label>
              <input
                id="edit-cue-start"
                data-testid="edit-cue-start-input"
                type="number"
                className="text-input"
                value={selectedCue.startMs}
                onChange={(e) =>
                  session.editCue(selectedCue.id, { startMs: Number(e.target.value) })
                }
              />
            </div>
            <div className="form-field">
              <label htmlFor="edit-cue-end">End (ms)</label>
              <input
                id="edit-cue-end"
                data-testid="edit-cue-end-input"
                type="number"
                className="text-input"
                value={selectedCue.endMs}
                onChange={(e) =>
                  session.editCue(selectedCue.id, { endMs: Number(e.target.value) })
                }
              />
            </div>
          </div>

          <div className="form-field">
            <label htmlFor="edit-cue-speaker">Speaker</label>
            <select
              id="edit-cue-speaker"
              data-testid="edit-cue-speaker-select"
              className="select-input"
              value={selectedCue.speakerId}
              onChange={(e) =>
                session.editCue(selectedCue.id, {
                  speakerId: e.target.value,
                  propagateSpeakerByPositiveLink: propagateSpeaker,
                })
              }
            >
              {project.speakers.map((s) => (
                <option key={s.id} value={s.id}>
                  {s.label} ({s.role})
                </option>
              ))}
            </select>
          </div>

          <label style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: 13, marginBottom: 10 }}>
            <input
              type="checkbox"
              checked={propagateSpeaker}
              onChange={(e) => setPropagateSpeaker(e.target.checked)}
            />
            Propagate speaker change to positively linked visual track cues only
          </label>

          <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8, marginBottom: 10 }}>
            <button
              type="button"
              className="btn"
              data-testid="split-cue-btn"
              onClick={() =>
                session.splitCue(
                  selectedCue.id,
                  Math.round((selectedCue.startMs + selectedCue.endMs) / 2),
                )
              }
            >
              Split Cue
            </button>
            <button
              type="button"
              className="btn"
              data-testid="merge-cue-btn"
              onClick={() => session.mergeCueWithNext(selectedCue.id)}
            >
              Merge With Next
            </button>
            <button
              type="button"
              className="btn"
              data-testid="reset-anchor-btn"
              disabled={!selectedCue.manualAnchor}
              onClick={() => session.editCue(selectedCue.id, { manualAnchor: null })}
            >
              Reset Body Anchor
            </button>
            <button
              type="button"
              className="btn btn-primary"
              data-testid="repair-interval-btn"
              onClick={() => void handleRepairSelectedInterval()}
            >
              Repair Interval
            </button>
          </div>

          {repairStatus && (
            <div className="notice-box" style={{ marginBottom: 8 }} data-testid="repair-status-msg">
              {repairStatus}
            </div>
          )}

          {selectedCue.conflictHints.length > 0 && (
            <div className="notice-box warn" data-testid="conflict-hints-box">
              <strong>Protected edit conflicts surfaced by Scout 2:</strong>
              <ul style={{ margin: '6px 0 0', paddingLeft: 18 }}>
                {selectedCue.conflictHints.map((hint) => (
                  <li key={hint.id}>
                    Field <code>{hint.field}</code>: proposed &ldquo;{hint.proposedValue}&rdquo; —{' '}
                    {hint.reason}
                  </li>
                ))}
              </ul>
            </div>
          )}
        </div>
      )}

      {/* Speaker Label & Color Manager */}
      <form
        onSubmit={handleAddSpeaker}
        style={{
          marginTop: 14,
          paddingTop: 14,
          borderTop: '1px solid var(--border-subtle)',
        }}
      >
        <h3 style={{ margin: '0 0 8px', fontSize: 14 }}>Add Project-Local Speaker</h3>
        <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
          <input
            type="text"
            aria-label="New speaker label"
            data-testid="new-speaker-name-input"
            className="text-input"
            style={{ flex: 1, minWidth: 140 }}
            placeholder="e.g. Jordan"
            value={newSpeakerName}
            onChange={(e) => setNewSpeakerName(e.target.value)}
          />
          <input
            type="color"
            aria-label="Speaker color"
            value={newSpeakerColor}
            style={{ width: 48, minHeight: 44, padding: 2, borderRadius: 6 }}
            onChange={(e) => setNewSpeakerColor(e.target.value)}
          />
          <button type="submit" className="btn" data-testid="add-speaker-btn">
            Add
          </button>
        </div>
      </form>
    </aside>
  );
};
