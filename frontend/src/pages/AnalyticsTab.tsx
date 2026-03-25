import { useState, useEffect, useCallback, useContext, lazy, Suspense } from 'react';
import { FormattedMessage, useIntl } from 'react-intl';
import { api } from '../services/api';
import { AuthContext } from '../auth/AuthContext';

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
  background: '#fff', borderRadius: 12, padding: 20, marginBottom: 16,
  border: '1px solid #e2e8f0', boxShadow: '0 1px 3px rgba(0,0,0,0.06)',
};

const sectionTitleStyle: React.CSSProperties = {
  fontSize: 16, fontWeight: 700, color: '#0f172a', margin: '0 0 12px',
};

const metricCardStyle: React.CSSProperties = {
  display: 'flex', flexDirection: 'column', alignItems: 'center',
  padding: 16, borderRadius: 10, backgroundColor: '#f8fafc',
  border: '1px solid #e2e8f0', minWidth: 120,
};

const metricValueStyle: React.CSSProperties = {
  fontSize: 28, fontWeight: 800, color: '#0f172a',
};

const metricLabelStyle: React.CSSProperties = {
  fontSize: 12, color: '#64748b', fontWeight: 600, marginTop: 4,
};

const tableStyle: React.CSSProperties = {
  width: '100%', borderCollapse: 'collapse', fontSize: 14,
};

const thStyle: React.CSSProperties = {
  textAlign: 'left', padding: '10px 14px', fontWeight: 700, color: '#0f172a',
  borderBottom: '2px solid #e2e8f0', fontSize: 12, textTransform: 'uppercase',
  letterSpacing: '0.04em',
};

const tdFn = (index: number): React.CSSProperties => ({
  padding: '12px 14px', borderBottom: '1px solid #f1f5f9',
  backgroundColor: index % 2 === 0 ? '#fff' : '#f9fafb', color: '#0f172a',
});

const primaryBtnStyle: React.CSSProperties = {
  padding: '10px 18px', backgroundColor: '#1a56db', color: '#fff',
  border: 'none', borderRadius: 10, fontSize: 14, fontWeight: 700,
  cursor: 'pointer', minHeight: 44,
};

const badgeStyle = (color: string, bg: string): React.CSSProperties => ({
  padding: '4px 10px', borderRadius: 6, fontSize: 12, fontWeight: 600,
  backgroundColor: bg, color, border: `1px solid ${color}22`,
});

function ragColor(utilization: number): { color: string; bg: string } {
  if (utilization >= 1.05) return { color: '#991b1b', bg: '#fef2f2' };
  if (utilization >= 0.65) return { color: '#166534', bg: '#f0fdf4' };
  return { color: '#92400e', bg: '#fffbeb' };
}

// --- Main Analytics Tab Component ---

export default function AnalyticsTab() {
  const intl = useIntl();
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
            backgroundColor: section === 'dashboard' ? '#1a56db' : '#e2e8f0',
            color: section === 'dashboard' ? '#fff' : '#64748b',
          }}
        >
          <FormattedMessage id="analytics.dashboard" defaultMessage="Dashboard" />
        </button>
        <button
          data-testid="analytics-batch-jobs-btn"
          onClick={() => setSection('batchJobs')}
          style={{
            ...primaryBtnStyle,
            backgroundColor: section === 'batchJobs' ? '#1a56db' : '#e2e8f0',
            color: section === 'batchJobs' ? '#fff' : '#64748b',
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
    } catch (e: any) {
      setError(e instanceof Error ? e.message : 'Failed to load analytics');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { loadData(); }, [loadData]);

  if (loading) return <div style={{ textAlign: 'center', padding: 40, color: '#64748b' }}>Loading analytics...</div>;
  if (error) return <div style={{ padding: 16, color: '#991b1b', backgroundColor: '#fef2f2', borderRadius: 8 }}>{error}</div>;

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
              color: utilization ? ragColor(utilization.avgUtilization).color : '#0f172a',
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
            <span style={{ ...metricValueStyle, color: (demand?.zeroResultSearches ?? 0) > 0 ? '#991b1b' : '#166534' }}>
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
            <span style={{ ...metricValueStyle, color: (demand?.reservations?.expiryRate ?? 0) > 0.3 ? '#991b1b' : '#0f172a' }}>
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
          <Suspense fallback={<div style={{ height: 250, display: 'flex', alignItems: 'center', justifyContent: 'center', color: '#64748b' }}>Loading chart...</div>}>
            <UtilizationChart data={utilization.details} />
          </Suspense>
        ) : (
          <div style={{ padding: 20, textAlign: 'center', color: '#64748b' }}>
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
                          {(s.utilization * 100).toFixed(1)}%
                        </span>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        ) : (
          <div style={{ padding: 20, textAlign: 'center', color: '#64748b' }}>
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
            style={{ padding: '10px 14px', borderRadius: 10, border: '2px solid #e2e8f0', fontSize: 14 }}
            data-testid="export-date-picker"
          />
          <a
            href={`/api/v1/analytics/hic?date=${exportDate}`}
            download
            style={{ ...primaryBtnStyle, textDecoration: 'none', display: 'inline-block' }}
            data-testid="download-hic-btn"
          >
            <FormattedMessage id="analytics.downloadHic" defaultMessage="Download HIC CSV" />
          </a>
          <a
            href={`/api/v1/analytics/pit?date=${exportDate}`}
            download
            style={{ ...primaryBtnStyle, textDecoration: 'none', display: 'inline-block', backgroundColor: '#059669' }}
            data-testid="download-pit-btn"
          >
            <FormattedMessage id="analytics.downloadPit" defaultMessage="Download PIT CSV" />
          </a>
        </div>
      </div>
    </>
  );
}

