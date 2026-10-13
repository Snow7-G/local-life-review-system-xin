package com.hmdp.service.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlogContentSanitizerTest {
    @Test
    void retainsBasicFormatting() {
        String html = BlogServiceImpl.sanitizeContent("<p>探店<br>记录</p>");
        assertTrue(html.contains("探店"));
        assertTrue(html.contains("<br"));
        assertTrue(html.contains("记录"));
    }

    @Test
    void removesExecutableMarkupFromExistingOrNewBlogs() {
        String html = BlogServiceImpl.sanitizeContent(
                "<img src=x onerror=alert(1)><a href=javascript:alert(1)>链接</a><script>alert(1)</script>");
        assertFalse(html.contains("<img"));
        assertFalse(html.contains("onerror"));
        assertFalse(html.contains("javascript:"));
        assertFalse(html.contains("<script"));
        assertTrue(html.contains("链接"));
    }
}
