import { useState, useRef, type DragEvent } from 'react';
import { FormattedMessage, useIntl } from 'react-intl';
import { api, ApiError } from '../services/api';
import { text, weight } from '../theme/typography';
import { color } from '../theme/colors';

interface ColumnMapping {
  sourceColumn: string;
  targetField: string;
  sampleValues: string[];
}

interface PreviewResponse {
  columns: ColumnMapping[];
  totalRows: number;
}

interface ImportResult {
  created: number;
  updated: number;
  skipped: number;
  errors: string[];
}

export function TwoOneOneImportPage() {
  const intl = useIntl();
  const fileInputRef = useRef<HTMLInputElement>(null);
  const [file, setFile] = useState<File | null>(null);
  const [dragOver, setDragOver] = useState(false);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [preview, setPreview] = useState<PreviewResponse | null>(null);
  const [result, setResult] = useState<ImportResult | null>(null);
  const [step, setStep] = useState<'upload' | 'preview' | 'done'>('upload');

  const handleDragOver = (e: DragEvent) => {
    e.preventDefault();
    setDragOver(true);
  };

  const handleDragLeave = (e: DragEvent) => {
    e.preventDefault();
    setDragOver(false);
  };

  const handleDrop = (e: DragEvent) => {
    e.preventDefault();
    setDragOver(false);
    const droppedFile = e.dataTransfer.files[0];
    if (droppedFile) {
      setFile(droppedFile);
      setPreview(null);
      setResult(null);
      setError(null);
      setStep('upload');
    }
  };

  const handleFileSelect = () => {
    const selected = fileInputRef.current?.files?.[0];
    if (selected) {
      setFile(selected);
      setPreview(null);
      setResult(null);
      setError(null);
      setStep('upload');
    }
  };

  const handlePreview = async () => {
    if (!file) return;

    setLoading(true);
    setError(null);

    try {
      const formData = new FormData();
      formData.append('file', file);

      const previewData = await api.post<PreviewResponse>('/api/v1/import/211/preview', formData, {
        isFormData: true,
      });
      setPreview(previewData);
      setStep('preview');
    } catch (err) {
      if (err instanceof ApiError) {
        setError(err.message);
      } else {
        setError(intl.formatMessage({ id: 'import.previewError' }));
      }
    } finally {
      setLoading(false);
    }
  };

  const handleImport = async () => {
    if (!file) return;

    setLoading(true);
    setError(null);

    try {
      const formData = new FormData();
      formData.append('file', file);

      const importResult = await api.post<ImportResult>('/api/v1/import/211', formData, {
        isFormData: true,
      });
      setResult(importResult);
      setStep('done');
    } catch (err) {
      if (err instanceof ApiError) {
        setError(err.message);
      } else {
        setError(intl.formatMessage({ id: 'import.importError' }));
      }
    } finally {
      setLoading(false);
    }
  };

  const handleReset = () => {
    setFile(null);
    setPreview(null);
    setResult(null);
    setError(null);
    setStep('upload');
  };

  return (
    <div style={{ maxWidth: '680px', margin: '0 auto' }}>
      <h2 style={{ fontSize: text['2xl'], fontWeight: weight.bold, color: color.text, marginBottom: '24px' }}>
        <FormattedMessage id="import.211" />
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

      {/* Step 1: Upload */}
      {step === 'upload' && (
        <>
          <div
            onDragOver={handleDragOver}
            onDragLeave={handleDragLeave}
            onDrop={handleDrop}
            onClick={() => fileInputRef.current?.click()}
            style={{
              border: dragOver ? `2px solid ${color.primaryText}` : `2px dashed ${color.borderMedium}`,
              borderRadius: '12px',
              padding: '48px 24px',
              textAlign: 'center',
              cursor: 'pointer',
              backgroundColor: dragOver ? color.bgHighlight : color.bgSecondary,
              transition: 'all 0.2s',
              marginBottom: '24px',
              minHeight: '44px',
            }}
          >
            <input
              ref={fileInputRef}
              type="file"
              accept=".csv"
              onChange={handleFileSelect}
              style={{ display: 'none' }}
            />
            {file ? (
              <div>
                <p style={{ fontSize: text.md, fontWeight: weight.semibold, color: color.text, margin: '0 0 4px' }}>
                  {file.name}
                </p>
                <p style={{ fontSize: text.base, color: color.textMuted, margin: 0 }}>
                  {(file.size / 1024).toFixed(1)} KB
                </p>
              </div>
            ) : (
              <div>
                <p style={{ fontSize: text.md, fontWeight: weight.medium, color: color.textSecondary, margin: '0 0 8px' }}>
                  <FormattedMessage id="import.211.dragDrop" />
                </p>
                <p style={{ fontSize: text.base, color: color.textMuted, margin: 0 }}>
                  <FormattedMessage id="import.211.accepts" />
                </p>
              </div>
            )}
          </div>

          <button
            onClick={handlePreview}
            disabled={!file || loading}
            style={{
              width: '100%',
              padding: '14px',
              backgroundColor: !file || loading ? color.primaryDisabled : color.primary,
              color: color.textInverse,
              border: 'none',
              borderRadius: '8px',
              fontSize: text.md,
              fontWeight: weight.semibold,
              cursor: !file || loading ? 'not-allowed' : 'pointer',
              minHeight: '44px',
            }}
          >
            {loading ? intl.formatMessage({ id: 'import.211.analyzing' }) : intl.formatMessage({ id: 'import.211.previewBtn' })}
          </button>
        </>
      )}

      {/* Step 2: Preview */}
      {step === 'preview' && preview && (
        <>
          <div
            style={{
              backgroundColor: color.bgSecondary,
              border: `1px solid ${color.border}`,
              borderRadius: '12px',
              padding: '20px',
              marginBottom: '24px',
            }}
          >
            <p style={{ fontSize: text.base, color: color.textMuted, marginTop: 0 }}>
              <FormattedMessage id="import.rowsDetected" values={{ count: preview.totalRows }} />
            </p>
            <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: text.base }}>
              <thead>
                <tr>
                  <th style={{ textAlign: 'left', padding: '10px 8px', borderBottom: `2px solid ${color.border}`, color: color.textSecondary }}>
                    <FormattedMessage id="import.sourceColumn" />
                  </th>
                  <th style={{ textAlign: 'left', padding: '10px 8px', borderBottom: `2px solid ${color.border}`, color: color.textSecondary }}>
                    <FormattedMessage id="import.mapsTo" />
                  </th>
                  <th style={{ textAlign: 'left', padding: '10px 8px', borderBottom: `2px solid ${color.border}`, color: color.textSecondary }}>
                    <FormattedMessage id="import.sampleValues" />
                  </th>
                </tr>
              </thead>
              <tbody>
                {preview.columns.map((col, i) => (
                  <tr key={i}>
                    <td style={{ padding: '8px', borderBottom: `1px solid ${color.bgTertiary}` }}>
                      {col.sourceColumn}
                    </td>
                    <td style={{ padding: '8px', borderBottom: `1px solid ${color.bgTertiary}`, fontWeight: weight.medium }}>
                      {col.targetField}
                    </td>
                    <td style={{ padding: '8px', borderBottom: `1px solid ${color.bgTertiary}`, color: color.textMuted, fontSize: text.sm }}>
                      {col.sampleValues.slice(0, 3).join(', ')}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          <div style={{ display: 'flex', gap: '12px' }}>
            <button
              onClick={handleReset}
              style={{
                flex: 1,
                padding: '14px',
                backgroundColor: color.bg,
                color: color.textSecondary,
                border: `1px solid ${color.borderMedium}`,
                borderRadius: '8px',
                fontSize: text.md,
                fontWeight: weight.medium,
                cursor: 'pointer',
                minHeight: '44px',
              }}
            >
              <FormattedMessage id="import.cancel" />
            </button>
            <button
              onClick={handleImport}
              disabled={loading}
              style={{
                flex: 2,
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
              {loading ? intl.formatMessage({ id: 'import.211.importing' }) : intl.formatMessage({ id: 'import.211.confirmBtn' })}
            </button>
          </div>
        </>
      )}

      {/* Step 3: Results */}
      {step === 'done' && result && (
        <>
          <div
            style={{
              backgroundColor: color.successBg,
              border: `1px solid ${color.successBorder}`,
              borderRadius: '12px',
              padding: '20px',
              marginBottom: '24px',
            }}
          >
            <h3 style={{ fontSize: text.md, fontWeight: weight.semibold, color: color.success, marginBottom: '12px', marginTop: 0 }}>
              <FormattedMessage id="import.complete" />
            </h3>
            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: '16px', marginBottom: '12px' }}>
              <div>
                <p style={{ fontSize: text['2xl'], fontWeight: weight.bold, color: color.success, margin: 0 }}>
                  {result.created}
                </p>
                <p style={{ fontSize: text.sm, color: color.textMuted, margin: 0 }}><FormattedMessage id="import.created" /></p>
              </div>
              <div>
                <p style={{ fontSize: text['2xl'], fontWeight: weight.bold, color: color.warningMid, margin: 0 }}>
                  {result.updated}
                </p>
                <p style={{ fontSize: text.sm, color: color.textMuted, margin: 0 }}><FormattedMessage id="import.updated" /></p>
              </div>
              <div>
                <p style={{ fontSize: text['2xl'], fontWeight: weight.bold, color: color.textMuted, margin: 0 }}>
                  {result.skipped}
                </p>
                <p style={{ fontSize: text.sm, color: color.textMuted, margin: 0 }}><FormattedMessage id="import.skipped" /></p>
              </div>
            </div>
            {result.errors.length > 0 && (
              <div>
                <p style={{ fontSize: text.base, fontWeight: weight.semibold, color: color.error, marginBottom: '8px' }}>
                  <FormattedMessage id="import.errors" values={{ count: result.errors.length }} />
                </p>
                <ul style={{ margin: 0, paddingLeft: '20px', fontSize: text.sm, color: color.error }}>
                  {result.errors.map((err, i) => (
                    <li key={i}>{err}</li>
                  ))}
                </ul>
              </div>
            )}
          </div>

          <button
            onClick={handleReset}
            style={{
              width: '100%',
              padding: '14px',
              backgroundColor: color.primary,
              color: color.textInverse,
              border: 'none',
              borderRadius: '8px',
              fontSize: text.md,
              fontWeight: weight.semibold,
              cursor: 'pointer',
              minHeight: '44px',
            }}
          >
            <FormattedMessage id="import.importAnother" />
          </button>
        </>
      )}
    </div>
  );
}
