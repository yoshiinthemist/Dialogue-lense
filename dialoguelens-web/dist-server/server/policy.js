import crypto from 'node:crypto';
import dns from 'node:dns/promises';
import net from 'node:net';
export function hashOwnerToken(token) {
    return crypto.createHash('sha256').update(token).digest('hex').slice(0, 32);
}
export class PolicyCoordinator {
    db;
    config;
    activeAbortControllers = new Map();
    constructor(db, config) {
        this.db = db;
        this.config = config;
    }
    getConfig() {
        return { ...this.config };
    }
    verifyOwnerToken(providedHeader) {
        const expected = this.config.apiToken.trim();
        if (!expected) {
            // Local dev mode without explicit token: derive consistent local owner hash
            const candidate = (providedHeader ?? 'local-session').replace(/^Bearer\s+/i, '').trim();
            return { ok: true, ownerHash: hashOwnerToken(candidate || 'local-session') };
        }
        const provided = (providedHeader ?? '').replace(/^Bearer\s+/i, '').trim();
        if (!provided) {
            return { ok: false, ownerHash: '' };
        }
        const a = Buffer.from(provided);
        const b = Buffer.from(expected);
        if (a.length !== b.length || !crypto.timingSafeEqual(a, b)) {
            return { ok: false, ownerHash: '' };
        }
        return { ok: true, ownerHash: hashOwnerToken(provided) };
    }
    getPublicPolicyState() {
        const row = this.db.getPolicyState();
        const counts = this.db.countActiveAndCancellingJobs();
        return {
            policyRevision: row.policyRevision,
            freeCcOnly: row.freeCcOnly,
            credentialsConfigured: this.config.credentialsConfigured,
            providerName: this.config.providerName,
            scout1Model: this.config.scout1Model,
            scout2Model: this.config.scout2Model,
            activeJobCount: counts.active,
            cancellingJobCount: counts.cancelling,
            sessionRequestsUsed: row.sessionRequestsUsed,
            sessionRequestBudget: this.config.sessionRequestBudget,
            circuitOpen: row.circuitOpen,
            circuitReason: row.circuitReason ?? undefined,
        };
    }
    registerAbortController(key) {
        const controller = new AbortController();
        this.activeAbortControllers.set(key, controller);
        return controller;
    }
    releaseAbortController(key) {
        this.activeAbortControllers.delete(key);
    }
    abortKey(key, reason) {
        const ctrl = this.activeAbortControllers.get(key);
        if (ctrl && !ctrl.signal.aborted) {
            ctrl.abort(new Error(reason));
        }
    }
    /**
     * Authoritative Free CC Only switch.
     * Persists the new policyRevision in SQLite, transitions active jobs to cancelling,
     * and aborts every registered in-flight AbortController immediately.
     * After this returns, zero new provider admissions or retries can be dispatched.
     */
    setFreeCcOnly(freeCcOnly) {
        this.db.updatePolicyFreeMode(freeCcOnly);
        if (freeCcOnly) {
            for (const [key, ctrl] of this.activeAbortControllers.entries()) {
                if (!ctrl.signal.aborted) {
                    ctrl.abort(new Error('Cancelled by acknowledged Free CC Only switch'));
                }
                this.activeAbortControllers.delete(key);
            }
        }
        return this.getPublicPolicyState();
    }
    /**
     * Checked immediately before EVERY single provider dispatch (initial attempt AND retries).
     */
    checkProviderAdmission(expectedPolicyRevision) {
        const row = this.db.getPolicyState();
        if (row.freeCcOnly) {
            return {
                allowed: false,
                code: 'free_cc_only',
                reason: 'Free CC Only mode is active; AI provider requests are strictly blocked.',
                policyRevision: row.policyRevision,
            };
        }
        if (expectedPolicyRevision !== undefined && expectedPolicyRevision !== row.policyRevision) {
            return {
                allowed: false,
                code: 'stale_policy_revision',
                reason: `Job policy revision ${expectedPolicyRevision} superseded by revision ${row.policyRevision}.`,
                policyRevision: row.policyRevision,
            };
        }
        if (!this.config.credentialsConfigured) {
            return {
                allowed: false,
                code: 'credentials_unconfigured',
                reason: 'GEMINI_API_KEY is not configured on the server. Configure credentials to enable AI analysis.',
                policyRevision: row.policyRevision,
            };
        }
        if (row.circuitOpen) {
            return {
                allowed: false,
                code: 'circuit_open',
                reason: row.circuitReason ?? 'Provider circuit breaker is open after repeated failures.',
                policyRevision: row.policyRevision,
            };
        }
        if (row.sessionRequestsUsed >= this.config.sessionRequestBudget) {
            return {
                allowed: false,
                code: 'budget_exhausted',
                reason: `Session AI request budget (${this.config.sessionRequestBudget}) has been exhausted.`,
                policyRevision: row.policyRevision,
            };
        }
        return {
            allowed: true,
            code: 'ok',
            reason: 'Provider request admitted.',
            policyRevision: row.policyRevision,
        };
    }
}
/**
 * Checks whether an IPv4 or IPv6 address belongs to a private, loopback, link-local,
 * carrier-grade NAT, or cloud metadata range to prevent SSRF attacks.
 */
