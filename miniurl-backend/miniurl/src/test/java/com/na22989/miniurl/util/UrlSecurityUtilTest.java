package com.na22989.miniurl.util;

import com.na22989.miniurl.validator.HttpUrlValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("URL 安全校验单元测试")
class UrlSecurityUtilTest {

    @ParameterizedTest(name = "安全 URL 应通过: {0}")
    @ValueSource(strings = {
            "https://example.com",
            "http://example.com",
            "http://example.com/a/b?x=1#y",
            "https://sub.domain.co.uk/path",
            "https://example.com:8080/path",
            "HTTP://EXAMPLE.COM",
            "https://user:pass@example.com",
            "  https://example.com  "
    })
    void isSafeHttpUrl_shouldAccept(String url) {
        assertTrue(UrlSecurityUtil.isSafeHttpUrl(url), "应判为安全但被拒绝: " + url);
    }

    @ParameterizedTest(name = "危险 URL 应拒绝: {0}")
    @ValueSource(strings = {
            "javascript:alert(1)",
            "JAVASCRIPT:alert(1)",
            "data:text/html,<script>alert(1)</script>",
            "vbscript:msgbox(1)",
            "file:///etc/passwd",
            "//evil.com",
            "ftp://example.com",
            "http://",
            "https://",
            "https://exa\nmple.com",
            "https://example.com\r\nSet-Cookie:evil"
    })
    void isSafeHttpUrl_shouldReject(String url) {
        assertFalse(UrlSecurityUtil.isSafeHttpUrl(url), "应判为危险但被放行: " + url);
    }

    @Test
    @DisplayName("null 或空字符串应拒绝")
    void isSafeHttpUrl_shouldRejectNullAndBlank() {
        assertFalse(UrlSecurityUtil.isSafeHttpUrl(null));
        assertFalse(UrlSecurityUtil.isSafeHttpUrl(""));
        assertFalse(UrlSecurityUtil.isSafeHttpUrl("   "));
    }

    @Test
    @DisplayName("HttpUrlValidator 应委托 UrlSecurityUtil（危险输入拒绝）")
    void httpUrlValidator_shouldRejectDangerousScheme() {
        HttpUrlValidator validator = new HttpUrlValidator();
        assertFalse(validator.isValid("javascript:alert(1)", null));
        assertTrue(validator.isValid("https://example.com", null));
    }

    @Test
    @DisplayName("HttpUrlValidator 判空应放行（交给 @NotBlank）")
    void httpUrlValidator_shouldAllowBlank() {
        HttpUrlValidator validator = new HttpUrlValidator();
        assertTrue(validator.isValid("", null));
        assertTrue(validator.isValid(null, null));
    }
}
