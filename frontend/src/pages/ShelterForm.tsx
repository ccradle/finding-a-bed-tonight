import { useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { FormattedMessage, useIntl } from 'react-intl';
import { api, ApiError } from '../services/api';
import { useOnlineStatus } from '../hooks/useOnlineStatus';
import { enqueueAction } from '../services/offlineQueue';
import { text, weight } from '../theme/typography';

const POPULATION_TYPES = [
  'SINGLE_ADULT',
  'FAMILY_WITH_CHILDREN',
  'WOMEN_ONLY',
  'VETERAN',
  'YOUTH_18_24',
  'YOUTH_UNDER_18',
  'DV_SURVIVOR',
];

interface Capacity {
  populationType: string;
  bedsTotal: number;
}

export function ShelterForm() {
  const navigate = useNavigate();
  const intl = useIntl();
  const { isOnline } = useOnlineStatus();

  const [name, setName] = useState('');
  const [addressStreet, setAddressStreet] = useState('');
  const [addressCity, setAddressCity] = useState('');
  const [addressState, setAddressState] = useState('');
  const [addressZip, setAddressZip] = useState('');
  const [phone, setPhone] = useState('');
  const [latitude, setLatitude] = useState('');
  const [longitude, setLongitude] = useState('');

  const [sobrietyRequired, setSobrietyRequired] = useState(false);
  const [idRequired, setIdRequired] = useState(false);
  const [referralRequired, setReferralRequired] = useState(false);
  const [petsAllowed, setPetsAllowed] = useState(false);
  const [wheelchairAccessible, setWheelchairAccessible] = useState(false);
  const [populationTypesServed, setPopulationTypesServed] = useState<string[]>([]);

  const [capacities, setCapacities] = useState<Capacity[]>([{ populationType: '', bedsTotal: 0 }]);

  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  const togglePopulationType = (type: string) => {
    setPopulationTypesServed((prev) =>
      prev.includes(type) ? prev.filter((t) => t !== type) : [...prev, type]
    );
  };

  const addCapacity = () => {
    setCapacities((prev) => [...prev, { populationType: '', bedsTotal: 0 }]);
  };

  const removeCapacity = (index: number) => {
    setCapacities((prev) => prev.filter((_, i) => i !== index));
  };

  const updateCapacity = (index: number, field: keyof Capacity, value: string | number) => {
    setCapacities((prev) =>
      prev.map((cap, i) => (i === index ? { ...cap, [field]: value } : cap))
    );
  };

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);

    if (!name.trim()) {
      setError('Shelter name is required');
      return;
    }
    if (!addressCity.trim()) {
      setError('City is required');
      return;
    }

    const payload = {
      name: name.trim(),
      addressStreet: addressStreet.trim(),
      addressCity: addressCity.trim(),
      addressState: addressState.trim(),
      addressZip: addressZip.trim(),
      phone: phone.trim(),
      latitude: latitude ? parseFloat(latitude) : null,
      longitude: longitude ? parseFloat(longitude) : null,
      dvShelter: false,
      constraints: {
        sobrietyRequired,
        idRequired,
        referralRequired,
        petsAllowed,
        wheelchairAccessible,
        populationTypesServed,
      },
      capacities: capacities.filter((c) => c.populationType),
    };

    setLoading(true);

    try {
      if (!isOnline) {
        await enqueueAction('CREATE_SHELTER', '/api/v1/shelters', 'POST', payload);
        navigate('/coordinator');
        return;
      }

      await api.post('/api/v1/shelters', payload);
      navigate('/coordinator');
    } catch (err) {
      if (err instanceof ApiError) {
        setError(err.message);
      } else {
        setError('Failed to create shelter');
      }
    } finally {
      setLoading(false);
    }
  };

  const inputStyle: React.CSSProperties = {
    width: '100%',
    padding: '12px',
    borderRadius: '8px',
    border: '1px solid #d1d5db',
    fontSize: text.md,
    minHeight: '44px',
    boxSizing: 'border-box',
  };

  const labelStyle: React.CSSProperties = {
    display: 'block',
    marginBottom: '6px',
    fontSize: text.base,
    fontWeight: weight.medium,
    color: '#374151',
  };

  const fieldGroup: React.CSSProperties = {
    marginBottom: '16px',
  };

  const checkboxLabel: React.CSSProperties = {
    display: 'flex',
    alignItems: 'center',
    gap: '8px',
    fontSize: text.base,
    color: '#374151',
    cursor: 'pointer',
    minHeight: '44px',
    padding: '4px 0',
  };

  return (
    <div style={{ maxWidth: '680px', margin: '0 auto' }}>
      <h2 style={{ fontSize: text['2xl'], fontWeight: weight.bold, color: '#111827', marginBottom: '24px' }}>
        <FormattedMessage id="shelter.create" />
      </h2>

      {error && (
        <div
          style={{
            backgroundColor: '#fef2f2',
            color: '#991b1b',
            padding: '12px 16px',
            borderRadius: '8px',
            marginBottom: '20px',
            fontSize: text.base,
          }}
          role="alert"
        >
          {error}
        </div>
      )}

      <form onSubmit={handleSubmit}>
        <div style={fieldGroup}>
          <label htmlFor="shelter-name" style={labelStyle}>
            <FormattedMessage id="shelter.name" /> *
          </label>
          <input
            id="shelter-name"
            type="text"
            value={name}
            onChange={(e) => setName(e.target.value)}
            required
            style={inputStyle}
          />
        </div>

        <div style={fieldGroup}>
          <label htmlFor="address-street" style={labelStyle}>
            Street Address
          </label>
          <input
            id="address-street"
            type="text"
            value={addressStreet}
            onChange={(e) => setAddressStreet(e.target.value)}
            style={inputStyle}
          />
        </div>

        <div style={{ display: 'grid', gridTemplateColumns: '2fr 1fr 1fr', gap: '12px', marginBottom: '16px' }}>
          <div>
            <label htmlFor="address-city" style={labelStyle}>
              City *
            </label>
            <input
              id="address-city"
              type="text"
              value={addressCity}
              onChange={(e) => setAddressCity(e.target.value)}
              required
              style={inputStyle}
            />
          </div>
          <div>
            <label htmlFor="address-state" style={labelStyle}>
              State
            </label>
            <input
              id="address-state"
              type="text"
              value={addressState}
              onChange={(e) => setAddressState(e.target.value)}
              maxLength={2}
              style={inputStyle}
            />
          </div>
          <div>
            <label htmlFor="address-zip" style={labelStyle}>
              ZIP
            </label>
            <input
              id="address-zip"
              type="text"
              value={addressZip}
              onChange={(e) => setAddressZip(e.target.value)}
              style={inputStyle}
            />
          </div>
        </div>

        <div style={fieldGroup}>
          <label htmlFor="phone" style={labelStyle}>
            Phone
          </label>
          <input
            id="phone"
            type="tel"
            value={phone}
            onChange={(e) => setPhone(e.target.value)}
            style={inputStyle}
          />
        </div>

        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '12px', marginBottom: '16px' }}>
          <div>
            <label htmlFor="latitude" style={labelStyle}>
              Latitude
            </label>
            <input
              id="latitude"
              type="number"
              step="any"
              value={latitude}
              onChange={(e) => setLatitude(e.target.value)}
              style={inputStyle}
            />
          </div>
          <div>
            <label htmlFor="longitude" style={labelStyle}>
              Longitude
            </label>
            <input
              id="longitude"
              type="number"
              step="any"
              value={longitude}
              onChange={(e) => setLongitude(e.target.value)}
              style={inputStyle}
            />
          </div>
        </div>

        {/* Constraints */}
        <h3 style={{ fontSize: text.md, fontWeight: weight.semibold, color: '#111827', marginBottom: '12px', marginTop: '24px' }}>
          Requirements &amp; Accessibility
        </h3>
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '4px 16px', marginBottom: '16px' }}>
          <label style={checkboxLabel}>
            <input type="checkbox" checked={sobrietyRequired} onChange={(e) => setSobrietyRequired(e.target.checked)} />
            Sobriety Required
          </label>
          <label style={checkboxLabel}>
            <input type="checkbox" checked={idRequired} onChange={(e) => setIdRequired(e.target.checked)} />
            ID Required
          </label>
          <label style={checkboxLabel}>
            <input type="checkbox" checked={referralRequired} onChange={(e) => setReferralRequired(e.target.checked)} />
            Referral Required
          </label>
          <label style={checkboxLabel}>
            <input type="checkbox" checked={petsAllowed} onChange={(e) => setPetsAllowed(e.target.checked)} />
            Pets Allowed
          </label>
          <label style={checkboxLabel}>
            <input
              type="checkbox"
              checked={wheelchairAccessible}
              onChange={(e) => setWheelchairAccessible(e.target.checked)}
            />
            Wheelchair Accessible
          </label>
        </div>

        {/* Population Types Served */}
        <h3 style={{ fontSize: text.md, fontWeight: weight.semibold, color: '#111827', marginBottom: '12px', marginTop: '24px' }}>
          Population Types Served
        </h3>
        <div style={{ display: 'flex', flexWrap: 'wrap', gap: '8px', marginBottom: '24px' }}>
          {POPULATION_TYPES.map((type) => (
            <button
              key={type}
              type="button"
              onClick={() => togglePopulationType(type)}
              style={{
                padding: '8px 16px',
                borderRadius: '20px',
                border: populationTypesServed.includes(type)
                  ? '2px solid #1a56db'
                  : '1px solid #d1d5db',
                backgroundColor: populationTypesServed.includes(type) ? '#dbeafe' : '#ffffff',
                color: populationTypesServed.includes(type) ? '#1a56db' : '#374151',
                cursor: 'pointer',
                fontSize: text.sm,
                fontWeight: weight.medium,
                minHeight: '44px',
              }}
            >
              {type.replace(/_/g, ' ')}
            </button>
          ))}
        </div>

        {/* Capacities */}
        <h3 style={{ fontSize: text.md, fontWeight: weight.semibold, color: '#111827', marginBottom: '12px', marginTop: '24px' }}>
          Bed Capacities
        </h3>
        {capacities.map((cap, index) => (
          <div
            key={index}
            style={{
              display: 'flex',
              gap: '12px',
              alignItems: 'flex-end',
              marginBottom: '12px',
            }}
          >
            <div style={{ flex: 2 }}>
              <label style={labelStyle}>Population Type</label>
              <select
                value={cap.populationType}
                onChange={(e) => updateCapacity(index, 'populationType', e.target.value)}
                aria-label={`Population type for capacity ${index + 1}`}
                style={inputStyle}
              >
                <option value="">Select...</option>
                {POPULATION_TYPES.map((type) => (
                  <option key={type} value={type}>
                    {type.replace(/_/g, ' ')}
                  </option>
                ))}
              </select>
            </div>
            <div style={{ flex: 1 }}>
              <label style={labelStyle}>Total Beds</label>
              <input
                type="number"
                min="0"
                value={cap.bedsTotal}
                onChange={(e) => updateCapacity(index, 'bedsTotal', parseInt(e.target.value) || 0)}
                style={inputStyle}
              />
            </div>
            <button
              type="button"
              onClick={() => removeCapacity(index)}
              style={{
                padding: '12px',
                backgroundColor: '#fef2f2',
                color: '#991b1b',
                border: '1px solid #fecaca',
                borderRadius: '8px',
                cursor: 'pointer',
                minHeight: '44px',
                minWidth: '44px',
                fontSize: text.md,
              }}
            >
              X
            </button>
          </div>
        ))}
        <button
          type="button"
          onClick={addCapacity}
          style={{
            padding: '10px 20px',
            backgroundColor: '#f9fafb',
            color: '#374151',
            border: '1px dashed #d1d5db',
            borderRadius: '8px',
            cursor: 'pointer',
            fontSize: text.base,
            marginBottom: '32px',
            minHeight: '44px',
          }}
        >
          + Add Capacity
        </button>

        <div>
          <button
            type="submit"
            disabled={loading}
            style={{
              width: '100%',
              padding: '14px',
              backgroundColor: loading ? '#93c5fd' : '#1a56db',
              color: '#ffffff',
              border: 'none',
              borderRadius: '8px',
              fontSize: text.md,
              fontWeight: weight.semibold,
              cursor: loading ? 'not-allowed' : 'pointer',
              minHeight: '44px',
            }}
          >
            {loading
              ? 'Saving...'
              : intl.formatMessage({ id: 'shelter.create' })}
          </button>
        </div>
      </form>
    </div>
  );
}
