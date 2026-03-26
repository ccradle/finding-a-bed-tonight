import { useState, useRef, type DragEvent } from 'react';
import { FormattedMessage } from 'react-intl';
import { api, ApiError } from '../services/api';

interface ImportResult {
  created: number;
  updated: number;
  skipped: number;
  errors: string[];
}

export function HsdsImportPage() {
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
        setError('Import failed. Please try again.');
      }
    } finally {
      setLoading(false);
    }
  };

  return (
    <div style={{ maxWidth: '680px', margin: '0 auto' }}>
      <h2 style={{ fontSize: '24px', fontWeight: 700, color: '#111827', marginBottom: '24px' }}>
        <FormattedMessage id="import.hsds" />
      </h2>

      {error && (
        <div
          style={{
            backgroundColor: '#fef2f2',
            color: '#991b1b',
            padding: '12px 16px',
            borderRadius: '8px',
            marginBottom: '20px',
            fontSize: '14px',
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
          border: dragOver ? '2px solid #1a56db' : '2px dashed #d1d5db',
          borderRadius: '12px',
          padding: '48px 24px',
          textAlign: 'center',
          cursor: 'pointer',
          backgroundColor: dragOver ? '#eff6ff' : '#f9fafb',
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
            <p style={{ fontSize: '16px', fontWeight: 600, color: '#111827', margin: '0 0 4px' }}>
              {file.name}
            </p>
            <p style={{ fontSize: '14px', color: '#6b7280', margin: 0 }}>
              {(file.size / 1024).toFixed(1)} KB
            </p>
          </div>
        ) : (
          <div>
            <p style={{ fontSize: '16px', fontWeight: 500, color: '#374151', margin: '0 0 8px' }}>
              Drag and drop an HSDS file here, or click to browse
            </p>
            <p style={{ fontSize: '14px', color: '#6b7280', margin: 0 }}>
              Accepts .json or .zip files
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
          backgroundColor: !file || loading ? '#93c5fd' : '#1a56db',
          color: '#ffffff',
          border: 'none',
          borderRadius: '8px',
          fontSize: '16px',
          fontWeight: 600,
          cursor: !file || loading ? 'not-allowed' : 'pointer',
          minHeight: '44px',
          marginBottom: '24px',
        }}
      >
        {loading ? 'Importing...' : 'Upload & Import'}
      </button>

      {/* Results */}
      {result && (
        <div
          style={{
            backgroundColor: '#f0fdf4',
            border: '1px solid #bbf7d0',
            borderRadius: '12px',
            padding: '20px',
          }}
        >
          <h3 style={{ fontSize: '16px', fontWeight: 600, color: '#166534', marginBottom: '12px', marginTop: 0 }}>
            Import Complete
          </h3>
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: '16px', marginBottom: '12px' }}>
            <div>
              <p style={{ fontSize: '24px', fontWeight: 700, color: '#166534', margin: 0 }}>
                {result.created}
              </p>
              <p style={{ fontSize: '13px', color: '#6b7280', margin: 0 }}>Created</p>
            </div>
            <div>
              <p style={{ fontSize: '24px', fontWeight: 700, color: '#ca8a04', margin: 0 }}>
                {result.updated}
              </p>
              <p style={{ fontSize: '13px', color: '#6b7280', margin: 0 }}>Updated</p>
            </div>
            <div>
              <p style={{ fontSize: '24px', fontWeight: 700, color: '#6b7280', margin: 0 }}>
                {result.skipped}
              </p>
              <p style={{ fontSize: '13px', color: '#6b7280', margin: 0 }}>Skipped</p>
            </div>
          </div>
          {result.errors.length > 0 && (
            <div>
              <p style={{ fontSize: '14px', fontWeight: 600, color: '#991b1b', marginBottom: '8px' }}>
                Errors ({result.errors.length}):
              </p>
              <ul style={{ margin: 0, paddingLeft: '20px', fontSize: '13px', color: '#991b1b' }}>
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
