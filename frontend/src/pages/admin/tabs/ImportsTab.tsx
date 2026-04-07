import { useState, useEffect, useCallback } from 'react';
import { Link } from 'react-router-dom';
import { useIntl } from 'react-intl';
import { api } from '../../../services/api';
import { color } from '../../../theme/colors';
import { weight } from '../../../theme/typography';
import { ErrorBox, NoData, Spinner } from '../components';
import { tableStyle, thStyle, tdStyle, primaryBtnStyle } from '../styles';
import type { ImportRow } from '../types';

function ImportsTab() {
  const intl = useIntl();
  const [imports, setImports] = useState<ImportRow[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const fetchImports = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const data = await api.get<ImportRow[]>('/api/v1/import/history');
      setImports(data || []);
    } catch (err: unknown) {
      const apiErr = err as { message?: string };
      setError(apiErr.message || intl.formatMessage({ id: 'coord.error' }));
    } finally {
      setLoading(false);
    }
  }, [intl]);

  useEffect(() => { fetchImports(); }, [fetchImports]);

  if (loading) return <Spinner />;

  return (
    <div>
      {error && <ErrorBox message={error} />}

      <div style={{ marginBottom: 16, display: 'flex', gap: 10 }}>
        <Link to="/coordinator/import/hsds" style={{
          ...primaryBtnStyle, textDecoration: 'none',
          display: 'inline-flex', alignItems: 'center',
        }}>HSDS Import</Link>
        <Link to="/coordinator/import/211" style={{
          ...primaryBtnStyle, textDecoration: 'none',
          display: 'inline-flex', alignItems: 'center',
        }}>2-1-1 Import</Link>
      </div>

      {imports.length === 0 ? <NoData /> : (
        <div style={{ overflowX: 'auto' }}>
          <table style={tableStyle}>
            <thead>
              <tr>
                <th style={thStyle}>Type</th>
                <th style={thStyle}>File</th>
                <th style={thStyle}>Created</th>
                <th style={thStyle}>Updated</th>
                <th style={thStyle}>Skipped</th>
                <th style={thStyle}>Errors</th>
                <th style={thStyle}>Date</th>
              </tr>
            </thead>
            <tbody>
              {imports.map((imp, i) => (
                <tr key={imp.id}>
                  <td style={{ ...tdStyle(i), fontWeight: weight.semibold }}>{imp.importType}</td>
                  <td style={tdStyle(i)}>{imp.filename}</td>
                  <td style={{ ...tdStyle(i), color: color.success, fontWeight: weight.semibold }}>{imp.created}</td>
                  <td style={{ ...tdStyle(i), color: color.primaryText, fontWeight: weight.semibold }}>{imp.updated}</td>
                  <td style={{ ...tdStyle(i), color: color.warning }}>{imp.skipped}</td>
                  <td style={{
                    ...tdStyle(i),
                    color: imp.errors > 0 ? color.error : color.textTertiary,
                    fontWeight: imp.errors > 0 ? weight.bold : weight.normal,
                  }}>{imp.errors}</td>
                  <td style={tdStyle(i)}>{new Date(imp.createdAt).toLocaleDateString()}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}


export default ImportsTab;
