import { useState, useEffect, useCallback } from 'react';
import { FormattedMessage, useIntl } from 'react-intl';
import { api } from '../../../services/api';
import { DataAge } from '../../../components/DataAge';
import { color } from '../../../theme/colors';
import { text, weight } from '../../../theme/typography';
import { ErrorBox, NoData, Spinner } from '../components';
import { tableStyle, thStyle, tdStyle, primaryBtnStyle } from '../styles';
import type { ShelterListItem } from '../types';

function SheltersTab() {
  const intl = useIntl();
  const [shelters, setShelters] = useState<ShelterListItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const fetchShelters = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const data = await api.get<ShelterListItem[]>('/api/v1/shelters');
      setShelters(data || []);
    } catch (err: unknown) {
      const apiErr = err as { message?: string };
      setError(apiErr.message || intl.formatMessage({ id: 'coord.error' }));
    } finally {
      setLoading(false);
    }
  }, [intl]);

  useEffect(() => { fetchShelters(); }, [fetchShelters]);

  if (loading) return <Spinner />;

  return (
    <div>
      {error && <ErrorBox message={error} />}

      <div style={{ marginBottom: 16, display: 'flex', justifyContent: 'flex-end' }}>
        <a href="/coordinator/shelters/new" style={{
          ...primaryBtnStyle, textDecoration: 'none',
          display: 'inline-flex', alignItems: 'center',
        }}>
          <FormattedMessage id="admin.addShelter" />
        </a>
      </div>

      {shelters.length === 0 ? <NoData /> : (
        <div style={{ overflowX: 'auto' }}>
          <table style={tableStyle}>
            <thead>
              <tr>
                <th style={thStyle}>Name</th>
                <th style={thStyle}>City</th>
                <th style={thStyle}>Beds Available</th>
                <th style={thStyle}>Updated</th>
                <th style={thStyle}></th>
              </tr>
            </thead>
            <tbody>
              {shelters.map((item, i) => (
                <tr key={item.shelter.id}>
                  <td style={{ ...tdStyle(i), fontWeight: weight.semibold }}>{item.shelter.name}</td>
                  <td style={tdStyle(i)}>{item.shelter.addressCity}</td>
                  <td style={tdStyle(i)}>
                    {item.availabilitySummary?.totalBedsAvailable != null
                      ? <span style={{ fontWeight: weight.bold, color: item.availabilitySummary.totalBedsAvailable > 0 ? color.success : color.error }}>
                          {item.availabilitySummary.totalBedsAvailable}
                        </span>
                      : <span style={{ color: color.textMuted }}>—</span>}
                  </td>
                  <td style={tdStyle(i)}>
                    <DataAge dataAgeSeconds={item.availabilitySummary?.dataAgeSeconds ?? null} />
                  </td>
                  <td style={tdStyle(i)}>
                    <a
                      href={`/coordinator/shelters/${item.shelter.id}/edit?from=/admin`}
                      data-testid={`edit-shelter-${item.shelter.id}`}
                      style={{
                        color: color.primaryText,
                        fontSize: text.sm,
                        fontWeight: weight.semibold,
                        textDecoration: 'none',
                      }}
                    >
                      <FormattedMessage id="shelter.editBtn" />
                    </a>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}


export default SheltersTab;
