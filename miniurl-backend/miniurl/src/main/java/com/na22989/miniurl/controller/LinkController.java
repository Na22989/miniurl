package com.na22989.miniurl.controller;

import com.na22989.miniurl.common.DeleteRequest;
import com.na22989.miniurl.common.PageResult;
import com.na22989.miniurl.common.Result;
import com.na22989.miniurl.model.dto.link.CreateLinkRequest;
import com.na22989.miniurl.model.dto.link.PageLinkRequest;
import com.na22989.miniurl.model.vo.link.LinkStatusVO;
import com.na22989.miniurl.model.vo.link.LinkVO;
import com.na22989.miniurl.service.LinkService;
import com.na22989.miniurl.service.StatusService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/link")
public class LinkController {

    private final LinkService linkService;

    private final StatusService statusService;

    /**
     * 创建短链接
     *
     * @param userId  创建者 ID（拦截器注入）
     * @param request 创建请求（长链接、可选过期时间）
     * @return 创建成功的短链接信息
     */
    @PostMapping("/create")
    public Result<LinkVO> createLink(@RequestAttribute("userId") Long userId,
                                     @Valid @RequestBody CreateLinkRequest request) {
        LinkVO linkVO = linkService.createLink(userId, request);
        return Result.success(linkVO);
    }

    /**
     * 分页查询当前用户的短链接列表
     *
     * @param request 分页参数
     * @param userId  用户 ID（拦截器注入）
     * @return 分页结果
     */
    @PostMapping("/list")
    public Result<PageResult<LinkVO>> listLinks(@RequestBody @Valid PageLinkRequest request,
                                               @RequestAttribute("userId") Long userId) {
        PageResult<LinkVO> linkVOPageResult = linkService.listUserLinks(userId, request);
        return Result.success(linkVOPageResult);
    }

    /**
     * 删除短链接（仅本人可删）
     *
     * @param userId  用户 ID（拦截器注入）
     * @param request 删除请求（含 linkId）
     * @return 统一结果
     * @throws BizException 不存在或非本人：LINK_NOT_FOUND
     */
    @DeleteMapping("/delete")
    public Result<Void> delete(@RequestAttribute("userId") Long userId,@RequestBody @Valid DeleteRequest request) {
        linkService.deleteLink(userId, request);
        return Result.success(null);
    }

    /**
     * 查询短链接详情（仅本人可见）
     *
     * @param linkId 链接 ID
     * @param userId 用户 ID（拦截器注入）
     * @return 短链接详情
     * @throws BizException 参数非法 BAD_REQUEST；不存在或非本人 LINK_NOT_FOUND
     */
    @GetMapping("/detail/{linkId}")
    public Result<LinkVO> getLinkDetail(@PathVariable Long linkId, @RequestAttribute("userId") Long userId) {
        LinkVO linkVO = linkService.getLinkDetail(userId, linkId);
        return Result.success(linkVO);
    }

    /**
     * 查询短链接访问统计
     *
     * @param linkId 链接 ID
     * @param userId 用户 ID（拦截器注入）
     * @return 访问统计
     * @throws BizException 参数非法 BAD_REQUEST；不存在或非本人 LINK_NOT_FOUND
     */
    @GetMapping("/status/{linkId}")
    public Result<LinkStatusVO> getLinkStatus(@PathVariable Long linkId, @RequestAttribute("userId") Long userId) {
        LinkStatusVO linkStatusVO = statusService.getLinkStatus(linkId, userId);
        return Result.success(linkStatusVO);
    }
}
