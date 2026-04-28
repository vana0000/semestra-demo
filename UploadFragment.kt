//UploadFragement.kt
package com.example.semestra.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.semestra.R
import com.example.semestra.data.AppDatabase
import com.example.semestra.data.CourseProfile
import com.example.semestra.data.EventStatus
import com.example.semestra.data.ExamEvent
import com.example.semestra.data.SessionStore
import com.example.semestra.data.Syllabus
import com.example.semestra.logic.ActivityLogWriter
import com.example.semestra.logic.PdfUploadValidator
import com.example.semestra.logic.SyllabusParser
import com.example.semestra.logic.SyllabusTextExtractor
import com.example.semestra.logic.SyllabusMetadataExtractor
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.CircularProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.UUID

class UploadFragment : Fragment(R.layout.fragment_upload) {

    private lateinit var statusText: TextView
    private lateinit var progressIndicator: CircularProgressIndicator
    private lateinit var uploadButton: MaterialButton

    private val pickSyllabus = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            uris.forEach { uri ->
                runCatching {
                    requireContext().contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
            }
            processSyllabi(uris)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        statusText = view.findViewById(R.id.textUploadStatus)
        progressIndicator = view.findViewById(R.id.progressUpload)
        uploadButton = view.findViewById(R.id.buttonPickPdf)

        uploadButton.setOnClickListener {
            pickSyllabus.launch(
                arrayOf(
                    "application/pdf",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                )
            )
        }
    }

    private fun processSyllabi(uris: List<Uri>) {
        val userId = SessionStore.getUserId(requireContext()) ?: return
	for (uri in uris) {
        	android.util.Log.e("SEMESTRA_DEBUG", "Validating URI: $uri")
        	val supported = PdfUploadValidator.isSupportedSyllabus(requireContext(), uri)
        	val tooLarge = PdfUploadValidator.exceedsSizeLimit(requireContext(), uri)
        	android.util.Log.e("SEMESTRA_DEBUG", "supported=$supported tooLarge=$tooLarge")
        	if (!supported) { ... }
        	if (tooLarge) { ... }
    	}
        for (uri in uris) {
            if (!PdfUploadValidator.isSupportedSyllabus(requireContext(), uri)) {
                Toast.makeText(requireContext(), R.string.error_not_supported_file, Toast.LENGTH_LONG).show()
                return
            }
            if (PdfUploadValidator.exceedsSizeLimit(requireContext(), uri)) {
                Toast.makeText(requireContext(), R.string.error_pdf_too_large, Toast.LENGTH_LONG).show()
                return
            }
        }
        setLoading(true)
        statusText.text = getString(R.string.upload_extracting_nlp)
        viewLifecycleOwner.lifecycleScope.launch {
            delay(3000)
            setLoading(false)
            showSectionDialog(userId, uris)
        }
    }

    private fun showSectionDialog(userId: String, uris: List<Uri>) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_verify_sections, null)
        val spinner = dialogView.findViewById<Spinner>(R.id.spinnerSections)
        spinner.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, listOf("001", "002", "003"))
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.upload_verify_section_title)
            .setView(dialogView)
            .setCancelable(false)
            .setPositiveButton(R.string.upload_confirm) { _, _ ->
                val section = spinner.selectedItem?.toString().orEmpty().ifBlank { "001" }
                seedDemoData(userId, uris, section)
            }
            .show()
    }

