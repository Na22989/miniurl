package com.na22989.miniurl.util;

import com.google.common.net.InetAddresses;
import com.na22989.miniurl.model.dto.link.AccessMeta;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * 网络工具类
 *
 * <p>=== 安全模型 ===
 * <pre>
 * 1. 先取直连 IP request.getRemoteAddr()（唯一不可被客户端伪造的源）
 * 2. 直连 IP 不在可信代理白名单 → 忽略所有代理头，只信 getRemoteAddr()，记审计日志
 * 3. 直连 IP 在可信代理白名单 → 才采信 X-Real-IP / X-Forwarded-For 等代理头
 * </pre>
 *
 * <p>修复的漏洞（详见 SECURITY_AUDIT_NetUtil.md）：
 * <ul>
 *   <li>P0#1：X-Real-IP 无条件信任 → 伪造头绕过限流。现在只有可信代理设的头才被采信</li>
 *   <li>P0#2：proxiesToTrust 默认 0 硬编码 → 移除默认值，调用方必须显式传参</li>
 *   <li>P1#4：extractIpFromForwardedFor 死代码边界</li>
 *   <li>P1#5：normalizeIp 强制 ::1 → 127.0.0.1，丢失 IPv6 原始格式</li>
 * </ul>
 */
@Slf4j
public class NetUtil {

    private static final String UNKNOWN = "unknown";
    private static final String LOCALHOST_IPV4 = "127.0.0.1";

    // 可信代理白名单：支持精确 IP 或 CIDR 段（如 172.16.0.0/12）。volatile：运行期可热更
    private static volatile Set<String> trustedProxies = defaultTrustedProxies();

    // 严格模式：检测到非可信源伪造代理头时抛异常（false = 仅记日志）
    private static volatile boolean strictMode = false;

    private static Set<String> defaultTrustedProxies() {
        Set<String> s = new HashSet<>();
        s.add(LOCALHOST_IPV4);
        s.add("::1");
        s.add("0:0:0:0:0:0:0:1");
        return s;
    }

    /**
     * 配置可信代理白名单（支持 CIDR 段）。线程安全。始终保留 localhost 以便本地直连调试。
     *
     * @param proxies 可信代理 IP / CIDR 集合
     */
    public static synchronized void setTrustedProxies(Set<String> proxies) {
        Set<String> copy = new HashSet<>();
        if (proxies != null) {
            for (String p : proxies) {
                if (p != null && !p.isBlank()) {
                    copy.add(p.trim());
                }
            }
        }
        // 始终保留本地回环，本地直连调试（spring-boot:run）不受影响
        copy.add(LOCALHOST_IPV4);
        copy.add("::1");
        copy.add("0:0:0:0:0:0:0:1");
        trustedProxies = Collections.unmodifiableSet(copy);
        log.info("[NetUtil] 已配置可信代理白名单: {}", trustedProxies);
    }

    /**
     * 启用/关闭严格模式。
     *
     * @param enabled true = 检测到伪造即抛异常拒绝；false = 仅记录审计日志
     */
    public static synchronized void setStrictMode(boolean enabled) {
        strictMode = enabled;
        log.info("[NetUtil] 严格模式: {}", enabled ? "已启用（检测到伪造即拒绝）" : "已关闭（仅记日志）");
    }

    /**
     * 获取当前可信代理白名单（测试用）。
     */
    public static Set<String> getTrustedProxies() {
        return trustedProxies;
    }

