/**
 * 短链信息，与后端 model/vo/link/LinkVO 对齐。
 * id 用 string：Snowflake 生成的 64 位长整型超过 Number.MAX_SAFE_INTEGER，
 * 前端只做透传/展示，不做数学运算，保持字符串可避免精度丢失。
 */
export interface LinkVO {
  id: string;
  shortCode: string;
  shortUrl: string;
  longUrl: string;
  userId: number;
  expireTime: string | null;
  createTime: string;
}

/** 按小时统计点，与后端 model/dto/link/HourlyStatus 对齐 */
export interface HourlyStatus {
  hour: string;
  pv: number;
  uv: number;
}

/** 短链访问统计，与后端 model/vo/link/LinkStatusVO 对齐 */
export interface LinkStatusVO {
  linkId: string;
  shortCode: string;
  pv: number;
  uv: number;
  todayPv: number;
  todayUv: number;
  hourlyTrend: HourlyStatus[];
}
