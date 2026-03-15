package com.example.linkchecker.util

import com.example.linkchecker.model.LinkItem
import com.example.linkchecker.model.Platform
import java.util.regex.Pattern

object LinkExtractor {

    /**
     * 改进后的通用 URL 正则表达式
     * 1. 匹配 http/https 协议
     * 2. 匹配域名部分
     * 3. 匹配路径、查询参数等，直到遇到空白字符、中文或特定标点符号
     */
    private val URL_PATTERN = Pattern.compile(
        "(https?://[a-zA-Z0-9][-a-zA-Z0-9]*(\\.[a-zA-Z0-9][-a-zA-Z0-9]*)+(:[0-9]+)?(/[^\\s\\u4e00-\\u9fa5\\u3000-\\u303f\\uff00-\\uffef]*)?)",
        Pattern.CASE_INSENSITIVE
    )

    fun extractLinks(text: String): List<LinkItem> {
        val links = mutableListOf<LinkItem>()
        val foundUrls = mutableSetOf<String>()

        val matcher = URL_PATTERN.matcher(text)
        while (matcher.find()) {
            var url = matcher.group(1) ?: ""
            
            // 进一步清理 URL 末尾可能误匹配的标点符号（如句号、逗号、括号等）
            url = cleanUrl(url)
            
            if (url.isNotEmpty() && foundUrls.add(url)) {
                val platform = detectPlatform(url)
                links.add(LinkItem(url = url, platform = platform))
            }
        }

        return links
    }

    /**
     * 清理 URL 末尾的非字符标点，防止误匹配
     */
    private fun cleanUrl(url: String): String {
        var result = url
        val trailingChars = charArrayOf('.', ',', '!', '?', ';', ':', ')', ']', '}', '>', '\"', '\'')
        while (result.isNotEmpty() && trailingChars.contains(result.last())) {
            result = result.substring(0, result.length - 1)
        }
        return result
    }

    private fun detectPlatform(url: String): Platform {
        val lowerUrl = url.lowercase()
        return when {
            lowerUrl.contains("douyin.com") || lowerUrl.contains("iesdouyin.com") -> Platform.DOUYIN
            lowerUrl.contains("xiaohongshu.com") || lowerUrl.contains("xhslink.com") -> Platform.XIAOHONGSHU
            lowerUrl.contains("toutiao.com") -> Platform.TOUTIAO
            lowerUrl.contains("mp.weixin.qq.com") || lowerUrl.contains("weixin.qq.com") -> Platform.WECHAT
            lowerUrl.contains("weibo.com") || lowerUrl.contains("weibo.cn") -> Platform.WEIBO
            lowerUrl.contains("kuaishou.com") || lowerUrl.contains("chenzhongtech.com") -> Platform.KUAISHOU
            else -> Platform.UNKNOWN
        }
    }

    fun getPlatformName(platform: Platform): String {
        return when (platform) {
            Platform.DOUYIN -> "抖音"
            Platform.XIAOHONGSHU -> "小红书"
            Platform.TOUTIAO -> "今日头条"
            Platform.WECHAT -> "微信"
            Platform.WEIBO -> "微博"
            Platform.KUAISHOU -> "快手"
            Platform.UNKNOWN -> "未知"
        }
    }
}
