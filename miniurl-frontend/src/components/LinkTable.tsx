import { Table, Button, Space, Popconfirm, Tag, Typography } from 'antd';
import { CopyOutlined, BarChartOutlined, DeleteOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import type { LinkVO } from '../types/link';
import { formatDateTime, truncateUrl } from '../utils/formatters';
import { useClipboard } from '../hooks/useClipboard';

interface LinkTableProps {
  data: LinkVO[];
  loading: boolean;
  total: number;
  current: number;
  pageSize: number;
  onPageChange: (page: number, pageSize: number) => void;
  onDelete: (id: string) => Promise<void>;
}

export default function LinkTable({
  data,
  loading,
  total,
  current,
  pageSize,
  onPageChange,
  onDelete,
}: LinkTableProps) {
  const navigate = useNavigate();
  const { copy } = useClipboard();

  const columns = [
    {
      title: '短链',
      dataIndex: 'shortUrl',
      key: 'shortUrl',
      render: (shortUrl: string) => (
        <Space>
          <Typography.Link href={shortUrl} target="_blank" rel="noopener noreferrer">
            {shortUrl}
          </Typography.Link>
          <Button
            size="small"
            type="text"
            icon={<CopyOutlined />}
            onClick={() => copy(shortUrl)}
            aria-label="复制短链"
          />
        </Space>
      ),
    },
    {
      title: '原链接',
      dataIndex: 'longUrl',
      key: 'longUrl',
      render: (longUrl: string) => (
        <Typography.Text title={longUrl}>{truncateUrl(longUrl)}</Typography.Text>
      ),
    },
    {
      title: '创建时间',
      dataIndex: 'createTime',
      key: 'createTime',
      render: (value: string) => formatDateTime(value),
    },
    {
      title: '过期时间',
      dataIndex: 'expireTime',
      key: 'expireTime',
      render: (value: string | null) =>
        value ? formatDateTime(value) : <Tag color="green">永久</Tag>,
    },
    {
      title: '操作',
      key: 'action',
      render: (_: unknown, record: LinkVO) => (
        <Space>
          <Button
            size="small"
            icon={<BarChartOutlined />}
            onClick={() => navigate(`/links/${record.id}/analytics`)}
          >
            统计
          </Button>
          <Popconfirm title="确认删除该短链？" onConfirm={() => onDelete(record.id)}>
            <Button size="small" danger icon={<DeleteOutlined />}>
              删除
            </Button>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  return (
    <Table<LinkVO>
      rowKey="id"
      columns={columns}
      dataSource={data}
      loading={loading}
      scroll={{ x: 720 }}
      pagination={{
        current,
        pageSize,
        total,
        onChange: onPageChange,
      }}
    />
  );
}
