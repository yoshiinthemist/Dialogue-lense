export const CURRENT_PROJECT_SCHEMA_VERSION = 2;
export const DEFAULT_USER_SETTINGS = {
    displayMode: 'bubbles',
    textDisplayMode: 'original',
    sourceLanguage: 'en',
    targetLanguage: 'en',
    fontSizeScale: 1,
    highContrast: false,
    bubbleSize: 'medium',
    motionPreference: 'system',
    freeCcOnly: true,
    preparationMode: 'prepare_ahead',
    uploadConsentGranted: false,
    glossary: [],
    budgets: {
        maxRequestsPerSession: 40,
        maxAttemptsPerInterval: 3,
        intervalDeadlineMs: 8000,
    },
};
export const SPEAKER_PALETTE = [
    '#2563EB',
    '#D97706',
    '#059669',
    '#9333EA',
    '#DC2626',
    '#0891B2',
    '#4F46E5',
    '#B45309',
];
export function createDefaultSpeakers(projectId) {
    return [
        {
            id: `${projectId}:spk-unknown`,
            projectId,
            label: 'Unknown speaker',
            color: '#475569',
            role: 'unknown',
            isUserNamed: false,
            linkedTrackIds: [],
        },
        {
            id: `${projectId}:spk-narrator`,
            projectId,
            label: 'Narrator',
            color: '#334155',
            role: 'narrator',
            isUserNamed: false,
            linkedTrackIds: [],
        },
        {
            id: `${projectId}:spk-offscreen`,
            projectId,
            label: 'Off-screen',
            color: '#64748B',
            role: 'offscreen',
            isUserNamed: false,
            linkedTrackIds: [],
        },
    ];
}
