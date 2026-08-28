import { useEffect, useState } from 'react';
import { Card, Col, Row, Statistic, Typography, Button, Result, Spin } from 'antd';
import { ArrowLeftOutlined } from '@ant-design/icons';
import { useNavigate, useParams } from 'react-router-dom';
import ReactECharts from 'echarts-for-react';
import { getLinkStatus } from '../api/link';
import type { LinkStatusVO } from '../types/link';

export default function LinkAnalyticsPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const [status, setStatus] = useState<LinkStatusVO | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  async function fetchStatus() {
    if (!id) return;
    setLoading(true);
    setError(null);
    try {
      const result = await getLinkStatus(id);
      setStatus(result);
    } catch (err) {
      setError((err as Error).message || '加载统计失败');
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    fetchStatus();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [id]);

  if (loading) {
    return (
      <div style={{ textAlign: 'center', padding: 48 }}>
        <Spin />
      </div>
    );
  }

  if (error || !status) {
    return (
      <Result
        status="error"
        title="加载失败"
        subTitle={error}
        extra={[
          <Button key="retry" onClick={fetchStatus}>
            重试
          </Button>,
          <Button key="back" onClick={() => navigate('/dashboard')}>
            返回列表
          </Button>,
        ]}
      />
    );
  }

  const chartOption = {
    tooltip: { trigger: 'axis' },
    legend: { data: ['PV', 'UV'] },
    xAxis: { type: 'category', data: status.hourlyTrend.map((p) => p.hour) },
    yAxis: { type: 'value' },
    series: [
      { name: 'PV', type: 'line', data: status.hourlyTrend.map((p) => p.pv) },
      { name: 'UV', type: 'line', data: status.hourlyTrend.map((p) => p.uv) },
    ],
  };

  return (
    <div>
      <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/dashboard')} style={{ marginBottom: 16 }}>
        返回列表
      </Button>
      <Typography.Title level={4}>短链统计（{status.shortCode}）</Typography.Title>

      <Row gutter={[16, 16]} style={{ marginBottom: 24 }}>
        <Col xs={12} md={6}>
          <Card>
            <Statistic title="总 PV" value={status.pv} />
          </Card>
        </Col>
        <Col xs={12} md={6}>
          <Card>
            <Statistic title="总 UV" value={status.uv} />
          </Card>
        </Col>
        <Col xs={12} md={6}>
          <Card>
            <Statistic title="今日 PV" value={status.todayPv} />
          </Card>
        </Col>
        <Col xs={12} md={6}>
          <Card>
            <Statistic title="今日 UV" value={status.todayUv} />
          </Card>
        </Col>
      </Row>

      <Card title="24 小时趋势">
        {status.hourlyTrend.length === 0 ? (
          <Typography.Text type="secondary">暂无访问数据</Typography.Text>
        ) : (
          <ReactECharts option={chartOption} style={{ height: 360 }} opts={{ renderer: 'canvas' }} />
        )}
      </Card>
    </div>
  );
}