    /**
     * 获取客户端真实 IP（安全版）。
     * <p>强制显式传 proxiesToTrust，不提供默认值（审计 P0#2：默认 0 在直连场景下会被客户端
     * 伪造的 X-Forwarded-For 利用）。</p>
     *
     * @param request        HTTP 请求
     * @param proxiesToTrust 可信代理层级（0 = 取直连 IP，1+ = 穿透代理层数）
     * @return 客户端 IP（IPv4 或 IPv6）
     */
    public static String getIpAddress(HttpServletRequest request, int proxiesToTrust) {
        if (request == null) {
            log.warn("[NetUtil] request 为 null，返回默认 IP");
            return LOCALHOST_IPV4;
        }

        // 唯一不可伪造的：直连 IP
        String remoteAddr = request.getRemoteAddr();
        if (remoteAddr == null || remoteAddr.isEmpty() || !isValidIp(remoteAddr)) {
            log.warn("[NetUtil] getRemoteAddr() 无效: {}", remoteAddr);
            return LOCALHOST_IPV4;
        }

        // 直连对端必须位于可信代理白名单，才允许采信代理头
        if (!isTrustedProxy(remoteAddr)) {
            String xRealIp = request.getHeader("X-Real-IP");
            String xForwardedFor = request.getHeader("X-Forwarded-For");
            if (xRealIp != null || xForwardedFor != null) {
                log.warn("[安全] 非可信源尝试设置代理头 | remoteAddr={}, X-Real-IP={}, X-Forwarded-For={}",
                        remoteAddr, xRealIp, xForwardedFor);
                if (strictMode) {
                    throw new SecurityException("检测到非法代理头伪造行为，来源 IP=" + remoteAddr);
                }
            }
            return normalizeIp(remoteAddr);
        }

        // 可信代理 → 处理代理头
        // 1. X-Real-IP（单层 Nginx 最常见）
        String ip = request.getHeader("X-Real-IP");
        if (isValidIp(ip)) {
            log.debug("[NetUtil] 使用 X-Real-IP: {}", ip);
            return normalizeIp(ip);
        }

        // 2. X-Forwarded-For（多级代理，按 proxiesToTrust 从右向左跳层级）
        //    注意：先提取再校验——整个头是 "client, proxy1, proxy2" 逗号串，整体不是合法 IP
        ip = request.getHeader("X-Forwarded-For");
        if (ip != null && !ip.isEmpty() && !UNKNOWN.equalsIgnoreCase(ip)) {
            String extracted = extractIpFromForwardedFor(ip, proxiesToTrust);
            if (isValidIp(extracted)) {
                log.debug("[NetUtil] 使用 X-Forwarded-For[{}]: {}", proxiesToTrust, extracted);
                return normalizeIp(extracted);
            }
        }

        // 3. 其他历史代理头
        String[] fallbackHeaders = {"Proxy-Client-IP", "WL-Proxy-Client-IP", "HTTP_CLIENT_IP"};
        for (String header : fallbackHeaders) {
            ip = request.getHeader(header);
            if (isValidIp(ip)) {
                log.debug("[NetUtil] 使用 {}: {}", header, ip);
                return normalizeIp(ip);
            }
        }

        // 4. 兜底：直连 IP
        return normalizeIp(remoteAddr);
    }

    /**
     * 构造访问日志元数据（LinkServiceImpl 重定向时记录访问日志）。
     */
    public static AccessMeta getAccessMeta(HttpServletRequest request, int proxiesToTrust) {
        if (request == null) {
            log.warn("[NetUtil] request 为 null，返回默认访问元数据");
            return AccessMeta.builder()
                    .ip(LOCALHOST_IPV4)
                    .userAgent("unknown")
                    .referer("")
                    .build();
        }

        String ip = getIpAddress(request, proxiesToTrust);

        String userAgent = request.getHeader("User-Agent");
        if (userAgent == null || userAgent.isEmpty()) {
            userAgent = "unknown";
        }
        // 长度检查略小于数据库阈值双重保障
        if (userAgent.length() > 450) {
            userAgent = userAgent.substring(0, 450);
        }

        String referer = request.getHeader("Referer");
        if (referer == null || referer.isEmpty()) {
            referer = "";
        }
        if (referer.length() > 2000) {
            referer = referer.substring(0, 2000);
        }

        return AccessMeta.builder()
                .ip(ip)
                .userAgent(userAgent)
                .referer(referer)
                .build();
    }

    /**
     * 从 X-Forwarded-For 提取 IP（修复死代码边界）。
     * <p>X-Forwarded-For: client, proxy1, proxy2（client 最左，最后一级代理最右）</p>
     * <p>proxiesToTrust=0 → 最右侧（直连本服务的代理）；1 → 倒数第 2；依此类推。</p>
     *
     * @return 提取出的 IP，无效则返回 null（调用方需再校验）
     */
    private static String extractIpFromForwardedFor(String forwardedFor, int proxiesToTrust) {
        if (forwardedFor == null || forwardedFor.isEmpty()) {
            return null;
        }

        String[] ips = forwardedFor.split(",");
        for (int i = 0; i < ips.length; i++) {
            ips[i] = ips[i].trim();
        }

        // 边界：层级不能为负
        if (proxiesToTrust < 0) {
            log.warn("[NetUtil] proxiesToTrust({}) 小于 0，重置为 0", proxiesToTrust);
            proxiesToTrust = 0;
        }
        // 边界：层级 >= IP 个数 → 配置超过实际代理层数，取最左（客户端）IP
        if (proxiesToTrust >= ips.length) {
            log.warn("[NetUtil] proxiesToTrust({}) >= IP 数量({})，取最左客户端 IP",
                    proxiesToTrust, ips.length);
            return isValidIp(ips[0]) ? ips[0] : null;
        }

        String ip = ips[ips.length - 1 - proxiesToTrust];
        return isValidIp(ip) ? ip : null;
    }

