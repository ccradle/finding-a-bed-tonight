import { text } from '../theme/typography';
import { color } from '../theme/colors';

interface LocaleSelectorProps {
  locale: string;
  onLocaleChange: (locale: string) => void;
}

const selectStyle: React.CSSProperties = {
  padding: '8px 12px',
  borderRadius: '6px',
  border: `1px solid ${color.borderMedium}`,
  backgroundColor: color.bg,
  fontSize: text.base,
  cursor: 'pointer',
  minHeight: '44px',
  minWidth: '44px',
};

export function LocaleSelector({ locale, onLocaleChange }: LocaleSelectorProps) {
  return (
    <select
      value={locale}
      onChange={(e) => onLocaleChange(e.target.value)}
      style={selectStyle}
      aria-label="Select language"
    >
      <option value="en">English</option>
      <option value="es">Espanol</option>
    </select>
  );
}