export function isPrivateOrReservedIp(ip) {
    const clean = ip.replace(/^::ffff:/i, '');
    if (net.isIPv4(clean)) {
        const octets = clean.split('.').map(Number);
        const [a, b] = octets;
        if (a === undefined || b === undefined)
            return true;
        if (a === 0 || a === 10 || a === 127)
            return true; // 0.0.0.0/8, 10/8, 127/8
        if (a === 169 && b === 254)
            return true; // Link-local & AWS/GCP metadata 169.254.169.254
        if (a === 172 && b >= 16 && b <= 31)
            return true; // 172.16/12
        if (a === 192 && b === 168)
            return true; // 192.168/16
        if (a === 100 && b >= 64 && b <= 127)
            return true; // CGNAT 100.64/10
        if (a >= 224)
            return true; // Multicast / reserved
        return false;
    }
    if (net.isIPv6(clean)) {
        const lower = clean.toLowerCase();
        if (lower === '::1' ||
            lower === '::' ||
            lower.startsWith('fc') ||
            lower.startsWith('fd') ||
            lower.startsWith('fe80') ||
            lower.startsWith('ff')) {
            return true;
        }
        return false;
    }
    return true;
}
/**
 * Validates external media URLs before server HEAD probing:
 * - Only http/https allowed
 * - Blocks localhost, .internal, .local, raw private/metadata IPs, and DNS records resolving to private IPs
 */
export async function validateSafeExternalUrl(rawUrl) {
    let parsed;
    try {
        parsed = new URL(rawUrl);
    }
    catch {
        return { ok: false, reason: 'Invalid URL format.' };
    }
    if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') {
        return { ok: false, reason: 'Only HTTP and HTTPS media URLs are permitted.' };
    }
    const host = parsed.hostname.toLowerCase();
    if (host === 'localhost' ||
        host.endsWith('.localhost') ||
        host.endsWith('.local') ||
        host.endsWith('.internal') ||
        host === 'metadata.google.internal') {
        return { ok: false, reason: 'Blocked access to internal or localhost destination.' };
    }
    if (net.isIP(host)) {
        if (isPrivateOrReservedIp(host)) {
            return { ok: false, reason: 'Blocked access to private, loopback, or metadata IP address.' };
        }
        return { ok: true, url: parsed };
    }
    try {
        const addresses = await dns.lookup(host, { all: true });
        if (addresses.length === 0) {
            return { ok: false, reason: 'Destination host did not resolve.' };
        }
        for (const addr of addresses) {
            if (isPrivateOrReservedIp(addr.address)) {
                return {
                    ok: false,
                    reason: `Destination resolved to blocked private/internal IP (${addr.address}).`,
                };
            }
        }
        return { ok: true, url: parsed };
    }
    catch {
        return { ok: false, reason: 'Could not resolve external media hostname.' };
    }
}
