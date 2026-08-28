import { useState } from 'react';
import { Button, Card, Form, Input, Typography, message } from 'antd';
import { Link, useNavigate } from 'react-router-dom';
import { useAuth } from '../hooks/useAuth';

interface RegisterFormValues {
  username: string;
  password: string;
  confirmPassword: string;
  nickname?: string;
}

export default function RegisterPage() {
  const { register } = useAuth();
  const navigate = useNavigate();
  const [submitting, setSubmitting] = useState(false);

  async function onFinish(values: RegisterFormValues) {
    setSubmitting(true);
    try {
      await register({ username: values.username, password: values.password, nickname: values.nickname });
      message.success('注册成功，请登录');
      navigate('/login', { replace: true });
    } catch (err) {
      message.error((err as Error).message || '注册失败');
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
          MiniURL 注册
        </Typography.Title>
        <Form<RegisterFormValues> layout="vertical" onFinish={onFinish} autoComplete="off">
          <Form.Item
            name="username"
            label="用户名"
            rules={[{ required: true, min: 4, max: 20, message: '用户名需 4-20 字符' }]}
          >
            <Input placeholder="4-20 字符" />
          </Form.Item>
          <Form.Item
            name="password"
            label="密码"
            rules={[{ required: true, min: 6, max: 20, message: '密码需 6-20 字符' }]}
          >
            <Input.Password placeholder="6-20 字符" />
          </Form.Item>
          <Form.Item
            name="confirmPassword"
            label="确认密码"
            dependencies={['password']}
            rules={[
              { required: true, message: '请确认密码' },
              ({ getFieldValue }) => ({
                validator(_, value) {
                  if (!value || getFieldValue('password') === value) {
                    return Promise.resolve();
                  }
                  return Promise.reject(new Error('两次密码输入不一致'));
                },
              }),
            ]}
          >
            <Input.Password placeholder="再次输入密码" />
          </Form.Item>
          <Form.Item name="nickname" label="昵称" rules={[{ max: 20, message: '昵称最多 20 字符' }]}>
            <Input placeholder="选填" />
          </Form.Item>
          <Form.Item>
            <Button type="primary" htmlType="submit" block loading={submitting}>
              注册
            </Button>
          </Form.Item>
        </Form>
        <div style={{ textAlign: 'center' }}>
          已有账号？<Link to="/login">去登录</Link>
        </div>
      </Card>
    </div>
  );
}
