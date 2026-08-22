package com.na22989.miniurl.controller;


import com.na22989.miniurl.common.ResultCodeEnum;
import com.na22989.miniurl.exception.BizException;
import com.na22989.miniurl.service.LinkService;
import com.na22989.miniurl.util.UrlSecurityUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

@RestController
@RequiredArgsConstructor
@Validated
public class RedirectController {

    private final LinkService linkService;

    @GetMapping("/s/{shortCode}")
    public void redirect(@PathVariable @Pattern(regexp = "^[0-9a-zA-Z]{1,11}$",
            message = "非法的短链") String shortCode, HttpServletResponse response, HttpServletRequest request) throws IOException {
        String targetUrl  = linkService.redirect(shortCode, request);
        // 输出侧兜底：重定向是 XSS 的最终出口，跳转前再校验一次协议
        if (!UrlSecurityUtil.isSafeHttpUrl(targetUrl)) {
            throw new BizException(ResultCodeEnum.LINK_NOT_FOUND);
        }
        response.sendRedirect(targetUrl);
    }
}
