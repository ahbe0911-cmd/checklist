package com.ahbe.checklist

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.app.AlarmManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.NumberPicker
import android.widget.Toast
import android.icu.util.Calendar as PersianIcuCalendar
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.icu.text.SimpleDateFormat
import android.icu.util.ULocale
import java.io.File
import java.util.Date
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.random.Random

/**
 * Native implementation inspired by the supplied Persian checklist mockup.
 * No HTML, JavaScript, WebView, network permission, or third-party runtime.
 */
class MainActivity : Activity() {
    private data class Note(
        val id: String,
        val text: String,
        var completed: Boolean,
        val color: Int,
        var reminderAt: Long? = null,
        var calendarEventId: Long? = null,
        val audioFilename: String? = null,
        val audioDurationMs: Long = 0L
    )

    private enum class Filter { ALL, ACTIVE, COMPLETED }

    private val surface = Color.rgb(244, 247, 246)
    private val ink = Color.rgb(30, 41, 59)
    private val muted = Color.rgb(100, 116, 139)
    private val softBorder = Color.rgb(226, 232, 240)
    private val indigo = Color.rgb(99, 102, 241)
    private val emerald = Color.rgb(16, 185, 129)
    private val palette = intArrayOf(
        Color.rgb(241, 248, 245),
        Color.rgb(252, 244, 244),
        Color.rgb(244, 246, 253),
        Color.rgb(253, 248, 240),
        Color.rgb(248, 244, 249),
        Color.rgb(245, 247, 249)
    )
    private val notes = mutableListOf<Note>()
    private var filter = Filter.ALL
    private var search = ""
    private var pendingPermissionNoteId: String? = null
    private val titleFont by lazy { loadTypeface("fonts/far_vazir.ttf") }
    private val textFont by lazy { loadTypeface("fonts/far_nazanin.ttf") }
    private val dateFont by lazy { loadTypeface("fonts/a_rooznameh.ttf") }

    // Appearance preferences are kept separately from the user's notes.
    private var noteTextSize = 15
    private var inputTextSize = 14
    private var searchTextSize = 12
    private var reminderTextSize = 11
    private var headerTextSize = 25
    private lateinit var headingLabel: TextView
    private lateinit var searchField: EditText

    private fun loadTypeface(path: String): Typeface =
        runCatching { Typeface.createFromAsset(assets, path) }.getOrDefault(Typeface.DEFAULT)

    private lateinit var dateLabel: TextView
    private lateinit var progressText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var noteList: LinearLayout
    private lateinit var emptyState: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private val filterButtons = mutableMapOf<Filter, TextView>()
    private lateinit var microphoneButton: ImageButton
    private lateinit var cancelAudioButton: ImageButton
    private lateinit var sendButton: ImageButton
    private lateinit var voiceStatus: TextView
    private val uiHandler = Handler(Looper.getMainLooper())
    private var recorder: MediaRecorder? = null
    private var recordingFilename: String? = null
    private var recordingStartedAt = 0L
    private var pendingVoiceFilename: String? = null
    private var pendingVoiceDurationMs = 0L
    private var voicePlayer: MediaPlayer? = null
    private var playingNoteId: String? = null
    private val voiceTicker = object : Runnable {
        override fun run() {
            if (recorder == null) return
            voiceStatus.text = "در حال ضبط • " +
                formatDuration(SystemClock.elapsedRealtime() - recordingStartedAt)
            uiHandler.postDelayed(this, 400L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.WHITE
        window.navigationBarColor = surface
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        ReminderEngine.createChannel(this)
        loadNotes()
        loadAppearanceSettings()
        buildScreen()
        updateDate()
        renderNotes()
    }

    override fun onResume() {
        super.onResume()
        if (::dateLabel.isInitialized) {
            updateDate()
            ReminderEngine.restore(this)
        }
    }

    override fun onStop() {
        if (recorder != null) finishVoiceRecording()
        stopPlayback()
        super.onStop()
    }

    override fun onDestroy() {
        uiHandler.removeCallbacks(voiceTicker)
        if (recorder != null) {
            runCatching { recorder?.stop() }
            runCatching { recorder?.release() }
            recorder = null
            recordingFilename?.let { storedAudioFile(it)?.delete() }
        }
        pendingVoiceFilename?.let { storedAudioFile(it)?.delete() }
        stopPlayback()
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun shape(color: Int, radiusDp: Int, border: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
            if (border != null) setStroke(dp(1), border)
        }

    private fun label(
        value: String,
        sp: Float,
        color: Int = ink,
        bold: Boolean = false
    ) = TextView(this).apply {
        text = value
        textSize = sp
        setTextColor(color)
        gravity = Gravity.CENTER_VERTICAL
        includeFontPadding = false
        typeface = if (sp >= 22f) titleFont else textFont
        if (bold && typeface == Typeface.DEFAULT) typeface = Typeface.DEFAULT_BOLD
    }

    private fun buildScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setBackgroundColor(surface)
        }
        setContentView(root)

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(17), dp(20), dp(14))
            setBackgroundColor(Color.WHITE)
            elevation = dp(3).toFloat()
        }
        root.addView(header, LinearLayout.LayoutParams(-1, -2))

        val headerTop = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(headerTop, LinearLayout.LayoutParams(-1, dp(49)))

