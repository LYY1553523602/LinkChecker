package com.example.linkchecker

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.linkchecker.model.LinkItem
import com.example.linkchecker.util.LinkAdapter
import com.example.linkchecker.util.LinkExtractor

class ResultActivity : AppCompatActivity() {

    private lateinit var linkAdapter: LinkAdapter
    private var linkList = mutableListOf<LinkItem>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_result)

        val links = intent.getParcelableArrayListExtra<LinkItem>("links")
        if (links != null) {
            linkList.addAll(links)
        }

        setupRecyclerView()
        setupButtons()
        updateSummary()
    }

    private fun setupRecyclerView() {
        val recyclerView = findViewById<RecyclerView>(R.id.recyclerViewResults)
        linkAdapter = LinkAdapter(linkList)
        recyclerView.apply {
            layoutManager = LinearLayoutManager(this@ResultActivity)
            adapter = linkAdapter
        }
    }

    private fun setupButtons() {
        val buttonCopyReport = findViewById<Button>(R.id.buttonCopyReport)
        val buttonBack = findViewById<Button>(R.id.buttonBack)

        buttonCopyReport.setOnClickListener {
            copyReportToClipboard()
        }

        buttonBack.setOnClickListener {
            finish()
        }
    }

    private fun updateSummary() {
        val textViewSummary = findViewById<TextView>(R.id.textViewSummary)
        val total = linkList.size
        val aboveThreshold = linkList.count { it.isAboveThreshold }
        val invalid = linkList.count { it.isInvalid }
        
        textViewSummary.text = "总计: $total | 达标(≥50): $aboveThreshold | 失效: $invalid"
    }

    private fun copyReportToClipboard() {
        val report = generateReport()
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("检测报告", report)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "报告已复制到剪贴板", Toast.LENGTH_SHORT).show()
    }

    private fun generateReport(): String {
        val sb = StringBuilder()
        sb.append("--- 链接点赞检测报告 ---\n\n")
        
        linkList.forEachIndexed { index, item ->
            val platformName = LinkExtractor.getPlatformName(item.platform)
            val author = item.author ?: "未知发布人"
            val title = item.title ?: "无标题"
            
            sb.append("${index + 1}.${platformName}“${author}”：${title}\n")
            
            if (item.isInvalid) {
                sb.append("【链接已失效】\n")
            } else {
                val fansStr = if (item.fans != null) " (粉丝量 ${item.fans})" else ""
                val shares = item.shares ?: "0"
                val comments = item.comments ?: "0"
                val likes = item.likes ?: "0"
                
                sb.append("【转发:${shares} 评论:${comments} 点赞:${likes}】${fansStr}\n")
                
                if (item.isAboveThreshold) {
                    sb.append("★ 达标链接 (点赞≥50)\n")
                }
            }
            
            sb.append("${item.url}\n\n")
        }
        
        val aboveThresholdLinks = linkList.filter { it.isAboveThreshold }
        if (aboveThresholdLinks.isNotEmpty()) {
            sb.append("--- 达标链接汇总 ---\n")
            aboveThresholdLinks.forEach {
                sb.append("${it.url}\n")
            }
        } else {
            sb.append("没有发现点赞超过 50 的链接。\n")
        }
        
        return sb.toString()
    }
}
