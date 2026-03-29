import { useState, useEffect, useCallback, useContext, lazy, Suspense } from 'react';
import { FormattedMessage } from 'react-intl';
import { api } from '../services/api';
import { AuthContext } from '../auth/AuthContext';
import { text, weight } from '../theme/typography';
import { color } from '../theme/colors';

// Lazy-load Recharts (~200KB) — only when admin opens Analytics tab.
// Outreach workers on phones never download it.
const LazyLineChart = lazy(() => import('recharts').then(m => ({ default: m.LineChart })));
const LazyLine = lazy(() => import('recharts').then(m => ({ default: m.Line })));
const LazyXAxis = lazy(() => import('recharts').then(m => ({ default: m.XAxis })));
const LazyYAxis = lazy(() => import('recharts').then(m => ({ default: m.YAxis })));
const LazyTooltip = lazy(() => import('recharts').then(m => ({ default: m.Tooltip })));
const LazyResponsiveContainer = lazy(() => import('recharts').then(m => ({ default: m.ResponsiveContainer })));

// --- Types ---

interface UtilizationData {
  avgUtilization: number;
  dataPoints: number;
  details: Array<{
    summaryDate: string;
    avgUtilization: number;
    shelterId: string;
    populationType: string;
  }>;
}

interface DemandData {
  totalSearches: number;
  zeroResultSearches: number;
  zeroResultRate: number;
  reservations: {
    total: number;
    CONFIRMED?: number;
    EXPIRED?: number;
    conversionRate: number;
    expiryRate: number;
  };
}

interface BatchJob {
  jobName: string;
  cron: string;
  enabled: boolean;
  lastStatus?: string;
  lastStartTime?: string;
  lastEndTime?: string;
}

interface JobExecution {
  executionId: number;
  status: string;
  startTime: string;
  endTime: string;
  exitCode: string;
  exitMessage: string;
  durationMs: number;
  steps: Array<{
    stepName: string;
    status: string;
    readCount: number;
    writeCount: number;
    skipCount: number;
    commitCount: number;
  }>;
}

interface GeographicShelter {
  id: string;
  name: string;
  address_city: string;
  latitude: number;
  longitude: number;
  total_beds: number;
  total_occupied: number;
  utilization: number;
}

// --- Styles ---

const sectionStyle: React.CSSProperties = {
  background: color.bg, borderRadius: 12, padding: 20, marginBottom: 16,
  border: `1px solid ${color.border}`, boxShadow: '0 1px 3px rgba(0,0,0,0.06)',
};

const sectionTitleStyle: React.CSSProperties = {
  fontSize: text.md, fontWeight: weight.bold, color: color.text, margin: '0 0 12px',
};

const metricCardStyle: React.CSSProperties = {
  display: 'flex', flexDirection: 'column', alignItems: 'center',
  padding: 16, borderRadius: 10, backgroundColor: color.bgSecondary,
  border: `1px solid ${color.border}`, minWidth: 120,
};

const metricValueStyle: React.CSSProperties = {
  fontSize: text['3xl'], fontWeight: weight.extrabold, color: color.text,
};

const metricLabelStyle: React.CSSProperties = {
  fontSize: text.xs, color: color.textTertiary, fontWeight: weight.semibold, marginTop: 4,
};

const tableStyle: React.CSSProperties = {
  width: '100%', borderCollapse: 'collapse', fontSize: text.base,
};

const thStyle: React.CSSProperties = {
  textAlign: 'left', padding: '10px 14px', fontWeight: weight.bold, color: color.text,
  borderBottom: `2px solid ${color.border}`, fontSize: text.xs, textTransform: 'uppercase',
  letterSpacing: '0.04em',
};

const tdFn = (index: number): React.CSSProperties => ({
  padding: '12px 14px', borderBottom: `1px solid ${color.borderLight}`,
  backgroundColor: index % 2 === 0 ? color.bg : color.bgSecondary, color: color.text,
});

const primaryBtnStyle: React.CSSProperties = {
  padding: '10px 18px', backgroundColor: color.primary, color: color.textInverse,
  border: 'none', borderRadius: 10, fontSize: text.base, fontWeight: weight.bold,
  cursor: 'pointer', minHeight: 44,
};

const badgeStyle = (color: string, bg: string): React.CSSProperties => ({
  padding: '4px 10px', borderRadius: 6, fontSize: text.xs, fontWeight: weight.semibold,
  backgroundColor: bg, color, border: `1px solid ${color}22`,
});

