package com.example.linkchecker.util

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.example.linkchecker.R
import com.example.linkchecker.model.CheckStatus
import com.example.linkchecker.model.LinkItem

class LinkAdapter(private val links: List<LinkItem>) : 
    RecyclerView.Adapter<LinkAdapter.LinkViewHolder>() {

    class LinkViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val textPlatform: TextView = itemView.findViewById(R.id.textPlatform)
        val textUrl: TextView = itemView.findViewById(R.id.textUrl)
        val textStatus: TextView = itemView.findViewById(R.id.textStatus)
        val textLikes: TextView = itemView.findViewById(R.id.textLikes)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LinkViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_link, parent, false)
        return LinkViewHolder(view)
    }

    override fun onBindViewHolder(holder: LinkViewHolder, position: Int) {
        val link = links[position]
        val context = holder.itemView.context

        holder.textPlatform.text = "[${LinkExtractor.getPlatformName(link.platform)}]"
        holder.textUrl.text = link.url

        when (link.status) {
            CheckStatus.PENDING -> {
                holder.textStatus.text = "待检测"
                holder.textStatus.setTextColor(ContextCompat.getColor(context, R.color.black))
                holder.textLikes.visibility = View.GONE
            }
            CheckStatus.CHECKING -> {
                holder.textStatus.text = "检测中..."
                holder.textStatus.setTextColor(ContextCompat.getColor(context, R.color.orange))
                holder.textLikes.visibility = View.GONE
            }
            CheckStatus.SUCCESS -> {
                holder.textStatus.text = if (link.isAboveThreshold) "✓ 达标" else "✗ 未达标"
                holder.textStatus.setTextColor(
                    if (link.isAboveThreshold) 
                        ContextCompat.getColor(context, R.color.green)
                    else 
                        ContextCompat.getColor(context, R.color.red)
                )
                holder.textLikes.text = "点赞: ${link.likes}"
                holder.textLikes.visibility = View.VISIBLE
            }
            CheckStatus.FAILED -> {
                holder.textStatus.text = "检测失败"
                holder.textStatus.setTextColor(ContextCompat.getColor(context, R.color.red))
                holder.textLikes.visibility = View.GONE
            }
            CheckStatus.TIMEOUT -> {
                holder.textStatus.text = "超时"
                holder.textStatus.setTextColor(ContextCompat.getColor(context, R.color.red))
                holder.textLikes.visibility = View.GONE
            }
            CheckStatus.INVALID -> {
                holder.textStatus.text = "链接失效"
                holder.textStatus.setTextColor(ContextCompat.getColor(context, R.color.red))
                holder.textLikes.visibility = View.GONE
            }
        }
    }

    override fun getItemCount(): Int = links.size
}
