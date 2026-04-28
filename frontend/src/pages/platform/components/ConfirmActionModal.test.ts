import { describe, expect, it } from 'vitest';
import { shouldRefuseDestructiveRender, type Variant } from './ConfirmActionModal';

/**
 * Round 9 #7 — pin the empty-slug refusal contract.
 *
 * The component itself can't be rendered in this vitest tree (no RTL /
 * jsdom), so the guard logic was extracted into a pure predicate that
 * the component calls before its first paint. These tests document the
 * contract so a future Slice E PR that mounts the destructive variant
 * can't quietly regress it.
 */

describe('shouldRefuseDestructiveRender', () => {
  it('refuses destructive variant with empty expectedSlug', () => {
    const v: Variant = { kind: 'destructive', expectedSlug: '', actionLabel: 'Suspend' };
    expect(shouldRefuseDestructiveRender(v)).toBe(true);
  });

  it('renders destructive variant with non-empty expectedSlug', () => {
    const v: Variant = {
      kind: 'destructive',
      expectedSlug: 'dev-coc',
      actionLabel: 'Suspend',
    };
    expect(shouldRefuseDestructiveRender(v)).toBe(false);
  });

  it('renders print variant regardless of slug semantics', () => {
    expect(shouldRefuseDestructiveRender({ kind: 'print' })).toBe(false);
  });

  it('renders copy variant regardless of slug semantics', () => {
    expect(shouldRefuseDestructiveRender({ kind: 'copy' })).toBe(false);
  });

  // Defensive: TS forbids `expectedSlug: undefined` at the type level
  // (it's typed `string`), but a `JSON.parse`-derived caller could pass
  // it anyway. Guard must catch the runtime shape.
  it('refuses destructive variant when expectedSlug is the runtime equivalent of missing', () => {
    const v = {
      kind: 'destructive',
      expectedSlug: undefined as unknown as string,
      actionLabel: 'Suspend',
    } as Variant;
    expect(shouldRefuseDestructiveRender(v)).toBe(true);
  });
});
