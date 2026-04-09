import { useState, useRef, useCallback, useId } from 'react';
import { useIntl, FormattedMessage } from 'react-intl';
import { color } from '../theme/colors';
import { text, weight } from '../theme/typography';

export interface CoordinatorOption {
  id: string;
  displayName: string;
  email: string;
  dvAccess: boolean;
}

interface Props {
  options: CoordinatorOption[];
  selected: CoordinatorOption[];
  onChange: (selected: CoordinatorOption[]) => void;
  isDvShelter: boolean;
}

/**
 * Searchable combobox + removable chips for coordinator assignment.
 * Follows W3C APG Combobox Pattern: role="combobox", aria-haspopup="listbox",
 * aria-activedescendant, keyboard arrows/enter/escape.
 */
export function CoordinatorCombobox({ options, selected, onChange, isDvShelter }: Props) {
  const intl = useIntl();
  const instanceId = useId();
  const listboxId = `${instanceId}-listbox`;
  const inputRef = useRef<HTMLInputElement>(null);

  const [query, setQuery] = useState('');
  const [open, setOpen] = useState(false);
  const [activeIndex, setActiveIndex] = useState(-1);

  const selectedIds = new Set(selected.map((c) => c.id));

  const filtered = options.filter(
    (o) =>
      !selectedIds.has(o.id) &&
      (o.displayName.toLowerCase().includes(query.toLowerCase()) ||
        o.email.toLowerCase().includes(query.toLowerCase()))
  );

  const activeDescendant = activeIndex >= 0 && activeIndex < filtered.length
    ? `${instanceId}-option-${filtered[activeIndex].id}`
    : undefined;

  const selectOption = useCallback(
    (option: CoordinatorOption) => {
      onChange([...selected, option]);
      setQuery('');
      setOpen(false);
      setActiveIndex(-1);
      inputRef.current?.focus();
    },
    [selected, onChange]
  );

  const removeOption = useCallback(
    (id: string) => {
      onChange(selected.filter((c) => c.id !== id));
      inputRef.current?.focus();
    },
    [selected, onChange]
  );

  const handleKeyDown = useCallback(
    (e: React.KeyboardEvent) => {
      if (!open && (e.key === 'ArrowDown' || e.key === 'ArrowUp')) {
        setOpen(true);
        setActiveIndex(0);
        e.preventDefault();
        return;
      }
      if (!open) return;

      switch (e.key) {
        case 'ArrowDown':
          e.preventDefault();
          setActiveIndex((i) => Math.min(i + 1, filtered.length - 1));
          break;
        case 'ArrowUp':
          e.preventDefault();
          setActiveIndex((i) => Math.max(i - 1, 0));
          break;
        case 'Enter':
          e.preventDefault();
          if (activeIndex >= 0 && activeIndex < filtered.length) {
            selectOption(filtered[activeIndex]);
          }
          break;
        case 'Escape':
          e.preventDefault();
          setOpen(false);
          setActiveIndex(-1);
          break;
      }
    },
    [open, filtered, activeIndex, selectOption]
  );

  return (
    <div style={{ marginTop: '16px' }}>
      <label
        htmlFor={`${instanceId}-input`}
        style={{ display: 'block', fontSize: text.sm, fontWeight: weight.semibold, color: color.text, marginBottom: '6px' }}
      >
        <FormattedMessage id="shelter.assignedCoordinators" />
      </label>

      {/* Chips */}
      {selected.length > 0 && (
        <div style={{ display: 'flex', flexWrap: 'wrap', gap: '6px', marginBottom: '8px' }} role="list" aria-label={intl.formatMessage({ id: 'shelter.assignedCoordinators' })}>
          {selected.map((c) => (
            <span
              key={c.id}
              role="listitem"
              style={{
                display: 'inline-flex',
                alignItems: 'center',
                gap: '4px',
                padding: '4px 8px',
                borderRadius: '16px',
                fontSize: text.sm,
                fontWeight: weight.medium,
                color: color.primaryText,
                backgroundColor: color.primaryLight,
                border: `1px solid ${color.border}`,
              }}
            >
              {c.displayName}
              {isDvShelter && !c.dvAccess && (
                <span
                  role="img"
                  style={{ color: color.warning, fontSize: text.xs, marginLeft: '2px' }}
                  aria-label={intl.formatMessage({ id: 'shelter.dvAccessWarning' })}
                >
                  ⚠
                </span>
              )}
              <button
                type="button"
                onClick={() => removeOption(c.id)}
                aria-label={intl.formatMessage({ id: 'shelter.removeCoordinator' }, { name: c.displayName })}
                style={{
                  background: 'none',
                  border: 'none',
                  cursor: 'pointer',
                  color: color.textMuted,
                  fontSize: text.md,
                  lineHeight: 1,
                  padding: '2px',
                  minHeight: '44px',
                  minWidth: '44px',
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'center',
                }}
              >
                ×
              </button>
            </span>
          ))}
        </div>
      )}

      {selected.length === 0 && (
        <p style={{ color: color.textMuted, fontSize: text.sm, margin: '0 0 8px 0' }}>
          <FormattedMessage id="shelter.noCoordinatorsAssigned" />
        </p>
      )}

      {/* Combobox input */}
      <div style={{ position: 'relative' }}>
        <input
          ref={inputRef}
          id={`${instanceId}-input`}
          type="text"
          role="combobox"
          aria-haspopup="listbox"
          aria-expanded={open}
          aria-controls={listboxId}
          aria-activedescendant={activeDescendant}
          aria-autocomplete="list"
          data-testid="coordinator-combobox-input"
          placeholder={intl.formatMessage({ id: 'shelter.searchCoordinators' })}
          value={query}
          onChange={(e) => {
            setQuery(e.target.value);
            setOpen(true);
            setActiveIndex(0);
          }}
          onFocus={() => { if (query || filtered.length > 0) setOpen(true); }}
          onBlur={() => { setTimeout(() => setOpen(false), 200); }}
          onKeyDown={handleKeyDown}
          style={{
            width: '100%',
            padding: '8px 12px',
            fontSize: text.base,
            border: `1px solid ${color.borderMedium}`,
            borderRadius: '6px',
            backgroundColor: color.bg,
            color: color.text,
            minHeight: '44px',
            boxSizing: 'border-box',
          }}
        />

        {/* Listbox dropdown */}
        {open && filtered.length > 0 && (
          <ul
            id={listboxId}
            role="listbox"
            data-testid="coordinator-combobox-listbox"
            style={{
              position: 'absolute',
              top: '100%',
              left: 0,
              right: 0,
              marginTop: '4px',
              padding: 0,
              listStyle: 'none',
              backgroundColor: color.bg,
              border: `1px solid ${color.borderMedium}`,
              borderRadius: '6px',
              boxShadow: '0 4px 12px rgba(0,0,0,0.1)',
              maxHeight: '200px',
              overflowY: 'auto',
              zIndex: 100,
            }}
          >
            {filtered.map((option, index) => (
              <li
                key={option.id}
                id={`${instanceId}-option-${option.id}`}
                role="option"
                aria-selected={index === activeIndex}
                data-testid={`coordinator-option-${option.id}`}
                onMouseDown={(e) => { e.preventDefault(); selectOption(option); }}
                onMouseEnter={() => setActiveIndex(index)}
                style={{
                  padding: '8px 12px',
                  cursor: 'pointer',
                  backgroundColor: index === activeIndex ? color.primaryLight : color.bg,
                  color: color.text,
                  fontSize: text.base,
                  minHeight: '44px',
                  display: 'flex',
                  flexDirection: 'column',
                  justifyContent: 'center',
                }}
              >
                <div style={{ fontWeight: weight.medium }}>
                  {option.displayName}
                  {isDvShelter && option.dvAccess && (
                    <span style={{
                      marginLeft: '8px',
                      fontSize: text.xs,
                      color: color.dvText,
                      backgroundColor: color.dvBg,
                      border: `1px solid ${color.dvBorder}`,
                      padding: '1px 6px',
                      borderRadius: '10px',
                    }}>
                      DV
                    </span>
                  )}
                </div>
                <div style={{ fontSize: text.xs, color: color.textMuted }}>
                  {option.email}
                </div>
                {isDvShelter && !option.dvAccess && (
                  <div style={{ fontSize: text.xs, color: color.warning, marginTop: '2px' }}>
                    <FormattedMessage id="shelter.dvAccessWarning" />
                  </div>
                )}
              </li>
            ))}
          </ul>
        )}
      </div>
    </div>
  );
}
