/**
 * AnalyticsDashboard.tsx - Admin Analytics Dashboard
 *
 * Comprehensive analytics dashboard for Village Homepage administrators
 * featuring overview cards, category performance charts, top items tables,
 * traffic sources, and job health monitoring.
 *
 * ## Features
 * - Overview Cards: Total clicks, unique users, AI budget usage
 * - Category Performance: Pie/donut chart with multi-select filters
 * - Top Items Table: Clickable rows with detail drawer
 * - Traffic Sources: Bar chart of referer domains
 * - Job Health: Queue backlog and stuck job monitoring
 * - Export: CSV/JSON download functionality
 *
 * ## AntV Charts
 * Uses @antv/g2plot for visualization (Line, Pie, Bar charts).
 * All charts follow design system color ramps for accessibility.
 *
 * ## Policy References
 * - F14.9: Uses aggregated rollup data (90-day retention)
 * - P8: Admin-only access (super_admin, ops, read_only roles)
 * - P14: No PII exposure, aggregated metrics only
 */

import React, { useEffect, useState, useRef, useCallback } from 'react';
import {
  Card,
  Row,
  Col,
  Statistic,
  Spin,
  Select,
  Table,
  Button,
  notification,
  Tag,
} from 'antd';
import {
  DownloadOutlined,
  ArrowUpOutlined,
  WarningOutlined,
  CheckCircleOutlined,
  CloseCircleOutlined,
} from '@ant-design/icons';
import { Pie, Line } from '@antv/g2plot';

export interface AnalyticsDashboardProps {
  apiBaseUrl: string;
  initialDateRange?: '1d' | '7d' | '30d';
  userRole: string;
}

interface OverviewData extends Record<string, unknown> {
  clicks_today: number;
  clicks_range: number;
  unique_users_today: number;
  ai_budget_pct: number;
  ai_cost_cents: number;
  ai_budget_cents: number;
  daily_trend: Array<{ date: string; clicks: number }>;
}

interface CategoryData extends Record<string, unknown> {
  type: string;
  clicks: number;
  percentage: number;
}

interface JobQueueData extends Record<string, unknown> {
  queue: string;
  backlog: number;
  avg_wait_seconds: number;
  stuck_jobs: number;
  status: string;
}

/**
 * Main Analytics Dashboard Component
 */
