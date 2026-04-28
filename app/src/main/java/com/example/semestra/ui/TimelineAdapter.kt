package com.example.semestra.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.semestra.R
import com.example.semestra.data.ExamEvent
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class TimelineAdapter(
    private val onClick: (ExamEvent) -> Unit,
    private val onHeaderClick: (LocalDate?) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val items = mutableListOf<TimelineItem>()
    private val dateFormatter = DateTimeFormatter.ofPattern("EEEE, MMMM d")

    sealed class TimelineItem {
        data class Header(val title: String, val date: LocalDate? = null) : TimelineItem()
        data class Event(val event: ExamEvent) : TimelineItem()
    }

    fun submitList(newItems: List<TimelineItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun getItemAt(position: Int): TimelineItem {
        return items[position]
    }

    fun getPositionForDate(date: LocalDate): Int {
        return items.indexOfFirst { it is TimelineItem.Header && it.date == date }
    }

    override fun getItemViewType(position: Int): Int {
        return when (items[position]) {
            is TimelineItem.Header -> VIEW_TYPE_HEADER
            is TimelineItem.Event -> VIEW_TYPE_EVENT
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            VIEW_TYPE_HEADER -> HeaderHolder(
                LayoutInflater.from(parent.context).inflate(R.layout.item_timeline_header, parent, false)
            )
            else -> EventHolder(
                LayoutInflater.from(parent.context).inflate(R.layout.item_schedule_event, parent, false)
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = items[position]
        if (holder is HeaderHolder && item is TimelineItem.Header) {
            holder.title.text = item.title
            holder.itemView.setOnClickListener { onHeaderClick(item.date) }
        } else if (holder is EventHolder && item is TimelineItem.Event) {
            holder.bind(item.event)
        }
    }

    override fun getItemCount(): Int = items.size

    inner class HeaderHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.textTimelineHeader)
    }

    inner class EventHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val scheduleHolder = ScheduleEventAdapter(onClick).Holder(view)
        fun bind(event: ExamEvent) {
            scheduleHolder.bind(event)
        }
    }

    companion object {
        private const val VIEW_TYPE_HEADER = 0
        private const val VIEW_TYPE_EVENT = 1
    }
}
