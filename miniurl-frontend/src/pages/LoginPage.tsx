import { useState } from 'react';
import { Button, Card, Form, Input, Typography, message } from 'antd';
import { Link, useLocation, useNavigate } from 'react-router-dom';
import { useAuth } from '../hooks/useAuth';

interface LoginFormValues {
  username: string;
  password: string;
}

export default function LoginPage() {
  const { login } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const [submitting, setSubmitting] = useState(false);

  async function onFinish(values: LoginFormValues) {
    setSubmitting(true);
    try {
      await login(values);
      const from = (location.state as { from?: Location })?.from;
      navigate(from?.pathname ?? '/dashboard', { replace: true });
    } catch (err) {
      message.error((err as Error).message || '登录失败');
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div
      style={{
        display: 'flex',
        justifyContent: 'center',
        alignItems: 'center',
        minHeight: '100vh',
        padding: 16,
      }}
    >
      <Card style={{ width: 360, maxWidth: '100%' }}>
        <Typography.Title level={3} style={{ textAlign: 'center', marginBottom: 24 }}>
          MiniURL 登录
        </Typography.Title>
        <Form<LoginFormValues> layout="vertical" onFinish={onFinish} autoComplete="off">
          <Form.Item
            name="username"
            label="用户名"
            rules={[{ required: true, min: 4, max: 20, message: '用户名需 4-20 字符' }]}
          >
            <Input placeholder="请输入用户名" />
          </Form.Item>
          <Form.Item
            name="password"
            label="密码"
            rules={[{ required: true, min: 6, max: 20, message: '密码需 6-20 字符' }]}
          >
            <Input.Password placeholder="请输入密码" />
          </Form.Item>
          <Form.Item>
            <Button type="primary" htmlType="submit" block loading={submitting}>
              登录
            </Button>
          </Form.Item>
        </Form>
        <div style={{ textAlign: 'center' }}>
          还没有账号？<Link to="/register">去注册</Link>
        </div>
      </Card>
    </div>
  );
}
