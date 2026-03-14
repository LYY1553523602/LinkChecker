package com.example.linkchecker

import android.os.Bundle
import android.view.MenuItem
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.linkchecker.model.LinkItem
import com.example.linkchecker.util.LinkAdapter

class ResultActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_result)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "检测结果"

        val links = intent.getParcelableArrayListExtra<LinkItem>("links") ?: arrayListOf()
        
        val textViewSummary = findViewById<TextView>(R.id.textViewSummary)
        val recyclerView = findViewById<RecyclerView>(R.id.recyclerViewResults)

        // 统计
        val total = links.size
        val above50 = links.count { it.isAboveThreshold }
        val failed = links.count { it.status.name == "FAILED" || it.status.name == "TIMEOUT" }

        textViewSummary.text = buildString {
            append("总计: $total 个链接\n")
            append("点赞≥50: $above50 个\n")
            append("检测失败: $failed 个")
        }

        // 只显示达标的链接
        val aboveThresholdLinks = links.filter { it.isAboveThreshold }
        
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = LinkAdapter(aboveThresholdLinks)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
