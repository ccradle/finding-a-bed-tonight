import { useState, useEffect, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { FormattedMessage, useIntl } from 'react-intl';
import { api, ApiError } from '../services/api';
import { useOnlineStatus } from '../hooks/useOnlineStatus';
import { enqueueAction } from '../services/offlineQueue';
import { text, weight } from '../theme/typography';
import { color } from '../theme/colors';
import { CoordinatorCombobox, type CoordinatorOption } from '../components/CoordinatorCombobox';

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

export interface ShelterInitialData {
  id: string;
  name: string;
  addressStreet: string;
  addressCity: string;
  addressState: string;
  addressZip: string;
  phone: string;
  latitude: number | null;
  longitude: number | null;
  dvShelter: boolean;
  constraints?: {
    sobrietyRequired: boolean;
    idRequired: boolean;
    referralRequired: boolean;
    petsAllowed: boolean;
    wheelchairAccessible: boolean;
    populationTypesServed: string[];
  };
  capacities: Capacity[];
}

interface ShelterFormProps {
  initialData?: ShelterInitialData;
  readOnlyFields?: string[];
  onSaveComplete?: () => void;
}

export function ShelterForm({ initialData, readOnlyFields = [], onSaveComplete }: ShelterFormProps) {
  const navigate = useNavigate();
  const intl = useIntl();
  const { isOnline } = useOnlineStatus();

  const isEditMode = !!initialData;

  const [name, setName] = useState(initialData?.name || '');
  const [addressStreet, setAddressStreet] = useState(initialData?.addressStreet || '');
  const [addressCity, setAddressCity] = useState(initialData?.addressCity || '');
  const [addressState, setAddressState] = useState(initialData?.addressState || '');
  const [addressZip, setAddressZip] = useState(initialData?.addressZip || '');
  const [phone, setPhone] = useState(initialData?.phone || '');
  const [latitude, setLatitude] = useState(initialData?.latitude?.toString() || '');
  const [longitude, setLongitude] = useState(initialData?.longitude?.toString() || '');
  const [dvShelter, setDvShelter] = useState(initialData?.dvShelter || false);

  const [sobrietyRequired, setSobrietyRequired] = useState(initialData?.constraints?.sobrietyRequired || false);
  const [idRequired, setIdRequired] = useState(initialData?.constraints?.idRequired || false);
  const [referralRequired, setReferralRequired] = useState(initialData?.constraints?.referralRequired || false);
  const [petsAllowed, setPetsAllowed] = useState(initialData?.constraints?.petsAllowed || false);
  const [wheelchairAccessible, setWheelchairAccessible] = useState(initialData?.constraints?.wheelchairAccessible || false);
  const [populationTypesServed, setPopulationTypesServed] = useState<string[]>(
    initialData?.constraints?.populationTypesServed || []
  );

  const [capacities, setCapacities] = useState<Capacity[]>(
    initialData?.capacities && initialData.capacities.length > 0
      ? initialData.capacities
      : [{ populationType: '', bedsTotal: 0 }]
  );

  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [showDvConfirm, setShowDvConfirm] = useState(false);
  const [pendingDvValue, setPendingDvValue] = useState(false);

  // Coordinator assignment state (edit mode only)
  const [eligibleCoordinators, setEligibleCoordinators] = useState<CoordinatorOption[]>([]);
  const [assignedCoordinators, setAssignedCoordinators] = useState<CoordinatorOption[]>([]);
  const [originalAssignedIds, setOriginalAssignedIds] = useState<Set<string>>(new Set());

  useEffect(() => {
    if (!isEditMode) return;
    // Fetch eligible users (COORDINATOR + COC_ADMIN roles) and current assignments in parallel
    Promise.all([
      api.get<Array<{ id: string; displayName: string; email: string; roles: string[]; dvAccess: boolean; status: string }>>('/api/v1/users'),
      api.get<string[]>(`/api/v1/shelters/${initialData.id}/coordinators`),
    ]).then(([users, assignedUserIds]) => {
      const eligible = users
        .filter((u) => u.status !== 'DEACTIVATED' &&
          (u.roles.includes('COORDINATOR') || u.roles.includes('COC_ADMIN')))
        .map((u) => ({ id: u.id, displayName: u.displayName, email: u.email, dvAccess: u.dvAccess }));
      setEligibleCoordinators(eligible);

      const assignedIdSet = new Set(assignedUserIds);
      const assigned = eligible.filter((u) => assignedIdSet.has(u.id));
      setAssignedCoordinators(assigned);
      setOriginalAssignedIds(new Set(assigned.map((c) => c.id)));
    }).catch(() => { /* best-effort */ });
  }, [isEditMode, initialData?.id]);

  const isFieldReadOnly = (field: string) => readOnlyFields.includes(field);

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

  const handleDvToggle = (newValue: boolean) => {
    // Turning DV off (true→false) requires confirmation
    if (dvShelter && !newValue) {
      setPendingDvValue(newValue);
      setShowDvConfirm(true);
      return;
    }
    setDvShelter(newValue);
  };

  const confirmDvChange = () => {
    setDvShelter(pendingDvValue);
    setShowDvConfirm(false);
  };

  const cancelDvChange = () => {
    setShowDvConfirm(false);
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

    const payload: Record<string, unknown> = {
      name: name.trim(),
      addressStreet: addressStreet.trim(),
      addressCity: addressCity.trim(),
      addressState: addressState.trim(),
      addressZip: addressZip.trim(),
      phone: phone.trim(),
      latitude: latitude ? parseFloat(latitude) : null,
      longitude: longitude ? parseFloat(longitude) : null,
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

    if (isEditMode) {
      payload.dvShelter = dvShelter;
    } else {
      payload.dvShelter = false;
    }

    setLoading(true);

    try {
      if (isEditMode) {
        await api.put(`/api/v1/shelters/${initialData.id}`, payload);

        // Diff coordinator assignments and apply changes
        const currentIds = new Set(assignedCoordinators.map((c) => c.id));
        const toAdd = assignedCoordinators.filter((c) => !originalAssignedIds.has(c.id));
        const toRemove = [...originalAssignedIds].filter((id) => !currentIds.has(id));

        await Promise.all([
          ...toAdd.map((c) => api.post(`/api/v1/shelters/${initialData.id}/coordinators`, { userId: c.id })),
          ...toRemove.map((id) => api.delete(`/api/v1/shelters/${initialData.id}/coordinators/${id}`)),
        ]);

        if (onSaveComplete) {
          onSaveComplete();
        } else {
          navigate('/admin');
        }
      } else {
        if (!isOnline) {
          await enqueueAction('CREATE_SHELTER', '/api/v1/shelters', 'POST', payload);
          navigate('/coordinator');
          return;
        }
        await api.post('/api/v1/shelters', payload);
        navigate('/coordinator');
      }
    } catch (err) {
      if (err instanceof ApiError) {
        setError(err.message);
      } else {
        setError(isEditMode ? 'Failed to update shelter' : 'Failed to create shelter');
      }
    } finally {
      setLoading(false);
    }
  };

  const inputStyle: React.CSSProperties = {
    width: '100%',
    padding: '12px',
    borderRadius: '8px',
    border: `1px solid ${color.borderMedium}`,
    fontSize: text.md,
    minHeight: '44px',
    boxSizing: 'border-box',
  };

  const disabledInputStyle: React.CSSProperties = {
    ...inputStyle,
    backgroundColor: color.bgTertiary,
    color: color.textMuted,
    cursor: 'not-allowed',
  };

  const labelStyle: React.CSSProperties = {
    display: 'block',
    marginBottom: '6px',
    fontSize: text.base,
    fontWeight: weight.medium,
    color: color.textSecondary,
  };

  const fieldGroup: React.CSSProperties = {
    marginBottom: '16px',
  };

  const checkboxLabel: React.CSSProperties = {
    display: 'flex',
    alignItems: 'center',
    gap: '8px',
    fontSize: text.base,
    color: color.textSecondary,
    cursor: 'pointer',
    minHeight: '44px',
    padding: '4px 0',
  };

  return (
    <div style={{ maxWidth: '680px', margin: '0 auto' }}>
      <h2 style={{ fontSize: text['2xl'], fontWeight: weight.bold, color: color.text, marginBottom: '24px' }}>
        <FormattedMessage id={isEditMode ? 'shelter.edit' : 'shelter.create'} />
      </h2>

      {error && (
        <div
          style={{
            backgroundColor: color.errorBg,
            color: color.error,
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
            data-testid="shelter-name"
            type="text"
            value={name}
            onChange={(e) => setName(e.target.value)}
            required
            disabled={isFieldReadOnly('name')}
            aria-disabled={isFieldReadOnly('name')}
            style={isFieldReadOnly('name') ? disabledInputStyle : inputStyle}
          />
        </div>

        <div style={fieldGroup}>
          <label htmlFor="address-street" style={labelStyle}>
            <FormattedMessage id="shelter.address" />
          </label>
          <input
            id="address-street"
            data-testid="shelter-address-street"
            type="text"
            value={addressStreet}
            onChange={(e) => setAddressStreet(e.target.value)}
            disabled={isFieldReadOnly('addressStreet')}
            aria-disabled={isFieldReadOnly('addressStreet')}
            style={isFieldReadOnly('addressStreet') ? disabledInputStyle : inputStyle}
          />
        </div>

        <div style={{ display: 'grid', gridTemplateColumns: '2fr 1fr 1fr', gap: '12px', marginBottom: '16px' }}>
          <div>
            <label htmlFor="address-city" style={labelStyle}>
              City *
            </label>
            <input
              id="address-city"
              data-testid="shelter-address-city"
              type="text"
              value={addressCity}
              onChange={(e) => setAddressCity(e.target.value)}
              required
              disabled={isFieldReadOnly('addressCity')}
              aria-disabled={isFieldReadOnly('addressCity')}
              style={isFieldReadOnly('addressCity') ? disabledInputStyle : inputStyle}
            />
          </div>
          <div>
            <label htmlFor="address-state" style={labelStyle}>
              State
            </label>
            <input
              id="address-state"
              data-testid="shelter-address-state"
              type="text"
              value={addressState}
              onChange={(e) => setAddressState(e.target.value)}
              maxLength={2}
              disabled={isFieldReadOnly('addressState')}
              aria-disabled={isFieldReadOnly('addressState')}
              style={isFieldReadOnly('addressState') ? disabledInputStyle : inputStyle}
            />
          </div>
          <div>
            <label htmlFor="address-zip" style={labelStyle}>
              ZIP
            </label>
            <input
              id="address-zip"
              data-testid="shelter-address-zip"
              type="text"
              value={addressZip}
              onChange={(e) => setAddressZip(e.target.value)}
              disabled={isFieldReadOnly('addressZip')}
              aria-disabled={isFieldReadOnly('addressZip')}
              style={isFieldReadOnly('addressZip') ? disabledInputStyle : inputStyle}
            />
          </div>
        </div>

        <div style={fieldGroup}>
          <label htmlFor="phone" style={labelStyle}>
            Phone
          </label>
          <input
            id="phone"
            data-testid="shelter-phone"
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

        {/* DV Shelter toggle — edit mode only */}
        {isEditMode && (
          <div style={{ ...fieldGroup, marginTop: '24px' }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: '12px', position: 'relative' }}>
              <label
                htmlFor="dv-shelter-toggle"
                style={{ ...labelStyle, marginBottom: 0, cursor: isFieldReadOnly('dvShelter') ? 'not-allowed' : 'pointer' }}
              >
                <FormattedMessage id="shelter.dvFlag" />
              </label>
              <button
                id="dv-shelter-toggle"
                data-testid="dv-shelter-toggle"
                type="button"
                role="switch"
                aria-checked={dvShelter}
                aria-disabled={isFieldReadOnly('dvShelter')}
                disabled={isFieldReadOnly('dvShelter')}
                onClick={() => handleDvToggle(!dvShelter)}
                style={{
                  width: '48px',
                  height: '28px',
                  borderRadius: '14px',
                  border: 'none',
                  backgroundColor: dvShelter ? color.dv : color.borderMedium,
                  cursor: isFieldReadOnly('dvShelter') ? 'not-allowed' : 'pointer',
                  position: 'relative',
                  transition: 'background-color 0.2s',
                  opacity: isFieldReadOnly('dvShelter') ? 0.5 : 1,
                }}
              >
                <span style={{
                  position: 'absolute',
                  top: '2px',
                  left: dvShelter ? '22px' : '2px',
                  width: '24px',
                  height: '24px',
                  borderRadius: '50%',
                  backgroundColor: color.bg,
                  transition: 'left 0.2s',
                }} />
              </button>
              {isFieldReadOnly('dvShelter') && (
                <span
                  data-testid="dv-readonly-tooltip"
                  style={{ fontSize: text.xs, color: color.textMuted, fontStyle: 'italic' }}
                >
                  <FormattedMessage id="shelter.dvFlagDisabled" />
                </span>
              )}
            </div>
          </div>
        )}

        {/* DV Confirmation Dialog */}
        {showDvConfirm && (
          <div
            role="alertdialog"
            aria-modal="true"
            aria-labelledby="dv-confirm-title"
            aria-describedby="dv-confirm-desc"
            data-testid="dv-confirm-dialog"
            style={{
              position: 'fixed',
              top: 0, left: 0, right: 0, bottom: 0,
              backgroundColor: 'rgba(0,0,0,0.5)',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              zIndex: 1000,
            }}
            onKeyDown={(e) => { if (e.key === 'Escape') cancelDvChange(); }}
          >
            <div style={{
              backgroundColor: color.bg,
              borderRadius: '12px',
              padding: '24px',
              maxWidth: '480px',
              width: '90%',
              boxShadow: '0 20px 60px rgba(0,0,0,0.3)',
            }}>
              <h3 id="dv-confirm-title" style={{ fontSize: text.lg, fontWeight: weight.bold, color: color.error, marginTop: 0 }}>
                <FormattedMessage id="shelter.dvConfirmTitle" />
              </h3>
              <p id="dv-confirm-desc" style={{ fontSize: text.base, color: color.textSecondary, lineHeight: 1.6 }}>
                <FormattedMessage id="shelter.dvConfirmMessage" />
              </p>
              <div style={{ display: 'flex', gap: '12px', justifyContent: 'flex-end', marginTop: '20px' }}>
                <button
                  type="button"
                  data-testid="dv-confirm-cancel"
                  onClick={cancelDvChange}
                  style={{
                    padding: '10px 20px',
                    borderRadius: '8px',
                    border: `1px solid ${color.borderMedium}`,
                    backgroundColor: color.bg,
                    color: color.textSecondary,
                    cursor: 'pointer',
                    fontSize: text.base,
                    minHeight: '44px',
                  }}
                >
                  <FormattedMessage id="referral.cancel" />
                </button>
                <button
                  type="button"
                  data-testid="dv-confirm-proceed"
                  onClick={confirmDvChange}
                  autoFocus
                  style={{
                    padding: '10px 20px',
                    borderRadius: '8px',
                    border: 'none',
                    backgroundColor: color.error,
                    color: color.textInverse,
                    cursor: 'pointer',
                    fontSize: text.base,
                    fontWeight: weight.semibold,
                    minHeight: '44px',
                  }}
                >
                  <FormattedMessage id="shelter.dvConfirmProceed" />
                </button>
              </div>
            </div>
          </div>
        )}

        {/* Constraints */}
        <h3 style={{ fontSize: text.md, fontWeight: weight.semibold, color: color.text, marginBottom: '12px', marginTop: '24px' }}>
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
        <h3 style={{ fontSize: text.md, fontWeight: weight.semibold, color: color.text, marginBottom: '12px', marginTop: '24px' }}>
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
                  ? `2px solid ${color.primaryText}`
                  : `1px solid ${color.borderMedium}`,
                backgroundColor: populationTypesServed.includes(type) ? color.primaryLight : color.bg,
                color: populationTypesServed.includes(type) ? color.primary : color.textSecondary,
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
        <h3 style={{ fontSize: text.md, fontWeight: weight.semibold, color: color.text, marginBottom: '12px', marginTop: '24px' }}>
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
                backgroundColor: color.errorBg,
                color: color.error,
                border: `1px solid ${color.errorBorder}`,
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
            backgroundColor: color.bgSecondary,
            color: color.textSecondary,
            border: `1px dashed ${color.borderMedium}`,
            borderRadius: '8px',
            cursor: 'pointer',
            fontSize: text.base,
            marginBottom: '32px',
            minHeight: '44px',
          }}
        >
          + Add Capacity
        </button>

        {/* Coordinator Assignment (edit mode only) */}
        {isEditMode && (
          <CoordinatorCombobox
            options={eligibleCoordinators}
            selected={assignedCoordinators}
            onChange={setAssignedCoordinators}
            isDvShelter={dvShelter}
          />
        )}

        <div style={{ marginTop: '24px' }}>
          <button
            type="submit"
            data-testid="shelter-save"
            disabled={loading}
            style={{
              width: '100%',
              padding: '14px',
              backgroundColor: loading ? color.primaryDisabled : color.primary,
              color: color.textInverse,
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
              : intl.formatMessage({ id: isEditMode ? 'shelter.save' : 'shelter.create' })}
          </button>
        </div>
      </form>
    </div>
  );
}
