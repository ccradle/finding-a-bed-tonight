import * as crypto from 'crypto';

/**
 * RFC 6238 TOTP code generator for Playwright fixtures.
 *
 * Phase G-4.4 §5.8 (warroom R-S1 amendment): tests do NOT read
 * `platform_user.mfa_secret` directly from the test DB — V87 REVOKEs the
 * `platform_user` table from `fabt_app`, and a test-only HTTP wrapper
 * adds runtime surface area. Instead, the secret is provisioned at seed
 * time (`infra/scripts/seed-data.sql`) with a deterministic value, and
 * this helper reproduces the standard RFC 6238 algorithm to mint codes
 * on demand.
 *
 * The base32 alphabet is RFC 4648; the time step is 30s; the digit
 * count is 6; the HMAC is SHA-1 (the standards-track default — RFC 6238
 * §1.2 documents SHA-256 / SHA-512 as alternatives but the FABT
 * platform_user.mfa_secret column stores codes minted with SHA-1).
 *
 * Usage:
 *   import { generateTotp, PLATFORM_BOOTSTRAP_TOTP_SECRET } from './totp-helper';
 *   const code = generateTotp(PLATFORM_BOOTSTRAP_TOTP_SECRET);
 */

/**
 * The dev-seeded TOTP secret for the bootstrap `platform_user` row (id
 * `0fab`). Provisioned by `infra/scripts/seed-data.sql`. Standard RFC
 * 4648 base32 test secret — NOT a production credential.
 */
export const PLATFORM_BOOTSTRAP_TOTP_SECRET = 'JBSWY3DPEHPK3PXP';

/**
 * Decode a base32-encoded string (RFC 4648, no padding required) to bytes.
 * Tolerates lowercase + spaces + missing `=` padding.
 */
function base32Decode(input: string): Buffer {
  const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';
  const cleaned = input.toUpperCase().replace(/[^A-Z2-7]/g, '');
  const bytes: number[] = [];
  let bits = 0;
  let value = 0;
  for (const ch of cleaned) {
    const idx = alphabet.indexOf(ch);
    if (idx === -1) continue;
    value = (value << 5) | idx;
    bits += 5;
    if (bits >= 8) {
      bits -= 8;
      bytes.push((value >>> bits) & 0xff);
    }
  }
  return Buffer.from(bytes);
}

/**
 * Compute the RFC 6238 TOTP code for the given base32 secret at the
 * given Unix-seconds time (default: now).
 *
 * @param secret base32-encoded HMAC key (RFC 4648; the canonical
 *               representation stored in `platform_user.mfa_secret`)
 * @param atUnixSeconds optional override for the current time (used
 *               by the lockout spec to look at the future window)
 * @returns 6-digit zero-padded numeric code
 */
export function generateTotp(secret: string, atUnixSeconds?: number): string {
  const nowSec = atUnixSeconds ?? Math.floor(Date.now() / 1000);
  const counter = Math.floor(nowSec / 30);

  // 8-byte big-endian counter
  const counterBuf = Buffer.alloc(8);
  // Counter fits in a 53-bit double well past year 2200; split high/low 32 bits.
  const high = Math.floor(counter / 0x100000000);
  const low = counter % 0x100000000;
  counterBuf.writeUInt32BE(high, 0);
  counterBuf.writeUInt32BE(low, 4);

  const key = base32Decode(secret);
  const hmac = crypto.createHmac('sha1', key).update(counterBuf).digest();

  const offset = hmac[hmac.length - 1] & 0x0f;
  const truncated =
    ((hmac[offset] & 0x7f) << 24) |
    ((hmac[offset + 1] & 0xff) << 16) |
    ((hmac[offset + 2] & 0xff) << 8) |
    (hmac[offset + 3] & 0xff);

  const code = (truncated % 1_000_000).toString().padStart(6, '0');
  return code;
}

/**
 * Generate a TOTP code at the same window as {@link generateTotp}, then
 * flip the last digit so the result is well-formed but typically wrong.
 * NOT "guaranteed wrong" — server-side ±1 window tolerance leaves a
 * tiny collision chance (~3 / 1,000,000 per call) where the mutated
 * digit happens to match the code at counter±1. Acceptable for
 * "submit a code that's almost-certainly different from a fresh
 * {@link generateTotp}" use cases; NOT acceptable for the lockout spec
 * where every attempt must be wrong with zero ambiguity. Use
 * {@link generateFutureWrongTotp} for that.
 */
export function generateWrongTotp(secret: string): string {
  const correct = generateTotp(secret);
  const lastDigit = parseInt(correct[correct.length - 1], 10);
  const wrongLast = (lastDigit + 1) % 10;
  return correct.slice(0, 5) + wrongLast.toString();
}

/**
 * Generate a TOTP code at counter+10 windows (5 minutes in the future).
 * Designed to fall outside the server's configured ±1 acceptance band:
 * the verify path checks counters [N-1, N, N+1] for the current epoch
 * N; we mint at N+10, which is outside the v0.53 default tolerance. If
 * the server's drift tolerance is ever widened past ±10 windows, this
 * helper would need to advance further — pin the assumption in
 * `platform-totp-lockout.spec.ts` rather than asserting it server-side.
 *
 * <p>Used by `platform-totp-lockout.spec.ts` to submit 5 wrong codes
 * with zero collision probability. Each call advances the offset to
 * keep submissions distinct (V88's TOTP-replay table may track distinct
 * codes per attempt — different offset → different code, even within
 * the same 30s window).
 *
 * @param secret base32 TOTP secret
 * @param offsetWindows additional 30s windows past the base +10 offset
 *        (default 0 — i.e. counter+10). The lockout spec passes 0..4
 *        across its 5 attempts to mint 5 distinct future codes.
 */
export function generateFutureWrongTotp(secret: string, offsetWindows = 0): string {
  const futureSec = Math.floor(Date.now() / 1000) + 300 + offsetWindows * 30;
  return generateTotp(secret, futureSec);
}