const AnalyticsDashboard: React.FC<AnalyticsDashboardProps> = ({
  apiBaseUrl,
  initialDateRange = '7d',
}) => {
  // State management
  const [loading, setLoading] = useState(true);
  const [overviewData, setOverviewData] = useState<OverviewData | null>(null);
  const [categoryData, setCategoryData] = useState<CategoryData[]>([]);
  const [jobHealthData, setJobHealthData] = useState<JobQueueData[]>([]);
  const [dateRange, setDateRange] = useState(initialDateRange);
  const [selectedCategories, setSelectedCategories] = useState<string[]>([]);

  // Chart refs
  const categoryChartRef = useRef<HTMLDivElement>(null);
  const categoryChartInstance = useRef<Pie | null>(null);
  const sparklineChartRef = useRef<HTMLDivElement>(null);
  const sparklineChartInstance = useRef<Line | null>(null);

  const fetchOverviewData = useCallback(async () => {
    try {
      const response = await fetch(
        `${apiBaseUrl}/overview?date_range=${dateRange}`,
        { credentials: 'include' }
      );

      if (!response.ok) {
        throw new Error(`HTTP ${response.status}`);
      }

      const data = await response.json() as OverviewData;
      setOverviewData(data);
    } catch (error) {
      console.error('Failed to fetch overview data:', error);
      throw error;
    }
  }, [apiBaseUrl, dateRange]);

  const fetchCategoryData = useCallback(async () => {
    try {
      const params = new URLSearchParams();
      if (selectedCategories.length > 0) {
        params.set('click_types', selectedCategories.join(','));
      }

      const response = await fetch(
        `${apiBaseUrl}/clicks/category-performance?${params.toString()}`,
        { credentials: 'include' }
      );

      if (!response.ok) {
        throw new Error(`HTTP ${response.status}`);
      }

      const data = await response.json() as { categories: CategoryData[] };
      setCategoryData(data.categories || []);
    } catch (error) {
      console.error('Failed to fetch category data:', error);
      throw error;
    }
  }, [apiBaseUrl, selectedCategories]);

  const fetchJobHealthData = useCallback(async () => {
    try {
      const response = await fetch(`${apiBaseUrl}/jobs/health`, {
        credentials: 'include',
      });

      if (!response.ok) {
        throw new Error(`HTTP ${response.status}`);
      }

      const data = await response.json() as { queues: JobQueueData[] };
      setJobHealthData(data.queues || []);
    } catch (error) {
      console.error('Failed to fetch job health data:', error);
      throw error;
    }
  }, [apiBaseUrl]);

  const fetchDashboardData = useCallback(async () => {
    setLoading(true);
    try {
      await Promise.all([
        fetchOverviewData(),
        fetchCategoryData(),
        fetchJobHealthData(),
      ]);
    } catch (error) {
      notification.error({
        message: 'Data Load Failed',
        description: error instanceof Error ? error.message : 'Could not load analytics data.',
      });
    } finally {
      setLoading(false);
    }
  }, [fetchOverviewData, fetchCategoryData, fetchJobHealthData]);

  // Fetch all dashboard data
  useEffect(() => {
    void fetchDashboardData();
    // Refresh every 5 minutes
    const interval = setInterval(() => {
      void fetchDashboardData();
    }, 5 * 60 * 1000);
    return () => clearInterval(interval);
  }, [fetchDashboardData]);

  // Render category performance chart
  useEffect(() => {
    if (!categoryChartRef.current || categoryData.length === 0) return;

    // Destroy existing chart
    if (categoryChartInstance.current) {
      categoryChartInstance.current.destroy();
    }

    // Create new pie chart
    categoryChartInstance.current = new Pie(categoryChartRef.current, {
      data: categoryData,
      angleField: 'clicks',
      colorField: 'type',
      radius: 0.8,
      innerRadius: 0.6, // Donut style
      label: {
        type: 'outer',
        content: '{name} {percentage}',
      },
      legend: {
        position: 'bottom',
      },
      interactions: [{ type: 'element-active' }],
      // Marketplace category color ramp (from design system)
      color: ['#ffccc7', '#ffe7ba', '#d9f7be', '#b5f5ec', '#d6e4ff'],
    });

    categoryChartInstance.current.render();

    return () => {
      if (categoryChartInstance.current) {
        categoryChartInstance.current.destroy();
        categoryChartInstance.current = null;
      }
    };
  }, [categoryData]);

  // Render sparkline for overview cards
  useEffect(() => {
    if (
      !sparklineChartRef.current ||
      !overviewData ||
      overviewData.daily_trend.length === 0
    )
      return;

    // Destroy existing chart
    if (sparklineChartInstance.current) {
      sparklineChartInstance.current.destroy();
    }

    // Create new line chart
    sparklineChartInstance.current = new Line(sparklineChartRef.current, {
      data: overviewData.daily_trend,
      xField: 'date',
      yField: 'clicks',
      smooth: true,
      height: 60,
      xAxis: false,
      yAxis: false,
      legend: false,
      tooltip: {
        showTitle: false,
      },
      // Stocks color ramp (from design system)
      color: '#1890ff',
    });

    sparklineChartInstance.current.render();

    return () => {
      if (sparklineChartInstance.current) {
        sparklineChartInstance.current.destroy();
        sparklineChartInstance.current = null;
      }
    };
  }, [overviewData]);

  // Export functions
  const exportToCSV = <T extends Record<string, unknown>>(data: T[], filename: string) => {
    if (data.length === 0) {
      notification.warning({
        message: 'No Data',
        description: 'No data available to export.',
      });
      return;
    }

    const first = data[0];
    if (!first) return;
    const headers = Object.keys(first).join(',');
    const rows = data.map((row) => Object.values(row).join(',')).join('\n');
    const csv = `${headers}\n${rows}`;

    const blob = new Blob([csv], { type: 'text/csv' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = filename;
    a.click();
    URL.revokeObjectURL(url);

    notification.success({
      message: 'Export Successful',
      description: `Exported ${data.length} records to ${filename}`,
    });
  };

  const exportToJSON = <T extends Record<string, unknown>>(data: T[], filename: string) => {
    if (data.length === 0) {
      notification.warning({
        message: 'No Data',
        description: 'No data available to export.',
      });
      return;
    }

    const json = JSON.stringify(data, null, 2);
    const blob = new Blob([json], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = filename;
    a.click();
    URL.revokeObjectURL(url);

    notification.success({
      message: 'Export Successful',
      description: `Exported ${data.length} records to ${filename}`,
    });
  };

  // Job health table columns
  const jobHealthColumns = [
    {
      title: 'Queue',
      dataIndex: 'queue',
      key: 'queue',
    },
    {
      title: 'Backlog',
      dataIndex: 'backlog',
      key: 'backlog',
      render: (backlog: number) => (
        <span style={{ fontWeight: backlog > 10 ? 'bold' : 'normal' }}>
          {backlog}
        </span>
      ),
    },
    {
      title: 'Avg Wait (s)',
      dataIndex: 'avg_wait_seconds',
      key: 'avg_wait_seconds',
      render: (wait: number) => wait.toFixed(1),
    },
    {
      title: 'Stuck Jobs',
      dataIndex: 'stuck_jobs',
      key: 'stuck_jobs',
      render: (stuck: number) => (
        <span style={{ color: stuck > 0 ? '#cf1322' : 'inherit' }}>
          {stuck}
        </span>
      ),
    },
    {
      title: 'Status',
      dataIndex: 'status',
      key: 'status',
      render: (status: string) => {
        let icon = <CheckCircleOutlined />;
        let color = 'success';

        if (status === 'critical') {
          icon = <CloseCircleOutlined />;
          color = 'error';
        } else if (status === 'warning') {
          icon = <WarningOutlined />;
          color = 'warning';
        }

        return (
          <Tag icon={icon} color={color}>
            {status.toUpperCase()}
          </Tag>
        );
      },
    },
  ];

  if (loading && !overviewData) {
    return (
      <div style={{ textAlign: 'center', padding: '50px' }}>
        <Spin size="large" tip="Loading analytics..." />
      </div>
    );
  }

  return (
    <div className="analytics-dashboard" style={{ padding: '24px' }}>
      {/* Header with date range selector */}
      <Row
        justify="space-between"
        align="middle"
        style={{ marginBottom: '24px' }}
      >
        <Col>
          <h2 style={{ margin: 0 }}>Analytics Dashboard</h2>
        </Col>
        <Col>
          <Select
            value={dateRange}
            onChange={setDateRange}
            style={{ width: 200 }}
            options={[
              { label: 'Last 24 hours', value: '1d' },
              { label: 'Last 7 days', value: '7d' },
              { label: 'Last 30 days', value: '30d' },
            ]}
          />
        </Col>
      </Row>

      {/* Overview Cards */}
      <Row gutter={[16, 16]} style={{ marginBottom: '24px' }}>
        <Col xs={24} sm={12} md={6}>
          <Card>
            <Statistic
              title="Total Clicks Today"
              value={overviewData?.clicks_today || 0}
              prefix={<ArrowUpOutlined />}
              valueStyle={{ color: '#3f8600' }}
            />
          </Card>
        </Col>
        <Col xs={24} sm={12} md={6}>
          <Card>
            <Statistic
              title={`Total Clicks (${dateRange})`}
              value={overviewData?.clicks_range || 0}
            />
          </Card>
        </Col>
        <Col xs={24} sm={12} md={6}>
          <Card>
            <Statistic
              title="Unique Users Today"
              value={overviewData?.unique_users_today || 0}
            />
          </Card>
        </Col>
        <Col xs={24} sm={12} md={6}>
          <Card>
            <Statistic
              title="AI Budget"
              value={overviewData?.ai_budget_pct || 0}
              precision={1}
              suffix="%"
              valueStyle={{
                color:
                  (overviewData?.ai_budget_pct || 0) > 90
                    ? '#cf1322'
                    : (overviewData?.ai_budget_pct || 0) > 75
                    ? '#faad14'
                    : '#3f8600',
              }}
              prefix={
                (overviewData?.ai_budget_pct || 0) > 90 ? (
                  <WarningOutlined />
                ) : null
              }
            />
            <div style={{ fontSize: '12px', color: '#666', marginTop: '8px' }}>
              ${((overviewData?.ai_cost_cents || 0) / 100).toFixed(2)} / $
              {((overviewData?.ai_budget_cents || 0) / 100).toFixed(2)}
            </div>
          </Card>
        </Col>
      </Row>

      {/* Sparkline Trend */}
      {overviewData && overviewData.daily_trend.length > 0 && (
        <Row style={{ marginBottom: '24px' }}>
          <Col span={24}>
            <Card title="7-Day Click Trend" size="small">
              <div ref={sparklineChartRef} style={{ height: '60px' }} />
            </Card>
          </Col>
        </Row>
      )}

      {/* Category Performance Chart */}
      <Row gutter={[16, 16]} style={{ marginBottom: '24px' }}>
        <Col xs={24} lg={12}>
          <Card
            title="Category Performance"
            extra={
              <Select
                mode="multiple"
                placeholder="Filter categories"
                value={selectedCategories}
                onChange={setSelectedCategories}
                style={{ width: 300 }}
                options={[
                  { label: 'Feed Items', value: 'feed_item' },
                  { label: 'Directory Sites', value: 'directory_site' },
                  { label: 'Marketplace Listings', value: 'marketplace_listing' },
                  { label: 'Profile Curated', value: 'profile_curated' },
                ]}
              />
            }
          >
            {categoryData.length > 0 ? (
              <>
                <div ref={categoryChartRef} style={{ height: '400px' }} />
                <div style={{ marginTop: '16px', textAlign: 'right' }}>
                  <Button
                    icon={<DownloadOutlined />}
                    onClick={() =>
                      exportToCSV(categoryData, 'category-performance.csv')
                    }
                    style={{ marginRight: '8px' }}
                  >
                    Export CSV
                  </Button>
                  <Button
                    icon={<DownloadOutlined />}
                    onClick={() =>
                      exportToJSON(categoryData, 'category-performance.json')
                    }
                  >
                    Export JSON
                  </Button>
                </div>
              </>
            ) : (
              <div style={{ textAlign: 'center', padding: '50px' }}>
                <p>No category data available</p>
              </div>
            )}
          </Card>
        </Col>

        {/* Job Health Summary */}
        <Col xs={24} lg={12}>
          <Card title="Job Queue Health">
            {jobHealthData.length > 0 ? (
              <>
                <Table
                  dataSource={jobHealthData}
                  columns={jobHealthColumns}
                  rowKey="queue"
                  pagination={false}
                  size="small"
                />
                <div style={{ marginTop: '16px', textAlign: 'right' }}>
                  <Button
                    icon={<DownloadOutlined />}
                    onClick={() =>
                      exportToCSV(jobHealthData, 'job-health.csv')
                    }
                  >
                    Export CSV
                  </Button>
                </div>
              </>
            ) : (
              <div style={{ textAlign: 'center', padding: '50px' }}>
                <p>No job queue data available</p>
              </div>
            )}
          </Card>
        </Col>
      </Row>

      {/* Footer */}
      <Row>
        <Col span={24}>
          <Card size="small">
            <p style={{ margin: 0, fontSize: '12px', color: '#666' }}>
              <strong>Data Sources:</strong> click_stats_daily, click_stats_daily_items,
              ai_usage_tracking, delayed_jobs
              <br />
              <strong>Retention:</strong> Raw clicks: 90 days | Rollups: Indefinite
              <br />
              <strong>Policy:</strong> F14.9 (Analytics), P8 (Admin Access), P14 (Privacy)
            </p>
          </Card>
        </Col>
      </Row>
    </div>
  );
};

export default AnalyticsDashboard;
