import { useState, type KeyboardEvent } from 'react';
import { useIntl } from 'react-intl';
import { color } from '../theme/colors';
import { text, weight } from '../theme/typography';

/**
 * Reusable input + chip-list editor for free-form string arrays.
 *
 * <p>Used by transitional-reentry-support slice 4 §10.3 for
 * `program_requirements` and `documentation_required` on the shelter
 * eligibility-criteria form. Could be reused by any future
 * array-of-strings field; keeps a generic API so it can.
 *
 * <p>Trimming + dedup applied on add. Empty strings are silently
 * dropped (Enter on empty input is a no-op). Keyboard: Enter adds the
 * current input; clicking the chip's × removes that chip.
 */
export interface TagEditorProps {
  values: string[];
  onChange: (next: string[]) => void;
  placeholderId?: string;
  'data-testid'?: string;
  maxLength?: number;
}

export function TagEditor({
  values,
  onChange,
  placeholderId = 'shelter.eligibility.tagInputPlaceholder',
  'data-testid': dataTestid,
  maxLength = 100,
}: TagEditorProps) {
  const intl = useIntl();
  const [input, setInput] = useState('');

  const addCurrent = () => {
    const v = input.trim();
    if (!v) return;
    if (values.includes(v)) {
      setInput('');
      return;
    }
    onChange([...values, v]);
    setInput('');
  };

  const removeAt = (index: number) => {
    onChange(values.filter((_, i) => i !== index));
  };

  const handleKeyDown = (e: KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'Enter') {
      e.preventDefault();
      addCurrent();
    }
  };

  return (
    <div data-testid={dataTestid}>
      <div style={{ display: 'flex', gap: 6 }}>
        <input
          type="text"
          value={input}
          onChange={(e) => setInput(e.target.value)}
          onKeyDown={handleKeyDown}
          placeholder={intl.formatMessage({ id: placeholderId })}
          maxLength={maxLength}
          style={{
            flex: 1, padding: '8px 12px', borderRadius: 6,
            border: `1.5px solid ${color.border}`, fontSize: text.base, minHeight: 38,
          }}
        />
        <button
          type="button"
          onClick={addCurrent}
          disabled={!input.trim()}
          style={{
            padding: '8px 14px', borderRadius: 6, border: `1.5px solid ${color.primary}`,
            backgroundColor: input.trim() ? color.primary : color.bgSecondary,
            color: input.trim() ? color.textInverse : color.textTertiary,
            fontSize: text.sm, fontWeight: weight.semibold,
            cursor: input.trim() ? 'pointer' : 'not-allowed',
          }}
        >
          {intl.formatMessage({ id: 'common.add' })}
        </button>
      </div>
      {values.length > 0 && (
        <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap', marginTop: 8 }}>
          {values.map((v, i) => (
            <span
              key={`${v}-${i}`}
              style={{
                display: 'inline-flex', alignItems: 'center', gap: 6,
                padding: '4px 4px 4px 10px', borderRadius: 12,
                backgroundColor: color.bgSecondary, color: color.text,
                fontSize: text.sm, fontWeight: weight.medium,
              }}
            >
              {v}
              <button
                type="button"
                onClick={() => removeAt(i)}
                aria-label={`${intl.formatMessage({ id: 'common.remove' })} ${v}`}
                style={{
                  border: 'none', backgroundColor: 'transparent',
                  color: color.textTertiary, fontSize: text.base,
                  cursor: 'pointer', padding: '0 6px', lineHeight: 1,
                }}
              >
                ×
              </button>
            </span>
          ))}
        </div>
      )}
    </div>
  );
}
