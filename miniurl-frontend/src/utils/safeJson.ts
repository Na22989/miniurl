// Snowflake 生成的 id（Link.id 等）是 64 位长整型，超过 Number.MAX_SAFE_INTEGER
// (9007199254740991，16 位)。原生 JSON.parse 会把这类大整数解析成 number 并静默丢精度，
// 例如 86636664834752512 会变成 86636664834752510。这里在解析前把 *id 字段里
// 16 位及以上的数字字面量加上引号，转成 string，避免精度丢失。
// 只匹配 16 位以上，天然不影响 userId 等自增小整数字段。
const LONG_ID_PATTERN = /"(\w*[Ii]d)":(\d{16,})/g;

export function parseSafeJson(text: string): unknown {
  const quoted = text.replace(LONG_ID_PATTERN, '"$1":"$2"');
  return JSON.parse(quoted);
}
