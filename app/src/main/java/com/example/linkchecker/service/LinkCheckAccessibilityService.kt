package com.example.linkchecker.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.linkchecker.MainActivity
import com.example.linkchecker.model.Platform
import java.io.File
import java.lang.StringBuilder
import kotlin.concurrent.thread

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
        
        @Volatile
        var currentStage = "idle"  // 当前处理阶段
        
        @Volatile
        var stageStartTime = 0L    // 阶段开始时间
        
        fun reset() {
            isWaitingForResult = false
            currentPlatform = null
            lastResult = null
            currentStage = "idle"
            stageStartTime = 0L
        }
        
        fun startWaiting(platform: Platform) {
            currentPlatform = platform
            isWaitingForResult = true
            lastResult = null
            currentStage = "waiting_page_load"
            stageStartTime = System.currentTimeMillis()
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
    private var pollingThread: Thread? = null
    private var shouldStopPolling = false
    private val POLLING_INTERVAL_MS = 500L
    private val STAGE_TIMEOUT_MS = 8000L  // 每个阶段最多 8 秒
    private val MAX_RETRIES_PER_STAGE = 3

    // 布局指纹定义
    private val layoutFingerprints = mapOf(
        Platform.DOUYIN to listOf(
            setOf("video", "player", "播放"),  // 视频播放器
            setOf("like", "点赞", "赞"),       // 点赞区域
            setOf("comment", "评论"),          // 评论区域
            setOf("share", "分享", "转发")     // 分享区域
        ),
        Platform.XIAOHONGSHU to listOf(
            setOf("image", "图片", "content"), // 内容区域
            setOf("like", "点赞", "赞"),
            setOf("comment", "评论"),
            setOf("share", "分享")
        ),
        Platform.WEIBO to listOf(
            setOf("content", "内容", "微博"),
            setOf("like", "点赞", "赞"),
            setOf("comment", "评论"),
            setOf("share", "分享", "转发")
        ),
        Platform.TOUTIAO to listOf(
            setOf("article", "内容", "文章"),
            setOf("like", "点赞", "赞"),
            setOf("comment", "评论"),
            setOf("share", "分享")
        ),
        Platform.KUAISHOU to listOf(
            setOf("video", "播放", "视频"),
            setOf("like", "点赞", "赞"),
            setOf("comment", "评论"),
            setOf("share", "分享")
        ),
        Platform.WECHAT to listOf(
            setOf("content", "内容", "文章"),
            setOf("like", "点赞", "赞"),
            setOf("comment", "评论"),
            setOf("share", "分享")
        )
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        shouldStopPolling = false
        startPollingThread()
    }

    override fun onDestroy() {
        super.onDestroy()
        shouldStopPolling = true
        pollingThread?.join(1000)
    }

    private fun startPollingThread() {
        pollingThread = thread(isDaemon = true) {
            while (!shouldStopPolling) {
                try {
                    if (isWaitingForResult) {
                        val rootNode = rootInActiveWindow
                        if (rootNode != null) {
                            handleActivePolling(rootNode)
                        }
                    }
                    Thread.sleep(POLLING_INTERVAL_MS)
                } catch (e: Exception) {
                    logDebug("Polling error: ${e.message}")
                }
            }
        }
    }

    private fun handleActivePolling(rootNode: AccessibilityNodeInfo) {
        try {
            // 自动点击常见弹窗
            handleAutoClickDialogs(rootNode)

            // 检查是否超时
            val elapsedMs = System.currentTimeMillis() - stageStartTime
            if (elapsedMs > STAGE_TIMEOUT_MS) {
                logDebug("Stage timeout: $currentStage after ${elapsedMs}ms")
                handleStageTimeout()
                return
            }

            // 检查是否链接已失效
            if (checkIsInvalid(rootNode)) {
                logDebug("Invalid link detected")
                handleInvalid()
                return
            }

            // 尝试匹配布局指纹
            if (!isPageLoaded(rootNode)) {
                logDebug("Page not fully loaded, simulating scroll")
                simulateSwipe()
                return
            }

            // 尝试抓取数据
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
                logDebug("Data extraction successful: likes=${result.likesNumber}")
                handleSuccess(result)
            }
        } catch (e: Exception) {
            logDebug("Polling error: ${e.message}")
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val rootNode = rootInActiveWindow ?: return
        handleAutoClickDialogs(rootNode)
    }

    override fun onInterrupt() {}

    /**
     * 检查页面是否已加载完成（通过布局指纹）
     */
    private fun isPageLoaded(rootNode: AccessibilityNodeInfo): Boolean {
        if (currentPlatform == null) return false

        val fingerprints = layoutFingerprints[currentPlatform] ?: return true
        
        // 检查是否至少有一个指纹特征被匹配
        val matchedFeatures = mutableSetOf<Int>()
        
        traverseNode(rootNode) { node ->
            val text = (node.text?.toString() ?: "").lowercase()
            val desc = (node.contentDescription?.toString() ?: "").lowercase()
            val id = (node.viewIdResourceName ?: "").lowercase()
            
            fingerprints.forEachIndexed { index, keywords ->
                if (keywords.any { keyword ->
                    text.contains(keyword) || desc.contains(keyword) || id.contains(keyword)
                }) {
                    matchedFeatures.add(index)
                }
            }
        }

        // 如果至少匹配了 2 个特征，认为页面已加载
        val isLoaded = matchedFeatures.size >= 2
        logDebug("Page load check: matched ${matchedFeatures.size}/${fingerprints.size} features")
        return isLoaded
    }

    private fun handleStageTimeout() {
        // 尝试模拟刷新或重新加载
        simulatePullToRefresh()
        stageStartTime = System.currentTimeMillis()  // 重置计时器
    }

    private fun simulatePullToRefresh() {
        val path = Path()
        path.moveTo(500f, 300f)
        path.lineTo(500f, 1200f)
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 800))
            .build()
        dispatchGesture(gesture, null, null)
    }

    private fun handleAutoClickDialogs(rootNode: AccessibilityNodeInfo) {
        val keywords = listOf(
            "允许", "同意", "打开", "继续", "确认", "我知道了", "以后再说", 
            "不再提示", "关闭", "跳过", "知道了", "继续观看",
            "Open", "Allow", "Accept", "Continue", "Skip"
        )
        findAndClickNodesByText(rootNode, keywords)
    }

    private fun findAndClickNodesByText(node: AccessibilityNodeInfo, keywords: List<String>) {
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""

        for (keyword in keywords) {
            if (text.equals(keyword, ignoreCase = true) || desc.equals(keyword, ignoreCase = true)) {
                if (node.isClickable) {
                    try {
                        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        logDebug("Auto-clicked dialog: $keyword")
                        return
                    } catch (e: Exception) {}
                }
                var parent = node.parent
                while (parent != null) {
                    if (parent.isClickable) {
                        try {
                            parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            logDebug("Auto-clicked dialog (parent): $keyword")
                            return
                        } catch (e: Exception) {}
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
        val invalidKeywords = listOf(
            "链接已失效", "内容已被删除", "页面不存在", "404", 
            "Invalid Link", "Deleted", "已过期", "不存在"
        )
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

    private fun handleSuccess(result: FullResult) {
        lastResult = result
        isWaitingForResult = false
        sendResultBroadcast()
    }

    private fun simulateSwipe() {
        val path = Path()
        path.moveTo(500f, 1500f)
        path.lineTo(500f, 300f)
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 500))
            .build()
        dispatchGesture(gesture, null, null)
    }

    private fun sendResultBroadcast() {
        val intent = Intent("com.example.linkchecker.LIKE_RESULT")
        intent.putExtra("likes", lastResult?.likes)
        intent.putExtra("likesNumber", lastResult?.likesNumber ?: -1)
        intent.putExtra("comments", lastResult?.comments)
        intent.putExtra("shares", lastResult?.shares)
        intent.putExtra("isAboveThreshold", lastResult?.isAboveThreshold ?: false)
        intent.putExtra("isInvalid", lastResult?.isInvalid ?: false)
        sendBroadcast(intent)
    }

    /**
     * 5级引擎：多维度回退匹配
     */
    private fun findInteraction(rootNode: AccessibilityNodeInfo?, keywords: List<String>, platform: String): String? {
        if (rootNode == null) return null

        // 第1级：通过 resource-id 匹配
        val ids = listOf("like", "digg", "zan", "comment", "share", "count", "点赞", "评论", "分享")
        val allNodes = rootNode.findAccessibilityNodeInfosByText("") ?: return null

        for (node in allNodes) {
            val id = (node.viewIdResourceName ?: "").lowercase()
            if (ids.any { id.contains(it.lowercase()) }) {
                val text = node.text?.toString()
                if (text != null && text.isNotEmpty()) {
                    logDebug("Found via resource-id: $text")
                    return text
                }
            }
        }

        // 第2级：通过 contentDescription 匹配
        for (node in allNodes) {
            val desc = (node.contentDescription?.toString() ?: "").lowercase()
            if (keywords.any { desc.contains(it.lowercase()) }) {
                val text = node.text?.toString()
                if (text != null && text.isNotEmpty()) {
                    logDebug("Found via contentDescription: $text")
                    return text
                }
            }
        }

        // 第3级：通过正则匹配数字
        var result = ""
        traverseNode(rootNode) { text ->
            if (text.matches(Regex("\\d+(\\.\\d+)?[万k亿]?"))) {
                result = text
            }
        }
        if (result.isNotEmpty()) {
            logDebug("Found via regex: $result")
            return result
        }

        // 第4级：通过容器的子元素
        val container = findBottomLinear(rootNode)
        if (container != null) {
            for (j in 0 until kotlin.math.min(3, container.childCount)) {
                val childText = container.getChild(j)?.text?.toString()
                if (childText != null && childText.isNotEmpty()) {
                    logDebug("Found via container: $childText")
                    return childText
                }
            }
        }

        // 第5级：通过兄弟节点
        result = ""
        traverseSiblings(rootNode) { text ->
            if (text.matches(Regex("\\d+[万k亿]?"))) {
                result = text
            }
        }
        if (result.isNotEmpty()) {
            logDebug("Found via siblings: $result")
            return result
        }

        logDebug("No match found for keywords: $keywords")
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
        try {
            val dir = File("/sdcard/LinkChecker/debug")
            dir.mkdirs()
            val file = File(dir, "${platform}_${System.currentTimeMillis()}.txt")
            val sb = StringBuilder()
            dumpNode(root, sb, 0)
            file.writeText(sb.toString())
        } catch (e: Exception) {}
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
        val kwLike = listOf("赞", "点赞", "like", "digg", "zan")
        val kwComment = listOf("评论", "comment", "留言")
        val kwShare = listOf("分享", "share", "转发", "转发")

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
        val cleaned = str.replace(Regex("[万k亿]"), "")
        val base = cleaned.toIntOrNull() ?: return -1
        return when {
            str.contains("万") -> base * 10000
            str.contains("k") || str.contains("K") -> base * 1000
            str.contains("亿") -> base * 100000000
            else -> base
        }
    }

    private fun parseDouyin(rootNode: AccessibilityNodeInfo?) = parseAnyPlatform(rootNode, Platform.DOUYIN)
    private fun parseXiaohongshu(rootNode: AccessibilityNodeInfo?) = parseAnyPlatform(rootNode, Platform.XIAOHONGSHU)
    private fun parseToutiao(rootNode: AccessibilityNodeInfo?) = parseAnyPlatform(rootNode, Platform.TOUTIAO)
    private fun parseWechat(rootNode: AccessibilityNodeInfo?) = parseAnyPlatform(rootNode, Platform.WECHAT)
    private fun parseWeibo(rootNode: AccessibilityNodeInfo?) = parseAnyPlatform(rootNode, Platform.WEIBO)
    private fun parseKuaishou(rootNode: AccessibilityNodeInfo?) = parseAnyPlatform(rootNode, Platform.KUAISHOU)

    private fun logDebug(message: String) {
        try {
            val dir = File("/sdcard/LinkChecker/logs")
            dir.mkdirs()
            val file = File(dir, "debug_${System.currentTimeMillis() / 1000}.log")
            file.appendText("${System.currentTimeMillis()}: $message\n")
        } catch (e: Exception) {}
    }
}