    /**
     * 校验 IP 是否合法（非空 / 非 unknown / 符合 IPv4 或 IPv6 格式）。
     */
    private static boolean isValidIp(String ip) {
        if (ip == null || ip.isEmpty()) {
            return false;
        }
        if (UNKNOWN.equalsIgnoreCase(ip)) {
            return false;
        }
        return InetAddresses.isInetAddress(ip);
    }

    /**
     * 标准化 IP：保留原始格式（修复 P1#5，不再把 ::1 强转 127.0.0.1）。
     * <p>前置条件：调用方必须已通过 {@link #isValidIp(String)} 校验。</p>
     */
    private static String normalizeIp(String ip) {
        if (ip == null || !isValidIp(ip)) {
            return LOCALHOST_IPV4;
        }
        return ip;
    }

    /**
     * 直连对端是否可信代理：先精确匹配白名单，再尝试 CIDR 段匹配。
     */
    private static boolean isTrustedProxy(String ip) {
        if (ip == null || !isValidIp(ip)) {
            return false;
        }
        if (trustedProxies.contains(ip)) {
            return true;
        }
        byte[] addrBytes = InetAddresses.forString(ip).getAddress();
        for (String entry : trustedProxies) {
            int slash = entry.indexOf('/');
            if (slash <= 0) {
                continue; // 非 CIDR 条目：精确匹配已在上方处理
            }
            String base = entry.substring(0, slash);
            int prefixLen;
            try {
                prefixLen = Integer.parseInt(entry.substring(slash + 1));
            } catch (NumberFormatException e) {
                continue;
            }
            if (prefixLen < 0 || prefixLen > 128 || !InetAddresses.isInetAddress(base)) {
                continue;
            }
            byte[] baseBytes = InetAddresses.forString(base).getAddress();
            if (baseBytes.length != addrBytes.length) {
                continue; // IPv4 与 IPv6 位长不一致，不匹配
            }
            if (matchesCidr(addrBytes, baseBytes, prefixLen)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 按前缀长度比对两个等长 IP 字节数组是否同段。
     */
    private static boolean matchesCidr(byte[] addr, byte[] base, int prefixLen) {
        int fullBytes = prefixLen / 8;
        int remainBits = prefixLen % 8;
        for (int i = 0; i < fullBytes; i++) {
            if ((addr[i] & 0xFF) != (base[i] & 0xFF)) {
                return false;
            }
        }
        if (remainBits > 0) {
            int mask = 0xFF << (8 - remainBits);
            if ((addr[fullBytes] & mask) != (base[fullBytes] & mask)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 判断是否本地 IP（回环）。保留用途：区分本地测试与生产环境。
     */
    public static boolean isLocalhost(String ip) {
        if (ip == null || ip.isEmpty()) {
            return false;
        }
        if (!isValidIp(ip)) {
            return "localhost".equalsIgnoreCase(ip);
        }
        return InetAddresses.forString(ip).isLoopbackAddress();
    }

    /**
     * 判断是否内网 IP（10/8、172.16/12、192.168/16、127/8）。
     */
    public static boolean isIntranetIp(String ip) {
        if (ip == null || ip.isEmpty()) {
            return false;
        }
        if (isLocalhost(ip)) {
            return true;
        }
        if (!isValidIp(ip)) {
            return false;
        }
        InetAddress addr = InetAddresses.forString(ip);
        if (!(addr instanceof Inet4Address)) {
            return false;
        }
        byte[] octets = addr.getAddress();
        int part1 = octets[0] & 0xFF;
        int part2 = octets[1] & 0xFF;
        return (part1 == 10)
                || (part1 == 172 && part2 >= 16 && part2 <= 31)
                || (part1 == 192 && part2 == 168)
                || (part1 == 127);
    }
}
