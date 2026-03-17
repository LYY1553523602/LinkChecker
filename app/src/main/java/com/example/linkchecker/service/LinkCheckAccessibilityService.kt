package com.example.linkchecker.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect          // 修复 boundsInScreen
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.linkchecker.MainActivity
import com.example.linkchecker.model.Platform
import java.io.File
import java.lang.StringBuilder

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

    // 必须实现的抽象方法（之前缺失导致编译失败）
    override fun onInterrupt() {}

    // 下面是你原有的所有函数（完全保留）
    private fun handleAutoClickDialogs(rootNode: AccessibilityNodeInfo) {
        val keywords = listOf("允许", "同意", "打开", "继续", "确认", "我知道了", "以后再说", "不再提示", "关闭", "Open", "Allow", "Accept", "Continue")
        findAndClickNodesByText(rootNode, keywords)
    }

    private fun findAndClickNodesByText(node: AccessibilityNodeInfo, keywords: List<String>) {
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        
        for (keyword in keywords) {
            if (text == keyword || desc == keyword) {
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

    // 5级引擎（用 Math.min 替换 minOf，避免 import 问题）
    private fun findInteraction(rootNode: AccessibilityNodeInfo?, keywords: List<String>, platform: String): String? {
        if (rootNode == null) return null
        val allNodes = rootNode.findAccessibilityNodeInfosByText("") ?: return null

        val ids = listOf("like", "digg", "zan", "comment", "share", "count")
        for (node in allNodes) {
            val id = node.viewIdResourceName ?: continue
            if (ids.any { id.contains(it, ignoreCase = true) }) return node.text?.toString()
        }

        for (node in allNodes) {
            val desc = node.contentDescription?.toString() ?: continue
            if (keywords.any { desc.contains(it) }) return node.text?.toString()
        }

        var result = ""
        traverseNode(rootNode) { text ->
            if (text.matches(Regex("\\d+(\\.\\d+)?[万k亿]?"))) result = text
        }
        if (result.isNotEmpty()) return result

        val container = findBottomLinear(rootNode)
        if (container != null) {
            for (j in 0 until Math.min(3, container.childCount)) {   // 用 Math.min
                val childText = container.getChild(j)?.text?.toString() ?: continue
                if (childText.isNotEmpty()) return childText
            }
        }

        result = ""
        traverseSiblings(rootNode) { text ->
            if (text.matches(Regex("\\d+[万k亿]?"))) result = text
        }
        if (result.isNotEmpty()) return result

        dumpAccessibilityTree(rootNode, platform)
        return null
    }

    // 下面 traverseNode、findBottomLinear 等函数保持不变（复制你原来的）
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
