package com.example.linkchecker.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.linkchecker.MainActivity
import com.example.linkchecker.model.Platform

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
        
        // 1. 自动点击“同意/打开”弹窗 (V2.1 强化版)
        handleAutoClickDialogs(rootNode)

        if (!isWaitingForResult || currentPlatform == null) return

        // 限制处理频率，避免过度消耗资源
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastEventTime < 500) return
        lastEventTime = currentTime

        try {
            // 2. 检查链接是否失效
            if (checkIsInvalid(rootNode)) {
                handleInvalid()
                return
            }

            // 3. 尝试抓取数据 (V2.1 多维容错引擎)
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
                // 如果没抓到，尝试模拟滑动唤醒 UI
                if (currentTime % 5000 < 1000) {
                    simulateSwipe()
                }
            }
        } catch (e: Exception) {
            // 忽略异常
        }
    }

    private fun handleAutoClickDialogs(rootNode: AccessibilityNodeInfo) {
        val keywords = listOf("允许", "同意", "打开", "继续", "确认", "我知道了", "以后再说", "不再提示", "关闭", "Open", "Allow", "Accept", "Continue")
        findAndClickNodesByText(rootNode, keywords)
    }

    private fun findAndClickNodesByText(node: AccessibilityNodeInfo, keywords: List<String>) {
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        
        for (keyword in keywords) {
            if (text == keyword || desc == keyword) { // 精确匹配优先
                if (node.isClickable) {
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    return
                }
                var parent = node.parent
                while (parent != null) {
                    if (parent.isClickable) {
                        parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        return
                    }
                    parent = parent.parent
                }
            }
        }
        
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findAndClickNodesByText(child, keywords)
        }
    }

    private fun checkIsInvalid(rootNode: AccessibilityNodeInfo): Boolean {
        val invalidKeywords = listOf("链接已失效", "内容已被删除", "页面不存在", "404", "Invalid Link", "Deleted")
        return findTextInNode(rootNode, invalidKeywords)
    }

    private fun findTextInNode(node: AccessibilityNodeInfo, keywords: List<String>): Boolean {
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        for (keyword in keywords) {
            if (text.contains(keyword) || desc.contains(keyword)) return true
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (findTextInNode(child, keywords)) return true
        }
        return false
    }

    private fun handleInvalid() {
        lastResult = FullResult(isInvalid = true)
        isWaitingForResult = false
        sendResultBroadcast()
    }

// ==================== V2.6 最终简化 + 编译安全版 ====================
import kotlin.math.minOf
import java.io.File

private fun findInteraction(rootNode: AccessibilityNodeInfo?, keywords: List<String>, platform: String): String? {
    if (rootNode == null) return null
    val allNodes = rootNode.findAccessibilityNodeInfosByText("") ?: return null

    // 1. resource-id 匹配
    val ids = listOf("like", "digg", "zan", "comment", "share", "count")
    for (node in allNodes) {
        val id = node.viewIdResourceName ?: continue
        if (ids.any { id.contains(it, ignoreCase = true) }) return node.text?.toString()
    }

    // 2. content-desc 匹配
    for (node in allNodes) {
        val desc = node.contentDescription?.toString() ?: continue
        if (keywords.any { desc.contains(it) }) return node.text?.toString()
    }

    // 3. 数字 regex 匹配
    var result = ""
    traverseNode(rootNode) { text ->
        if (text.matches(Regex("\\d+(\\.\\d+)?[万k亿]?")) ) result = text
    }
    if (result.isNotEmpty()) return result

    // 4. 底部 LinearLayout 子节点
    val container = findBottomLinear(rootNode)
    if (container != null) {
        for (j in 0 until minOf(3, container.childCount)) {
            val childText = container.getChild(j)?.text?.toString() ?: continue
            if (childText.isNotEmpty()) return childText
        }
    }

    // 5. 兄弟节点遍历
    result = ""
    traverseSiblings(rootNode) { text ->
        if (text.matches(Regex("\\d+[万k亿]?")) ) result = text
    }
    if (result.isNotEmpty()) return result

    dumpAccessibilityTree(rootNode, platform)
    return null
}

private fun traverseNode(node: AccessibilityNodeInfo?, callback: (String) -> Unit) {
    if (node == null) return
    val text = node.text?.toString() ?: ""
    if (text.isNotEmpty()) callback(text)
    for (i in 0 until node.childCount) traverseNode(node.getChild(i), callback)
}

private fun findBottomLinear(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
    if (root == null) return null
    val list = mutableListOf<AccessibilityNodeInfo>()
    collectLinearLayouts(root, list)
    return list.lastOrNull { it.childCount >= 2 }
}

private fun collectLinearLayouts(node: AccessibilityNodeInfo?, list: MutableList<AccessibilityNodeInfo>) {
    if (node == null) return
    if (node.className?.contains("LinearLayout") == true) list.add(node)
    for (i in 0 until node.childCount) collectLinearLayouts(node.getChild(i), list)
}

private fun traverseSiblings(node: AccessibilityNodeInfo?, callback: (String) -> Unit) {
    if (node == null) return
    var p = node.parent
    while (p != null) {
        for (i in 0 until p.childCount) {
            val childText = p.getChild(i)?.text?.toString() ?: continue
            if (childText.isNotEmpty()) callback(childText)
        }
        p = p.parent
    }
}

private fun dumpAccessibilityTree(root: AccessibilityNodeInfo?, platform: String) {
    if (root == null) return
    val dir = File("/sdcard/LinkChecker/debug")
    dir.mkdirs()
    val file = File(dir, "${platform}_${System.currentTimeMillis()}.txt")
    val sb = StringBuilder()
    dumpNode(root, sb, 0)
    file.writeText(sb.toString())
}

private fun dumpNode(node: AccessibilityNodeInfo, sb: StringBuilder, depth: Int) {
    sb.append("  ".repeat(depth))
        .append("class=").append(node.className ?: "null")
        .append(", text=").append(node.text ?: "null")
        .append(", desc=").append(node.contentDescription ?: "null")
        .append(", id=").append(node.viewIdResourceName ?: "null")
        .append(", bounds=").append(node.boundsInScreen)
        .append("\n")
    for (i in 0 until node.childCount) {
        node.getChild(i)?.let { dumpNode(it, sb, depth + 1) }
    }
}

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

private fun parseDouyin(rootNode: AccessibilityNodeInfo?) = parseAnyPlatform(rootNode, Platform.DOUYIN)
private fun parseXiaohongshu(rootNode: AccessibilityNodeInfo?) = parseAnyPlatform(rootNode, Platform.XIAOHONGSHU)
private fun parseToutiao(rootNode: AccessibilityNodeInfo?) = parseAnyPlatform(rootNode, Platform.TOUTIAO)
private fun parseWechat(rootNode: AccessibilityNodeInfo?) = parseAnyPlatform(rootNode, Platform.WECHAT)
private fun parseWeibo(rootNode: AccessibilityNodeInfo?) = parseAnyPlatform(rootNode, Platform.WEIBO)
private fun parseKuaishou(rootNode: AccessibilityNodeInfo?) = parseAnyPlatform(rootNode, Platform.KUAISHOU)