// WCAG 1.4.1 — status label so color is not the sole indicator
function ragColor(utilization: number): { color: string; bg: string; label: string } {
  if (utilization >= 1.05) return { color: color.error, bg: color.errorBg, label: 'Over' };
  if (utilization >= 0.65) return { color: color.success, bg: color.successBg, label: 'OK' };
  return { color: color.warning, bg: color.warningBg, label: 'Low' };
}

// --- Main Analytics Tab Component ---

export default function AnalyticsTab() {
  const { user } = useContext(AuthContext);
  const isPlatformAdmin = user?.roles?.includes('PLATFORM_ADMIN');

  const [section, setSection] = useState<'dashboard' | 'batchJobs'>('dashboard');

  return (
    <div>
      {/* Sub-navigation */}
      <div style={{ display: 'flex', gap: 8, marginBottom: 16 }}>
        <button
          data-testid="analytics-dashboard-btn"
          onClick={() => setSection('dashboard')}
          style={{
            ...primaryBtnStyle,
            backgroundColor: section === 'dashboard' ? color.primary : color.border,
            color: section === 'dashboard' ? color.textInverse : color.textTertiary,
          }}
        >
          <FormattedMessage id="analytics.dashboard" defaultMessage="Dashboard" />
        </button>
        <button
          data-testid="analytics-batch-jobs-btn"
          onClick={() => setSection('batchJobs')}
          style={{
            ...primaryBtnStyle,
            backgroundColor: section === 'batchJobs' ? color.primary : color.border,
            color: section === 'batchJobs' ? color.textInverse : color.textTertiary,
          }}
        >
          <FormattedMessage id="analytics.batchJobs" defaultMessage="Batch Jobs" />
        </button>
      </div>

      {section === 'dashboard' && <DashboardSection />}
      {section === 'batchJobs' && <BatchJobsSection isPlatformAdmin={isPlatformAdmin ?? false} />}
    </div>
  );
}

// --- Dashboard Section ---

