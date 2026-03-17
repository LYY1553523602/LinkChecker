package com.example.linkchecker.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect  // 修复 boundsInScreen
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.linkchecker.MainActivity
import com.example.linkchecker.model.Platform
import java.io.File
import java.lang.StringBuilder

// 避免 minOf import 问题，用 Math.min 替代
import kotlin.math.min as mathMin  // 可选，如果 minOf 仍报错，用 mathMin

class LinkCheckAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: LinkCheckAccessibilityService? = null
        
        @Volatile
        var isWaitingForResult = false
        
        @Volatile
        var currentPlatform: Platform? = null
        
        @Volatile
        var lastResult: FullResult? = null
        
        fun reset() {
            isWaitingForResult = false
            currentPlatform = null
            lastResult = null
        }
        
        fun startWaiting(platform: Platform) {
            currentPlatform = platform
            isWaitingForResult = true
            lastResult = null
        }
    }

    data class FullResult(
        val fans: String? = null,
        val likes: String? = null,
        val likesNumber: Int = -1,
        val comments: String? = null,
        val shares: String? = null,
        val isAboveThreshold: Boolean = false,
        val isInvalid: Boolean = false
    )

    private val handler = Handler(Looper.getMainLooper())
    private var lastEventTime = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val rootNode = rootInActiveWindow ?: return
        
        handleAutoClickDialogs(rootNode)
        
        if (!isWaitingForResult || currentPlatform == null) return
        
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastEventTime < 500) return
        lastEventTime = currentTime
        
        try {
            if (checkIsInvalid(rootNode)) {
                handleInvalid()
                return
            }
            
            val result = when (currentPlatform) {
                Platform.DOUYIN -> parseDouyin(rootNode)
                Platform.XIAOHONGSHU -> parseXiaohongshu(rootNode)
                Platform.TOUTIAO -> parseToutiao(rootNode)
                Platform.WECHAT -> parseWechat(rootNode)
                Platform.WEIBO -> parseWeibo(rootNode)
                Platform.KUAISHOU -> parseKuaishou(rootNode)
                else -> null
            }
            
            if (result != null) {
                handleSuccess(result)
            } else {
                simulateSwipe()
            }
        } catch (e: Exception) {}
    }

    override fun onInterrupt() {
        // 必须实现，空实现即可
    }

    // 其余函数保持不变（handleAutoClickDialogs, findAndClickNodesByText 等）
    // ... (复制你原有这些函数)

    // 5级引擎 - 用 Math.min 替代 minOf，避免 import 问题
    private fun findInteraction(rootNode: AccessibilityNodeInfo?, keywords: List<String>, platform: String): String? {
        if (rootNode == null) return null
        val allNodes = rootNode.findAccessibilityNodeInfosByText("") ?: return null

        // 1. resource-id
        val ids = listOf("like", "digg", "zan", "comment", "share", "count")
        for (node in allNodes) {
            val id = node.viewIdResourceName ?: continue
            if (ids.any { id.contains(it, ignoreCase = true) }) return node.text?.toString()
        }

        // 2. content-desc
        for (node in allNodes) {
            val desc = node.contentDescription?.toString() ?: continue
            if (keywords.any { desc.contains(it) }) return node.text?.toString()
        }

        // 3. 数字 regex
        var result = ""
        traverseNode(rootNode) { text ->
            if (text.matches(Regex("\\d+(\\.\\d+)?[万k亿]?"))) result = text
        }
        if (result.isNotEmpty()) return result

        // 4. 底部容器
        val container = findBottomLinear(rootNode)
        if (container != null) {
            for (j in 0 until Math.min(3, container.childCount)) {  // 用 Math.min
                val childText = container.getChild(j)?.text?.toString() ?: continue
                if (childText.isNotEmpty()) return childText
            }
        }

        // 5. 兄弟节点
        result = ""
        traverseSiblings(rootNode) { text ->
            if (text.matches(Regex("\\d+[万k亿]?"))) result = text
        }
        if (result.isNotEmpty()) return result

        dumpAccessibilityTree(rootNode, platform)
        return null
    }

    // 其余 traverseNode, findBottomLinear, collectLinearLayouts, traverseSiblings, dumpAccessibilityTree, dumpNode 函数保持不变

    private fun parseAnyPlatform(rootNode: AccessibilityNodeInfo?, platform: Platform): FullResult? {
        if (rootNode == null) return null
        val kwLike = listOf("赞", "点赞", "like", "digg")
        val kwComment = listOf("评论", "comment")
        val kwShare = listOf("分享", "share", "转发")

        val likes = findInteraction(rootNode, kwLike, platform.name)
        val comments = findInteraction(rootNode, kwComment, platform.name)
        val shares = findInteraction(rootNode, kwShare, platform.name)

        if (likes != null) {
            val num = parseLikeNumber(likes)
            return FullResult(likes = likes, likesNumber = num, comments = comments, shares = shares, isAboveThreshold = num >= 50)
        }
        return null
    }

    private fun parseLikeNumber(str: String): Int {
        return str.replace(Regex("[万k亿]"), "").toIntOrNull() ?: -1
    }

    private fun parseDouyin(rootNode: AccessibilityNodeInfo?) = parseAnyPlatform(rootNode, Platform.DOUYIN)
    private fun parseXiaohongshu(rootNode: AccessibilityNodeInfo?) = parseAnyPlatform(rootNode, Platform.XIAOHONGSHU)
    private fun parseToutiao(rootNode: AccessibilityNodeInfo?) = parseAnyPlatform(rootNode, Platform.TOUTIAO)
    private fun parseWechat(rootNode: AccessibilityNodeInfo?) = parseAnyPlatform(rootNode, Platform.WECHAT)
    private fun parseWeibo(rootNode: AccessibilityNodeInfo?) = parseAnyPlatform(rootNode, Platform.WEIBO)
    private fun parseKuaishou(rootNode: AccessibilityNodeInfo?) = parseAnyPlatform(rootNode, Platform.KUAISHOU)
}