private fun seedDemoData(userId: String, uris: List<Uri>, section: String) {
    setLoading(true)
    statusText.text = getString(R.string.upload_extracting_nlp)
    viewLifecycleOwner.lifecycleScope.launch {
        val result = withContext(Dispatchers.IO) {
            runCatching {

		uris.forEach { uri ->
                    android.util.Log.e("SEMESTRA_DEBUG", "=== URI: $uri ===")
                    try {
                        val stream = requireContext().contentResolver.openInputStream(uri)
                        android.util.Log.e("SEMESTRA_DEBUG", "Stream opened: ${stream != null}")
                        stream?.close()
                    } catch (e: Exception) {
                        android.util.Log.e("SEMESTRA_DEBUG", "Stream FAILED: ${e.message}")
                    }
                    val text = SyllabusTextExtractor.extractText(requireContext(), uri)
                    android.util.Log.e("SEMESTRA_DEBUG", "Text length: ${text.length}")
                    android.util.Log.e("SEMESTRA_DEBUG", "Text start: ${text.take(200)}")
                }
		
                val db = AppDatabase.getInstance(requireContext().applicationContext)
                db.examEventDao().deleteForUser(userId)
                db.courseProfileDao().deleteForUser(userId)

                val parser = SyllabusParser()
                val allEvents = mutableListOf<ExamEvent>()

                uris.forEach { uri ->
                    // 1. Extract raw text
                    val rawText = SyllabusTextExtractor.extractText(requireContext(), uri)

                    // Debug logging (remove before release)
                    android.util.Log.d("SYLLABUS_DEBUG", "Text length: ${rawText.length}")
                    android.util.Log.d("SYLLABUS_DEBUG", "First 500: ${rawText.take(500)}")

                    // 2. Parse metadata and insert Syllabus row
                    val meta = SyllabusMetadataExtractor.extract(rawText)
                    val syllabusId = UUID.randomUUID().toString()
                    db.syllabusDao().insert(
                        Syllabus(
                            syllabusId = syllabusId,
                            userId = userId,
                            filePath = uri.toString(),
                            uploadDate = System.currentTimeMillis(),
                            courseSection = meta.courseSection ?: "CSE 3315 Section $section",
                            instructorName = meta.instructorName,
                            instructorEmail = meta.instructorEmail,
                            taName = meta.taName,
                            taEmail = meta.taEmail,
                            meetingInfo = meta.meetingInfo,
                            gradingSummary = meta.gradingSummary
                        )
                    )

                    // 3. Parse events
                    val parseResult = parser.parseText(rawText)

// Fallback fix
val fixedEvents = parseResult.events.map { pe ->
    val fallbackCourse = meta.courseSection
        ?.let { Regex("""([A-Z]{2,4}\s?\d{4})""").find(it)?.value }

    pe.copy(
        className = if (pe.className == "Unknown Course" && fallbackCourse != null)
            fallbackCourse
        else pe.className
    )
}
                    val parsed = parseResult.events.map { pe ->
                        ExamEvent(
                            eventId = UUID.randomUUID().toString(),
                            userId = userId,
                            syllabusId = syllabusId,
                            className = pe.className,
                            examTitle = pe.examTitle,
                            eventDate = pe.eventDate,
                            location = "",
                            startTime = "09:00 AM",
                            endTime = "10:00 AM",
                            eventType = inferEventType(pe.examTitle),
                            synced = false,
                            status = EventStatus.SAVED,
                            needsReview = pe.needsReview
                        )
                    }
                    allEvents.addAll(parsed)
                }

                // 4. Fall back to demo data if parsing found nothing
                val finalEvents = if (allEvents.isEmpty()) {
                    demoSeedEvents(userId).also {
                        db.courseProfileDao().insertAll(demoProfiles(userId))
                    }
                } else {
                    allEvents
                }

                db.examEventDao().insertAll(finalEvents)
                ActivityLogWriter.write(
                    requireContext().applicationContext,
                    userId,
                    getString(R.string.activity_log_upload_title),
                    getString(R.string.activity_log_upload_details, finalEvents.size)
                )
                finalEvents.size
            }
        }
        setLoading(false)
        result.onSuccess {
            statusText.text = getString(R.string.upload_seed_complete, it)
            SessionStore.markDemoParsed(requireContext(), true)
            (activity as? MainShellActivity)?.openHomeWithClassFilter(null)
        }.onFailure {
            statusText.text = getString(R.string.upload_error, it.localizedMessage.orEmpty())
        }
    }
}

