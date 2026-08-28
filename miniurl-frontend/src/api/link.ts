import client from './client';
import type { PageResult } from '../types/api';
import type { LinkVO, LinkStatusVO } from '../types/link';

export interface CreateLinkRequest {
  longUrl: string;
  expireTime?: string;
}

export interface PageLinkRequest {
  current?: number;
  size?: number;
}

export function createLink(req: CreateLinkRequest): Promise<LinkVO> {
  return client.post('/link/create', req);
}

export function listLinks(req: PageLinkRequest = {}): Promise<PageResult<LinkVO>> {
  return client.post('/link/list', { current: 1, size: 10, ...req });
}

export function deleteLink(id: string): Promise<null> {
  return client.delete('/link/delete', { data: { id } });
}

export function getLinkDetail(id: string): Promise<LinkVO> {
  return client.get(`/link/detail/${id}`);
}

export function getLinkStatus(id: string): Promise<LinkStatusVO> {
  return client.get(`/link/status/${id}`);
}
