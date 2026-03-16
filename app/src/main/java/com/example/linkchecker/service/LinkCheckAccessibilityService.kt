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

    // ==================== V2.3 真实5级回退引擎（小白专用版）====================
private fun findInteraction(rootNode: AccessibilityNodeInfo, keywords: List<String>, platform: String): String? {
    // 5级超级容错查找（2026年最新适配）
    for (i in 0 until 5) {
        when (i) {
            0 -> { // 第1级：resource-id 模糊匹配
                val ids = listOf("like", "digg", "zan", "comment", "share", "count")
                rootNode.findAccessibilityNodeInfosByText("")?.forEach { node ->
                    val id = node.viewIdResourceName ?: ""
                    if (ids.any { id.contains(it) }) return node.text?.toString()
                }
            }
            1 -> { // 第2级：content-desc 包含关键词
                val nodes = rootNode.findAccessibilityNodeInfosByText("")
                nodes.forEach { node ->
                    val desc = node.contentDescription?.toString() ?: ""
                    if (keywords.any { desc.contains(it) }) return node.text?.toString()
                }
            }
            2 -> { // 第3级：数字正则（万/k/亿）
                traverseForRegex(rootNode) { text ->
                    if (Regex("\\d+(\\.\\d+)?[万k亿]?").containsMatchIn(text)) return text
                }
            }
            3 -> { // 第4级：底部LinearLayout的第1/2/3个子节点（点赞-评论-分享常见结构）
                findBottomLinear(rootNode)?.let { container ->
                    for (j in 0 until minOf(3, container.childCount)) {
                        val childText = container.getChild(j)?.text?.toString() ?: ""
                        if (childText.isNotEmpty()) return childText
                    }
                }
            }
            4 -> { // 第5级：兄弟节点遍历（最强保底）
                traverseSiblings(rootNode) { return it }
            }
        }
    }
    // 失败自动dump调试文件（关键！）
    dumpAccessibilityTree(rootNode, platform)
    return null
}

private fun traverseForRegex(node: AccessibilityNodeInfo, callback: (String) -> Unit) {
    val text = node.text?.toString() ?: ""
    if (text.isNotEmpty()) callback(text)
    for (i in 0 until node.childCount) node.getChild(i)?.let { traverseForRegex(it, callback) }
}

private fun findBottomLinear(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
    val list = mutableListOf<AccessibilityNodeInfo>()
    collectLinearLayouts(root, list)
    return list.lastOrNull { it.childCount >= 2 }
}

private fun collectLinearLayouts(node: AccessibilityNodeInfo, list: MutableList<AccessibilityNodeInfo>) {
    if (node.className?.toString()?.contains("LinearLayout") == true) list.add(node)
    for (i in 0 until node.childCount) node.getChild(i)?.let { collectLinearLayouts(it, list) }
}

private fun traverseSiblings(node: AccessibilityNodeInfo, callback: (String) -> Unit) {
    var p = node.parent
    while (p != null) {
        for (i in 0 until p.childCount) {
            val childText = p.getChild(i)?.text?.toString() ?: ""
            if (childText.isNotEmpty()) callback(childText)
        }
        p = p.parent
    }
}

private fun dumpAccessibilityTree(root: AccessibilityNodeInfo, platform: String) {
    val file = java.io.File("/sdcard/LinkChecker/debug/${platform}_${System.currentTimeMillis()}.txt")
    file.parentFile?.mkdirs()
    file.writeText(root.toString() + "\n\n=== 详细节点树 ===")
}

// 新统一解析函数（取代所有旧parseXXX）
private fun parseAnyPlatform(rootNode: AccessibilityNodeInfo, platform: Platform): FullResult? {
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

// 把下面所有旧的 parseDouyin / parseXiaohongshu 等函数全部替换成这一行调用：
private fun parseDouyin(rootNode: AccessibilityNodeInfo) = parseAnyPlatform(rootNode, Platform.DOUYIN)
private fun parseXiaohongshu(rootNode: AccessibilityNodeInfo) = parseAnyPlatform(rootNode, Platform.XIAOHONGSHU)
private fun parseToutiao(rootNode: AccessibilityNodeInfo) = parseAnyPlatform(rootNode, Platform.TOUTIAO)
private fun parseWechat(rootNode: AccessibilityNodeInfo) = parseAnyPlatform(rootNode, Platform.WECHAT)
private fun parseWeibo(rootNode: AccessibilityNodeInfo) = parseAnyPlatform(rootNode, Platform.WEIBO)
private fun parseKuaishou(rootNode: AccessibilityNodeInfo) = parseAnyPlatform(rootNode, Platform.KUAISHOU)
