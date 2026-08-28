import dayjs from 'dayjs';

export function formatDateTime(value: string | null): string {
  if (!value) return '永久';
  return dayjs(value).format('YYYY-MM-DD HH:mm');
}

export function truncateUrl(url: string, maxLength = 40): string {
  if (url.length <= maxLength) return url;
  return `${url.slice(0, maxLength)}...`;
}
