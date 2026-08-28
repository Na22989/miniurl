import { message } from 'antd';

export function useClipboard() {
  async function copy(text: string) {
    try {
      if (navigator.clipboard) {
        await navigator.clipboard.writeText(text);
      } else {
        // 降级：不支持 Clipboard API（非 https / 旧浏览器）时用隐藏 textarea + execCommand
        const textarea = document.createElement('textarea');
        textarea.value = text;
        textarea.style.position = 'fixed';
        textarea.style.opacity = '0';
        document.body.appendChild(textarea);
        textarea.select();
        document.execCommand('copy');
        document.body.removeChild(textarea);
      }
      message.success('已复制到剪贴板');
    } catch {
      message.error('复制失败，请手动复制');
    }
  }

  return { copy };
}
