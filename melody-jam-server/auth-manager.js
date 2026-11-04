const crypto = require('crypto');

// Secret for HMAC-SHA256 signing (can be overridden via JAM_JWT_SECRET environment variable)
const SECRET_KEY = process.env.JAM_JWT_SECRET || 'melody_jam_super_secure_secret_key_2026_x89a1';
const TOKEN_TTL_SECONDS = 7 * 24 * 60 * 60; // 7 days session lifetime

class AuthManager {
  /**
   * Generates or derives a deterministic, secure guest ID from installation ID.
   */
  static deriveGuestId(installationId) {
    if (!installationId || typeof installationId !== 'string') {
      return 'gst_' + crypto.randomBytes(8).toString('hex');
    }
    const hash = crypto.createHmac('sha256', SECRET_KEY).update(installationId).digest('hex');
    return 'gst_' + hash.substring(0, 16);
  }

  /**
   * Creates a signed guest session token.
   */
  static createSession(installationId, displayName = 'Melody User') {
    const safeDisplayName = (displayName && displayName.trim()) 
      ? displayName.trim().substring(0, 30) 
      : 'Melody User';

    const guestId = this.deriveGuestId(installationId);
    const now = Math.floor(Date.now() / 1000);
    const expiresAt = now + TOKEN_TTL_SECONDS;

    const payload = {
      guestId,
      installationId: installationId || 'unknown',
      displayName: safeDisplayName,
      iat: now,
      exp: expiresAt
    };

    const payloadEncoded = Buffer.from(JSON.stringify(payload)).toString('base64url');
    const signature = crypto
      .createHmac('sha256', SECRET_KEY)
      .update(payloadEncoded)
      .digest('base64url');

    const token = `${payloadEncoded}.${signature}`;

    return {
      guestId,
      displayName: safeDisplayName,
      token,
      expiresAt: expiresAt * 1000
    };
  }

  /**
   * Verifies and decodes a signed guest session token.
   * Returns decoded payload or null if invalid/expired.
   */
  static verifyToken(token) {
    if (!token || typeof token !== 'string') return null;
    const parts = token.split('.');
    if (parts.length !== 2) return null;

    const [payloadEncoded, signature] = parts;
    const expectedSig = crypto
      .createHmac('sha256', SECRET_KEY)
      .update(payloadEncoded)
      .digest('base64url');

    // Constant-time comparison to prevent timing attacks
    if (!crypto.timingSafeEqual(Buffer.from(signature), Buffer.from(expectedSig))) {
      return null;
    }

    try {
      const json = Buffer.from(payloadEncoded, 'base64url').toString('utf8');
      const payload = JSON.parse(json);
      const now = Math.floor(Date.now() / 1000);

      if (payload.exp && payload.exp < now) {
        return null; // Expired token
      }

      return payload;
    } catch (_err) {
      return null;
    }
  }
}

module.exports = AuthManager;