// --- Utilization Chart (lazy-loaded Recharts) ---

function UtilizationChart({ data }: { data: Array<{ summaryDate: string; avgUtilization: number }> }) {
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
    <LazyResponsiveContainer width="100%" height={250}>
      <LazyLineChart data={chartData}>
        <LazyXAxis dataKey="date" tick={{ fontSize: 11 }} />
        <LazyYAxis tick={{ fontSize: 11 }} domain={[0, 120]} unit="%" />
        <LazyTooltip />
        <LazyLine type="monotone" dataKey="utilization" stroke="#1a56db" strokeWidth={2} dot={false} />
      </LazyLineChart>
    </LazyResponsiveContainer>
  );
}

// --- Batch Jobs Section ---

function BatchJobsSection({ isPlatformAdmin }: { isPlatformAdmin: boolean }) {
  const intl = useIntl();
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
      setJobs(res);
    } catch (e: any) {
      setError(e instanceof Error ? e.message : 'Failed to load batch jobs');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { loadJobs(); }, [loadJobs]);

  const loadExecutions = useCallback(async (jobName: string) => {
    try {
      const res = await api.get<JobExecution[]>(`/api/v1/batch/jobs/${jobName}/executions`);
      setExecutions(res);
      setSelectedJob(jobName);
    } catch (e: any) {
      setError(e instanceof Error ? e.message : 'Failed to load executions');
    }
  }, []);

  const handleRunNow = async (jobName: string) => {
    if (!confirm(`Run ${jobName} now?`)) return;
    try {
      await api.post(`/api/v1/batch/jobs/${jobName}/run`, {});
      loadJobs();
    } catch (e: any) {
      setError(e instanceof Error ? e.message : 'Failed to trigger job');
    }
  };

  const handleRestart = async (jobName: string, executionId: number) => {
    if (!confirm(`Restart execution ${executionId}?`)) return;
    try {
      await api.post(`/api/v1/batch/jobs/${jobName}/restart/${executionId}`, {});
      if (selectedJob) loadExecutions(selectedJob);
    } catch (e: any) {
      setError(e instanceof Error ? e.message : 'Failed to restart job');
    }
  };

  const handleToggleEnabled = async (jobName: string, enabled: boolean) => {
    try {
      await api.put(`/api/v1/batch/jobs/${jobName}/enable`, { enabled: !enabled });
      loadJobs();
    } catch (e: any) {
      setError(e instanceof Error ? e.message : 'Failed to toggle job');
    }
  };

  const handleSaveCron = async () => {
    if (!editCron) return;
    try {
      await api.put(`/api/v1/batch/jobs/${editCron.jobName}/schedule`, { cron: editCron.cron });
      setEditCron(null);
      loadJobs();
    } catch (e: any) {
      setError(e instanceof Error ? e.message : 'Invalid cron expression');
    }
  };

  if (loading) return <div style={{ textAlign: 'center', padding: 40, color: '#64748b' }}>Loading batch jobs...</div>;
  if (error) return <div style={{ padding: 16, color: '#991b1b', backgroundColor: '#fef2f2', borderRadius: 8 }}>{error}</div>;

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
                    style={{ background: 'none', border: 'none', color: '#1a56db', cursor: 'pointer', fontWeight: 600, fontSize: 14 }}
                    data-testid={`batch-job-expand-${job.jobName}`}
                  >
                    {job.jobName}
                  </button>
                </td>
                <td style={tdFn(i)}>
                  <code style={{ fontSize: 12, backgroundColor: '#f1f5f9', padding: '2px 6px', borderRadius: 4 }}>
                    {job.cron}
                  </code>
                  {isPlatformAdmin && (
                    <button
                      onClick={() => setEditCron({ jobName: job.jobName, cron: job.cron })}
                      style={{ marginLeft: 8, background: 'none', border: 'none', color: '#64748b', cursor: 'pointer', fontSize: 12 }}
                      data-testid={`batch-job-edit-cron-${job.jobName}`}
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
                          job.enabled ? '#166534' : '#991b1b',
                          job.enabled ? '#f0fdf4' : '#fef2f2'
                        ),
                        cursor: 'pointer', border: 'none',
                      }}
                      data-testid={`batch-job-toggle-${job.jobName}`}
                    >
                      {job.enabled ? 'Enabled' : 'Disabled'}
                    </button>
                  ) : (
                    <span style={badgeStyle(
                      job.enabled ? '#166534' : '#991b1b',
                      job.enabled ? '#f0fdf4' : '#fef2f2'
                    )}>
                      {job.enabled ? 'Enabled' : 'Disabled'}
                    </span>
                  )}
                </td>
                <td style={tdFn(i)}>
                  {job.lastStatus ? (
                    <span style={badgeStyle(
                      job.lastStatus === 'COMPLETED' ? '#166534' : '#991b1b',
                      job.lastStatus === 'COMPLETED' ? '#f0fdf4' : '#fef2f2'
                    )}>
                      {job.lastStatus}
                    </span>
                  ) : '—'}
                </td>
                <td style={tdFn(i)}>
                  {isPlatformAdmin && (
                    <button
                      onClick={() => handleRunNow(job.jobName)}
                      style={{ ...primaryBtnStyle, padding: '6px 12px', fontSize: 12 }}
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
          <div style={{ background: '#fff', borderRadius: 16, padding: 24, width: 400 }}>
            <h3 style={{ margin: '0 0 16px' }}>
              <FormattedMessage id="analytics.editSchedule" defaultMessage="Edit Schedule" /> — {editCron.jobName}
            </h3>
            <input
              type="text"
              value={editCron.cron}
              onChange={e => setEditCron({ ...editCron, cron: e.target.value })}
              style={{ width: '100%', padding: '12px 14px', borderRadius: 10, border: '2px solid #e2e8f0', fontSize: 14, boxSizing: 'border-box' }}
              data-testid="edit-cron-input"
            />
            <div style={{ display: 'flex', gap: 8, marginTop: 16, justifyContent: 'flex-end' }}>
              <button onClick={() => setEditCron(null)} style={{ ...primaryBtnStyle, backgroundColor: '#e2e8f0', color: '#64748b' }}>
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
                        style={{ background: 'none', border: 'none', color: '#1a56db', cursor: 'pointer', fontWeight: 600 }}
                        data-testid={`execution-expand-${exec.executionId}`}
                      >
                        #{exec.executionId}
                      </button>
                    </td>
                    <td style={tdFn(i)}>
                      <span style={badgeStyle(
                        exec.status === 'COMPLETED' ? '#166534' : '#991b1b',
                        exec.status === 'COMPLETED' ? '#f0fdf4' : '#fef2f2'
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
                          style={{ ...primaryBtnStyle, padding: '6px 12px', fontSize: 12, backgroundColor: '#dc2626' }}
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
                      <td colSpan={5} style={{ padding: '8px 14px 16px', backgroundColor: '#f8fafc' }}>
                        <table style={{ ...tableStyle, fontSize: 12 }}>
                          <thead>
                            <tr>
                              <th style={{ ...thStyle, fontSize: 11 }}>Step</th>
                              <th style={{ ...thStyle, fontSize: 11 }}>Status</th>
                              <th style={{ ...thStyle, fontSize: 11 }}>Read</th>
                              <th style={{ ...thStyle, fontSize: 11 }}>Write</th>
                              <th style={{ ...thStyle, fontSize: 11 }}>Skip</th>
                              <th style={{ ...thStyle, fontSize: 11 }}>Commits</th>
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
                          <div style={{ marginTop: 8, padding: 8, backgroundColor: '#fef2f2', borderRadius: 6, fontSize: 12, color: '#991b1b' }}>
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
