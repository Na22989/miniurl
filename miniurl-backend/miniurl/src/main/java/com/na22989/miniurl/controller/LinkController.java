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

    @PostMapping("/create")
    public Result<LinkVO> createLink(@RequestAttribute("userId") Long userId,@Valid @RequestBody CreateLinkRequest request) {
        LinkVO linkVO = linkService.createLink(userId, request);
        return Result.success(linkVO);
    }

    @PostMapping("/list")
    public Result<PageResult<LinkVO>> listLinks(@RequestBody @Valid PageLinkRequest request,
                                               @RequestAttribute("userId") Long userId) {
        PageResult<LinkVO> linkVOPageResult = linkService.listUserLinks(userId, request);
        return Result.success(linkVOPageResult);
    }

    @DeleteMapping("/delete")
    public Result<Void> delete(@RequestAttribute("userId") Long userId,@RequestBody @Valid DeleteRequest request) {
        linkService.deleteLink(userId, request);
        return Result.success(null);
    }

    @GetMapping("/detail/{linkId}")
    public Result<LinkVO> getLinkDetail(@PathVariable Long linkId, @RequestAttribute("userId") Long userId) {
        LinkVO linkVO = linkService.getLinkDetail(userId, linkId);
        return Result.success(linkVO);
    }

    @GetMapping("/status/{linkId}")
    public Result<LinkStatusVO> getLinkStatus(@PathVariable Long linkId, @RequestAttribute("userId") Long userId) {
        LinkStatusVO linkStatusVO = statusService.getLinkStatus(linkId, userId);
        return Result.success(linkStatusVO);
    }
}
