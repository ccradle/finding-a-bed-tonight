import { useState, useRef, type DragEvent } from 'react';
import { FormattedMessage, useIntl } from 'react-intl';
import { api, ApiError } from '../services/api';
import { text, weight } from '../theme/typography';
import { color } from '../theme/colors';

interface ImportResult {
  created: number;
  updated: number;
  skipped: number;
  errors: string[];
}

export function HsdsImportPage() {
  const intl = useIntl();
  const fileInputRef = useRef<HTMLInputElement>(null);
  const [file, setFile] = useState<File | null>(null);
  const [dragOver, setDragOver] = useState(false);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [result, setResult] = useState<ImportResult | null>(null);

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
      setResult(null);
      setError(null);
    }
  };

  const handleFileSelect = () => {
    const selected = fileInputRef.current?.files?.[0];
    if (selected) {
      setFile(selected);
      setResult(null);
      setError(null);
    }
  };

  const handleSubmit = async () => {
    if (!file) return;

    setLoading(true);
    setError(null);
    setResult(null);

    try {
      const formData = new FormData();
      formData.append('file', file);

      const importResult = await api.post<ImportResult>('/api/v1/import/hsds', formData, {
        isFormData: true,
      });
      setResult(importResult);
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

  return (
    <div style={{ maxWidth: '680px', margin: '0 auto' }}>
      <h2 style={{ fontSize: text['2xl'], fontWeight: weight.bold, color: color.text, marginBottom: '24px' }}>
        <FormattedMessage id="import.hsds" />
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

      {/* Drop zone */}
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
          accept=".json,.zip"
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
              <FormattedMessage id="import.hsds.dragDrop" />
            </p>
            <p style={{ fontSize: text.base, color: color.textMuted, margin: 0 }}>
              <FormattedMessage id="import.hsds.accepts" />
            </p>
          </div>
        )}
      </div>

      <button
        onClick={handleSubmit}
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
          marginBottom: '24px',
        }}
      >
        {loading ? intl.formatMessage({ id: 'import.hsds.uploading' }) : intl.formatMessage({ id: 'import.hsds.uploadBtn' })}
      </button>

      {/* Results */}
      {result && (
        <div
          style={{
            backgroundColor: color.successBg,
            border: `1px solid ${color.successBorder}`,
            borderRadius: '12px',
            padding: '20px',
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
      )}
    </div>
  );
}
