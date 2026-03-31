package com.github.swerchansky.vkturnproxy.ui.logs

import android.content.Context
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.github.swerchansky.vkturnproxy.R

class LogLineAdapter(
    context: Context,
    private val onLongPress: (String) -> Unit,
) : RecyclerView.Adapter<LogLineAdapter.ViewHolder>() {

    private val colorSuccess = ContextCompat.getColor(context, R.color.log_success)
    private val colorError = ContextCompat.getColor(context, R.color.log_error)
    private val colorInfo = ContextCompat.getColor(context, R.color.log_info)
    private val colorWarning = ContextCompat.getColor(context, R.color.log_warning)
    private val colorTimestamp = ContextCompat.getColor(context, R.color.log_timestamp)
    private val colorDefault = ContextCompat.getColor(context, R.color.log_default)

    private var items: List<LogLine> = emptyList()
    var showTimestamps: Boolean = true
    var wordWrap: Boolean = true

    class ViewHolder(val textView: TextView) : RecyclerView.ViewHolder(textView)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val tv = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_log_line, parent, false) as TextView
        return ViewHolder(tv)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val line = items[position]
        holder.textView.maxLines = if (wordWrap) Int.MAX_VALUE else 1
        holder.textView.ellipsize = if (wordWrap) null else android.text.TextUtils.TruncateAt.END
        holder.textView.text = buildSpanned(line)
        holder.textView.setOnLongClickListener {
            onLongPress(line.raw)
            true
        }
    }

    override fun getItemCount() = items.size

    fun submitList(newItems: List<LogLine>) {
        val prevSize = items.size
        items = newItems
        when {
            newItems.isEmpty() -> notifyDataSetChanged()
            newItems.size > prevSize ->
                notifyItemRangeInserted(prevSize, newItems.size - prevSize)
            else -> notifyDataSetChanged()
        }
    }

    fun updateDisplayOptions(showTimestamps: Boolean, wordWrap: Boolean) {
        val changed = this.showTimestamps != showTimestamps || this.wordWrap != wordWrap
        this.showTimestamps = showTimestamps
        this.wordWrap = wordWrap
        if (changed) notifyItemRangeChanged(0, items.size)
    }

    private fun buildSpanned(line: LogLine): SpannableString {
        val display = if (!showTimestamps && line.raw.startsWith("[") && line.raw.contains("] ")) {
            line.raw.substringAfter("] ")
        } else {
            line.raw
        }

        val ss = SpannableString(display)
        val lineColor = when (line.level) {
            LogLevel.SUCCESS -> colorSuccess
            LogLevel.ERROR -> colorError
            LogLevel.WARN -> colorWarning
            LogLevel.INFO -> colorInfo
            LogLevel.ALL -> colorDefault
        }

        if (showTimestamps && display.startsWith("[") && display.contains("] ")) {
            val tsEnd = display.indexOf("] ") + 2
            ss.setSpan(ForegroundColorSpan(colorTimestamp), 0, tsEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            ss.setSpan(ForegroundColorSpan(lineColor), tsEnd, display.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        } else {
            ss.setSpan(ForegroundColorSpan(lineColor), 0, display.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        return ss
    }
}