        val heading = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        headerTop.addView(heading, LinearLayout.LayoutParams(0, -1, 1f))
        headingLabel = label("یادداشت‌های من", headerTextSize.toFloat(), ink, true).apply {
            typeface = titleFont
            maxLines = 1
        }
        heading.addView(headingLabel)

        val divider = View(this).apply { setBackgroundColor(softBorder) }
        heading.addView(divider, LinearLayout.LayoutParams(dp(1), dp(18)).apply {
            marginStart = dp(10)
            marginEnd = dp(10)
        })
        dateLabel = label("", 13f, muted, true).apply { typeface = dateFont }
        heading.addView(dateLabel)

        val settingsButton = ImageButton(this).apply {
            setImageResource(R.drawable.ic_settings)
            imageTintList = ColorStateList.valueOf(muted)
            background = shape(Color.rgb(248, 250, 252), 10, softBorder)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            contentDescription = "تنظیمات"
            setOnClickListener { showAppearanceSettings() }
        }
        heading.addView(settingsButton, LinearLayout.LayoutParams(dp(34), dp(34)).apply {
            marginStart = dp(7)
        })

        val progressBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        headerTop.addView(progressBlock, LinearLayout.LayoutParams(dp(106), -1))
        progressText = label("", 10f, muted, true).apply { gravity = Gravity.END }
        progressBlock.addView(progressText, LinearLayout.LayoutParams(-1, -2))
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            progressTintList = ColorStateList.valueOf(indigo)
            progressBackgroundTintList = ColorStateList.valueOf(Color.rgb(241, 245, 249))
            isIndeterminate = false
        }
        progressBlock.addView(progressBar, LinearLayout.LayoutParams(-1, dp(5)).apply {
            topMargin = dp(8)
        })

        val searchAndFilters = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(searchAndFilters, LinearLayout.LayoutParams(-1, dp(44)).apply {
            topMargin = dp(12)
        })

        searchField = EditText(this).apply {
            hint = "جستجوی یادداشت..."
            textSize = searchTextSize.toFloat()
            setTextColor(ink)
            setHintTextColor(Color.rgb(148, 163, 184))
            background = shape(Color.rgb(248, 250, 252), 12, softBorder)
            setSingleLine(true)
            setPadding(dp(11), 0, dp(11), 0)
            compoundDrawablePadding = dp(6)
            setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_search, 0, 0, 0)
        }
        searchField.typeface = textFont
        searchAndFilters.addView(searchField, LinearLayout.LayoutParams(0, dp(41), 1f).apply {
            marginEnd = dp(10)
        })
        searchField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                search = s?.toString()?.trim().orEmpty()
                renderNotes()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        val filters = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(3), dp(3), dp(3), dp(3))
            background = shape(Color.rgb(241, 245, 249), 12)
        }
        searchAndFilters.addView(filters, LinearLayout.LayoutParams(-2, dp(41)))

        listOf(
            Filter.ALL to "همه",
            Filter.ACTIVE to "جاری",
            Filter.COMPLETED to "انجام"
        ).forEach { (type, caption) ->
            val tab = label(caption, 11f, muted, true).apply {
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                contentDescription = "نمایش " + caption
                setOnClickListener {
                    filter = type
                    updateFilters()
                    renderNotes()
                }
            }
            filters.addView(tab, LinearLayout.LayoutParams(dp(46), -1))
            filterButtons[type] = tab
        }
        updateFilters()

        val main = FrameLayout(this)
        root.addView(main, LinearLayout.LayoutParams(-1, 0, 1f))
        scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            isVerticalScrollBarEnabled = false
            setPadding(dp(20), dp(16), dp(20), dp(20))
        }
        main.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        noteList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroll.addView(noteList, FrameLayout.LayoutParams(-1, -2))

        emptyState = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, dp(66), 0, 0)
        }
        main.addView(emptyState, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        emptyState.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_leaf)
            imageTintList = ColorStateList.valueOf(Color.rgb(203, 213, 225))
        }, LinearLayout.LayoutParams(dp(54), dp(54)))
        emptyState.addView(label("چیزی پیدا نشد!", 23f, Color.rgb(148, 163, 184), true).apply {
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(12) })

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(7), dp(8), dp(7))
            background = shape(Color.WHITE, 17, Color.rgb(241, 245, 249))
            elevation = dp(9).toFloat()
        }
        root.addView(bottom, LinearLayout.LayoutParams(-1, dp(65)).apply {
            marginStart = dp(20)
            marginEnd = dp(20)
            bottomMargin = dp(12)
            topMargin = dp(8)
        })
        input = EditText(this).apply {
            hint = "یک کار جدید بنویس..."
            textSize = inputTextSize.toFloat()
            setTextColor(ink)
            setHintTextColor(Color.rgb(148, 163, 184))
            background = null
            setSingleLine(true)
            setPadding(dp(12), 0, dp(12), 0)
            imeOptions = EditorInfo.IME_ACTION_DONE
            setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_DONE) {
                    addNote()
                    true
                } else false
            }
        }
        input.typeface = textFont
        bottom.addView(input, LinearLayout.LayoutParams(0, -1, 1f))

        voiceStatus = label("", inputTextSize.toFloat(), muted).apply {
            visibility = View.GONE
            typeface = textFont
            gravity = Gravity.CENTER_VERTICAL or Gravity.RIGHT
            setPadding(dp(12), 0, dp(10), 0)
        }
        bottom.addView(voiceStatus, LinearLayout.LayoutParams(0, -1, 1f))

        microphoneButton = ImageButton(this).apply {
            setImageResource(R.drawable.ic_microphone)
            imageTintList = ColorStateList.valueOf(muted)
            background = shape(Color.rgb(243, 246, 249), 12)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(10), dp(10), dp(10), dp(10))
            contentDescription = "ضبط پیام صوتی"
            setOnClickListener { onMicrophoneClicked() }
        }
        bottom.addView(microphoneButton, LinearLayout.LayoutParams(dp(43), dp(43)).apply {
            marginStart = dp(5)
        })

        cancelAudioButton = ImageButton(this).apply {
            setImageResource(R.drawable.ic_close)
            imageTintList = ColorStateList.valueOf(muted)
            background = shape(Color.rgb(249, 241, 240), 11)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(10), dp(10), dp(10), dp(10))
            contentDescription = "لغو ضبط صدا"
            setOnClickListener { discardVoiceRecording() }
            visibility = View.GONE
        }
        bottom.addView(cancelAudioButton, LinearLayout.LayoutParams(dp(39), dp(39)).apply {
            marginStart = dp(5)
        })

        sendButton = ImageButton(this).apply {
            setImageResource(R.drawable.ic_arrow_up)
            imageTintList = ColorStateList.valueOf(Color.WHITE)
            background = shape(ink, 12)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(10), dp(10), dp(10), dp(10))
            contentDescription = "افزودن یادداشت"
            setOnClickListener { addNote() }
        }
        bottom.addView(sendButton, LinearLayout.LayoutParams(dp(45), dp(45)))
        refreshVoiceComposer()
    }

    private fun updateFilters() {
        filterButtons.forEach { (key, tab) ->
            val selected = key == filter
            tab.setTextColor(if (selected) Color.WHITE else muted)
            tab.background = if (selected) shape(ink, 9) else null
        }
    }

    private fun updateDate() {
        val format = SimpleDateFormat("d MMMM yyyy", ULocale("fa_IR@calendar=persian"))
        dateLabel.text = format.format(Date())
    }

    private fun fa(num: Int) = num.toString().map {
        if (it in '0'..'9') "۰۱۲۳۴۵۶۷۸۹"[it - '0'] else it
    }.joinToString("")

    private fun updateProgress() {
        val finished = notes.count { it.completed }
        progressText.text = fa(finished) + " از " + fa(notes.size) + " انجام شده"
        progressBar.progress = if (notes.isEmpty()) 0 else (finished * 100) / notes.size
        progressBar.progressTintList = ColorStateList.valueOf(
            if (finished == notes.size && notes.isNotEmpty()) emerald else indigo
        )
    }

    private fun renderNotes() {
        if (!::noteList.isInitialized) return
        noteList.removeAllViews()
        val visible = notes.filter { note ->
            val searchMatch = note.text.contains(search, ignoreCase = true)
            val filterMatch = when (filter) {
                Filter.ALL -> true
                Filter.ACTIVE -> !note.completed
                Filter.COMPLETED -> note.completed
            }
            searchMatch && filterMatch
        }
        emptyState.visibility = if (visible.isEmpty()) View.VISIBLE else View.GONE
        visible.forEachIndexed { index, note -> addNoteCard(note, index + 1) }
        updateProgress()
    }

    private fun addNoteCard(note: Note, index: Int) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(58)
            setPadding(dp(13), dp(10), dp(9), dp(10))
            background = shape(note.color, 16, Color.argb(12, 0, 0, 0))
            elevation = dp(2).toFloat()
            alpha = if (note.completed) 0.78f else 1f
        }
        noteList.addView(card, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(12)
        })

        val tapArea = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            contentDescription = (if (note.completed) "لغو انجام " else "انجام ") + note.text
            setOnClickListener {
                note.completed = !note.completed
                if (note.completed && note.reminderAt != null) {
                    ReminderEngine.cancel(this@MainActivity, note.id)
                    PhoneCalendarStore.deleteEvent(this@MainActivity, note.calendarEventId)
                    note.calendarEventId = null
                    note.reminderAt = null
                }
                saveNotes()
                renderNotes()
            }
        }
        card.addView(tapArea, LinearLayout.LayoutParams(0, -2, 1f))

        tapArea.addView(label(fa(index) + "-", 14f, Color.rgb(148, 163, 184), true),
            LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(7) })

        val checkBox = FrameLayout(this).apply {
            background = shape(if (note.completed) emerald else Color.WHITE, 6,
                if (note.completed) emerald else Color.rgb(203, 213, 225))
        }
        tapArea.addView(checkBox, LinearLayout.LayoutParams(dp(22), dp(22)).apply {
            marginEnd = dp(11)
        })
        if (note.completed) {
            checkBox.addView(ImageView(this).apply {
                setImageResource(R.drawable.ic_check)
                imageTintList = ColorStateList.valueOf(Color.WHITE)
            }, FrameLayout.LayoutParams(dp(17), dp(17), Gravity.CENTER))
        }

        val noteTextColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        tapArea.addView(noteTextColumn, LinearLayout.LayoutParams(0, -2, 1f))
        val noteText = label(note.text, noteTextSize.toFloat(), if (note.completed) muted else ink, !note.completed).apply {
            typeface = textFont
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            if (note.completed) paintFlags = paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
        }
        noteTextColumn.addView(noteText, LinearLayout.LayoutParams(-1, -2))
        if (note.audioFilename != null) {
            val playback = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutDirection = View.LAYOUT_DIRECTION_RTL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(3), 0, 0)
            }
            val isPlaying = playingNoteId == note.id
            val play = ImageButton(this).apply {
                setImageResource(if (isPlaying) R.drawable.ic_pause_voice else R.drawable.ic_play_voice)
                imageTintList = ColorStateList.valueOf(indigo)
                background = shape(Color.rgb(234, 237, 252), 9)
                setPadding(dp(8), dp(8), dp(8), dp(8))
                contentDescription = if (isPlaying) "توقف پخش" else "پخش پیام صوتی"
                setOnClickListener { toggleVoicePlayback(note) }
            }
            playback.addView(play, LinearLayout.LayoutParams(dp(35), dp(35)))
            playback.addView(label(formatDuration(note.audioDurationMs), 12f, muted),
                LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
            noteTextColumn.addView(playback, LinearLayout.LayoutParams(-1, -2))
        }
        val reminderTime = note.reminderAt
        if (reminderTime != null && reminderTime > System.currentTimeMillis()) {
            val scheduled = SimpleDateFormat("d MMMM yyyy، HH:mm",
                ULocale("fa_IR@calendar=persian")).format(Date(reminderTime))
            noteTextColumn.addView(
                label("یادآوری: " + scheduled, reminderTextSize.toFloat(), indigo).apply {
                    typeface = textFont
                },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) }
            )
        }
        val bell = ImageButton(this).apply {
            setImageResource(R.drawable.ic_bell)
            imageTintList = ColorStateList.valueOf(if (reminderTime != null) indigo else muted)
            background = shape(if (reminderTime != null) Color.rgb(224, 231, 255)
                    else Color.argb(160, 255, 255, 255), 10)
            setPadding(dp(9), dp(9), dp(9), dp(9))
            contentDescription = "تنظیم اعلان " + note.text
            setOnClickListener { startReminderSetup(note) }
        }
        card.addView(bell, LinearLayout.LayoutParams(dp(37), dp(37)).apply {
            marginStart = dp(7)
        })

        val remove = ImageButton(this).apply {
            setImageResource(R.drawable.ic_trash)
            imageTintList = ColorStateList.valueOf(Color.rgb(148, 163, 184))
            background = shape(Color.argb(155, 255, 255, 255), 10)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            contentDescription = "حذف " + note.text
            setOnClickListener {
                isEnabled = false
                card.animate().alpha(0f).scaleX(0.96f).scaleY(0.9f).setDuration(200L)
                    .withEndAction {
                        ReminderEngine.cancel(this@MainActivity, note.id)
                        PhoneCalendarStore.deleteEvent(this@MainActivity, note.calendarEventId)
                        if (playingNoteId == note.id) stopPlayback()
                        note.audioFilename?.let { storedAudioFile(it)?.delete() }
                        notes.removeAll { it.id == note.id }
                        saveNotes()
                        renderNotes()
                    }.start()
            }
        }
        card.addView(remove, LinearLayout.LayoutParams(dp(36), dp(36)).apply {
            marginStart = dp(8)
        })
    }

    private fun addNote() {
        if (recorder != null) finishVoiceRecording()
        if (pendingVoiceFilename != null) {
            submitVoiceNote()
            return
        }
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        notes.add(Note(UUID.randomUUID().toString(), text, false, palette[Random.nextInt(palette.size)]))
        saveNotes()
        input.text.clear()
        if (filter != Filter.ALL) {
            filter = Filter.ALL
            updateFilters()
        }
        renderNotes()
        scroll.post { scroll.smoothScrollTo(0, noteList.bottom) }
    }


    private fun showReminderDialog(note: Note) {
        if (note.completed) {
            Toast.makeText(this, "برای تنظیم اعلان، ابتدا یادداشت را به حالت جاری برگردانید", Toast.LENGTH_LONG).show()
            return
        }
        val months = arrayOf("فروردین", "اردیبهشت", "خرداد", "تیر", "مرداد", "شهریور",
            "مهر", "آبان", "آذر", "دی", "بهمن", "اسفند")
        val persian = PersianIcuCalendar.getInstance(ULocale("fa_IR@calendar=persian"))
        note.reminderAt?.let { persian.timeInMillis = it }
        val currentYear = PersianIcuCalendar.getInstance(
            ULocale("fa_IR@calendar=persian")).get(PersianIcuCalendar.YEAR)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            gravity = Gravity.CENTER
            setPadding(dp(9), dp(12), dp(9), dp(4))
        }
        val year = NumberPicker(this).apply {
            minValue = currentYear
            maxValue = currentYear + 10
            value = persian.get(PersianIcuCalendar.YEAR).coerceIn(minValue, maxValue)
            wrapSelectorWheel = false
            descendantFocusability = NumberPicker.FOCUS_BLOCK_DESCENDANTS
        }
        val month = NumberPicker(this).apply {
            minValue = 1
            maxValue = 12
            displayedValues = months
            value = persian.get(PersianIcuCalendar.MONTH) + 1
            wrapSelectorWheel = false
            descendantFocusability = NumberPicker.FOCUS_BLOCK_DESCENDANTS
        }
        val day = NumberPicker(this).apply {
            minValue = 1
            maxValue = 31
            value = persian.get(PersianIcuCalendar.DAY_OF_MONTH)
            wrapSelectorWheel = false
            descendantFocusability = NumberPicker.FOCUS_BLOCK_DESCENDANTS
        }
        fun limitDay() {
            val c = PersianIcuCalendar.getInstance(ULocale("fa_IR@calendar=persian"))
            c.set(PersianIcuCalendar.YEAR, year.value)
            c.set(PersianIcuCalendar.MONTH, month.value - 1)
            c.set(PersianIcuCalendar.DAY_OF_MONTH, 1)
            day.maxValue = c.getActualMaximum(PersianIcuCalendar.DAY_OF_MONTH)
        }
        year.setOnValueChangedListener { _, _, _ -> limitDay() }
        month.setOnValueChangedListener { _, _, _ -> limitDay() }
        limitDay()
        row.addView(day, LinearLayout.LayoutParams(0, dp(157), 1f))
        row.addView(month, LinearLayout.LayoutParams(0, dp(157), 1.45f))
        row.addView(year, LinearLayout.LayoutParams(0, dp(157), 1.15f))
        AlertDialog.Builder(this)
            .setTitle("تاریخ اعلان")
            .setMessage(note.text)
            .setView(row)
            .setNegativeButton("انصراف", null)
            .setNeutralButton("حذف اعلان") { _, _ ->
                ReminderEngine.cancel(this, note.id)
                PhoneCalendarStore.deleteEvent(this, note.calendarEventId)
                note.calendarEventId = null
                note.reminderAt = null
                saveNotes()
                renderNotes()
            }
            .setPositiveButton("انتخاب ساعت") { _, _ ->
                val initial = java.util.Calendar.getInstance().apply {
                    timeInMillis = note.reminderAt ?: System.currentTimeMillis()
                }
                TimePickerDialog(this, { _, hour, minute ->
                    val target = PersianIcuCalendar.getInstance(
                        ULocale("fa_IR@calendar=persian"))
                    target.isLenient = false
                    target.clear()
                    target.set(year.value, month.value - 1, day.value, hour, minute, 0)
                    target.set(PersianIcuCalendar.MILLISECOND, 0)
                    val timestamp = runCatching { target.timeInMillis }.getOrNull()
                    if (timestamp == null || timestamp <= System.currentTimeMillis()) {
                        Toast.makeText(this, "تاریخ و زمان باید در آینده باشد", Toast.LENGTH_LONG).show()
                        return@TimePickerDialog
                    }
                    val newEventId = PhoneCalendarStore.saveEvent(
                        this, note.text, timestamp, note.calendarEventId
                    )
                    note.reminderAt = timestamp
                    if (newEventId != null) {
                        note.calendarEventId = newEventId
                        Toast.makeText(this, "یادداشت در تقویم گوشی ثبت شد", Toast.LENGTH_LONG).show()
                    } else if (!PhoneCalendarStore.hasPermissions(this)) {
                        Toast.makeText(this, "اعلان داخل برنامه تنظیم شد؛ برای ثبت در تقویم گوشی، مجوز تقویم را فعال کنید", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this, "یادآوری داخل برنامه تنظیم شد؛ تقویم گوشی در دسترس یا قابل‌نوشتن نیست", Toast.LENGTH_LONG).show()
                    }
                    saveNotes()
                    ReminderEngine.schedule(this, note.id, note.text, timestamp)
                    renderNotes()
                    showMissingReminderAccessOptions()
                }, initial.get(java.util.Calendar.HOUR_OF_DAY),
                    initial.get(java.util.Calendar.MINUTE), true).show()
            }
            .show()
    }

    private fun startReminderSetup(note: Note) {
        if (note.completed) {
            Toast.makeText(this, "برای تنظیم اعلان، ابتدا یادداشت را به حالت جاری برگردانید", Toast.LENGTH_LONG).show()
            return
        }
        val missing = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED) {
            missing.add(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= 23) {
            if (checkSelfPermission(android.Manifest.permission.READ_CALENDAR) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED) {
                missing.add(android.Manifest.permission.READ_CALENDAR)
            }
            if (checkSelfPermission(android.Manifest.permission.WRITE_CALENDAR) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED) {
                missing.add(android.Manifest.permission.WRITE_CALENDAR)
            }
        }
        if (missing.isNotEmpty()) {
            pendingPermissionNoteId = note.id
            requestPermissions(missing.toTypedArray(), 811)
        } else {
            showReminderDialog(note)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 912) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startVoiceRecording()
            } else {
                Toast.makeText(this, "برای ضبط صدا اجازه میکروفون لازم است", Toast.LENGTH_LONG).show()
            }
            return
        }
        if (requestCode == 811) {
            val noteId = pendingPermissionNoteId
            pendingPermissionNoteId = null
            val note = notes.firstOrNull { it.id == noteId } ?: return
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "مجوز اعلان داده نشد؛ یادآوری داخل برنامه ذخیره می‌شود اما ممکن است اعلان نمایش داده نشود", Toast.LENGTH_LONG).show()
            }
            if (!PhoneCalendarStore.hasPermissions(this)) {
                Toast.makeText(this, "بدون مجوز تقویم، رویداد در تقویم اصلی گوشی ثبت نمی‌شود", Toast.LENGTH_LONG).show()
            }
            showReminderDialog(note)
        }
    }

    private fun showMissingReminderAccessOptions() {
        val notificationMissing = Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        val exactMissing = Build.VERSION.SDK_INT >= 31 &&
            !(getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()
        if (!notificationMissing && !exactMissing) return

        val message = when {
            notificationMissing && exactMissing ->
                "برای نمایش اعلان و اجرای آن در ساعت دقیق، دسترسی‌های زیر را بررسی کن."
            notificationMissing ->
                "اعلان‌های این برنامه مسدود هستند. برای دیدن یادآوری‌ها، اجازه اعلان را فعال کن."
            else ->
                "زنگ دقیق فعال نیست؛ اندروید ممکن است یادآوری را با تأخیر اجرا کند."
        }
        val builder = AlertDialog.Builder(this)
            .setTitle("تنظیم دسترسی یادآوری")
            .setMessage(message)
            .setNegativeButton("بعداً", null)
        if (notificationMissing) {
            builder.setPositiveButton("تنظیم اعلان‌ها") { _, _ ->
                runCatching {
                    startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                        putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                    })
                }
            }
        }
        if (exactMissing) {
            builder.setNeutralButton("تنظیم زنگ دقیق") { _, _ ->
                runCatching {
                    startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                        .setData(Uri.parse("package:$packageName")))
                }
            }
        }
        builder.show()
    }

    private fun promptPreciseAlarmPermission() {
        if (Build.VERSION.SDK_INT >= 31) {
            val manager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            if (!manager.canScheduleExactAlarms()) {
                AlertDialog.Builder(this)
                    .setTitle("دسترسی زمان‌بندی دقیق")
                    .setMessage("برای نمایش اعلان دقیقاً در زمان انتخابی، دسترسی «Alarms & reminders» را فعال کنید. بدون آن، اعلان ممکن است با تأخیر نمایش داده شود.")
                    .setPositiveButton("تنظیم دسترسی") { _, _ ->
                        runCatching {
                            startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                                .setData(Uri.parse("package:$packageName")))
                        }
                    }
                    .setNegativeButton("بعداً", null)
                    .show()
            }
        }
    }


    private fun formatDuration(durationMs: Long): String {
        val totalSeconds = (durationMs.coerceAtLeast(0L) / 1000L)
        return fa((totalSeconds / 60L).toInt()) + ":" +
            fa((totalSeconds % 60L).toString().padStart(2, '0').toInt())
                .padStart(2, '۰')
    }

    // Only generated file names are accepted. Audio is never exposed to public storage.
    private fun storedAudioFile(filename: String): File? {
        if (!Regex("^[0-9a-fA-F-]{36}\\.m4a$").matches(filename)) return null
        return File(File(filesDir, "voice_notes"), filename)
    }

    private fun refreshVoiceComposer() {
        if (!::voiceStatus.isInitialized) return
        val isRecording = recorder != null
        val hasDraft = pendingVoiceFilename != null
        val active = isRecording || hasDraft
        input.visibility = if (active) View.GONE else View.VISIBLE
        voiceStatus.visibility = if (active) View.VISIBLE else View.GONE
        cancelAudioButton.visibility = if (active) View.VISIBLE else View.GONE
        microphoneButton.isEnabled = !hasDraft
        microphoneButton.setImageResource(
            if (isRecording) R.drawable.ic_stop_voice else R.drawable.ic_microphone
        )
        microphoneButton.imageTintList = ColorStateList.valueOf(
            if (isRecording) Color.WHITE else muted
        )
        microphoneButton.background = shape(
            if (isRecording) Color.rgb(208, 65, 69) else Color.rgb(243, 246, 249), 12
        )
        microphoneButton.contentDescription =
            if (isRecording) "توقف ضبط" else "ضبط پیام صوتی"
        sendButton.contentDescription = if (active) "ارسال پیام صوتی" else "افزودن یادداشت"
        if (hasDraft) {
            voiceStatus.text = "صدا آماده ارسال • " + formatDuration(pendingVoiceDurationMs)
        } else if (isRecording) {
            voiceStatus.text = "در حال ضبط • " +
                formatDuration(SystemClock.elapsedRealtime() - recordingStartedAt)
        }
    }

    private fun onMicrophoneClicked() {
        if (recorder != null) {
            finishVoiceRecording()
            return
        }
        if (pendingVoiceFilename != null) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 912)
            return
        }
        startVoiceRecording()
    }

    @Suppress("DEPRECATION")
    private fun startVoiceRecording() {
        if (recorder != null || pendingVoiceFilename != null) return
        stopPlayback()
        val directory = File(filesDir, "voice_notes")
        if (!directory.exists() && !directory.mkdirs()) {
            Toast.makeText(this, "امکان ذخیره صدا وجود ندارد", Toast.LENGTH_LONG).show()
            return
        }
        val filename = UUID.randomUUID().toString() + ".m4a"
        val file = storedAudioFile(filename) ?: return
        var newRecorder: MediaRecorder? = null
        try {
            newRecorder = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()
            newRecorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            newRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            newRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            newRecorder.setAudioEncodingBitRate(64000)
            newRecorder.setAudioSamplingRate(44100)
            newRecorder.setOutputFile(file.absolutePath)
            newRecorder.prepare()
            newRecorder.start()
            recorder = newRecorder
            recordingFilename = filename
            recordingStartedAt = SystemClock.elapsedRealtime()
            refreshVoiceComposer()
            uiHandler.post(voiceTicker)
        } catch (_: Exception) {
            runCatching { newRecorder?.release() }
            file.delete()
            Toast.makeText(this, "میکروفون در دسترس نیست یا ضبط انجام نشد", Toast.LENGTH_LONG).show()
            refreshVoiceComposer()
        }
    }

    private fun finishVoiceRecording() {
        val currentRecorder = recorder ?: return
        val filename = recordingFilename
        val duration = SystemClock.elapsedRealtime() - recordingStartedAt
        recorder = null
        recordingFilename = null
        uiHandler.removeCallbacks(voiceTicker)
        val completed = runCatching { currentRecorder.stop() }.isSuccess
        runCatching { currentRecorder.release() }
        val file = filename?.let { storedAudioFile(it) }
        if (!completed || file == null || !file.isFile || file.length() < 512L) {
            file?.delete()
            Toast.makeText(this, "ضبط صدا کامل نشد. دوباره امتحان کنید", Toast.LENGTH_LONG).show()
        } else {
            pendingVoiceFilename = filename
            pendingVoiceDurationMs = duration
        }
        refreshVoiceComposer()
    }

    private fun discardVoiceRecording() {
        if (recorder != null) {
            val currentRecorder = recorder
            recorder = null
            uiHandler.removeCallbacks(voiceTicker)
            runCatching { currentRecorder?.stop() }
            runCatching { currentRecorder?.release() }
            recordingFilename?.let { storedAudioFile(it)?.delete() }
            recordingFilename = null
        }
        pendingVoiceFilename?.let { storedAudioFile(it)?.delete() }
        pendingVoiceFilename = null
        pendingVoiceDurationMs = 0L
        refreshVoiceComposer()
    }

    private fun submitVoiceNote() {
        val filename = pendingVoiceFilename ?: return
        val file = storedAudioFile(filename)
        if (file == null || !file.isFile || file.length() == 0L) {
            discardVoiceRecording()
            Toast.makeText(this, "فایل صدای ضبط‌شده پیدا نشد", Toast.LENGTH_LONG).show()
            return
        }
        notes.add(Note(
            UUID.randomUUID().toString(),
            "پیام صوتی",
            false,
            palette[Random.nextInt(palette.size)],
            audioFilename = filename,
            audioDurationMs = pendingVoiceDurationMs
        ))
        saveNotes()
        pendingVoiceFilename = null
        pendingVoiceDurationMs = 0L
        refreshVoiceComposer()
        if (filter != Filter.ALL) {
            filter = Filter.ALL
            updateFilters()
        }
        renderNotes()
        scroll.post { scroll.smoothScrollTo(0, noteList.bottom) }
    }

    private fun stopPlayback() {
        val previous = voicePlayer
        voicePlayer = null
        playingNoteId = null
        if (previous != null) {
            runCatching { previous.stop() }
            runCatching { previous.release() }
        }
    }

    private fun toggleVoicePlayback(note: Note) {
        if (playingNoteId == note.id) {
            stopPlayback()
            renderNotes()
            return
        }
        stopPlayback()
        val file = note.audioFilename?.let { storedAudioFile(it) }
        if (file == null || !file.isFile) {
            Toast.makeText(this, "فایل صوتی موجود نیست", Toast.LENGTH_LONG).show()
            return
        }
        val player = MediaPlayer()
        try {
            player.setDataSource(file.absolutePath)
            player.prepare()
            player.setOnCompletionListener {
                stopPlayback()
                renderNotes()
            }
            player.start()
            voicePlayer = player
            playingNoteId = note.id
            renderNotes()
        } catch (_: Exception) {
            runCatching { player.release() }
            Toast.makeText(this, "پخش صدا انجام نشد", Toast.LENGTH_LONG).show()
        }
    }

    private fun loadAppearanceSettings() {
        val prefs = getSharedPreferences("checklist_appearance", MODE_PRIVATE)
        noteTextSize = prefs.getInt("noteTextSize", 15).coerceIn(12, 24)
        inputTextSize = prefs.getInt("inputTextSize", 14).coerceIn(12, 22)
        searchTextSize = prefs.getInt("searchTextSize", 12).coerceIn(10, 18)
        reminderTextSize = prefs.getInt("reminderTextSize", 11).coerceIn(9, 16)
        headerTextSize = prefs.getInt("headerTextSize", 25).coerceIn(18, 30)
    }

    private fun saveAppearanceSettings() {
        getSharedPreferences("checklist_appearance", MODE_PRIVATE).edit()
            .putInt("noteTextSize", noteTextSize)
            .putInt("inputTextSize", inputTextSize)
            .putInt("searchTextSize", searchTextSize)
            .putInt("reminderTextSize", reminderTextSize)
            .putInt("headerTextSize", headerTextSize)
            .apply()
    }

    private fun applyAppearanceSettings() {
        if (::headingLabel.isInitialized) {
            headingLabel.textSize = headerTextSize.toFloat()
            headingLabel.typeface = titleFont
        }
        if (::searchField.isInitialized) {
            searchField.textSize = searchTextSize.toFloat()
            searchField.typeface = textFont
        }
        if (::input.isInitialized) {
            input.textSize = inputTextSize.toFloat()
            input.typeface = textFont
        }
        if (::noteList.isInitialized) renderNotes()
    }

    private fun addSizeControl(
        container: LinearLayout,
        title: String,
        minValue: Int,
        maxValue: Int,
        currentValue: Int,
        onValueChanged: (Int) -> Unit
    ) {
        val block = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(0, dp(7), 0, dp(5))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(label(title, 14f, ink, true),
            LinearLayout.LayoutParams(0, -2, 1f))
        val valueLabel = label(fa(currentValue) + " sp", 12f, indigo, true)
        header.addView(valueLabel, LinearLayout.LayoutParams(-2, -2))
        block.addView(header, LinearLayout.LayoutParams(-1, -2))

        val slider = SeekBar(this).apply {
            max = maxValue - minValue
            progress = (currentValue - minValue).coerceIn(0, max)
            progressTintList = ColorStateList.valueOf(indigo)
            thumbTintList = ColorStateList.valueOf(indigo)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    seekBar: SeekBar?,
                    progress: Int,
                    fromUser: Boolean
                ) {
                    val selected = minValue + progress
                    valueLabel.text = fa(selected) + " sp"
                    onValueChanged(selected)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        block.addView(slider, LinearLayout.LayoutParams(-1, dp(38)))
        container.addView(block, LinearLayout.LayoutParams(-1, -2))
        container.addView(View(this).apply { setBackgroundColor(softBorder) },
            LinearLayout.LayoutParams(-1, dp(1)))
    }

    private fun showAppearanceSettings() {
        var noteSize = noteTextSize
        var inputSize = inputTextSize
        var searchSize = searchTextSize
        var reminderSize = reminderTextSize
        var headerSize = headerTextSize

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(20), dp(5), dp(20), dp(8))
        }
        val scrollContent = ScrollView(this).apply {
            isFillViewport = false
            addView(content, FrameLayout.LayoutParams(-1, -2))
        }

        addSizeControl(content, "اندازه متن یادداشت‌ها", 12, 24, noteSize) { noteSize = it }
        addSizeControl(content, "اندازه متن افزودن کار", 12, 22, inputSize) { inputSize = it }
        addSizeControl(content, "اندازه متن جست‌وجو", 10, 18, searchSize) { searchSize = it }
        addSizeControl(content, "اندازه متن یادآوری", 9, 16, reminderSize) { reminderSize = it }
        addSizeControl(content, "اندازه عنوان برنامه", 18, 30, headerSize) { headerSize = it }

        AlertDialog.Builder(this)
            .setTitle("تنظیمات")
            .setView(scrollContent)
            .setNegativeButton("انصراف", null)
            .setNeutralButton("بازگشت به پیش‌فرض") { _, _ ->
                noteTextSize = 15
                inputTextSize = 14
                searchTextSize = 12
                reminderTextSize = 11
                headerTextSize = 25
                saveAppearanceSettings()
                applyAppearanceSettings()
                Toast.makeText(this, "اندازه‌ها به حالت پیش‌فرض برگشتند", Toast.LENGTH_SHORT).show()
            }
            .setPositiveButton("ذخیره تغییرات") { _, _ ->
                noteTextSize = noteSize
                inputTextSize = inputSize
                searchTextSize = searchSize
                reminderTextSize = reminderSize
                headerTextSize = headerSize
                saveAppearanceSettings()
                applyAppearanceSettings()
                Toast.makeText(this, "تنظیمات ظاهری ذخیره شد", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun loadNotes() {
        val content = getSharedPreferences("checklist_native", MODE_PRIVATE)
            .getString("optimized_notes", "[]") ?: "[]"
        runCatching {
            val saved = JSONArray(content)
            for (i in 0 until saved.length()) {
                val obj = saved.getJSONObject(i)
                notes.add(Note(
                    obj.getString("id"),
                    obj.getString("text"),
                    obj.optBoolean("completed", false),
                    obj.optInt("color", palette[i % palette.size]),
                    obj.optLong("reminderAt", 0L).takeIf { it > 0L },
                    obj.optLong("calendarEventId", 0L).takeIf { it > 0L },
                    obj.optString("audioFilename", "").takeIf { it.isNotEmpty() },
                    obj.optLong("audioDurationMs", 0L).coerceAtLeast(0L)
                ))
            }
        }
    }

    private fun saveNotes() {
        val json = JSONArray()
        notes.forEach {
            json.put(JSONObject().apply {
                put("id", it.id)
                put("text", it.text)
                put("completed", it.completed)
                put("color", it.color)
                put("reminderAt", it.reminderAt ?: 0L)
                put("calendarEventId", it.calendarEventId ?: 0L)
                put("audioFilename", it.audioFilename ?: "")
                put("audioDurationMs", it.audioDurationMs)
            })
        }
        getSharedPreferences("checklist_native", MODE_PRIVATE)
            .edit().putString("optimized_notes", json.toString()).apply()
    }
}
