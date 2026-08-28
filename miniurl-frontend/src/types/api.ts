/** 统一响应体，与后端 common/Result<T> 对齐 */
export interface Result<T> {
  code: number;
  message: string;
  data: T;
}

/** 分页结果，与后端 common/PageResult<T> 对齐 */
export interface PageResult<T> {
  records: T[];
  total: number;
  current: number;
  size: number;
  pages: number;
}