private fun inferEventType(title: String): String {
    val lower = title.lowercase(Locale.US)
    return when {
        "exam" in lower || "midterm" in lower || "final" in lower -> "Exam"
        "quiz" in lower -> "Quiz"
        "assignment" in lower || "delivery" in lower || "due" in lower -> "Assignment"
        else -> "Lecture"
    }
}

    private fun setLoading(loading: Boolean) {
        progressIndicator.visibility = if (loading) View.VISIBLE else View.GONE
        uploadButton.isEnabled = !loading
    }

    private fun demoSeedEvents(userId: String): List<ExamEvent> {
        val dateFormat = SimpleDateFormat("MMM d, yyyy", Locale.US)
        data class SeedRow(val className: String, val date: String, val type: String, val topic: String)
        val rows = listOf(
            // CSE 3314 (NH 203)
            SeedRow("CSE 3314", "Jan 22, 2026", "Lecture", "Chapter 1 (Assignment 1 Due)"),
            SeedRow("CSE 3314", "Jan 29, 2026", "Lecture", "Chapter 2"),
            SeedRow("CSE 3314", "Feb 3, 2026", "Quiz", "Quiz 1"),
            SeedRow("CSE 3314", "Feb 10, 2026", "Lecture", "Guest Speaker #1 (Assignment 2 Due)"),
            SeedRow("CSE 3314", "Feb 19, 2026", "Lecture", "Chapter 4"),
            SeedRow("CSE 3314", "Mar 3, 2026", "Quiz", "Quiz 2"),
            SeedRow("CSE 3314", "Mar 5, 2026", "Exam", "Exam #1 (Assignment 3 Due)"),
            SeedRow("CSE 3314", "Mar 17, 2026", "Lecture", "Chapter 5"),
            SeedRow("CSE 3314", "Mar 24, 2026", "Lecture", "Chapter 6 (Assignment 4 Due)"),
            SeedRow("CSE 3314", "Mar 31, 2026", "Quiz", "Quiz 3"),
            SeedRow("CSE 3314", "Apr 9, 2026", "Lecture", "Chapter 8 (Assignment 5 Due)"),
            SeedRow("CSE 3314", "Apr 16, 2026", "Lecture", "Communicating in a Teamworking Environment"),
            SeedRow("CSE 3314", "Apr 28, 2026", "Quiz", "Quiz 4"),
            SeedRow("CSE 3314", "Apr 30, 2026", "Exam", "Final Exam"),
            // CSE 3310 (SWSH 221)
            SeedRow("CSE 3310", "Jan 20, 2026", "Lecture", "Software Processes & Project Management"),
            SeedRow("CSE 3310", "Feb 3, 2026", "Lecture", "Intro to UML"),
            SeedRow("CSE 3310", "Feb 10, 2026", "Lecture", "Requirements Engineering"),
            SeedRow("CSE 3310", "Feb 19, 2026", "Assignment", "Increment I Delivery Due (UML Document)"),
            SeedRow("CSE 3310", "Feb 24, 2026", "Lecture", "Software Testing"),
            SeedRow("CSE 3310", "Mar 3, 2026", "Exam", "Midterm Exam"),
            SeedRow("CSE 3310", "Mar 17, 2026", "Lecture", "Software Evolution"),
            SeedRow("CSE 3310", "Mar 26, 2026", "Assignment", "Increment 2 Delivery Due (SRA Document)"),
            SeedRow("CSE 3310", "Mar 31, 2026", "Lecture", "Distributed SE & Cloud Computing"),
            SeedRow("CSE 3310", "Apr 7, 2026", "Lecture", "Agile Software Dev & Managing People"),
            SeedRow("CSE 3310", "Apr 16, 2026", "Assignment", "Increment 3 Delivery Due (Test Plan & Peer Reviews)"),
            SeedRow("CSE 3310", "Apr 28, 2026", "Assignment", "Increment 4 Delivery Due (Final Project Binder)"),
            SeedRow("CSE 3310", "May 5, 2026", "Exam", "Final Exam"),
            // CSE 3302 (NH 109)
            SeedRow("CSE 3302", "Jan 13, 2026", "Lecture", "Course Overview & Intro to Programming Languages"),
            SeedRow("CSE 3302", "Jan 20, 2026", "Lecture", "Language Design Criteria"),
            SeedRow("CSE 3302", "Jan 27, 2026", "Lecture", "Functional Programming"),
            SeedRow("CSE 3302", "Feb 10, 2026", "Lecture", "Object-Oriented Programming"),
            SeedRow("CSE 3302", "Feb 24, 2026", "Lecture", "Basic Semantics"),
            SeedRow("CSE 3302", "Mar 3, 2026", "Exam", "Midterm Exam"),
            SeedRow("CSE 3302", "Mar 24, 2026", "Lecture", "Control Structures I"),
            SeedRow("CSE 3302", "Apr 7, 2026", "Lecture", "Abstract Data Types and Modules"),
            SeedRow("CSE 3302", "May 5, 2026", "Exam", "Final Exam"),
            // CSE 3315 (NH 202)
            SeedRow("CSE 3315", "Jan 13, 2026", "Lecture", "Introduction and Finite Automata"),
            SeedRow("CSE 3315", "Jan 22, 2026", "Lecture", "Nondeterminism"),
            SeedRow("CSE 3315", "Feb 3, 2026", "Lecture", "Pumping Lemma (Regular Languages)"),
            SeedRow("CSE 3315", "Feb 17, 2026", "Exam", "Exam 1"),
            SeedRow("CSE 3315", "Feb 19, 2026", "Lecture", "Context-free Grammars"),
            SeedRow("CSE 3315", "Mar 19, 2026", "Lecture", "Turing Machines"),
            SeedRow("CSE 3315", "Mar 31, 2026", "Exam", "Exam 2"),
            SeedRow("CSE 3315", "Apr 14, 2026", "Lecture", "Class Ptime and Class P closure"),
            SeedRow("CSE 3315", "May 5, 2026", "Exam", "Final Exam")
        )
        return rows.map { row ->
            val className = row.className
            val title = row.topic
            val millis = dateFormat.parse(row.date)?.time ?: System.currentTimeMillis()
            ExamEvent(
                eventId = UUID.randomUUID().toString(),
                userId = userId,
                syllabusId = null,
                className = className,
                examTitle = title,
                eventDate = millis,
                location = defaultLocationFor(className),
                startTime = defaultStartFor(className),
                endTime = defaultEndFor(className),
                eventType = row.type,
                synced = false,
                status = EventStatus.SAVED,
                needsReview = false
            )
        }
    }

    private fun defaultLocationFor(className: String): String = when (className) {
        "CSE 3315" -> "NH 202"
        "CSE 3302" -> "NH 109"
        "CSE 3314" -> "NH 203"
        "CSE 3310" -> "SWSH 221"
        else -> "TBD"
    }

    private fun eventTypeFromTitle(title: String): String {
        val s = title.lowercase(Locale.US)
        return when {
            "exam" in s || "midterm" in s || "final" in s -> "Exam"
            "quiz" in s -> "Quiz"
            "assignment" in s || "delivery" in s || "due" in s -> "Assignment"
            else -> "Lecture"
        }
    }

    private fun defaultStartFor(className: String): String = when (className) {
        "CSE 3315" -> "11:00 AM"
        "CSE 3302" -> "12:30 PM"
        "CSE 3314" -> "03:30 PM"
        "CSE 3310" -> "02:00 PM"
        else -> "09:00 AM"
    }

    private fun defaultEndFor(className: String): String = when (className) {
        "CSE 3315" -> "12:20 PM"
        "CSE 3302" -> "01:50 PM"
        "CSE 3314" -> "04:50 PM"
        "CSE 3310" -> "03:20 PM"
        else -> "10:00 AM"
    }

    private fun demoProfiles(userId: String): List<CourseProfile> = listOf(
        CourseProfile(
            "${userId}_CSE3315_002", userId, "CSE 3315", "002",
            "Tu/Th 11:00 AM", "NH 202", "barasch@exchange.uta.edu",
            "Hanani Pankaj", "HW 5%, Quizzes 15%, Exams 50%, Final 30%"
        ),
        CourseProfile(
            "${userId}_CSE3302_001", userId, "CSE 3302", "001",
            "Tu/Th 12:30 PM", "NH 109", "jiandong.wang@uta.edu",
            "cxh1126@mavs.uta.edu", "Labs 25%, HW 35%, Midterm 20%, Final 20%"
        ),
        CourseProfile(
            "${userId}_CSE3314_004", userId, "CSE 3314", "004",
            "Tu/Th 3:30 PM", "NH 203", "nomaan.mufti@uta.edu",
            "mohamed.mohamed4@mavs.uta.edu", "Exams 100pts, Quizzes 30pts, 5 Assignments"
        ),
        CourseProfile(
            "${userId}_CSE3310_001", userId, "CSE 3310", "001",
            "Tu/Th 2:00 PM", "SWSH 221", "khalili@uta.edu",
            "lxs5171@mavs.uta.edu", "Mid-term 25%, Final 25%, Term Project 50%"
        )
    )
}