function DashboardSection() {
  const [utilization, setUtilization] = useState<UtilizationData | null>(null);
  const [demand, setDemand] = useState<DemandData | null>(null);
  const [geographic, setGeographic] = useState<GeographicShelter[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [exportDate, setExportDate] = useState(new Date().toISOString().split('T')[0]);
  const [downloading, setDownloading] = useState<string | null>(null);

  const handleDownloadCsv = async (type: 'hic' | 'pit') => {
    setDownloading(type);
    try {
      const token = localStorage.getItem('fabt_access_token');
      const res = await fetch(`/api/v1/analytics/${type}?date=${exportDate}`, {
        headers: token ? { Authorization: `Bearer ${token}` } : {},
      });
      if (!res.ok) throw new Error(`Export failed: ${res.status}`);
      const blob = await res.blob();
      const disposition = res.headers.get('Content-Disposition');
      const filename = disposition?.match(/filename=(.+)/)?.[1] || `${type}-${exportDate}.csv`;
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = filename;
      document.body.appendChild(a);
      a.click();
      document.body.removeChild(a);
      URL.revokeObjectURL(url);
    } catch (err) {
      console.error(`Failed to download ${type.toUpperCase()} CSV:`, err);
    } finally {
      setDownloading(null);
    }
  };

  const loadData = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const today = new Date().toISOString().split('T')[0];
      const thirtyDaysAgo = new Date(Date.now() - 30 * 86400000).toISOString().split('T')[0];

      const [utilRes, demandRes, geoRes] = await Promise.all([
        api.get(`/api/v1/analytics/utilization?from=${thirtyDaysAgo}&to=${today}&granularity=daily`),
        api.get(`/api/v1/analytics/demand?from=${thirtyDaysAgo}&to=${today}`),
        api.get('/api/v1/analytics/geographic'),
      ]);

      setUtilization(utilRes as unknown as UtilizationData);
      setDemand(demandRes as unknown as DemandData);
      setGeographic(geoRes as unknown as GeographicShelter[]);
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : 'Failed to load analytics');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { loadData(); }, [loadData]);

  if (loading) return <div style={{ textAlign: 'center', padding: 40, color: color.textTertiary }}>Loading analytics...</div>;
  if (error) return <div style={{ padding: 16, color: color.error, backgroundColor: color.errorBg, borderRadius: 8 }}>{error}</div>;

  return (
    <>
      {/* Executive Summary */}
      <div style={sectionStyle} data-testid="analytics-executive-summary">
        <h3 style={sectionTitleStyle}>
          <FormattedMessage id="analytics.executiveSummary" defaultMessage="Executive Summary" />
        </h3>
        <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap' }}>
          <div style={metricCardStyle} data-testid="metric-utilization">
            <span style={{
              ...metricValueStyle,
              color: utilization ? ragColor(utilization.avgUtilization).color : color.text,
            }}>
              {utilization ? `${(utilization.avgUtilization * 100).toFixed(1)}%` : '—'}
            </span>
            <span style={metricLabelStyle}>
              <FormattedMessage id="analytics.utilization" defaultMessage="Utilization" />
            </span>
          </div>
          <div style={metricCardStyle} data-testid="metric-searches">
            <span style={metricValueStyle}>{demand?.totalSearches ?? '—'}</span>
            <span style={metricLabelStyle}>
              <FormattedMessage id="analytics.totalSearches" defaultMessage="Total Searches" />
            </span>
          </div>
          <div style={metricCardStyle} data-testid="metric-zero-results">
            <span style={{ ...metricValueStyle, color: (demand?.zeroResultSearches ?? 0) > 0 ? color.error : color.success }}>
              {demand?.zeroResultSearches ?? '—'}
            </span>
            <span style={metricLabelStyle}>
              <FormattedMessage id="analytics.zeroResultSearches" defaultMessage="Zero-Result Searches" />
            </span>
          </div>
          <div style={metricCardStyle} data-testid="metric-conversion-rate">
            <span style={metricValueStyle}>
              {demand?.reservations ? `${(demand.reservations.conversionRate * 100).toFixed(1)}%` : '—'}
            </span>
            <span style={metricLabelStyle}>
              <FormattedMessage id="analytics.conversionRate" defaultMessage="Reservation Conversion" />
            </span>
          </div>
          <div style={metricCardStyle} data-testid="metric-expiry-rate">
            <span style={{ ...metricValueStyle, color: (demand?.reservations?.expiryRate ?? 0) > 0.3 ? color.error : color.text }}>
              {demand?.reservations ? `${(demand.reservations.expiryRate * 100).toFixed(1)}%` : '—'}
            </span>
            <span style={metricLabelStyle}>
              <FormattedMessage id="analytics.expiryRate" defaultMessage="Expiry Rate" />
            </span>
          </div>
        </div>
      </div>

      {/* Utilization Trends */}
      <div style={sectionStyle} data-testid="analytics-utilization-trends">
        <h3 style={sectionTitleStyle}>
          <FormattedMessage id="analytics.utilizationTrends" defaultMessage="Utilization Trends" />
        </h3>
        {utilization && utilization.details.length > 0 ? (
          <Suspense fallback={<div style={{ height: 250, display: 'flex', alignItems: 'center', justifyContent: 'center', color: color.textTertiary }}>Loading chart...</div>}>
            <UtilizationChart data={utilization.details} />
          </Suspense>
        ) : (
          <div style={{ padding: 20, textAlign: 'center', color: color.textTertiary }}>
            <FormattedMessage id="analytics.noData" defaultMessage="No utilization data available for this period" />
          </div>
        )}
      </div>

      {/* Shelter Performance (Geographic Fallback Table) */}
      <div style={sectionStyle} data-testid="analytics-shelter-performance">
        <h3 style={sectionTitleStyle}>
          <FormattedMessage id="analytics.shelterPerformance" defaultMessage="Shelter Performance" />
        </h3>
        {geographic.length > 0 ? (
          <div style={{ overflowX: 'auto' }}>
            <table style={tableStyle}>
              <thead>
                <tr>
                  <th style={thStyle}><FormattedMessage id="analytics.shelter" defaultMessage="Shelter" /></th>
                  <th style={thStyle}><FormattedMessage id="analytics.city" defaultMessage="City" /></th>
                  <th style={thStyle}><FormattedMessage id="analytics.totalBeds" defaultMessage="Total Beds" /></th>
                  <th style={thStyle}><FormattedMessage id="analytics.occupied" defaultMessage="Occupied" /></th>
                  <th style={thStyle}><FormattedMessage id="analytics.utilizationCol" defaultMessage="Utilization" /></th>
                </tr>
              </thead>
              <tbody>
                {geographic.map((s, i) => {
                  const rag = ragColor(s.utilization);
                  return (
                    <tr key={s.id} data-testid={`shelter-perf-${s.id}`}>
                      <td style={tdFn(i)}>{s.name}</td>
                      <td style={tdFn(i)}>{s.address_city}</td>
                      <td style={tdFn(i)}>{s.total_beds}</td>
                      <td style={tdFn(i)}>{s.total_occupied}</td>
                      <td style={tdFn(i)}>
                        <span style={badgeStyle(rag.color, rag.bg)}>
                          {(s.utilization * 100).toFixed(1)}% {rag.label}
                        </span>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        ) : (
          <div style={{ padding: 20, textAlign: 'center', color: color.textTertiary }}>
            <FormattedMessage id="analytics.noShelters" defaultMessage="No shelter data available" />
          </div>
        )}
      </div>

      {/* HIC/PIT Export */}
      <div style={sectionStyle} data-testid="analytics-export">
        <h3 style={sectionTitleStyle}>
          <FormattedMessage id="analytics.hicPitExport" defaultMessage="HIC/PIT Export" />
        </h3>
        <div style={{ display: 'flex', gap: 12, alignItems: 'center', flexWrap: 'wrap' }}>
          <input
            type="date"
            value={exportDate}
            onChange={e => setExportDate(e.target.value)}
            style={{ padding: '10px 14px', borderRadius: 10, border: `2px solid ${color.border}`, fontSize: text.base, minHeight: 44 }}
            data-testid="export-date-picker"
            aria-label="Export date for HIC/PIT report"
          />
          <button
            onClick={() => handleDownloadCsv('hic')}
            disabled={downloading === 'hic'}
            style={primaryBtnStyle}
            data-testid="download-hic-btn"
          >
            {downloading === 'hic' ? '...' : <FormattedMessage id="analytics.downloadHic" defaultMessage="Download HIC CSV" />}
          </button>
          <button
            onClick={() => handleDownloadCsv('pit')}
            disabled={downloading === 'pit'}
            style={{ ...primaryBtnStyle, backgroundColor: color.success }}
            data-testid="download-pit-btn"
          >
            {downloading === 'pit' ? '...' : <FormattedMessage id="analytics.downloadPit" defaultMessage="Download PIT CSV" />}
          </button>
        </div>
      </div>
    </>
  );
}

// --- Utilization Chart (lazy-loaded Recharts) ---

function UtilizationChart({ data }: { data: Array<{ summaryDate: string; avgUtilization: number }> }) {
  const [showTable, setShowTable] = useState(false);
  // Respect prefers-reduced-motion (WCAG 2.3.3)
  const prefersReducedMotion = typeof window !== 'undefined'
    && window.matchMedia('(prefers-reduced-motion: reduce)').matches;

  // Aggregate by date for the line chart
  const byDate = new Map<string, number[]>();
  for (const d of data) {
    const existing = byDate.get(d.summaryDate) || [];
    existing.push(d.avgUtilization);
    byDate.set(d.summaryDate, existing);
  }
  const chartData = Array.from(byDate.entries()).map(([date, values]) => ({
    date,
    utilization: Math.round((values.reduce((a, b) => a + b, 0) / values.length) * 100 * 10) / 10,
  })).sort((a, b) => a.date.localeCompare(b.date));

  return (
    <div>
      <button
        onClick={() => setShowTable(!showTable)}
        style={{ fontSize: text.xs, color: color.primaryText, background: 'none', border: 'none', cursor: 'pointer', marginBottom: 8, textDecoration: 'underline' }}
        aria-label={showTable ? 'Show as chart' : 'Show as table'}
      >
        {showTable ? 'Show as chart' : 'Show as table'}
      </button>
      {showTable ? (
        <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: text.xs }}>
          <thead>
            <tr>
              <th style={{ textAlign: 'left', padding: '6px 10px', borderBottom: `2px solid ${color.border}` }}>Date</th>
              <th style={{ textAlign: 'right', padding: '6px 10px', borderBottom: `2px solid ${color.border}` }}>Utilization</th>
            </tr>
          </thead>
          <tbody>
            {chartData.map(d => (
              <tr key={d.date}>
                <td style={{ padding: '4px 10px', borderBottom: `1px solid ${color.borderLight}` }}>{d.date}</td>
                <td style={{ padding: '4px 10px', borderBottom: `1px solid ${color.borderLight}`, textAlign: 'right' }}>{d.utilization}%</td>
              </tr>
            ))}
          </tbody>
        </table>
      ) : (
        <LazyResponsiveContainer width="100%" height={250}>
          <LazyLineChart data={chartData}>
            <LazyXAxis dataKey="date" tick={{ fontSize: text['2xs'] }} />
            <LazyYAxis tick={{ fontSize: text['2xs'] }} domain={[0, 120]} unit="%" />
            <LazyTooltip />
            <LazyLine type="monotone" dataKey="utilization" stroke={color.primary} strokeWidth={2} dot={false}
              isAnimationActive={!prefersReducedMotion} />
          </LazyLineChart>
        </LazyResponsiveContainer>
      )}
    </div>
  );
}

// --- Batch Jobs Section ---

function BatchJobsSection({ isPlatformAdmin }: { isPlatformAdmin: boolean }) {
  const [jobs, setJobs] = useState<BatchJob[]>([]);
  const [selectedJob, setSelectedJob] = useState<string | null>(null);
  const [executions, setExecutions] = useState<JobExecution[]>([]);
  const [expandedExec, setExpandedExec] = useState<number | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [editCron, setEditCron] = useState<{ jobName: string; cron: string } | null>(null);

  const loadJobs = useCallback(async () => {
    setLoading(true);
    try {
      const res = await api.get<BatchJob[]>('/api/v1/batch/jobs');
      setJobs(res || []);
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : 'Failed to load batch jobs');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { loadJobs(); }, [loadJobs]);

  const loadExecutions = useCallback(async (jobName: string) => {
    try {
      const res = await api.get<JobExecution[]>(`/api/v1/batch/jobs/${jobName}/executions`);
      setExecutions(res || []);
      setSelectedJob(jobName);
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : 'Failed to load executions');
    }
  }, []);

  const handleRunNow = async (jobName: string) => {
    if (!confirm(`Run ${jobName} now?`)) return;
    try {
      await api.post(`/api/v1/batch/jobs/${jobName}/run`, {});
      loadJobs();
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : 'Failed to trigger job');
    }
  };

  const handleRestart = async (jobName: string, executionId: number) => {
    if (!confirm(`Restart execution ${executionId}?`)) return;
    try {
      await api.post(`/api/v1/batch/jobs/${jobName}/restart/${executionId}`, {});
      if (selectedJob) loadExecutions(selectedJob);
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : 'Failed to restart job');
    }
  };

  const handleToggleEnabled = async (jobName: string, enabled: boolean) => {
    try {
      await api.put(`/api/v1/batch/jobs/${jobName}/enable`, { enabled: !enabled });
      loadJobs();
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : 'Failed to toggle job');
    }
  };

  const handleSaveCron = async () => {
    if (!editCron) return;
    try {
      await api.put(`/api/v1/batch/jobs/${editCron.jobName}/schedule`, { cron: editCron.cron });
      setEditCron(null);
      loadJobs();
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : 'Invalid cron expression');
    }
  };

  if (loading) return <div style={{ textAlign: 'center', padding: 40, color: color.textTertiary }}>Loading batch jobs...</div>;
  if (error) return <div style={{ padding: 16, color: color.error, backgroundColor: color.errorBg, borderRadius: 8 }}>{error}</div>;

  return (
    <>
      {/* Job List */}
      <div style={sectionStyle} data-testid="batch-jobs-list">
        <h3 style={sectionTitleStyle}>
          <FormattedMessage id="analytics.batchJobsList" defaultMessage="Batch Jobs" />
        </h3>
        <table style={tableStyle}>
          <thead>
            <tr>
              <th style={thStyle}><FormattedMessage id="analytics.jobName" defaultMessage="Job" /></th>
              <th style={thStyle}><FormattedMessage id="analytics.schedule" defaultMessage="Schedule" /></th>
              <th style={thStyle}><FormattedMessage id="analytics.enabled" defaultMessage="Enabled" /></th>
              <th style={thStyle}><FormattedMessage id="analytics.lastStatus" defaultMessage="Last Status" /></th>
              <th style={thStyle}><FormattedMessage id="analytics.actions" defaultMessage="Actions" /></th>
            </tr>
          </thead>
          <tbody>
            {jobs.map((job, i) => (
              <tr key={job.jobName} data-testid={`batch-job-${job.jobName}`}>
                <td style={tdFn(i)}>
                  <button
                    onClick={() => loadExecutions(job.jobName)}
                    style={{ background: 'none', border: 'none', color: color.primaryText, cursor: 'pointer', fontWeight: weight.semibold, fontSize: text.base }}
                    data-testid={`batch-job-expand-${job.jobName}`}
                  >
                    {job.jobName}
                  </button>
                </td>
                <td style={tdFn(i)}>
                  <code style={{ fontSize: text.xs, backgroundColor: color.borderLight, padding: '2px 6px', borderRadius: 4 }}>
                    {job.cron}
                  </code>
                  {isPlatformAdmin && (
                    <button
                      onClick={() => setEditCron({ jobName: job.jobName, cron: job.cron })}
                      style={{ marginLeft: 8, background: 'none', border: 'none', color: color.textTertiary, cursor: 'pointer', fontSize: text.xs, minWidth: 44, minHeight: 44 }}
                      data-testid={`batch-job-edit-cron-${job.jobName}`}
                      aria-label={`Edit schedule for ${job.jobName}`}
                    >
                      ✎
                    </button>
                  )}
                </td>
                <td style={tdFn(i)}>
                  {isPlatformAdmin ? (
                    <button
                      onClick={() => handleToggleEnabled(job.jobName, job.enabled)}
                      style={{
                        ...badgeStyle(
                          job.enabled ? color.success : color.error,
                          job.enabled ? color.successBg : color.errorBg
                        ),
                        cursor: 'pointer', border: 'none',
                      }}
                      data-testid={`batch-job-toggle-${job.jobName}`}
                    >
                      {job.enabled ? 'Enabled' : 'Disabled'}
                    </button>
                  ) : (
                    <span style={badgeStyle(
                      job.enabled ? color.success : color.error,
                      job.enabled ? color.successBg : color.errorBg
                    )}>
                      {job.enabled ? 'Enabled' : 'Disabled'}
                    </span>
                  )}
                </td>
                <td style={tdFn(i)}>
                  {job.lastStatus ? (
                    <span style={badgeStyle(
                      job.lastStatus === 'COMPLETED' ? color.success : color.error,
                      job.lastStatus === 'COMPLETED' ? color.successBg : color.errorBg
                    )}>
                      {job.lastStatus}
                    </span>
                  ) : '—'}
                </td>
                <td style={tdFn(i)}>
                  {isPlatformAdmin && (
                    <button
                      onClick={() => handleRunNow(job.jobName)}
                      style={{ ...primaryBtnStyle, padding: '6px 12px', fontSize: text.xs }}
                      data-testid={`batch-job-run-${job.jobName}`}
                    >
                      <FormattedMessage id="analytics.runNow" defaultMessage="Run Now" />
                    </button>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {/* Edit Cron Modal */}
      {editCron && (
        <div style={{
          position: 'fixed', top: 0, left: 0, right: 0, bottom: 0,
          backgroundColor: 'rgba(0,0,0,0.5)', display: 'flex', alignItems: 'center', justifyContent: 'center',
          zIndex: 1000,
        }} data-testid="edit-cron-modal">
          <div style={{ background: color.bg, borderRadius: 16, padding: 24, width: 400 }}>
            <h3 style={{ margin: '0 0 16px' }}>
              <FormattedMessage id="analytics.editSchedule" defaultMessage="Edit Schedule" /> — {editCron.jobName}
            </h3>
            <input
              type="text"
              value={editCron.cron}
              onChange={e => setEditCron({ ...editCron, cron: e.target.value })}
              style={{ width: '100%', padding: '12px 14px', borderRadius: 10, border: `2px solid ${color.border}`, fontSize: text.base, boxSizing: 'border-box' }}
              data-testid="edit-cron-input"
            />
            <div style={{ display: 'flex', gap: 8, marginTop: 16, justifyContent: 'flex-end' }}>
              <button onClick={() => setEditCron(null)} style={{ ...primaryBtnStyle, backgroundColor: color.border, color: color.textTertiary }}>
                <FormattedMessage id="common.cancel" defaultMessage="Cancel" />
              </button>
              <button onClick={handleSaveCron} style={primaryBtnStyle} data-testid="save-cron-btn">
                <FormattedMessage id="common.save" defaultMessage="Save" />
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Execution History */}
      {selectedJob && (
        <div style={sectionStyle} data-testid="batch-execution-history">
          <h3 style={sectionTitleStyle}>
            <FormattedMessage id="analytics.executionHistory" defaultMessage="Execution History" /> — {selectedJob}
          </h3>
          <table style={tableStyle}>
            <thead>
              <tr>
                <th style={thStyle}>ID</th>
                <th style={thStyle}><FormattedMessage id="analytics.status" defaultMessage="Status" /></th>
                <th style={thStyle}><FormattedMessage id="analytics.startTime" defaultMessage="Start" /></th>
                <th style={thStyle}><FormattedMessage id="analytics.duration" defaultMessage="Duration" /></th>
                <th style={thStyle}><FormattedMessage id="analytics.actions" defaultMessage="Actions" /></th>
              </tr>
            </thead>
            <tbody>
              {executions.map((exec, i) => (
                <>
                  <tr key={exec.executionId} data-testid={`execution-${exec.executionId}`}>
                    <td style={tdFn(i)}>
                      <button
                        onClick={() => setExpandedExec(expandedExec === exec.executionId ? null : exec.executionId)}
                        style={{ background: 'none', border: 'none', color: color.primaryText, cursor: 'pointer', fontWeight: weight.semibold }}
                        data-testid={`execution-expand-${exec.executionId}`}
                      >
                        #{exec.executionId}
                      </button>
                    </td>
                    <td style={tdFn(i)}>
                      <span style={badgeStyle(
                        exec.status === 'COMPLETED' ? color.success : color.error,
                        exec.status === 'COMPLETED' ? color.successBg : color.errorBg
                      )}>
                        {exec.status}
                      </span>
                    </td>
                    <td style={tdFn(i)}>{exec.startTime ? new Date(exec.startTime).toLocaleString() : '—'}</td>
                    <td style={tdFn(i)}>{exec.durationMs ? `${(exec.durationMs / 1000).toFixed(1)}s` : '—'}</td>
                    <td style={tdFn(i)}>
                      {isPlatformAdmin && exec.status === 'FAILED' && (
                        <button
                          onClick={() => handleRestart(selectedJob, exec.executionId)}
                          style={{ ...primaryBtnStyle, padding: '6px 12px', fontSize: text.xs, backgroundColor: color.errorMid }}
                          data-testid={`execution-restart-${exec.executionId}`}
                        >
                          <FormattedMessage id="analytics.restart" defaultMessage="Restart" />
                        </button>
                      )}
                    </td>
                  </tr>
                  {/* Step Detail */}
                  {expandedExec === exec.executionId && exec.steps.length > 0 && (
                    <tr key={`${exec.executionId}-steps`}>
                      <td colSpan={5} style={{ padding: '8px 14px 16px', backgroundColor: color.bgSecondary }}>
                        <table style={{ ...tableStyle, fontSize: text.xs }}>
                          <thead>
                            <tr>
                              <th style={{ ...thStyle, fontSize: text['2xs'] }}>Step</th>
                              <th style={{ ...thStyle, fontSize: text['2xs'] }}>Status</th>
                              <th style={{ ...thStyle, fontSize: text['2xs'] }}>Read</th>
                              <th style={{ ...thStyle, fontSize: text['2xs'] }}>Write</th>
                              <th style={{ ...thStyle, fontSize: text['2xs'] }}>Skip</th>
                              <th style={{ ...thStyle, fontSize: text['2xs'] }}>Commits</th>
                            </tr>
                          </thead>
                          <tbody>
                            {exec.steps.map((step, si) => (
                              <tr key={step.stepName} data-testid={`step-${step.stepName}`}>
                                <td style={tdFn(si)}>{step.stepName}</td>
                                <td style={tdFn(si)}>{step.status}</td>
                                <td style={tdFn(si)}>{step.readCount}</td>
                                <td style={tdFn(si)}>{step.writeCount}</td>
                                <td style={tdFn(si)}>{step.skipCount}</td>
                                <td style={tdFn(si)}>{step.commitCount}</td>
                              </tr>
                            ))}
                          </tbody>
                        </table>
                        {exec.exitMessage && (
                          <div style={{ marginTop: 8, padding: 8, backgroundColor: color.errorBg, borderRadius: 6, fontSize: text.xs, color: color.error }}>
                            {exec.exitMessage}
                          </div>
                        )}
                      </td>
                    </tr>
                  )}
                </>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </>
  );
}
