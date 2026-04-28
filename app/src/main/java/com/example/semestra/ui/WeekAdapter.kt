package com.example.semestra.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.example.semestra.R
import com.example.semestra.data.ExamEvent
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

class WeekAdapter(
    private val onDateSelected: (LocalDate) -> Unit
) : RecyclerView.Adapter<WeekAdapter.DayViewHolder>() {

    private var days = emptyList<LocalDate>()
    private var selectedDate: LocalDate = LocalDate.now()
    private var eventsByDate: Map<LocalDate, List<ExamEvent>> = emptyMap()

    fun submitList(newDays: List<LocalDate>, selected: LocalDate, events: Map<LocalDate, List<ExamEvent>>) {
        days = newDays
        selectedDate = selected
        eventsByDate = events
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DayViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_calendar_day, parent, false)
        // Ensure 7 items fit exactly
        val width = parent.width / 7
        view.layoutParams = ViewGroup.LayoutParams(width, ViewGroup.LayoutParams.MATCH_PARENT)
        return DayViewHolder(view)
    }

    override fun onBindViewHolder(holder: DayViewHolder, position: Int) {
        holder.bind(days[position])
    }

    override fun getItemCount(): Int = days.size

    inner class DayViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val textDay: TextView = view.findViewById(R.id.textDayNumber)
        private val dotContainer: LinearLayout = view.findViewById(R.id.dotContainer)

        fun bind(date: LocalDate) {
            textDay.text = date.dayOfMonth.toString()
            
            val context = itemView.context
            when {
                date == selectedDate -> {
                    textDay.setBackgroundResource(R.drawable.bg_calendar_day_selected)
                    textDay.setTextColor(ContextCompat.getColor(context, R.color.notion_text_primary))
                }
                date == LocalDate.now() -> {
                    textDay.setBackgroundResource(R.drawable.bg_calendar_today_outline)
                    textDay.setTextColor(ContextCompat.getColor(context, R.color.two_tone_on_dark))
                }
                else -> {
                    textDay.setBackgroundResource(0)
                    textDay.setTextColor(ContextCompat.getColor(context, R.color.two_tone_on_dark))
                }
            }

            itemView.setOnClickListener { onDateSelected(date) }
            
            // Re-using dot logic (simplified for here, would ideally be shared)
            dotContainer.removeAllViews()
            val events = eventsByDate[date].orEmpty()
            val colors = events.mapNotNull { dotColorFor(it) }.distinct()
            colors.forEach { colorRes ->
                val dot = View(context).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(4), dp(4)).apply { marginEnd = dp(2) }
                    background = ContextCompat.getDrawable(context, R.drawable.bg_calendar_dot)
                    background?.setTint(ContextCompat.getColor(context, colorRes))
                }
                dotContainer.addView(dot)
            }
        }

        private fun dotColorFor(event: ExamEvent): Int? {
            val title = event.examTitle.lowercase(Locale.US)
            val type = event.eventType.lowercase(Locale.US)
            return when {
                "exam" in title || "midterm" in title || "final" in title || type == "exam" -> R.color.event_exam_stripe
                "quiz" in title || type == "quiz" -> R.color.event_quiz_stripe
                "assignment" in title || "delivery" in title || "due" in title || type == "assignment" -> R.color.event_assignment_stripe
                "lecture" in title || type == "lecture" -> R.color.event_lecture_stripe
                else -> null
            }
        }

        private fun dp(value: Int): Int = (value * itemView.resources.displayMetrics.density).toInt()
    }
}
