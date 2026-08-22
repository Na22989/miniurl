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

    LinkVO createLink(Long userId, CreateLinkRequest request);

    String redirect(String shortCode, HttpServletRequest request) ;

    PageResult<LinkVO> listUserLinks(Long userId, PageLinkRequest request);

    void deleteLink(Long userId, DeleteRequest request);

    LinkVO getLinkDetail(Long userId, Long linkId);

    int batchUpdateClickCount(List<SyncClickCounts2DBTask.ClickSyncDTO> updates);


}
