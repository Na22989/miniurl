import { useCallback, useEffect, useState } from 'react';
import { Button, Typography, message } from 'antd';
import { PlusOutlined } from '@ant-design/icons';
import { listLinks, deleteLink } from '../api/link';
import type { LinkVO } from '../types/link';
import LinkTable from '../components/LinkTable';
import EmptyState from '../components/EmptyState';
import CreateLinkModal from '../components/CreateLinkModal';

export default function DashboardPage() {
  const [data, setData] = useState<LinkVO[]>([]);
  const [total, setTotal] = useState(0);
  const [current, setCurrent] = useState(1);
  const [pageSize, setPageSize] = useState(10);
  const [loading, setLoading] = useState(false);
  const [modalOpen, setModalOpen] = useState(false);

  const fetchList = useCallback(async (page = current, size = pageSize) => {
    setLoading(true);
    try {
      const result = await listLinks({ current: page, size });
      setData(result.records);
      setTotal(result.total);
      setCurrent(result.current);
      setPageSize(result.size);
    } catch (err) {
      message.error((err as Error).message || '加载列表失败');
    } finally {
      setLoading(false);
    }
  }, [current, pageSize]);

  useEffect(() => {
    fetchList(1, 10);
    // 首次加载固定拉第一页，之后翻页/删除走 handlePageChange / handleDelete 各自的当前页参数
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  function handlePageChange(page: number, size: number) {
    fetchList(page, size);
  }

  async function handleDelete(id: string) {
    try {
      await deleteLink(id);
      message.success('删除成功');
      // 删除后若当前页因删空需要回退到上一页，简单起见统一重拉当前页
      fetchList(current, pageSize);
    } catch (err) {
      message.error((err as Error).message || '删除失败');
    }
  }

  return (
    <div>
      <div
        style={{
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'center',
          flexWrap: 'wrap',
          gap: 8,
          marginBottom: 16,
        }}
      >
        <Typography.Title level={4} style={{ margin: 0 }}>
          我的短链
        </Typography.Title>
        <Button type="primary" icon={<PlusOutlined />} onClick={() => setModalOpen(true)}>
          创建短链
        </Button>
      </div>

      {!loading && total === 0 ? (
        <EmptyState onCreate={() => setModalOpen(true)} />
      ) : (
        <LinkTable
          data={data}
          loading={loading}
          total={total}
          current={current}
          pageSize={pageSize}
          onPageChange={handlePageChange}
          onDelete={handleDelete}
        />
      )}

      <CreateLinkModal
        open={modalOpen}
        onClose={() => setModalOpen(false)}
        onCreated={() => fetchList(1, pageSize)}
      />
    </div>
  );
}
