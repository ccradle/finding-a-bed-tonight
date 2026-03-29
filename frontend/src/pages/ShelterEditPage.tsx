import { useState, useEffect } from 'react';
import { useParams, useNavigate, useSearchParams } from 'react-router-dom';
import { FormattedMessage } from 'react-intl';
import { api } from '../services/api';
import { useAuth } from '../auth/useAuth';
import { ShelterForm, type ShelterInitialData } from './ShelterForm';
import { text } from '../theme/typography';

export function ShelterEditPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const returnTo = searchParams.get('from') || '/admin';
  const { user } = useAuth();

  const [initialData, setInitialData] = useState<ShelterInitialData | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const isCoordinatorOnly = user?.roles.includes('COORDINATOR')
    && !user?.roles.includes('COC_ADMIN')
    && !user?.roles.includes('PLATFORM_ADMIN');

  useEffect(() => {
    if (!id) return;

    (async () => {
      try {
        const detail = await api.get<{
          shelter: {
            id: string; name: string; addressStreet: string; addressCity: string;
            addressState: string; addressZip: string; phone: string;
            latitude: number | null; longitude: number | null; dvShelter: boolean;
          };
          constraints: {
            sobrietyRequired: boolean; idRequired: boolean; referralRequired: boolean;
            petsAllowed: boolean; wheelchairAccessible: boolean;
            populationTypesServed: string[];
          } | null;
          capacities: { populationType: string; bedsTotal: number }[];
        }>(`/api/v1/shelters/${id}`);

        setInitialData({
          id: detail.shelter.id,
          name: detail.shelter.name,
          addressStreet: detail.shelter.addressStreet || '',
          addressCity: detail.shelter.addressCity || '',
          addressState: detail.shelter.addressState || '',
          addressZip: detail.shelter.addressZip || '',
          phone: detail.shelter.phone || '',
          latitude: detail.shelter.latitude,
          longitude: detail.shelter.longitude,
          dvShelter: detail.shelter.dvShelter,
          constraints: detail.constraints || undefined,
          capacities: detail.capacities && detail.capacities.length > 0
            ? detail.capacities
            : [{ populationType: '', bedsTotal: 0 }],
        });
      } catch {
        setError('Failed to load shelter');
      } finally {
        setLoading(false);
      }
    })();
  }, [id]);

  if (loading) {
    return (
      <div style={{ textAlign: 'center', padding: '40px', fontSize: text.base }}>
        <FormattedMessage id="coord.loading" />
      </div>
    );
  }

  if (error || !initialData) {
    return (
      <div style={{ textAlign: 'center', padding: '40px', color: '#991b1b', fontSize: text.base }}>
        {error || 'Shelter not found'}
      </div>
    );
  }

  return (
    <ShelterForm
      initialData={initialData}
      readOnlyFields={isCoordinatorOnly
        ? ['name', 'addressStreet', 'addressCity', 'addressState', 'addressZip', 'dvShelter']
        : []}
      onSaveComplete={() => navigate(returnTo)}
    />
  );
}
