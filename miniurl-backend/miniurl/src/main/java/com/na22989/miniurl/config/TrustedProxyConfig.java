package com.na22989.miniurl.config;

import com.na22989.miniurl.util.NetUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

/**
 * NetUtil 可信代理白名单配置（Week 4 Day 6）
 * <p>绑定 application.yml 的 {@code miniurl.security.*}，属性绑定完成后把值注入 NetUtil 静态配置。
 * 注意时序：{@link ConfigurationProperties} 绑定发生在 bean 初始化（afterPropertiesSet）之前。</p>
 */
@Slf4j
@Component
@ConfigurationProperties(prefix = "miniurl.security")
public class TrustedProxyConfig implements InitializingBean {

    /**
     * 可信代理白名单：支持精确 IP 与 CIDR 段（如 172.16.0.0/12）。
     * 留空时 NetUtil 默认只信任本地回环（127.0.0.1 / ::1）。
     */
    private Set<String> trustedProxies = new HashSet<>();

    /**
     * 严格模式：true = 检测到非可信源伪造代理头直接拒绝；false = 仅记审计日志。
     */
    private boolean strictMode = false;

    public void setTrustedProxies(Set<String> trustedProxies) {
        this.trustedProxies = trustedProxies;
    }

    public void setStrictMode(boolean strictMode) {
        this.strictMode = strictMode;
    }

    @Override
    public void afterPropertiesSet() {
        if (trustedProxies != null && !trustedProxies.isEmpty()) {
            NetUtil.setTrustedProxies(trustedProxies);
        }
        NetUtil.setStrictMode(strictMode);
        log.info("[TrustedProxyConfig] 初始化完成，可信代理={}，strict-mode={}",
                NetUtil.getTrustedProxies(), strictMode);
    }
}
