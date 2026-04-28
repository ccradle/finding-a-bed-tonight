# Platform-operator MFA — authenticator compatibility matrix

**F11 §6.10.** Manual cross-authenticator QA results for the platform-operator MFA enrollment flow.

The platform-operator backend issues TOTP / RFC 6238 secrets. Any
authenticator app that implements RFC 6238 should work. This matrix
captures the team's hands-on QA against the v0.54 enrollment flow
on real devices.

## How to fill in this matrix

For each app × OS combination:

1. From a deploy of v0.54 (or local `./dev-start.sh --nginx`), open
   `/platform/mfa-enroll` as a `platform_user` with `mfa_enabled=false`
   (or the freshly-bootstrapped state).
2. Scan the QR with the authenticator app.
3. Enter the resulting 6-digit code into the confirm field.
4. Verify the codes phase displays + mfa-confirm completes successfully.
5. Record one of `pass` / `fail` / `partial` plus a one-line note.

A `partial` indicates the app accepted the QR but produced codes that
were rejected by the backend (typically a SHA-1 vs SHA-256 algorithm
mismatch — FABT uses SHA-1 per RFC 6238 baseline; some apps default to
SHA-256 if the QR's `algorithm=SHA1` parameter isn't honored).

## Matrix

> **Status:** uncompleted — manual QA pending. Operators with phones
> available should fill this in before the v0.54 tag, OR after deploy
> as a fast-follow. The deploy is not blocked on this matrix; the
> v0.54 spec accepts that the matrix lands as a separate commit.

| Authenticator | iOS | Android | Notes |
|---|---|---|---|
| Google Authenticator | TBD | TBD | TBD |
| Microsoft Authenticator | TBD | TBD | TBD |
| 1Password | TBD | TBD | TBD |
| Authy | TBD | TBD | TBD |
| Bitwarden | TBD | TBD | TBD |

## Methodology

- **Test fixture:** seeded `platform-smoke@dev.fabt.org` user (verified
  fresh-MFA-state via `UPDATE platform_user SET mfa_enabled=false,
  mfa_secret=NULL WHERE email='platform-smoke@dev.fabt.org'` between
  attempts; secret is one-shot per enrollment).
- **TOTP secret length:** 16 bytes (RFC 6238 baseline). Some apps cap at 32 bytes;
  not relevant for our secret length.
- **Algorithm:** SHA-1 (RFC 6238 §5.3 default). Some apps offer SHA-256
  / SHA-512 as user-toggleable; the QR encodes `algorithm=SHA1` so
  default-honoring apps stay on SHA-1.
- **Time-step:** 30 seconds. ±1 step tolerance enforced server-side per
  `TotpService.verifyCode`.
- **Device-OS pairs:** at minimum one iOS device + one Android device per
  app. The matrix can be extended with specific OS-version columns if
  the team encounters version-specific issues.

## Known issues

(empty — populate during QA.)

## Cross-references

- [`platform-operator-user-guide.md`](./platform-operator-user-guide.md)
  section 1 references this file.
- F11 OpenSpec change `tasks.md §6.10` tracks the matrix as a
  pre-merge / fast-follow item.
- Backend implementation: `backend/src/main/java/org/fabt/auth/platform/TotpService.java`
  (secret generation + code verification).
