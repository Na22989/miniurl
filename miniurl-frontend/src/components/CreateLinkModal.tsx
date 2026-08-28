import { useState } from 'react';
import { Modal, Form, Input, DatePicker, message } from 'antd';
import dayjs, { Dayjs } from 'dayjs';
import { createLink } from '../api/link';

interface CreateLinkModalProps {
  open: boolean;
  onClose: () => void;
  onCreated: () => void;
}

interface CreateLinkFormValues {
  longUrl: string;
  expireTime?: Dayjs;
}

// 前端预检正则，宽松匹配 http(s):// 开头，后端 @HttpUrl 兜底
const URL_PATTERN = /^https?:\/\/.+/;

export default function CreateLinkModal({ open, onClose, onCreated }: CreateLinkModalProps) {
  const [form] = Form.useForm<CreateLinkFormValues>();
  const [submitting, setSubmitting] = useState(false);

  async function handleOk() {
    try {
      const values = await form.validateFields();
      setSubmitting(true);
      await createLink({
        longUrl: values.longUrl,
        // 不用 toISOString()：它输出 UTC（带 Z），而后端 expireTime 是无时区的
        // LocalDateTime，Jackson 按字面量解析会丢掉 Z，导致本地时间差 8 小时。
        // 这里输出本地字面量（不带 Z），与后端语义一致。
        expireTime: values.expireTime?.format('YYYY-MM-DDTHH:mm:ss'),
      });
      message.success('创建成功');
      form.resetFields();
      onCreated();
      onClose();
    } catch (err) {
      if ((err as Error).message) {
        message.error((err as Error).message);
      }
    } finally {
      setSubmitting(false);
    }
  }

  function handleCancel() {
    form.resetFields();
    onClose();
  }

  return (
    <Modal
      title="创建短链"
      open={open}
      onOk={handleOk}
      onCancel={handleCancel}
      confirmLoading={submitting}
      okText="创建"
      cancelText="取消"
    >
      <Form<CreateLinkFormValues> form={form} layout="vertical">
        <Form.Item
          name="longUrl"
          label="原链接"
          rules={[
            { required: true, message: '请输入原链接' },
            { max: 2048, message: '链接长度不能超过 2048' },
            { pattern: URL_PATTERN, message: '请输入以 http:// 或 https:// 开头的链接' },
          ]}
        >
          <Input placeholder="https://example.com/very/long/path" />
        </Form.Item>
        <Form.Item name="expireTime" label="过期时间（可选）">
          <DatePicker
            showTime
            style={{ width: '100%' }}
            disabledDate={(current) => current && current < dayjs().startOf('day')}
          />
        </Form.Item>
      </Form>
    </Modal>
  );
}
