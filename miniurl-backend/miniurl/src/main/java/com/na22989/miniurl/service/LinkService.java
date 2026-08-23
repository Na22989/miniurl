package com.na22989.miniurl.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.na22989.miniurl.common.DeleteRequest;
import com.na22989.miniurl.common.PageResult;
import com.na22989.miniurl.model.dto.link.CreateLinkRequest;
import com.na22989.miniurl.model.dto.link.PageLinkRequest;
import com.na22989.miniurl.model.entity.Link;
import com.na22989.miniurl.model.vo.link.LinkStatusVO;
import com.na22989.miniurl.model.vo.link.LinkVO;
import com.na22989.miniurl.task.SyncClickCounts2DBTask;
import jakarta.servlet.http.HttpServletRequest;

import java.util.List;


public interface LinkService extends IService<Link> {

    /**
     * 创建短链接
     *
     * @param userId  创建者 ID
     * @param request 创建请求（长链接、可选过期时间）
     * @return 创建成功的短链接信息
     */
    LinkVO createLink(Long userId, CreateLinkRequest request);

    /**
     * 重定向：布隆过滤器预检 + L1/L2 缓存 + DB 兜底，返回目标长链接
     *
     * @param shortCode 短码
     * @param request   请求（用于提取客户端 IP / UA 写访问日志）
     * @return 目标长链接
     * @throws BizException 短码不存在或已过期：LINK_NOT_FOUND / LINK_EXPIRED
     */
    String redirect(String shortCode, HttpServletRequest request) ;

    /**
     * 分页查询当前用户的短链接列表
     *
     * @param userId  用户 ID
     * @param request 分页参数
     * @return 分页结果
     */
    PageResult<LinkVO> listUserLinks(Long userId, PageLinkRequest request);

    /**
     * 删除短链接并清理其缓存（仅本人可删）
     *
     * @param userId  用户 ID
     * @param request 删除请求（含 linkId）
     * @throws BizException 不存在或非本人：LINK_NOT_FOUND
     */
    void deleteLink(Long userId, DeleteRequest request);

    /**
     * 查询短链接详情（仅本人可见）
     *
     * @param userId 用户 ID
     * @param linkId 链接 ID
     * @return 短链接详情
     * @throws BizException 参数非法 BAD_REQUEST；不存在或非本人 LINK_NOT_FOUND
     */
    LinkVO getLinkDetail(Long userId, Long linkId);

    /**
     * 批量累加点击计数（定时任务回写 DB 用）
     *
     * @param updates 短码与点击增量列表
     * @return 受影响行数
     */
    int batchUpdateClickCount(List<SyncClickCounts2DBTask.ClickSyncDTO> updates);


}
