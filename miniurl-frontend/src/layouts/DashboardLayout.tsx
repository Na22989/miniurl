import { Layout, Typography, Button, Space } from 'antd';
import { LogoutOutlined } from '@ant-design/icons';
import { Outlet, useNavigate } from 'react-router-dom';
import { useAuth } from '../hooks/useAuth';

const { Header, Content } = Layout;

export default function DashboardLayout() {
  const { userInfo, logout } = useAuth();
  const navigate = useNavigate();

  async function handleLogout() {
    await logout();
    navigate('/login', { replace: true });
  }

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Header style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
        <Typography.Title level={4} style={{ color: '#fff', margin: 0 }}>
          MiniURL
        </Typography.Title>
        <Space>
          <Typography.Text style={{ color: '#fff' }}>{userInfo?.nickname || userInfo?.username}</Typography.Text>
          <Button icon={<LogoutOutlined />} onClick={handleLogout}>
            登出
          </Button>
        </Space>
      </Header>
      <Content style={{ padding: 24 }}>
        <Outlet />
      </Content>
    </Layout>
  );
}
