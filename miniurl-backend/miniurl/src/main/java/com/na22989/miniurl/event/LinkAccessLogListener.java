package com.na22989.miniurl.event;

import com.na22989.miniurl.mapper.LinkAccessLogMapper;
import com.na22989.miniurl.model.entity.LinkAccessLog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 访问日志监听器：短链访问事件 → 打日志行 + 异步落库 link_access_log。
 * <p>
 * 尽力而为：插入失败只记日志不抛异常（异步任务异常不影响主流程）。
 * 顺序刻意先打日志行：即使 DB 失败，应用日志里也有访问记录。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LinkAccessLogListener {

    private final LinkAccessLogMapper linkAccessLogMapper;

    @EventListener
    @Async("clickCountExecutor")
    public void onLinkAccess(LinkAccessedEvent event) {
        // 先打日志行：DB 失败时日志文件仍有访问记录
        log.info("[ACCESS] shortCode={} linkId={} ip={} ua={} referer={}",
                event.getShortCode(), event.getLinkId(), event.getIp(),
                event.getUserAgent(), event.getReferer());

        try {
            LinkAccessLog record = new LinkAccessLog();
            record.setLinkId(event.getLinkId());
            record.setShortCode(event.getShortCode());
            record.setIp(event.getIp());
            record.setUserAgent(event.getUserAgent());
            record.setReferer(event.getReferer());
            // access_time 交给 DB 的 DEFAULT CURRENT_TIMESTAMP 填充，不显式赋值
            linkAccessLogMapper.insert(record);
        } catch (Exception e) {
            log.error("[访问日志] shortCode={} 写入 link_access_log 失败，本次访问日志可能丢失", event.getShortCode(), e);
        }
    }
}
