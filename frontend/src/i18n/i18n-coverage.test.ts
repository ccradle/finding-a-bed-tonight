import { describe, it, expect } from 'vitest';
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join } from 'node:path';
import enMessages from './en.json';
import esMessages from './es.json';

/**
 * i18n coverage lint — every `<FormattedMessage id="...">` and
 * `intl.formatMessage({ id: '...' })` reference in the source tree must
 * have a matching key in BOTH en.json and es.json. Missing keys fall
 * back to the `defaultMessage=` value (always English) regardless of
 * locale, which produces silent English-in-Spanish bugs that Sam and
 * Tomás would consider an a11y violation.
 *
 * Pre-existing bugs caught by the manual audit that led to this test:
 * - `login.organization` / `login.organizationPlaceholder` /
 *   `login.emailPlaceholder` — rendered literal key text on
 *   `/login/forgot-password` since v0.29. Fixed in v0.44.2.
 * - `totp.settingsButton` — Layout.tsx defaultMessage="Security" fell
 *   through to English for Spanish users. Fixed in v0.44.3.
 * - `referral.requestTitle` — aria-label on DV referral modal. Fixed
 *   in v0.44.3 by reusing existing `referral.title` key.
 *
 * If this test fails, either (a) add the missing key to both en.json
 * and es.json, or (b) change the code to use an existing key.
 */

const FRONTEND_SRC = join(__dirname, '..', '..', 'src');
const SKIP_DIRS = new Set(['node_modules', 'dist', 'dist-v0.32.1-backup']);

function walkSourceFiles(root: string): string[] {
  const out: string[] = [];
  for (const entry of readdirSync(root)) {
    if (SKIP_DIRS.has(entry)) continue;
    const full = join(root, entry);
    const s = statSync(full);
    if (s.isDirectory()) out.push(...walkSourceFiles(full));
    else if (s.isFile() && (entry.endsWith('.tsx') || entry.endsWith('.ts')) && !entry.endsWith('.test.ts') && !entry.endsWith('.test.tsx')) {
      out.push(full);
    }
  }
  return out;
}

function extractReferencedIds(): Set<string> {
  const ids = new Set<string>();
  const fmPattern = /<FormattedMessage\s+id="([^"]+)"/g;
  const imPattern = /formatMessage\(\{\s*id:\s*['"]([^'"]+)['"]/g;
  for (const file of walkSourceFiles(FRONTEND_SRC)) {
    const text = readFileSync(file, 'utf8');
    for (const m of text.matchAll(fmPattern)) ids.add(m[1]);
    for (const m of text.matchAll(imPattern)) ids.add(m[1]);
  }
  return ids;
}

describe('i18n coverage', () => {
  const referenced = extractReferencedIds();
  const en = enMessages as Record<string, string>;
  const es = esMessages as Record<string, string>;

  it('every referenced id is present in en.json', () => {
    const missing = [...referenced].filter((k) => !(k in en)).sort();
    expect(missing, `keys referenced in source but missing in en.json:\n  ${missing.join('\n  ')}`).toEqual([]);
  });

  it('every referenced id is present in es.json', () => {
    const missing = [...referenced].filter((k) => !(k in es)).sort();
    expect(missing, `keys referenced in source but missing in es.json:\n  ${missing.join('\n  ')}`).toEqual([]);
  });

  it('en.json and es.json have matching key sets (no locale drift)', () => {
    const enKeys = new Set(Object.keys(en));
    const esKeys = new Set(Object.keys(es));
    const enOnly = [...enKeys].filter((k) => !esKeys.has(k)).sort();
    const esOnly = [...esKeys].filter((k) => !enKeys.has(k)).sort();
    expect(enOnly, `keys in en.json but not es.json:\n  ${enOnly.join('\n  ')}`).toEqual([]);
    expect(esOnly, `keys in es.json but not en.json:\n  ${esOnly.join('\n  ')}`).toEqual([]);
  });
});
