/**
 * Shared style objects for admin panel tabs.
 * Extracted from AdminPanel.tsx for reuse across tab files.
 */
import type React from 'react';
import { color } from '../../theme/colors';
import { text, weight } from '../../theme/typography';

export const tableStyle: React.CSSProperties = {
  width: '100%', borderCollapse: 'collapse', fontSize: text.base,
};

export const thStyle: React.CSSProperties = {
  textAlign: 'left', padding: '10px 14px', fontWeight: weight.bold, color: color.text,
  borderBottom: `2px solid ${color.border}`, fontSize: text.xs, textTransform: 'uppercase',
  letterSpacing: '0.04em',
};

export const tdStyle = (index: number): React.CSSProperties => ({
  padding: '12px 14px', borderBottom: `1px solid ${color.borderLight}`,
  backgroundColor: index % 2 === 0 ? color.bg : color.bgSecondary,
  color: color.text,
});

export const primaryBtnStyle: React.CSSProperties = {
  padding: '12px 20px', backgroundColor: color.primary, color: color.textInverse,
  border: 'none', borderRadius: 10, fontSize: text.base, fontWeight: weight.bold,
  cursor: 'pointer', minHeight: 44,
};

export const inputStyle: React.CSSProperties = {
  width: '100%', padding: '12px 14px', borderRadius: 10,
  border: `2px solid ${color.border}`, fontSize: text.base, boxSizing: 'border-box',
  color: color.text, fontWeight: weight.medium,
};
