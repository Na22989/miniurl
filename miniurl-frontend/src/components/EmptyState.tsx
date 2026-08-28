import { Button, Empty } from 'antd';

interface EmptyStateProps {
  onCreate: () => void;
}

export default function EmptyState({ onCreate }: EmptyStateProps) {
  return (
    <Empty description="还没有短链，创建第一个吧">
      <Button type="primary" onClick={onCreate}>
        创建短链
      </Button>
    </Empty>
  );
}
