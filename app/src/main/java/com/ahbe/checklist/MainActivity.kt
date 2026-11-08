package com.ahbe.checklist

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.ContentValues
import android.content.pm.PackageManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
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
    private data class AudioAttachment(val filename: String, val title: String, val durationMs: Long)

    private data class Note(
        val id: String,
        val text: String,
        var completed: Boolean,
        val color: Int,
        val audioFilename: String? = null,
        val audioDurationMs: Long = 0L,
        var category: String = "کتابخانه",
        var parentId: String? = null,
        val imageFilenames: MutableList<String> = mutableListOf(),
        val audioAttachments: MutableList<AudioAttachment> = mutableListOf(),
        var dueAt: Long? = null
    )

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
    private val categories = mutableListOf("کتابخانه", "کافی‌نت", "دانشگاه")
    private var selectedCategory = "کتابخانه"
    private val categoryButtons = mutableMapOf<String, LinearLayout>()
    private data class TabStyle(val colorIndex: Int, val iconIndex: Int)
    private val tabStyles = mutableMapOf<String, TabStyle>()
    private val tabColors = intArrayOf(
        Color.rgb(53, 116, 211), // blue - library
        Color.rgb(13, 148, 136), // turquoise - internet cafe
        Color.rgb(132, 79, 195), // violet - university
        Color.rgb(213, 118, 48), // amber
        Color.rgb(194, 71, 121), // rose
        Color.rgb(67, 132, 93)   // green
    )
    private val tabColorNames = arrayOf("آبی", "فیروزه‌ای", "بنفش", "نارنجی", "صورتی", "سبز")
    private val tabIconNames = arrayOf("کتاب", "رایانه", "دانشگاه", "پوشه", "کیف کار", "ستاره")
    private val tabIcons = intArrayOf(R.drawable.ic_tab_book, R.drawable.ic_tab_computer,
        R.drawable.ic_tab_university, R.drawable.ic_tab_folder,
        R.drawable.ic_tab_briefcase, R.drawable.ic_tab_star)

    private var pickingImageForNoteId: String? = null
    private var voiceTargetNoteId: String? = null
    private var search = ""
    private var lastInsertedNoteId: String? = null
    private val titleFont by lazy { loadTypeface("fonts/far_vazir.ttf") }
    private val textFont by lazy { loadTypeface("fonts/far_nazanin.ttf") }
    private val dateFont by lazy { loadTypeface("fonts/a_rooznameh.ttf") }

    // Appearance preferences are kept separately from the user's notes.
    private var noteTextSize = 15
    private var inputTextSize = 14
    private var searchTextSize = 12
    private var headerTextSize = 25
    // The home screen and the in-app note screen share one Activity, not an AlertDialog.
    private lateinit var screenRoot: FrameLayout
    private lateinit var homePage: LinearLayout
    private var notePage: LinearLayout? = null
    private var currentPageNoteId: String? = null
    private var pageVoiceButton: ImageButton? = null
    private var pageVoiceStatus: TextView? = null
    private var pageSendButton: ImageButton? = null

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
    private lateinit var categoryBar: LinearLayout
    private lateinit var microphoneButton: ImageButton
    private lateinit var cancelAudioButton: ImageButton
    private lateinit var sendButton: ImageButton
    private lateinit var voiceStatus: TextView
    private lateinit var voiceTimer: TextView
    private lateinit var recordingHeader: LinearLayout
    private lateinit var recordingIndicator: View
    private lateinit var composerSpacerStart: View
    private lateinit var composerSpacerEnd: View
    private lateinit var composerGapBeforeCancel: View
    private lateinit var composerGapBeforeSend: View
    private val uiHandler = Handler(Looper.getMainLooper())
    private var recorder: MediaRecorder? = null
    private var recordingFilename: String? = null
    private var recordingStartedAt = 0L
    private var pendingVoiceFilename: String? = null
    private var pendingVoiceDurationMs = 0L
    private var voicePlayer: MediaPlayer? = null
    private var playingNoteId: String? = null
    private var compactPlayerTicker: Runnable? = null
    private val voiceTicker = object : Runnable {
        override fun run() {
            if (VoiceRecordingService.isRecording) {
                voiceTimer.text = formatDuration(
                    SystemClock.elapsedRealtime() - VoiceRecordingService.recordingStartedAt)
                updatePageVoiceControls()
                uiHandler.postDelayed(this, 400L)
            } else {
                syncVoiceDraft()
                refreshVoiceComposer()
                updatePageVoiceControls()
            }
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
        loadCategories()
        loadTabStyles()
        selectedCategory = getSharedPreferences("checklist_native", MODE_PRIVATE)
            .getString("active_category", "کتابخانه")?.takeIf { it in categories }
            ?: categories.first()
        loadNotes()
        loadAppearanceSettings()
        buildScreen()
        updateDate()
        renderNotes()
        savedInstanceState?.getString("opened_note_id")?.let { id ->
            notes.firstOrNull { it.id == id }?.let { showNotePage(it) }
        }
        requestInitialPermissions()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        currentPageNoteId?.let { outState.putString("opened_note_id", it) }
        super.onSaveInstanceState(outState)
    }

    @Deprecated("Back navigation is handled by the current note page.")
    override fun onBackPressed() {
        if (currentPageNoteId != null) navigateBackFromNote() else super.onBackPressed()
    }

    override fun onResume() {
        super.onResume()
        if (::dateLabel.isInitialized) {
            updateDate()
            syncVoiceDraft()
            refreshVoiceComposer()
            updatePageVoiceControls()
            if (VoiceRecordingService.isRecording) {
                uiHandler.removeCallbacks(voiceTicker)
                uiHandler.post(voiceTicker)
            }
        }
    }

    override fun onStop() {
        // Mic capture belongs to the foreground service; never stop on screen lock.
        stopPlayback()
        super.onStop()
    }

    override fun onDestroy() {
        uiHandler.removeCallbacks(voiceTicker)
        compactPlayerTicker?.let { uiHandler.removeCallbacks(it) }
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
        screenRoot = FrameLayout(this)
        homePage = root
        screenRoot.addView(homePage, FrameLayout.LayoutParams(-1, -1))
        setContentView(screenRoot)

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
        header.addView(headerTop, LinearLayout.LayoutParams(-1, dp(64)))

        val heading = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        headerTop.addView(heading, LinearLayout.LayoutParams(0, -1, 1f))
        headingLabel = label("یادداشت‌های من", headerTextSize.toFloat(), ink, true).apply {
            typeface = titleFont
            maxLines = 1
        }
        heading.addView(headingLabel)

        dateLabel = label("", 15f, Color.rgb(58, 77, 114), true).apply {
            typeface = titleFont
            gravity = Gravity.CENTER
            background = shape(Color.rgb(236, 242, 255), 11, Color.rgb(215, 227, 250))
            setPadding(dp(11), dp(4), dp(11), dp(4))
            maxLines = 1
        }
        heading.addView(dateLabel, LinearLayout.LayoutParams(-2, -2).apply {
            topMargin = dp(4)
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

        // The settings control stays in the far top corner, outside the title.
        val settingsButton = ImageButton(this).apply {
            setImageResource(R.drawable.ic_settings)
            imageTintList = ColorStateList.valueOf(muted)
            background = shape(Color.rgb(248, 250, 252), 10, softBorder)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            contentDescription = "تنظیمات و مدیریت سربرگ‌ها"
            setOnClickListener { showMainSettings() }
        }
        headerTop.addView(settingsButton, LinearLayout.LayoutParams(dp(44), dp(44)).apply {
            marginStart = dp(10)
        })
        categoryBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = shape(Color.rgb(244, 246, 251), 13)
        }
        val categoryScroll = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            isFillViewport = true
            addView(categoryBar, FrameLayout.LayoutParams(-1, dp(47)))
        }
        header.addView(categoryScroll, LinearLayout.LayoutParams(-1, dp(47)).apply {
            topMargin = dp(11)
        })
        renderCategoryTabs()

        val searchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(searchRow, LinearLayout.LayoutParams(-1, dp(44)).apply {
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
        searchRow.addView(searchField, LinearLayout.LayoutParams(-1, dp(41)))
        searchField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                search = s?.toString()?.trim().orEmpty()
                renderNotes()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

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

        // One refined composer surface: the recording state gets an extra status
        // row so actions can sit comfortably apart without squeezing the timer.
        val composerCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.WHITE, Color.rgb(250, 252, 255))
            ).apply {
                cornerRadius = dp(24).toFloat()
                setStroke(dp(1), Color.rgb(225, 232, 241))
            }
            elevation = dp(11).toFloat()
            clipToOutline = true
        }
        root.addView(composerCard, LinearLayout.LayoutParams(-1, -2).apply {
            marginStart = dp(17)
            marginEnd = dp(17)
            bottomMargin = dp(15)
            topMargin = dp(10)
        })
        recordingHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(6), dp(18), dp(6))
            visibility = View.GONE
            background = shape(Color.rgb(255, 247, 247), 16)
        }
        composerCard.addView(recordingHeader, LinearLayout.LayoutParams(-1, dp(46)))
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(11), dp(10), dp(11), dp(10))
        }
        composerCard.addView(bottom, LinearLayout.LayoutParams(-1, dp(72)))
        composerSpacerStart = View(this).apply { visibility = View.GONE }
        bottom.addView(composerSpacerStart, LinearLayout.LayoutParams(0, dp(1), 1f))
        input = EditText(this).apply {
            hint = "یک کار جدید بنویس..."
            textSize = inputTextSize.toFloat()
            setTextColor(ink)
            setHintTextColor(Color.rgb(148, 163, 184))
            background = null
            setSingleLine(true)
            gravity = Gravity.CENTER_VERTICAL or Gravity.RIGHT
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(11), 0, dp(11), 0)
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

        recordingIndicator = View(this).apply {
            background = shape(Color.rgb(210, 64, 71), 7)
        }
        recordingHeader.addView(recordingIndicator,
            LinearLayout.LayoutParams(dp(9), dp(9)).apply { marginEnd = dp(11) })
        voiceStatus = label("در حال ضبط صدا", 15f, ink, true).apply {
            typeface = textFont
            gravity = Gravity.CENTER_VERTICAL or Gravity.RIGHT
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        recordingHeader.addView(voiceStatus, LinearLayout.LayoutParams(0, -1, 1f))
        voiceTimer = label("۰۰:۰۰", 13f, Color.rgb(179, 62, 67), true).apply {
            typeface = textFont
            gravity = Gravity.CENTER
            background = shape(Color.WHITE, 12, Color.rgb(252, 225, 226))
            setPadding(dp(12), 0, dp(12), 0)
        }
        recordingHeader.addView(voiceTimer,
            LinearLayout.LayoutParams(-2, dp(30)).apply { marginStart = dp(9) })

        microphoneButton = ImageButton(this).apply {
            setImageResource(R.drawable.ic_microphone)
            imageTintList = ColorStateList.valueOf(muted)
            background = shape(Color.rgb(242, 246, 250), 24, Color.rgb(225, 232, 240))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(12), dp(12), dp(12), dp(12))
            contentDescription = "ضبط پیام صوتی"
            setOnClickListener { onMicrophoneClicked() }
        }
        bottom.addView(microphoneButton, LinearLayout.LayoutParams(dp(48), dp(48)))
        composerGapBeforeCancel = View(this)
        bottom.addView(composerGapBeforeCancel, LinearLayout.LayoutParams(dp(13), dp(1)))

        cancelAudioButton = ImageButton(this).apply {
            setImageResource(R.drawable.ic_close)
            imageTintList = ColorStateList.valueOf(Color.rgb(190, 73, 78))
            background = shape(Color.rgb(255, 244, 244), 24, Color.rgb(249, 221, 223))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(12), dp(12), dp(12), dp(12))
            contentDescription = "لغو ضبط صدا"
            setOnClickListener { discardVoiceRecording() }
            visibility = View.GONE
        }
        bottom.addView(cancelAudioButton, LinearLayout.LayoutParams(dp(48), dp(48)))
        composerGapBeforeSend = View(this).apply { visibility = View.GONE }
        bottom.addView(composerGapBeforeSend, LinearLayout.LayoutParams(dp(17), dp(1)))

        sendButton = ImageButton(this).apply {
            setImageResource(R.drawable.ic_send_message)
            imageTintList = ColorStateList.valueOf(Color.WHITE)
            background = shape(ink, 17)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(12), dp(12), dp(12), dp(12))
            contentDescription = "افزودن یادداشت"
            elevation = dp(2).toFloat()
            setOnClickListener { addNote() }
        }
        bottom.addView(sendButton, LinearLayout.LayoutParams(dp(49), dp(49)))
        composerSpacerEnd = View(this).apply { visibility = View.GONE }
        bottom.addView(composerSpacerEnd, LinearLayout.LayoutParams(0, dp(1), 1f))
        refreshVoiceComposer()
    }

    private fun defaultTabStyle(name: String): TabStyle {
        val defaultIndex = when (name) {
            "کتابخانه" -> 0
            "کافی‌نت" -> 1
            "دانشگاه" -> 2
            else -> categories.indexOf(name).coerceAtLeast(0) + 3
        }
        return TabStyle(defaultIndex % tabColors.size, defaultIndex % tabIcons.size)
    }

    private fun styleForCategory(name: String): TabStyle =
        tabStyles[name] ?: defaultTabStyle(name)

    private fun renderCategoryTabs() {
        if (!::categoryBar.isInitialized) return
        categoryBar.removeAllViews()
        categoryButtons.clear()
        categories.forEach { category ->
            val tab = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutDirection = View.LAYOUT_DIRECTION_RTL
                gravity = Gravity.CENTER
                setPadding(dp(8), 0, dp(8), 0)
                minimumWidth = dp(94)
                isClickable = true
                isFocusable = true
                contentDescription = "سربرگ " + category
                setOnClickListener {
                    selectedCategory = category
                    persistSelectedCategory()
                    updateCategories()
                    renderNotes()
                }
            }
            val symbol = ImageView(this).apply {
                setImageResource(tabIcons[styleForCategory(category).iconIndex])
                scaleType = ImageView.ScaleType.FIT_CENTER
                contentDescription = tabIconNames[styleForCategory(category).iconIndex]
            }
            tab.addView(symbol, LinearLayout.LayoutParams(dp(19), dp(19)).apply {
                marginEnd = dp(6)
            })
            val text = label(category, 13f, ink, true).apply {
                typeface = titleFont // Far Vazir, independent of note fonts.
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                includeFontPadding = false
            }
            tab.addView(text, LinearLayout.LayoutParams(-2, -2))
            categoryBar.addView(tab, (
                if (categories.size <= 3) LinearLayout.LayoutParams(0, -1, 1f)
                else LinearLayout.LayoutParams(-2, -1)
            ).apply { marginEnd = dp(4) })
            categoryButtons[category] = tab
        }
        updateCategories()
    }

    private fun updateCategories() {
        categoryButtons.forEach { (category, tab) ->
            val style = styleForCategory(category)
            val tone = tabColors[style.colorIndex]
            val active = category == selectedCategory
            tab.background = shape(
                if (active) Color.argb(40, Color.red(tone), Color.green(tone), Color.blue(tone))
                else Color.argb(14, Color.red(tone), Color.green(tone), Color.blue(tone)),
                10, if (active) tone else Color.argb(50, Color.red(tone), Color.green(tone), Color.blue(tone))
            )
            tab.alpha = if (active) 1f else 0.86f
            tab.elevation = if (active) dp(2).toFloat() else 0f
            (tab.getChildAt(0) as ImageView).imageTintList = ColorStateList.valueOf(tone)
            (tab.getChildAt(1) as TextView).setTextColor(tone)
        }
    }

    private fun loadTabStyles() {
        val raw = getSharedPreferences("checklist_native", MODE_PRIVATE)
            .getString("category_visual_styles_v1", null) ?: return
        runCatching {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val name = item.optString("category")
                if (name.isBlank()) continue
                tabStyles[name] = TabStyle(
                    item.optInt("color", 0).coerceIn(0, tabColors.lastIndex),
                    item.optInt("icon", 3).coerceIn(0, tabIcons.lastIndex)
                )
            }
        }
    }

    private fun saveTabStyles() {
        val array = JSONArray()
        tabStyles.forEach { (name, style) ->
            array.put(JSONObject().apply {
                put("category", name)
                put("color", style.colorIndex)
                put("icon", style.iconIndex)
            })
        }
        getSharedPreferences("checklist_native", MODE_PRIVATE).edit()
            .putString("category_visual_styles_v1", array.toString()).apply()
    }

    private fun persistSelectedCategory() {
        getSharedPreferences("checklist_native", MODE_PRIVATE).edit()
            .putString("active_category", selectedCategory).apply()
    }

    private fun loadCategories() {
        val saved = getSharedPreferences("checklist_native", MODE_PRIVATE)
            .getString("categories_v2", null) ?: return
        runCatching {
            val json = JSONArray(saved)
            val loaded = mutableListOf<String>()
            for (i in 0 until json.length()) {
                val value = json.optString(i).trim()
                if (value.isNotEmpty() && value !in loaded) loaded.add(value)
            }
            if (loaded.isNotEmpty()) {
                categories.clear()
                categories.addAll(loaded)
            }
        }
    }

    private fun saveCategories() {
        getSharedPreferences("checklist_native", MODE_PRIVATE).edit()
            .putString("categories_v2", JSONArray(categories).toString())
            .putString("active_category", selectedCategory).apply()
    }

    private fun showMainSettings() {
        AlertDialog.Builder(this)
            .setTitle("تنظیمات")
            .setItems(arrayOf("مدیریت سربرگ‌ها", "تنظیمات ظاهری و فونت", "پشتیبان‌گیری از اطلاعات", "بازیابی نسخه پشتیبان")) { _, choice ->
                when(choice) {
                    0 -> showCategoryManager()
                    1 -> showAppearanceSettings()
                    2 -> chooseBackupDestination()
                    3 -> chooseBackupFile()
                }
            }
            .setNegativeButton("بستن", null)
            .show()
    }

    private fun chooseBackupDestination() {
        val intent=Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type="application/zip"
            putExtra(Intent.EXTRA_TITLE,"checklist-backup-${java.time.LocalDate.now()}.zip")
        }
        runCatching { startActivityForResult(intent,2091) }.onFailure {
            Toast.makeText(this,"امکان انتخاب محل ذخیره وجود ندارد",Toast.LENGTH_LONG).show()
        }
    }

    private fun chooseBackupFile() {
        val intent=Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type="application/zip"
        }
        runCatching { startActivityForResult(intent,2092) }.onFailure {
            Toast.makeText(this,"امکان انتخاب فایل پشتیبان وجود ندارد",Toast.LENGTH_LONG).show()
        }
    }

    private fun confirmBackupRestore(uri:Uri) {
        AlertDialog.Builder(this)
            .setTitle("بازیابی اطلاعات")
            .setMessage("اطلاعات موجود با اطلاعات فایل پشتیبان جایگزین می‌شود. ادامه می‌دهید؟")
            .setNegativeButton("انصراف",null)
            .setPositiveButton("بازیابی") { _,_ ->
                Thread {
                    val result=runCatching { UserBackup.restore(this,uri) }
                    runOnUiThread {
                        if(result.isSuccess) {
                            Toast.makeText(this,"اطلاعات بازیابی شد؛ برنامه دوباره باز می‌شود",Toast.LENGTH_LONG).show()
                            val launch=Intent(this,MainActivity::class.java).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                            }
                            startActivity(launch)
                            finish()
                        } else {
                            Toast.makeText(this,"بازیابی ناموفق بود: ${result.exceptionOrNull()?.message}",Toast.LENGTH_LONG).show()
                        }
                    }
                }.start()
            }.show()
    }

    private fun normalizedCategoryName(raw: String, previous: String? = null): String? {
        val title = raw.trim()
        when {
            title.isEmpty() -> Toast.makeText(this, "نام سربرگ نمی‌تواند خالی باشد", Toast.LENGTH_SHORT).show()
            title.length > 32 -> Toast.makeText(this, "نام سربرگ باید کوتاه‌تر از ۳۳ حرف باشد", Toast.LENGTH_SHORT).show()
            categories.any { it == title && it != previous } ->
                Toast.makeText(this, "این نام قبلاً وجود دارد", Toast.LENGTH_SHORT).show()
            else -> return title
        }
        return null
    }

    private fun refreshCategoriesAfterEdit() {
        renderCategoryTabs()
        renderNotes()
    }

    private fun renameCategory(oldName: String) {
        val field = EditText(this).apply {
            setText(oldName)
            selectAll()
            setSingleLine(true)
            typeface = textFont
            setPadding(dp(20), dp(13), dp(20), dp(13))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("ویرایش نام سربرگ")
            .setView(field)
            .setNegativeButton("انصراف", null)
            .setPositiveButton("ذخیره", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val title = normalizedCategoryName(field.text.toString(), oldName)
                    ?: return@setOnClickListener
                if (oldName != title) {
                    val position = categories.indexOf(oldName)
                    if (position >= 0) categories[position] = title
                    notes.filter { it.category == oldName }.forEach { it.category = title }
                    if (selectedCategory == oldName) selectedCategory = title
                    tabStyles[title] = tabStyles.remove(oldName) ?: defaultTabStyle(oldName)
                    saveTabStyles()
                    saveNotes()
                    saveCategories()
                    refreshCategoriesAfterEdit()
                }
                dialog.dismiss()
                showCategoryManager()
            }
        }
        dialog.show()
    }

    private fun deleteCategory(name: String) {
        if (categories.size <= 1) {
            Toast.makeText(this, "حداقل یک سربرگ باید باقی بماند", Toast.LENGTH_LONG).show()
            return
        }
        val count = notes.count { it.category == name }
        if (count == 0) {
            AlertDialog.Builder(this)
                .setTitle("حذف سربرگ «" + name + "»")
                .setMessage("این سربرگ خالی است. حذف شود؟")
                .setNegativeButton("انصراف", null)
                .setPositiveButton("حذف") { _, _ ->
                    categories.remove(name)
                    tabStyles.remove(name)
                    saveTabStyles()
                    if (selectedCategory == name) selectedCategory = categories.first()
                    saveCategories()
                    refreshCategoriesAfterEdit()
                    showCategoryManager()
                }.show()
            return
        }
        val destinations = categories.filter { it != name }
        AlertDialog.Builder(this)
            .setTitle("انتقال یادداشت‌ها پیش از حذف")
            .setMessage("در این سربرگ " + fa(count) +
                " یادداشت و زیرمجموعه وجود دارد. برای جلوگیری از حذف اطلاعات، مقصد آن‌ها را انتخاب کن.")
            .setNegativeButton("انصراف", null)
            .setItems(destinations.toTypedArray()) { _, which ->
                val destination = destinations[which]
                AlertDialog.Builder(this)
                    .setTitle("حذف سربرگ")
                    .setMessage("همه یادداشت‌های «" + name + "» به «" +
                        destination + "» منتقل شوند و فقط سربرگ قبلی حذف شود؟")
                    .setNegativeButton("انصراف", null)
                    .setPositiveButton("انتقال و حذف") { _, _ ->
                        notes.filter { it.category == name }.forEach { it.category = destination }
                        categories.remove(name)
                        tabStyles.remove(name)
                        saveTabStyles()
                        if (selectedCategory == name) selectedCategory = destination
                        saveNotes()
                        saveCategories()
                        refreshCategoriesAfterEdit()
                        showCategoryManager()
                    }.show()
            }.show()
    }

    private fun showCategoryAppearance(name: String) {
        val current = styleForCategory(name)
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(20), dp(12), dp(20), dp(8))
        }
        val preview = label(name, 17f, tabColors[current.colorIndex], true).apply {
            typeface = titleFont
            gravity = Gravity.CENTER
            background = shape(Color.rgb(246, 247, 251), 14, softBorder)
        }
        outer.addView(preview, LinearLayout.LayoutParams(-1, dp(48)))
        var selectedColor = current.colorIndex
        var selectedIcon = current.iconIndex
        fun previewSelection() {
            val tone = tabColors[selectedColor]
            preview.setTextColor(tone)
            preview.setCompoundDrawablesRelativeWithIntrinsicBounds(
                tabIcons[selectedIcon], 0, 0, 0
            )
            preview.compoundDrawableTintList = ColorStateList.valueOf(tone)
            preview.compoundDrawablePadding = dp(8)
        }
        previewSelection()
        outer.addView(label("رنگ سربرگ", 13f, ink, true),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        val colors = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        outer.addView(colors, LinearLayout.LayoutParams(-1, dp(49)))
        for (i in tabColors.indices) {
            val index = i
            val color = tabColors[i]
            val swatch = FrameLayout(this).apply {
                background = shape(color, 15)
                contentDescription = tabColorNames[index]
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    selectedColor = index
                    previewSelection()
                }
            }
            colors.addView(swatch, LinearLayout.LayoutParams(0, dp(28), 1f).apply {
                marginStart = dp(4)
                marginEnd = dp(4)
            })
        }
        outer.addView(label("آیکن سربرگ", 13f, ink, true),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(13) })
        val icons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        outer.addView(icons, LinearLayout.LayoutParams(-1, dp(50)))
        for (i in tabIcons.indices) {
            val index = i
            icons.addView(ImageView(this).apply {
                setImageResource(tabIcons[index])
                imageTintList = ColorStateList.valueOf(tabColors[selectedColor])
                setPadding(dp(7), dp(7), dp(7), dp(7))
                contentDescription = tabIconNames[index]
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    selectedIcon = index
                    previewSelection()
                }
            }, LinearLayout.LayoutParams(0, dp(45), 1f))
        }
        AlertDialog.Builder(this)
            .setTitle("ظاهر سربرگ «" + name + "»")
            .setView(outer)
            .setNegativeButton("انصراف", null)
            .setPositiveButton("ذخیره") { _, _ ->
                tabStyles[name] = TabStyle(selectedColor, selectedIcon)
                saveTabStyles()
                refreshCategoriesAfterEdit()
                showCategoryManager()
            }
            .show()
    }

    private fun showCategoryManager() {
        val items = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(14), dp(12), dp(14), dp(14))
        }
        val dialog = AlertDialog.Builder(this).setTitle("مدیریت سربرگ‌ها")
            .setView(ScrollView(this).apply { addView(items) })
            .setPositiveButton("بستن", null).create()
        categories.toList().forEach { category ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), dp(5), dp(4), dp(5))
            }
            items.addView(row, LinearLayout.LayoutParams(-1, -2))
            row.addView(label(category, 15f, tabColors[styleForCategory(category).colorIndex], true).apply {
                typeface = titleFont
                setCompoundDrawablesRelativeWithIntrinsicBounds(
                    tabIcons[styleForCategory(category).iconIndex], 0, 0, 0
                )
                compoundDrawableTintList = ColorStateList.valueOf(
                    tabColors[styleForCategory(category).colorIndex])
                compoundDrawablePadding = dp(6)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(label("ظاهر", 12f, indigo, true).apply {
                typeface = titleFont
                setPadding(dp(7), dp(12), dp(7), dp(12))
                setOnClickListener {
                    dialog.dismiss()
                    showCategoryAppearance(category)
                }
            })
            row.addView(label("ویرایش", 12f, indigo, true).apply {
                setPadding(dp(9), dp(12), dp(9), dp(12))
                setOnClickListener {
                    dialog.dismiss()
                    renameCategory(category)
                }
            })
            row.addView(label("حذف", 12f, Color.rgb(180, 60, 65), true).apply {
                setPadding(dp(9), dp(12), dp(9), dp(12))
                setOnClickListener {
                    dialog.dismiss()
                    deleteCategory(category)
                }
            })
            items.addView(View(this).apply { setBackgroundColor(softBorder) },
                LinearLayout.LayoutParams(-1, dp(1)))
        }
        val addRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        items.addView(addRow, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(15)
        })
        val nameInput = EditText(this).apply {
            hint = "نام سربرگ جدید"
            textSize = 15f
            typeface = textFont
            setSingleLine(true)
            background = shape(Color.rgb(248, 250, 252), 10, softBorder)
            setPadding(dp(12), 0, dp(12), 0)
        }
        addRow.addView(nameInput, LinearLayout.LayoutParams(0, dp(48), 1f))
        addRow.addView(label("افزودن", 13f, indigo, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(12), 0, dp(12), 0)
            setOnClickListener {
                val name = normalizedCategoryName(nameInput.text.toString())
                    ?: return@setOnClickListener
                categories.add(name)
                tabStyles[name] = defaultTabStyle(name)
                saveTabStyles()
                selectedCategory = name
                saveCategories()
                refreshCategoriesAfterEdit()
                dialog.dismiss()
                showCategoryManager()
            }
        }, LinearLayout.LayoutParams(-2, dp(48)))
        dialog.show()
    }

    private fun requestInitialPermissions() {
        val prefs = getSharedPreferences("checklist_native", MODE_PRIVATE)
        if (prefs.getBoolean("initial_permissions_requested", false)) return
        prefs.edit().putBoolean("initial_permissions_requested", true).apply()
        val missing = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            missing.add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            missing.add(Manifest.permission.POST_NOTIFICATIONS)
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 915)
    }

    private fun updateDate() {
        val format = SimpleDateFormat("d MMMM yyyy", ULocale("fa_IR@calendar=persian"))
        dateLabel.text = format.format(Date())
    }

    private fun fa(num: Int) = num.toString().map {
        if (it in '0'..'9') "۰۱۲۳۴۵۶۷۸۹"[it - '0'] else it
    }.joinToString("")

    private fun updateProgress() {
        val current = notes.filter { it.category == selectedCategory }
        val finished = current.count { it.completed }
        progressText.text = fa(finished) + " از " + fa(current.size) + " انجام شده"
        progressBar.progress = if (current.isEmpty()) 0 else (finished * 100) / current.size
        progressBar.progressTintList = ColorStateList.valueOf(
            if (finished == current.size && current.isNotEmpty()) emerald else indigo
        )
    }

    private fun renderNotes() {
        if (!::noteList.isInitialized) return
        noteList.removeAllViews()
        val visible = notes.filter { note ->
            val searchMatch = note.text.contains(search, ignoreCase = true) ||
                notes.any { it.parentId == note.id && it.text.contains(search, ignoreCase = true) }
            note.category == selectedCategory && note.parentId == null && searchMatch
        }
        emptyState.visibility = if (visible.isEmpty()) View.VISIBLE else View.GONE
        visible.forEachIndexed { index, note -> addNoteCard(note, index + 1) }
        updateProgress()
        renderOpenNotePage()
    }

    private fun addNoteCard(note: Note, index: Int, destination: LinearLayout = noteList) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(58)
            setPadding(dp(13), dp(10), dp(9), dp(10))
            background = shape(note.color, 16, Color.argb(12, 0, 0, 0))
            elevation = dp(2).toFloat()
            alpha = if (note.completed) 0.78f else 1f
        }
        destination.addView(card, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(12)
        })

        val tapArea = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            contentDescription = "بازکردن یادداشت " + note.text
            setOnClickListener { showNotePage(note) }
        }
        card.addView(tapArea, LinearLayout.LayoutParams(0, -2, 1f))

        tapArea.addView(label(fa(index) + "-", 14f, Color.rgb(148, 163, 184), true),
            LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(7) })

        val checkBox = FrameLayout(this).apply {
            background = shape(if (note.completed) emerald else Color.WHITE, 6,
                if (note.completed) emerald else Color.rgb(203, 213, 225))
            isClickable = true
            contentDescription = if (note.completed) "برگرداندن به جاری" else "علامت انجام"
            setOnClickListener { toggleCompleted(note) }
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
            // Word-style paragraph justification for multi-line Persian text.
            // Preserve the complete original text instead of squeezing it into two lines.
            maxLines = Int.MAX_VALUE
            ellipsize = null
            gravity = Gravity.TOP or Gravity.RIGHT
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG
            breakStrategy = android.text.Layout.BREAK_STRATEGY_HIGH_QUALITY
            justificationMode = android.text.Layout.JUSTIFICATION_MODE_INTER_WORD
            setLineSpacing(dp(4).toFloat(), 1.12f)
            includeFontPadding = true
            setPadding(dp(3), dp(5), dp(3), dp(5))
            if (note.completed) paintFlags = paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
        }
        noteTextColumn.addView(noteText, LinearLayout.LayoutParams(-1, -2))
        note.dueAt?.let { due ->
            noteTextColumn.addView(label("تاریخ اجرا: " + formatDueDate(due), 12f,
                Color.rgb(195, 45, 52), true).apply { typeface = textFont },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5) })
        }
        val countChildren = notes.count { it.parentId == note.id }
        if (countChildren > 0 || note.imageFilenames.isNotEmpty() || note.audioAttachments.isNotEmpty()) {
            noteTextColumn.addView(
                label("زیرشاخه: " + fa(countChildren) + "  |  عکس: " +
                    fa(note.imageFilenames.size) + "  |  صوت: " +
                    fa(note.audioAttachments.size), 11f, indigo),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5) }
            )
        }
        if (note.audioFilename != null) {
            val audioBadge = label("یادداشت صوتی", 10f, indigo, true).apply {
                background = shape(Color.rgb(237, 240, 255), 8)
                setPadding(dp(8), dp(4), dp(8), dp(4))
            }
            noteTextColumn.addView(audioBadge,
                LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(5) })

            val playback = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutDirection = View.LAYOUT_DIRECTION_RTL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(5), dp(4), dp(10), dp(4))
                background = shape(Color.rgb(248, 249, 255), 13, Color.rgb(230, 233, 250))
            }
            val isPlaying = playingNoteId == note.id
            val play = ImageButton(this).apply {
                setImageResource(if (isPlaying) R.drawable.ic_pause_voice else R.drawable.ic_play_voice)
                imageTintList = ColorStateList.valueOf(indigo)
                background = shape(Color.WHITE, 18, Color.rgb(231, 234, 250))
                setPadding(dp(9), dp(9), dp(9), dp(9))
                contentDescription = if (isPlaying) "توقف پخش" else "پخش " + note.text
                setOnClickListener { toggleVoicePlayback(note) }
            }
            playback.addView(play, LinearLayout.LayoutParams(dp(36), dp(36)))
            playback.addView(ImageView(this).apply {
                setImageResource(R.drawable.ic_waveform)
                imageTintList = ColorStateList.valueOf(indigo)
                contentDescription = "موج صدای ضبط‌شده"
            }, LinearLayout.LayoutParams(dp(20), dp(20)).apply {
                marginStart = dp(10)
            })
            playback.addView(label(formatDuration(note.audioDurationMs), 12f, muted, true),
                LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(7) })
            noteTextColumn.addView(playback,
                LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(5) })
            noteTextColumn.addView(voiceActionBar(note.audioFilename),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
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
                        removeNoteTree(note)
                        saveNotes()
                        renderNotes()
                    }.start()
            }
        }
        card.addView(remove, LinearLayout.LayoutParams(dp(36), dp(36)).apply {
            marginStart = dp(8)
        })
    }

    /**
     * Inline navigation. Tapping a note opens this full-screen view in the existing
     * Activity; only tapping "افزودن" opens the pre-existing composer dialog.
     */
    private fun showNotePage(note: Note) {
        if (notes.none { it.id == note.id }) return
        currentPageNoteId = note.id
        renderOpenNotePage()
    }

    private fun renderOpenNotePage() {
        if (!::screenRoot.isInitialized) return
        val id = currentPageNoteId ?: return
        val note = notes.firstOrNull { it.id == id }
        if (note == null) {
            closeNotePage()
            return
        }

        notePage?.let { screenRoot.removeView(it) }
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setBackgroundColor(surface)
        }
        notePage = page
        homePage.visibility = View.GONE
        screenRoot.addView(page, FrameLayout.LayoutParams(-1, -1))

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = shape(Color.WHITE, 0)
            elevation = dp(2).toFloat()
        }
        page.addView(header, LinearLayout.LayoutParams(-1, -2))
        val back = label("‹  بازگشت", 14f, indigo, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(7), dp(4), dp(7))
            contentDescription = "بازگشت به صفحه قبل"
            isClickable = true
            isFocusable = true
            setOnClickListener { navigateBackFromNote() }
        }
        header.addView(back, LinearLayout.LayoutParams(-2, dp(44)).apply {
            marginEnd = dp(14)
        })
        val title = label(note.text, 21f, ink, true).apply {
            typeface = titleFont
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL or Gravity.RIGHT
        }
        header.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        note.dueAt?.let { due ->
            page.addView(label("تاریخ اجرا: " + formatDueDate(due), 12f,
                Color.rgb(195, 45, 52), true).apply {
                typeface = textFont
                setPadding(dp(20), dp(8), dp(20), dp(3))
            }, LinearLayout.LayoutParams(-1, -2))
        }

        val content = FrameLayout(this)
        page.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        val listScroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            isVerticalScrollBarEnabled = false
            setPadding(dp(20), dp(16), dp(20), dp(20))
        }
        content.addView(listScroll, FrameLayout.LayoutParams(-1, -1))
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        listScroll.addView(list, FrameLayout.LayoutParams(-1, -2))

        val children = notes.filter { it.parentId == note.id }
        children.forEachIndexed { index, child -> addNoteCard(child, index + 1, list) }

        note.audioAttachments.forEach { attachment ->
            val voiceCard = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutDirection = View.LAYOUT_DIRECTION_RTL
                setPadding(dp(12), dp(11), dp(12), dp(11))
                background = shape(Color.WHITE, 13, softBorder)
            }
            list.addView(voiceCard, LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(9)
            })
            voiceCard.addView(label(attachment.title, 15f, ink, true).apply {
                typeface = titleFont
            })
            voiceCard.addView(compactAudioPlayer(attachment.filename, attachment.durationMs),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(7) })
            voiceCard.addView(voiceActionBar(attachment.filename),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(7) })
        }
        // Gallery-style thumbnails live on this page, alongside voice and text.
        if (note.imageFilenames.isNotEmpty()) {
            list.addView(label("تصاویر", 16f, ink, true).apply {
                typeface = titleFont
                setPadding(dp(4), dp(10), dp(4), dp(8))
            }, LinearLayout.LayoutParams(-1, -2))
            val gallery = android.widget.GridLayout(this).apply {
                columnCount = 3
                alignmentMode = android.widget.GridLayout.ALIGN_BOUNDS
                useDefaultMargins = false
            }
            list.addView(gallery, LinearLayout.LayoutParams(-1, -2))
            val tileWidthPx = (resources.displayMetrics.widthPixels - dp(56)) / 3
            note.imageFilenames.forEachIndexed { index, filename ->
                val file = storedImageFile(filename)
                if (file == null || !file.isFile) return@forEachIndexed
                val tile = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    background = shape(Color.WHITE, 12, softBorder)
                    setPadding(dp(5), dp(6), dp(5), dp(6))
                }
                gallery.addView(tile, android.widget.GridLayout.LayoutParams().apply {
                    width = tileWidthPx
                    height = dp(125)
                    columnSpec = android.widget.GridLayout.spec(index % 3)
                    rowSpec = android.widget.GridLayout.spec(index / 3)
                    setMargins(dp(2), dp(2), dp(2), dp(2))
                })
                val preview = ImageView(this).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    background = shape(Color.rgb(242, 246, 250), 9)
                    val options = android.graphics.BitmapFactory.Options().apply {
                        inJustDecodeBounds = true
                    }
                    android.graphics.BitmapFactory.decodeFile(file.absolutePath, options)
                    val sample = (maxOf(options.outWidth, options.outHeight) / 260).coerceAtLeast(1)
                    val bitmap = android.graphics.BitmapFactory.decodeFile(file.absolutePath,
                        android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
                    setImageBitmap(bitmap)
                    contentDescription = "بازکردن " + imageTitle(filename)
                    setOnClickListener { openImageInPhone(filename) }
                }
                tile.addView(preview, LinearLayout.LayoutParams(-1, dp(78)))
                tile.addView(label(imageTitle(filename), 11f, ink, true).apply {
                    typeface = titleFont
                    gravity = Gravity.CENTER
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setOnClickListener { openImageInPhone(filename) }
                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
                tile.addView(label("تغییر نام", 10f, indigo).apply {
                    typeface = titleFont
                    gravity = Gravity.CENTER
                    setOnClickListener { renameImage(filename) }
                })
            }
        }
        if (children.isEmpty() && note.audioAttachments.isEmpty() && note.imageFilenames.isEmpty()) {
            val empty = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(65), dp(12), dp(10))
            }
            list.addView(empty, LinearLayout.LayoutParams(-1, -2))
            empty.addView(label("هنوز موردی ثبت نشده است", 17f, muted).apply {
                gravity = Gravity.CENTER
            })
        }


        // Record and send directly into the currently open note.
        val composer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(7), dp(8), dp(7))
            background = shape(Color.WHITE, 16, softBorder)
        }
        page.addView(composer, LinearLayout.LayoutParams(-1, -2).apply {
            marginStart = dp(16)
            marginEnd = dp(16)
            topMargin = dp(7)
            bottomMargin = dp(8)
        })
        val entryField = EditText(this).apply {
            hint = "یک مورد جدید بنویس..."
            setSingleLine(true)
            textSize = 14f
            typeface = textFont
            setTextColor(ink)
            setHintTextColor(muted)
            background = null
            setPadding(dp(8), 0, dp(8), 0)
            imeOptions = EditorInfo.IME_ACTION_DONE
        }
        composer.addView(entryField, LinearLayout.LayoutParams(0, dp(45), 1f))
        val mic = ImageButton(this).apply {
            setImageResource(R.drawable.ic_microphone)
            background = shape(Color.rgb(240, 245, 251), 12, softBorder)
            imageTintList = ColorStateList.valueOf(indigo)
            setPadding(dp(11), dp(11), dp(11), dp(11))
            contentDescription = "ضبط صدا در این یادداشت"
            setOnClickListener { handlePageMicrophone(note) }
        }
        pageVoiceButton = mic
        composer.addView(mic, LinearLayout.LayoutParams(dp(45), dp(45)).apply {
            marginStart = dp(6)
        })
        val send = ImageButton(this).apply {
            setImageResource(R.drawable.ic_send_message)
            imageTintList = ColorStateList.valueOf(Color.WHITE)
            background = shape(ink, 12)
            setPadding(dp(11), dp(11), dp(11), dp(11))
            contentDescription = "ارسال متن یا صدای یادداشت"
        }
        pageSendButton = send
        composer.addView(send, LinearLayout.LayoutParams(dp(45), dp(45)).apply {
            marginStart = dp(6)
        })
        val submitPageEntry = {
            syncVoiceDraft()
            when {
                VoiceRecordingService.isRecording -> handlePageMicrophone(note)
                pendingVoiceFilename != null -> showVoiceNoteNameDialog()
                else -> {
                    val value = entryField.text.toString().trim()
                    if (value.isEmpty()) {
                        entryField.error = "متن را بنویس یا میکروفون را بزن"
                    } else {
                        notes.add(Note(UUID.randomUUID().toString(), value, false,
                            palette[Random.nextInt(palette.size)],
                            category = note.category, parentId = note.id))
                        saveNotes()
                        renderNotes()
                    }
                }
            }
        }
        send.setOnClickListener { submitPageEntry() }
        entryField.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) {
                submitPageEntry()
                true
            } else false
        }
        pageVoiceStatus = label("", 12f, Color.rgb(176, 58, 66), true).apply {
            typeface = titleFont
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        page.addView(pageVoiceStatus, LinearLayout.LayoutParams(-1, -2))
        page.addView(label("＋ افزودن عکس", 14f, indigo, true).apply {
            typeface = titleFont
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            background = shape(Color.rgb(237, 243, 255), 11, softBorder)
            setPadding(dp(8), dp(9), dp(8), dp(9))
            setOnClickListener { pickImageForNote(note) }
        }, LinearLayout.LayoutParams(-1, -2).apply {
            marginStart = dp(16)
            marginEnd = dp(16)
            bottomMargin = dp(4)
        })
        page.addView(label("افزودن عکس، تاریخ یا جزئیات", 12f, indigo, true).apply {
            typeface = titleFont
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            setPadding(dp(5), dp(8), dp(5), dp(11))
            setOnClickListener { showNoteDetail(note) }
        }, LinearLayout.LayoutParams(-1, -2))
        updatePageVoiceControls()
    }


    private fun updatePageVoiceControls() {
        val button = pageVoiceButton ?: return
        val noteId = currentPageNoteId ?: return
        val prefs = getSharedPreferences(VoiceRecordingService.PREFS, MODE_PRIVATE)
        val targetId = prefs.getString("target_note_id", voiceTargetNoteId)
        val recording = VoiceRecordingService.isRecording && targetId == noteId
        val ready = pendingVoiceFilename != null && (voiceTargetNoteId == noteId || targetId == noteId)
        button.setImageResource(if (recording) R.drawable.ic_stop_voice else R.drawable.ic_microphone)
        button.background = if (recording) shape(Color.rgb(203, 62, 73), 12)
            else shape(Color.rgb(240, 245, 251), 12, softBorder)
        button.imageTintList = ColorStateList.valueOf(if (recording) Color.WHITE else indigo)
        button.contentDescription = if (recording) "توقف ضبط و نام‌گذاری صدا" else "شروع ضبط صدا"
        pageVoiceStatus?.apply {
            visibility = if (recording || ready) View.VISIBLE else View.GONE
            text = when {
                recording -> "● در حال ضبط صدا  " + formatDuration(
                    SystemClock.elapsedRealtime() - VoiceRecordingService.recordingStartedAt)
                ready -> "صدا آماده است؛ ارسال را بزن تا نام‌گذاری شود."
                else -> ""
            }
        }
        pageSendButton?.background = shape(if (ready) emerald else ink, 12)
    }

    private fun handlePageMicrophone(note: Note) {
        if (VoiceRecordingService.isRecording) {
            val target = getSharedPreferences(VoiceRecordingService.PREFS, MODE_PRIVATE)
                .getString("target_note_id", voiceTargetNoteId)
            if (target != note.id) {
                Toast.makeText(this, "یک ضبط دیگر فعال است", Toast.LENGTH_LONG).show()
                return
            }
            finishVoiceRecording()
            awaitVoiceDraft(note.id, 0)
            return
        }
        syncVoiceDraft()
        if (pendingVoiceFilename != null) {
            if (voiceTargetNoteId == note.id) showVoiceNoteNameDialog()
            else Toast.makeText(this, "ابتدا صدای قبلی را ثبت کن", Toast.LENGTH_LONG).show()
            return
        }
        voiceTargetNoteId = note.id
        getSharedPreferences(VoiceRecordingService.PREFS, MODE_PRIVATE).edit()
            .putString("target_note_id", note.id).commit()
        onMicrophoneClicked()
        updatePageVoiceControls()
        uiHandler.postDelayed({ updatePageVoiceControls() }, 650L)
    }

    private fun awaitVoiceDraft(noteId: String, tries: Int) {
        uiHandler.postDelayed({
            syncVoiceDraft()
            updatePageVoiceControls()
            if (pendingVoiceFilename != null && voiceTargetNoteId == noteId) {
                showVoiceNoteNameDialog()
            } else if (tries < 15) {
                awaitVoiceDraft(noteId, tries + 1)
            } else {
                Toast.makeText(this, "فایل ضبط نشد؛ دوباره امتحان کن", Toast.LENGTH_LONG).show()
            }
        }, 180L)
    }

    private fun navigateBackFromNote() {
        val open = currentPageNoteId?.let { id -> notes.firstOrNull { it.id == id } }
        val parent = open?.parentId?.let { id -> notes.firstOrNull { it.id == id } }
        if (parent != null) showNotePage(parent) else closeNotePage()
    }

    private fun closeNotePage() {
        currentPageNoteId = null
        notePage?.let { screenRoot.removeView(it) }
        notePage = null
        pageVoiceButton = null
        pageVoiceStatus = null
        pageSendButton = null
        if (::homePage.isInitialized) homePage.visibility = View.VISIBLE
    }

    private fun toggleCompleted(note: Note) {
        note.completed = !note.completed
        saveNotes()
        renderNotes()
    }

    private fun removeNoteTree(note: Note) {
        notes.filter { it.parentId == note.id }.toList().forEach { removeNoteTree(it) }
        if (playingNoteId == note.id) stopPlayback()
        note.audioFilename?.let { storedAudioFile(it)?.delete() }
        note.audioAttachments.forEach { storedAudioFile(it.filename)?.delete() }
        note.imageFilenames.forEach { storedImageFile(it)?.delete() }
        notes.removeAll { it.id == note.id }
    }


    private fun formatDueDate(time: Long): String =
        SimpleDateFormat("yyyy/MM/dd", ULocale("fa_IR@calendar=persian")).format(Date(time))

    /** Execution date is shown in red and does not create an alarm. */
    private fun showPersianDatePicker(initialAt: Long?, onPick: (Long?) -> Unit) {
        val months = arrayOf("فروردین", "اردیبهشت", "خرداد", "تیر", "مرداد", "شهریور",
            "مهر", "آبان", "آذر", "دی", "بهمن", "اسفند")
        val now = PersianIcuCalendar.getInstance(ULocale("fa_IR@calendar=persian"))
        val selected = PersianIcuCalendar.getInstance(ULocale("fa_IR@calendar=persian"))
        if (initialAt != null) selected.timeInMillis = initialAt
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            gravity = Gravity.CENTER
            setPadding(dp(9), dp(12), dp(9), dp(4))
        }
        val currentYear = now.get(PersianIcuCalendar.YEAR)
        val year = NumberPicker(this).apply {
            minValue = currentYear - 5
            maxValue = currentYear + 15
            value = selected.get(PersianIcuCalendar.YEAR).coerceIn(minValue, maxValue)
            wrapSelectorWheel = false
            descendantFocusability = NumberPicker.FOCUS_BLOCK_DESCENDANTS
        }
        val month = NumberPicker(this).apply {
            minValue = 1
            maxValue = 12
            displayedValues = months
            value = selected.get(PersianIcuCalendar.MONTH) + 1
            wrapSelectorWheel = false
            descendantFocusability = NumberPicker.FOCUS_BLOCK_DESCENDANTS
        }
        val day = NumberPicker(this).apply {
            minValue = 1
            maxValue = 31
            value = selected.get(PersianIcuCalendar.DAY_OF_MONTH)
            wrapSelectorWheel = false
            descendantFocusability = NumberPicker.FOCUS_BLOCK_DESCENDANTS
        }
        fun limitDay() {
            val temp = PersianIcuCalendar.getInstance(ULocale("fa_IR@calendar=persian"))
            temp.clear()
            temp.set(year.value, month.value - 1, 1, 12, 0, 0)
            val max = temp.getActualMaximum(PersianIcuCalendar.DAY_OF_MONTH)
            day.maxValue = max
            if (day.value > max) day.value = max
        }
        year.setOnValueChangedListener { _, _, _ -> limitDay() }
        month.setOnValueChangedListener { _, _, _ -> limitDay() }
        limitDay()
        row.addView(day, LinearLayout.LayoutParams(0, dp(152), 1f))
        row.addView(month, LinearLayout.LayoutParams(0, dp(152), 1.5f))
        row.addView(year, LinearLayout.LayoutParams(0, dp(152), 1.2f))
        AlertDialog.Builder(this)
            .setTitle("تاریخ اجرای کار (شمسی)")
            .setView(row)
            .setNegativeButton("انصراف", null)
            .setNeutralButton("حذف تاریخ") { _, _ -> onPick(null) }
            .setPositiveButton("ثبت تاریخ") { _, _ ->
                val date = PersianIcuCalendar.getInstance(ULocale("fa_IR@calendar=persian"))
                date.isLenient = false
                date.clear()
                date.set(year.value, month.value - 1, day.value, 12, 0, 0)
                val timestamp = runCatching { date.timeInMillis }.getOrNull()
                if (timestamp == null) {
                    Toast.makeText(this, "تاریخ نامعتبر است", Toast.LENGTH_LONG).show()
                } else {
                    onPick(timestamp)
                }
            }.show()
    }

    private fun actionLabel(text: String, callback: () -> Unit): TextView =
        label(text, 14f, indigo, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(13), dp(12), dp(13), dp(12))
            background = shape(Color.rgb(240, 244, 255), 12, Color.rgb(218, 225, 249))
            isClickable = true
            isFocusable = true
            setOnClickListener { callback() }
        }

    /** A note is a folder-like node: it can contain children and independent attachments. */
    private fun showNoteDetail(note: Note, scrollToBottom: Boolean = false) {
        if (notes.none { it.id == note.id } || isFinishing) return
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(18), dp(10), dp(18), dp(15))
        }
        val scroller = ScrollView(this).apply { addView(content) }
        val detail = AlertDialog.Builder(this)
            .setTitle(note.text)
            .setView(scroller)
            .setPositiveButton("بستن", null)
            .create()

        if (note.parentId != null) {
            content.addView(label("‹ بازگشت به یادداشت قبلی", 13f, indigo).apply {
                setPadding(0, 0, 0, dp(11))
                setOnClickListener {
                    detail.dismiss()
                    notes.firstOrNull { it.id == note.parentId }?.let { showNotePage(it) }
                }
            }, LinearLayout.LayoutParams(-1, -2))
        }
        content.addView(label(
            note.dueAt?.let { "تاریخ اجرا: " + formatDueDate(it) }
                ?: "انتخاب تاریخ اجرای شمسی",
            13f, if (note.dueAt == null) muted else Color.rgb(195, 45, 52), true
        ).apply {
            setPadding(0, dp(5), 0, dp(10))
            setOnClickListener {
                showPersianDatePicker(note.dueAt) { picked ->
                    note.dueAt = picked
                    saveNotes()
                    renderNotes()
                    detail.dismiss()
                    showNoteDetail(note)
                }
            }
        }, LinearLayout.LayoutParams(-1, -2))

        val mediaRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        content.addView(mediaRow, LinearLayout.LayoutParams(-1, -2))
        mediaRow.addView(label("عکس +", 12f, indigo, true).apply {
            setPadding(dp(5), dp(8), dp(15), dp(8))
            setOnClickListener {
                detail.dismiss()
                pickImageForNote(note)
            }
        })
        mediaRow.addView(label("صوت +", 12f, indigo, true).apply {
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setOnClickListener {
                voiceTargetNoteId = note.id
                getSharedPreferences(VoiceRecordingService.PREFS, MODE_PRIVATE).edit()
                    .putString("target_note_id", note.id).apply()
                detail.dismiss()
                onMicrophoneClicked()
            }
        })
        mediaRow.addView(View(this), LinearLayout.LayoutParams(0, dp(1), 1f))
        mediaRow.addView(label(if (note.completed) "✓ انجام‌شده" else "○ انجام",
            12f, if (note.completed) emerald else muted).apply {
            setPadding(dp(10), dp(8), dp(5), dp(8))
            setOnClickListener {
                toggleCompleted(note)
                detail.dismiss()
                showNoteDetail(note)
            }
        })

        if (note.audioFilename != null) {
            content.addView(label("صدای اصلی یادداشت", 15f, ink, true),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18) })
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            content.addView(row)
            row.addView(actionLabel("پخش") { toggleVoicePlayback(note) },
                LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(voiceActionBar(note.audioFilename),
                LinearLayout.LayoutParams(0, -2, 2f).apply { marginStart = dp(6) })
        }

        if (note.imageFilenames.isNotEmpty()) {
            content.addView(label("عکس‌های این یادداشت", 16f, ink, true),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(19) })
            note.imageFilenames.toList().forEach { name ->
                val file = storedImageFile(name)
                if (file != null && file.isFile) {
                    val picture = ImageView(this).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        val bounds = android.graphics.BitmapFactory.Options().apply {
                            inJustDecodeBounds = true
                        }
                        android.graphics.BitmapFactory.decodeFile(file.absolutePath, bounds)
                        val scale = (maxOf(bounds.outWidth, bounds.outHeight) / 960).coerceAtLeast(1)
                        val options = android.graphics.BitmapFactory.Options().apply {
                            inSampleSize = scale
                        }
                        setImageBitmap(android.graphics.BitmapFactory.decodeFile(file.absolutePath, options))
                        background = shape(Color.rgb(240, 244, 247), 10)
                    }
                    content.addView(picture, LinearLayout.LayoutParams(-1, dp(170)).apply {
                        topMargin = dp(9)
                    })
                    content.addView(actionLabel("حذف این عکس") {
                        AlertDialog.Builder(this).setTitle("حذف عکس")
                            .setMessage("این عکس از یادداشت حذف شود؟")
                            .setNegativeButton("انصراف", null)
                            .setPositiveButton("حذف") { _, _ ->
                                note.imageFilenames.remove(name)
                                file.delete()
                                saveNotes()
                                renderNotes()
                                detail.dismiss()
                                showNoteDetail(note)
                            }.show()
                    }, LinearLayout.LayoutParams(-1, -2))
                }
            }
        }

        if (note.audioAttachments.isNotEmpty()) {
            content.addView(label("صداهای پیوست", 16f, ink, true),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(19) })
            note.audioAttachments.toList().forEach { attachment ->
                content.addView(label(attachment.title + "  " + formatDuration(attachment.durationMs),
                    14f, ink), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(11) })
                val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                content.addView(controls)
                controls.addView(actionLabel("پخش") {
                    toggleVoicePlayback(Note("attachment-" + attachment.filename,
                        attachment.title, false, Color.WHITE,
                        audioFilename = attachment.filename,
                        audioDurationMs = attachment.durationMs))
                }, LinearLayout.LayoutParams(0, -2, 1f))
                controls.addView(actionLabel("حذف") {
                    AlertDialog.Builder(this).setTitle("حذف صوت")
                        .setMessage("فایل صوتی از این یادداشت حذف شود؟")
                        .setNegativeButton("انصراف", null)
                        .setPositiveButton("حذف") { _, _ ->
                            note.audioAttachments.remove(attachment)
                            storedAudioFile(attachment.filename)?.delete()
                            saveNotes()
                            renderNotes()
                            detail.dismiss()
                            showNoteDetail(note)
                        }.show()
                }, LinearLayout.LayoutParams(0, -2, 1f))
                content.addView(voiceActionBar(attachment.filename),
                    LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
            }
        }


        val children = notes.filter { it.parentId == note.id }
        if (children.isNotEmpty()) {
            content.addView(View(this).apply { setBackgroundColor(softBorder) },
                LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(12) })
        }
        children.forEach { child ->
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(9), dp(5), dp(9), dp(5))
                background = shape(Color.rgb(249, 250, 252), 10)
            }
            content.addView(item, LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(7)
            })
            item.addView(label(if (child.completed) "☑" else "□", 22f,
                if (child.completed) emerald else muted).apply {
                setPadding(dp(2), 0, dp(9), 0)
                contentDescription = "علامت انجام"
                setOnClickListener {
                    toggleCompleted(child)
                    detail.dismiss()
                    showNoteDetail(note)
                }
            })
            val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            item.addView(body, LinearLayout.LayoutParams(0, -2, 1f))
            body.addView(label(child.text, 15f, if (child.completed) muted else ink).apply {
                typeface = textFont
                if (child.completed) paintFlags = paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
                setPadding(0, dp(7), 0, dp(7))
                setOnClickListener {
                    detail.dismiss()
                    showNotePage(child)
                }
            }, LinearLayout.LayoutParams(-1, -2))
            child.dueAt?.let { due ->
                body.addView(label("تاریخ اجرا: " + formatDueDate(due), 12f,
                    Color.rgb(195, 45, 52), true).apply {
                    typeface = textFont
                    setPadding(0, 0, 0, dp(6))
                    setOnClickListener {
                        showPersianDatePicker(child.dueAt) { selected ->
                            child.dueAt = selected
                            saveNotes()
                            detail.dismiss()
                            showNoteDetail(note)
                        }
                    }
                })
            }
            item.addView(label("تاریخ", 11f, indigo).apply {
                setPadding(dp(7), dp(10), dp(7), dp(10))
                setOnClickListener {
                    showPersianDatePicker(child.dueAt) { selected ->
                        child.dueAt = selected
                        saveNotes()
                        detail.dismiss()
                        showNoteDetail(note)
                    }
                }
            })
            item.addView(label("×", 20f, muted).apply {
                gravity = Gravity.CENTER
                setPadding(dp(9), dp(8), dp(3), dp(8))
                contentDescription = "حذف " + child.text
                setOnClickListener {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("حذف این مورد")
                        .setMessage("«" + child.text + "» و تمام زیرمجموعه‌های آن حذف شوند؟")
                        .setNegativeButton("انصراف", null)
                        .setPositiveButton("حذف") { _, _ ->
                            removeNoteTree(child)
                            saveNotes()
                            renderNotes()
                            detail.dismiss()
                            showNoteDetail(note)
                        }.show()
                }
            })
        }

        // One familiar text field plus a send button. No separate sub-branch dialog.
        var draftDueAt: Long? = null
        val composer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = shape(Color.rgb(248, 250, 252), 14, softBorder)
            setPadding(dp(6), dp(5), dp(6), dp(5))
        }
        content.addView(composer, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(14)
        })
        val childInput = EditText(this).apply {
            hint = "یک مورد جدید بنویس..."
            typeface = textFont
            textSize = 15f
            setSingleLine(true)
            setTextColor(ink)
            setHintTextColor(muted)
            background = null
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(9), 0, dp(9), 0)
            imeOptions = EditorInfo.IME_ACTION_DONE
        }
        composer.addView(childInput, LinearLayout.LayoutParams(0, dp(43), 1f))
        val sendChild = ImageButton(this).apply {
            setImageResource(R.drawable.ic_send_message)
            imageTintList = ColorStateList.valueOf(Color.WHITE)
            background = shape(ink, 12)
            setPadding(dp(11), dp(11), dp(11), dp(11))
            contentDescription = "ثبت مورد در این یادداشت"
        }
        composer.addView(sendChild, LinearLayout.LayoutParams(dp(43), dp(43)))
        val draftDate = label("تاریخ اجرا (اختیاری)", 12f, muted).apply {
            setPadding(dp(6), dp(10), dp(6), dp(5))
            setOnClickListener {
                showPersianDatePicker(draftDueAt) { selected ->
                    draftDueAt = selected
                    text = selected?.let { "تاریخ اجرا: " + formatDueDate(it) }
                        ?: "تاریخ اجرا (اختیاری)"
                    setTextColor(if (selected == null) muted else Color.rgb(195, 45, 52))
                }
            }
        }
        content.addView(draftDate, LinearLayout.LayoutParams(-1, -2))
        val submitChild = {
            val value = childInput.text.toString().trim()
            if (value.isEmpty()) {
                childInput.error = "متن را بنویس"
            } else {
                notes.add(Note(UUID.randomUUID().toString(), value, false,
                    palette[Random.nextInt(palette.size)],
                    category = note.category, parentId = note.id, dueAt = draftDueAt))
                saveNotes()
                renderNotes()
                detail.dismiss()
                renderOpenNotePage()
            }
        }
        sendChild.setOnClickListener { submitChild() }
        childInput.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) {
                submitChild()
                true
            } else false
        }
        detail.show()
        if (scrollToBottom) scroller.post { scroller.fullScroll(View.FOCUS_DOWN) }
    }



    /** Compact inline audio player; waveform bars animate while playback is active. */
    private fun compactAudioPlayer(filename: String, durationMs: Long): LinearLayout {
        val playerId = "attached-" + filename
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            background = shape(Color.rgb(245, 248, 255), 12, Color.rgb(227, 235, 251))
            setPadding(dp(10), dp(8), dp(10), dp(7))
        }
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(controls, LinearLayout.LayoutParams(-1, -2))
        val play = ImageButton(this).apply {
            setImageResource(R.drawable.ic_play_voice)
            imageTintList = ColorStateList.valueOf(Color.WHITE)
            background = shape(indigo, 20)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            contentDescription = "پخش یا توقف صدا"
        }
        controls.addView(play, LinearLayout.LayoutParams(dp(39), dp(39)))
        val wave = object : View(this) {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            private val waveform = FloatArray(44) { i ->
                // Organic-looking, stable bars, rather than all bars oscillating in sync.
                val low = kotlin.math.abs(kotlin.math.sin(i * 0.29 + filename.hashCode() * 0.001))
                val middle = kotlin.math.abs(kotlin.math.sin(i * 1.17 + i * i * 0.011))
                val peaks = kotlin.math.abs(kotlin.math.sin(i * 2.41 + 0.85))
                val fade = 0.73 + 0.27 * kotlin.math.sin(Math.PI * i / 43)
                ((0.14 + 0.38 * low + 0.31 * middle + 0.17 * peaks) * fade)
                    .toFloat().coerceIn(0.12f, 0.98f)
            }
            override fun onDraw(canvas: android.graphics.Canvas) {
                super.onDraw(canvas)
                val player = voicePlayer
                val selected = playingNoteId == playerId && player != null
                val fraction = if (selected) runCatching {
                    (player!!.currentPosition.toFloat() / player.duration.coerceAtLeast(1))
                        .coerceIn(0f, 1f)
                }.getOrDefault(0f) else 0f
                val barCount = waveform.size
                val gap = width.toFloat() / barCount
                val radius = dp(2).toFloat()
                val center = height / 2f
                val minHeight = dp(4).toFloat()
                for (i in 0 until barCount) {
                    val played = (i + 0.5f) / barCount <= fraction
                    val crest = if (selected && player?.isPlaying == true &&
                        kotlin.math.abs((i + 0.5f) / barCount - fraction) < 0.065f) 1.09f else 1f
                    val barHeight = (height * waveform[i] * crest).coerceIn(minHeight, height.toFloat())
                    paint.color = if (played) indigo else Color.rgb(167, 178, 205)
                    val left = (i + 0.17f) * gap
                    canvas.drawRoundRect(left, center - barHeight / 2, left + gap * 0.56f,
                        center + barHeight / 2, radius, radius, paint)
                }
                if (selected) {
                    paint.color = Color.rgb(66, 79, 173)
                    val x = fraction * width
                    canvas.drawCircle(x.coerceIn(dp(3).toFloat(),
                        (width - dp(3)).coerceAtLeast(dp(3)).toFloat()),
                        center, dp(2).toFloat(), paint)
                }
            }
        }
        controls.addView(wave, LinearLayout.LayoutParams(0, dp(35), 1f).apply {
            marginStart = dp(10)
            marginEnd = dp(8)
        })
        var speedIndex = 0
        val speeds = floatArrayOf(1f, 1.5f, 2f)
        val speed = label("۱×", 12f, indigo, true).apply {
            typeface = titleFont
            gravity = Gravity.CENTER
            background = shape(Color.WHITE, 9, softBorder)
            setPadding(dp(5), dp(7), dp(5), dp(7))
            contentDescription = "تغییر سرعت پخش"
            setOnClickListener {
                speedIndex = (speedIndex + 1) % speeds.size
                text = arrayOf("۱×", "۱٫۵×", "۲×")[speedIndex]
                if (playingNoteId == playerId) {
                    runCatching {
                        voicePlayer?.playbackParams =
                            voicePlayer!!.playbackParams.setSpeed(speeds[speedIndex])
                    }
                }
            }
        }
        controls.addView(speed, LinearLayout.LayoutParams(dp(48), dp(39)))
        val slider = SeekBar(this).apply {
            // Playback progress must increase visually from LEFT to RIGHT in an RTL app.
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            textDirection = View.TEXT_DIRECTION_LTR
            max = 1000
            progress = 0
            progressTintList = ColorStateList.valueOf(indigo)
            thumbTintList = ColorStateList.valueOf(indigo)
            progressBackgroundTintList = ColorStateList.valueOf(Color.rgb(209, 218, 239))
            setPadding(dp(1), 0, dp(1), 0)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                    if (fromUser && playingNoteId == playerId) {
                        voicePlayer?.let { media ->
                            runCatching { media.seekTo((media.duration * value / 1000f).toInt()) }
                        }
                    }
                }
                override fun onStartTrackingTouch(bar: SeekBar?) {}
                override fun onStopTrackingTouch(bar: SeekBar?) {}
            })
        }
        row.addView(slider, LinearLayout.LayoutParams(-1, dp(30)))
        val time = label("۰:۰۰ / " + formatDuration(durationMs), 11f, muted).apply {
            typeface = titleFont
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            textDirection = View.TEXT_DIRECTION_LTR
            gravity = Gravity.LEFT
        }
        row.addView(time, LinearLayout.LayoutParams(-1, -2))
        fun repaintState() {
            val playing = playingNoteId == playerId && voicePlayer?.isPlaying == true
            play.setImageResource(if (playing) R.drawable.ic_pause_voice else R.drawable.ic_play_voice)
            wave.invalidate()
        }
        play.setOnClickListener {
            if (playingNoteId == playerId && voicePlayer != null) {
                val media = voicePlayer!!
                if (media.isPlaying) media.pause() else media.start()
                repaintState()
                return@setOnClickListener
            }
            val file = storedAudioFile(filename)
            if (file == null || !file.isFile) {
                Toast.makeText(this, "فایل صدا پیدا نشد", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            stopPlayback()
            val media = MediaPlayer()
            val started = runCatching {
                media.setDataSource(file.absolutePath)
                media.prepare()
                media.start()
                media.playbackParams = media.playbackParams.setSpeed(speeds[speedIndex])
                true
            }.getOrDefault(false)
            if (!started) {
                runCatching { media.release() }
                Toast.makeText(this, "پخش صدا انجام نشد", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            voicePlayer = media
            playingNoteId = playerId
            media.setOnCompletionListener {
                stopPlayback()
                slider.progress = 0
                time.text = "۰:۰۰ / " + formatDuration(durationMs)
                repaintState()
            }
            val ticker = object : Runnable {
                override fun run() {
                    if (playingNoteId != playerId || voicePlayer !== media) return
                    val position = runCatching { media.currentPosition }.getOrDefault(0)
                    val total = runCatching { media.duration }.getOrDefault(0)
                    slider.progress = if (total > 0) position * 1000 / total else 0
                    time.text = formatDuration(position.toLong()) + " / " +
                        formatDuration(total.toLong())
                    repaintState()
                    uiHandler.postDelayed(this, 160L)
                }
            }
            compactPlayerTicker = ticker
            uiHandler.post(ticker)
            repaintState()
        }
        return row
    }

    private fun imageTitle(filename: String): String =
        getSharedPreferences("image_display_titles", MODE_PRIVATE)
            .getString(filename, null)?.takeIf { it.isNotBlank() }
            ?: "عکس " + filename.take(6)

    private fun renameImage(filename: String) {
        val textField = EditText(this).apply {
            setText(imageTitle(filename))
            selectAll()
            setSingleLine(true)
            typeface = titleFont
            setPadding(dp(18), dp(10), dp(18), dp(10))
        }
        AlertDialog.Builder(this)
            .setTitle("تغییر نام عکس")
            .setView(textField)
            .setNegativeButton("انصراف", null)
            .setPositiveButton("ذخیره") { _, _ ->
                val name = textField.text.toString().trim()
                if (name.isNotEmpty()) {
                    getSharedPreferences("image_display_titles", MODE_PRIVATE).edit()
                        .putString(filename, name).apply()
                    renderOpenNotePage()
                }
            }.show()
    }

    private fun pickImageForNote(note: Note) {
        pickingImageForNoteId = note.id
        getSharedPreferences("checklist_native", MODE_PRIVATE).edit()
            .putString("image_target", note.id).apply()
        val chooser = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
        }
        runCatching { startActivityForResult(chooser, 2014) }.onFailure {
            Toast.makeText(this, "انتخاب تصویر در دسترس نیست", Toast.LENGTH_LONG).show()
        }
    }

    private fun openImageInPhone(filename: String) {
        val file = storedImageFile(filename)
        if (file == null || !file.isFile) {
            Toast.makeText(this, "عکس پیدا نشد", Toast.LENGTH_LONG).show()
            return
        }
        val uri = NoteImageProvider.uri(this, filename)
        val open = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, when (file.extension) {
                "png" -> "image/png"
                "webp" -> "image/webp"
                else -> "image/jpeg"
            })
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = android.content.ClipData.newRawUri(imageTitle(filename), uri)
        }
        runCatching { startActivity(open) }.onFailure {
            Toast.makeText(this, "نمایشگر عکس در گوشی پیدا نشد", Toast.LENGTH_LONG).show()
        }
    }

    private fun storedImageFile(filename: String): File? {
        if (!Regex("^[0-9a-fA-F-]{36}\\.(jpg|png|webp)$").matches(filename)) return null
        return File(File(filesDir, "note_images"), filename)
    }

    @Deprecated("Uses the platform document picker for images")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 2091 || requestCode == 2092) {
            val uri=if(resultCode==RESULT_OK)data?.data else null
            if(uri!=null) {
                if(requestCode==2092) confirmBackupRestore(uri)
                else Thread {
                    val result=runCatching { UserBackup.export(this,uri) }
                    runOnUiThread {
                        Toast.makeText(this,
                            if(result.isSuccess)"فایل پشتیبان ذخیره شد"
                            else "پشتیبان‌گیری ناموفق بود: ${result.exceptionOrNull()?.message}",
                            Toast.LENGTH_LONG).show()
                    }
                }.start()
            }
            return
        }
        if (requestCode == 2016) {
            val prefs = getSharedPreferences("checklist_native", MODE_PRIVATE)
            val filename = prefs.getString("pending_audio_save", null)
            prefs.edit().remove("pending_audio_save").apply()
            val original = filename?.let { storedAudioFile(it) }
            val destination = if (resultCode == RESULT_OK) data?.data else null
            if (destination != null && original != null && original.isFile) {
                val saved = runCatching {
                    contentResolver.openOutputStream(destination, "w")?.use { output ->
                        original.inputStream().use { input -> input.copyTo(output) }
                    } ?: error("No writable file")
                }.isSuccess
                Toast.makeText(this, if (saved) "فایل صوتی ذخیره شد" else
                    "ذخیره فایل صوتی انجام نشد", Toast.LENGTH_LONG).show()
            }
            return
        }
        if (requestCode != 2014) return
        val prefs = getSharedPreferences("checklist_native", MODE_PRIVATE)
        val targetId = pickingImageForNoteId ?: prefs.getString("image_target", null)
        pickingImageForNoteId = null
        prefs.edit().remove("image_target").apply()
        val note = notes.firstOrNull { it.id == targetId } ?: return
        val uri = if (resultCode == RESULT_OK) data?.data else null
        if (uri == null) {
            renderOpenNotePage()
            return
        }
        val extension = when (contentResolver.getType(uri)) {
            "image/png" -> "png"
            "image/webp" -> "webp"
            else -> "jpg"
        }
        val dir = File(filesDir, "note_images")
        dir.mkdirs()
        val filename = UUID.randomUUID().toString() + "." + extension
        val file = storedImageFile(filename) ?: return
        val saved = runCatching {
            contentResolver.openInputStream(uri)?.use { source ->
                file.outputStream().use { target ->
                    val buffer = ByteArray(8192)
                    var total = 0L
                    while (true) {
                        val read = source.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > 15L * 1024 * 1024) error("Image too large")
                        target.write(buffer, 0, read)
                    }
                    total > 0L
                }
            } == true
        }.getOrDefault(false)
        if (saved) {
            note.imageFilenames.add(filename)
            saveNotes()
            renderNotes()
        } else {
            file.delete()
            Toast.makeText(this, "تصویر ذخیره نشد یا حجم آن بیشتر از ۱۵ مگابایت است", Toast.LENGTH_LONG).show()
        }
        renderOpenNotePage()
    }


    // Two clear, compact actions replace text-only sharing controls.
    private fun audioActionButton(
        caption: String, icon: Int, tint: Int, callback: () -> Unit
    ): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutDirection = View.LAYOUT_DIRECTION_RTL
        gravity = Gravity.CENTER
        setPadding(dp(5), dp(7), dp(5), dp(7))
        background = shape(Color.rgb(244, 247, 255), 11, Color.rgb(226, 232, 248))
        isClickable = true
        isFocusable = true
        contentDescription = caption
        setOnClickListener { callback() }
        addView(ImageView(this@MainActivity).apply {
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(tint)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }, LinearLayout.LayoutParams(dp(18), dp(18)).apply {
            marginEnd = dp(5)
        })
        addView(label(caption, 11f, tint, true))
    }

    private fun voiceActionBar(filename: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutDirection = View.LAYOUT_DIRECTION_RTL
        gravity = Gravity.CENTER_VERTICAL
        addView(audioActionButton("اشتراک‌گذاری", R.drawable.ic_share_audio, indigo) {
            shareAudio(filename)
        }, LinearLayout.LayoutParams(0, dp(39), 1.3f).apply {
            marginEnd = dp(6)
        })
        addView(audioActionButton("دانلود", R.drawable.ic_download_audio, ink) {
            saveAudioToDevice(filename)
        }, LinearLayout.LayoutParams(0, dp(39), 1f))
    }

    /**
     * On Android 10+, save a copy to Downloads / یادداشت‌های من without new
     * storage permissions. Older versions use the system Save As picker.
     */
    private fun saveAudioToDevice(filename: String?) {
        val original = filename?.let { storedAudioFile(it) }
        if (original == null || !original.isFile) {
            Toast.makeText(this, "فایل صوتی پیدا نشد", Toast.LENGTH_LONG).show()
            return
        }
        val displayName = VoiceFileProvider.exportName(VoiceFileProvider.titleFor(this, original.name))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, "audio/mp4")
                put(MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/یادداشت‌های من")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            var destination: Uri? = null
            val succeeded = runCatching {
                destination = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: error("No downloads location")
                contentResolver.openOutputStream(destination!!)?.use { output ->
                    original.inputStream().use { input -> input.copyTo(output) }
                } ?: error("Failed to write")
                contentResolver.update(destination!!, ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }, null, null)
            }.isSuccess
            if (succeeded) {
                Toast.makeText(this, "فایل «" + displayName + "» در دانلودها ذخیره شد",
                    Toast.LENGTH_LONG).show()
                return
            }
            destination?.let { runCatching { contentResolver.delete(it, null, null) } }
        }
        // Android 8/9, or the shared Downloads collection is unavailable.
        getSharedPreferences("checklist_native", MODE_PRIVATE).edit()
            .putString("pending_audio_save", original.name).apply()
        val choose = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/mp4"
            putExtra(Intent.EXTRA_TITLE, displayName)
        }
        runCatching { startActivityForResult(choose, 2016) }.onFailure {
            Toast.makeText(this, "امکان بازکردن مسیر ذخیره وجود ندارد", Toast.LENGTH_LONG).show()
        }
    }

    private fun shareAudio(filename: String?) {
        val file = filename?.let { storedAudioFile(it) }
        if (file == null || !file.isFile) {
            Toast.makeText(this, "فایل صوتی پیدا نشد", Toast.LENGTH_LONG).show()
            return
        }
        val displayName = VoiceFileProvider.exportName(VoiceFileProvider.titleFor(this, file.name))
        val audioUri = VoiceFileProvider.uri(this, file.name)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "audio/mp4"
            putExtra(Intent.EXTRA_STREAM, audioUri)
            putExtra(Intent.EXTRA_TITLE, displayName)
            clipData = android.content.ClipData.newRawUri(displayName, audioUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching {
            startActivity(Intent.createChooser(send, "اشتراک‌گذاری «" + displayName + "»"))
        }.onFailure {
            Toast.makeText(this, "اشتراک‌گذاری در دسترس نیست", Toast.LENGTH_LONG).show()
        }
    }

    private fun addNote() {
        if (VoiceRecordingService.isRecording) {
            finishVoiceRecording()
            uiHandler.postDelayed({
                syncVoiceDraft()
                if (pendingVoiceFilename != null) showVoiceNoteNameDialog()
            }, 700L)
            return
        }
        syncVoiceDraft()
        if (pendingVoiceFilename != null) {
            showVoiceNoteNameDialog()
            return
        }
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        notes.add(Note(UUID.randomUUID().toString(), text, false, palette[Random.nextInt(palette.size)], category = selectedCategory))
        saveNotes()
        input.text.clear()
        renderNotes()
        scroll.post { scroll.smoothScrollTo(0, noteList.bottom) }
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
        val isRecording = VoiceRecordingService.isRecording
        val hasDraft = pendingVoiceFilename != null
        val active = isRecording || hasDraft

        input.visibility = if (active) View.GONE else View.VISIBLE
        recordingHeader.visibility = if (active) View.VISIBLE else View.GONE
        composerSpacerStart.visibility = if (active) View.VISIBLE else View.GONE
        composerSpacerEnd.visibility = if (active) View.VISIBLE else View.GONE

        // 3 separated buttons during recording; 2 buttons after stopping.
        microphoneButton.visibility = if (hasDraft) View.GONE else View.VISIBLE
        cancelAudioButton.visibility = if (active) View.VISIBLE else View.GONE
        composerGapBeforeCancel.visibility = if (hasDraft) View.GONE else View.VISIBLE
        composerGapBeforeSend.visibility = if (active) View.VISIBLE else View.GONE

        microphoneButton.isEnabled = !hasDraft
        microphoneButton.setImageResource(
            if (isRecording) R.drawable.ic_stop_voice else R.drawable.ic_microphone
        )
        microphoneButton.imageTintList = ColorStateList.valueOf(
            if (isRecording) Color.WHITE else muted
        )
        microphoneButton.background = if (isRecording) {
            shape(Color.rgb(210, 64, 71), 24)
        } else {
            shape(Color.rgb(242, 246, 250), 24, Color.rgb(225, 232, 240))
        }
        microphoneButton.contentDescription =
            if (isRecording) "توقف ضبط" else "ضبط پیام صوتی"
        sendButton.background = shape(if (hasDraft) emerald else ink, 17)
        sendButton.contentDescription =
            if (active) "نام‌گذاری و ارسال پیام صوتی" else "افزودن یادداشت"

        if (active) {
            recordingIndicator.background = shape(
                if (isRecording) Color.rgb(210, 64, 71) else emerald, 7
            )
            recordingHeader.background = shape(
                if (isRecording) Color.rgb(255, 247, 247)
                else Color.rgb(242, 252, 248), 16
            )
            voiceStatus.text = if (isRecording) "در حال ضبط صدا" else "صدا آماده ارسال"
            voiceStatus.setTextColor(
                if (isRecording) Color.rgb(170, 55, 63) else Color.rgb(18, 123, 88)
            )
            voiceTimer.setTextColor(
                if (isRecording) Color.rgb(170, 55, 63) else Color.rgb(18, 123, 88)
            )
            voiceTimer.background = shape(Color.WHITE, 12,
                if (isRecording) Color.rgb(252, 225, 226)
                else Color.rgb(210, 242, 227))
            voiceTimer.text = if (hasDraft) formatDuration(pendingVoiceDurationMs)
                else formatDuration(SystemClock.elapsedRealtime() - VoiceRecordingService.recordingStartedAt)
        }
    }

    private fun syncVoiceDraft() {
        if (VoiceRecordingService.isRecording) return
        val prefs = getSharedPreferences(VoiceRecordingService.PREFS, MODE_PRIVATE)
        val stored = prefs.getString("draft_filename", null)
        val file = stored?.let { storedAudioFile(it) }
        if (file != null && file.isFile && file.length() >= 512L) {
            pendingVoiceFilename = stored
            pendingVoiceDurationMs = prefs.getLong("draft_duration", 0L)
            voiceTargetNoteId = prefs.getString("target_note_id", null)
        } else if (stored != null) {
            prefs.edit().remove("draft_filename").remove("draft_duration").apply()
        }
    }

    private fun onMicrophoneClicked() {
        if (VoiceRecordingService.isRecording) {
            finishVoiceRecording()
            return
        }
        syncVoiceDraft()
        if (pendingVoiceFilename != null) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 912)
            return
        }
        startVoiceRecording()
    }

    private fun startVoiceRecording() {
        if (VoiceRecordingService.isRecording || pendingVoiceFilename != null) return
        stopPlayback()
        try {
            startForegroundService(Intent(this, VoiceRecordingService::class.java).apply {
                action = VoiceRecordingService.ACTION_START
            })
            uiHandler.postDelayed({
                refreshVoiceComposer()
                if (VoiceRecordingService.isRecording) {
                    uiHandler.removeCallbacks(voiceTicker)
                    uiHandler.post(voiceTicker)
                } else {
                    Toast.makeText(this, "ضبط شروع نشد؛ مجوز و وضعیت میکروفون را بررسی کنید",
                        Toast.LENGTH_LONG).show()
                }
            }, 550L)
        } catch (_: Exception) {
            Toast.makeText(this, "برای ضبط، برنامه باید باز و مجوز میکروفون فعال باشد",
                Toast.LENGTH_LONG).show()
        }
    }

    private fun finishVoiceRecording() {
        if (!VoiceRecordingService.isRecording) return
        startService(Intent(this, VoiceRecordingService::class.java).apply {
            action = VoiceRecordingService.ACTION_STOP
        })
        uiHandler.removeCallbacks(voiceTicker)
        uiHandler.postDelayed({
            syncVoiceDraft()
            refreshVoiceComposer()
        }, 450L)
    }

    private fun discardVoiceRecording() {
        if (VoiceRecordingService.isRecording) {
            startService(Intent(this, VoiceRecordingService::class.java).apply {
                action = VoiceRecordingService.ACTION_CANCEL
            })
        }
        uiHandler.removeCallbacks(voiceTicker)
        pendingVoiceFilename?.let { storedAudioFile(it)?.delete() }
        pendingVoiceFilename = null
        pendingVoiceDurationMs = 0L
        voiceTargetNoteId = null
        getSharedPreferences(VoiceRecordingService.PREFS, MODE_PRIVATE).edit()
            .remove("draft_filename").remove("draft_duration")
            .remove("target_note_id").apply()
        uiHandler.postDelayed({ refreshVoiceComposer() }, 350L)
    }

    private fun showVoiceNoteNameDialog() {
        if (pendingVoiceFilename == null) return
        val nameInput = EditText(this).apply {
            setText("پیام صوتی")
            setSelection(text.length)
            hint = "مثلاً: ایده ویدئوی جدید"
            textSize = 16f
            typeface = textFont
            setTextColor(ink)
            setHintTextColor(Color.rgb(148, 163, 184))
            setSingleLine(true)
            gravity = Gravity.CENTER_VERTICAL or Gravity.RIGHT
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(13), 0, dp(13), 0)
            background = shape(Color.rgb(248, 250, 252), 13, softBorder)
            setFilters(arrayOf(android.text.InputFilter.LengthFilter(60)))
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(20), dp(6), dp(20), dp(5))
            addView(label("برای اینکه بعداً راحت‌تر پیدایش کنی، یک نام کوتاه انتخاب کن.", 13f, muted),
                LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
            addView(nameInput, LinearLayout.LayoutParams(-1, dp(50)))
            addView(label("مدت صدا: " + formatDuration(pendingVoiceDurationMs), 12f, indigo, true),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("نام‌گذاری پیام صوتی")
            .setView(content)
            .setNegativeButton("انصراف", null)
            .setPositiveButton("ثبت پیام صوتی", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val title = nameInput.text.toString().trim()
                if (title.isEmpty()) {
                    nameInput.error = "یک نام برای پیام صوتی بنویس"
                    return@setOnClickListener
                }
                submitVoiceNote(title)
                dialog.dismiss()
            }
        }
        dialog.show()
        nameInput.requestFocus()
    }

    private fun submitVoiceNote(title: String) {
        val filename = pendingVoiceFilename ?: return
        val file = storedAudioFile(filename)
        if (file == null || !file.isFile || file.length() == 0L) {
            discardVoiceRecording()
            Toast.makeText(this, "فایل صدای ضبط‌شده پیدا نشد", Toast.LENGTH_LONG).show()
            return
        }
        val targetId = voiceTargetNoteId ?: getSharedPreferences(
            VoiceRecordingService.PREFS, MODE_PRIVATE).getString("target_note_id", null)
        val target = notes.firstOrNull { it.id == targetId }
        if (target != null) {
            target.audioAttachments.add(AudioAttachment(filename, title, pendingVoiceDurationMs))
        } else {
            notes.add(Note(
                UUID.randomUUID().toString(),
                title,
                false,
                palette[Random.nextInt(palette.size)],
                audioFilename = filename,
                audioDurationMs = pendingVoiceDurationMs,
                category = selectedCategory
            ))
        }
        saveNotes()
        pendingVoiceFilename = null
        pendingVoiceDurationMs = 0L
        voiceTargetNoteId = null
        getSharedPreferences(VoiceRecordingService.PREFS, MODE_PRIVATE).edit()
            .remove("draft_filename").remove("draft_duration")
            .remove("target_note_id").apply()
        refreshVoiceComposer()
        renderNotes()
        if (target != null) {
            if (currentPageNoteId == target.id) renderOpenNotePage()
            else showNotePage(target)
        } else scroll.post { scroll.smoothScrollTo(0, noteList.bottom) }
    }

    private fun stopPlayback() {
        compactPlayerTicker?.let { uiHandler.removeCallbacks(it) }
        compactPlayerTicker = null
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
        headerTextSize = prefs.getInt("headerTextSize", 25).coerceIn(18, 30)
    }

    private fun saveAppearanceSettings() {
        getSharedPreferences("checklist_appearance", MODE_PRIVATE).edit()
            .putInt("noteTextSize", noteTextSize)
            .putInt("inputTextSize", inputTextSize)
            .putInt("searchTextSize", searchTextSize)
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
        addSizeControl(content, "اندازه عنوان برنامه", 18, 30, headerSize) { headerSize = it }

        AlertDialog.Builder(this)
            .setTitle("تنظیمات")
            .setView(scrollContent)
            .setNegativeButton("انصراف", null)
            .setNeutralButton("بازگشت به پیش‌فرض") { _, _ ->
                noteTextSize = 15
                inputTextSize = 14
                searchTextSize = 12
                headerTextSize = 25
                saveAppearanceSettings()
                applyAppearanceSettings()
                Toast.makeText(this, "اندازه‌ها به حالت پیش‌فرض برگشتند", Toast.LENGTH_SHORT).show()
            }
            .setPositiveButton("ذخیره تغییرات") { _, _ ->
                noteTextSize = noteSize
                inputTextSize = inputSize
                searchTextSize = searchSize
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
                    obj.optString("audioFilename", "").takeIf { it.isNotEmpty() },
                    obj.optLong("audioDurationMs", 0L).coerceAtLeast(0L),
                    category = obj.optString("category", "کتابخانه").ifBlank { "کتابخانه" },
                    parentId = obj.optString("parentId", "").takeIf { it.isNotEmpty() },
                    imageFilenames = mutableListOf<String>().apply {
                        val images = obj.optJSONArray("images") ?: JSONArray()
                        for (j in 0 until images.length()) {
                            val name = images.optString(j)
                            if (Regex("^[0-9a-fA-F-]{36}\\.(jpg|png|webp)$").matches(name)) add(name)
                        }
                    },
                    audioAttachments = mutableListOf<AudioAttachment>().apply {
                        val audio = obj.optJSONArray("audioAttachments") ?: JSONArray()
                        for (j in 0 until audio.length()) {
                            val item = audio.optJSONObject(j) ?: continue
                            val name = item.optString("filename")
                            if (Regex("^[0-9a-fA-F-]{36}\\.m4a$").matches(name)) add(
                                AudioAttachment(name, item.optString("title", "صوت"),
                                    item.optLong("durationMs", 0L))
                            )
                        }
                    },
                    dueAt = obj.optLong("dueAt", 0L).takeIf { it > 0L }
                ))
            }
        }
        // Preserve any custom category already stored in note data.
        var categoriesChanged = false
        notes.forEach { note ->
            if (note.category !in categories) {
                categories.add(note.category)
                categoriesChanged = true
            }
        }
        if (categoriesChanged) saveCategories()
    }

    private fun saveNotes() {
        val json = JSONArray()
        notes.forEach {
            json.put(JSONObject().apply {
                put("id", it.id)
                put("text", it.text)
                put("completed", it.completed)
                put("color", it.color)
                put("audioFilename", it.audioFilename ?: "")
                put("audioDurationMs", it.audioDurationMs)
                put("category", it.category)
                put("parentId", it.parentId ?: "")
                put("dueAt", it.dueAt ?: 0L)
                put("images", JSONArray().apply { it.imageFilenames.forEach { name -> put(name) } })
                put("audioAttachments", JSONArray().apply {
                    it.audioAttachments.forEach { item -> put(JSONObject().apply {
                        put("filename", item.filename)
                        put("title", item.title)
                        put("durationMs", item.durationMs)
                    }) }
                })
            })
        }
        getSharedPreferences("checklist_native", MODE_PRIVATE)
            .edit().putString("optimized_notes", json.toString()).apply()
    }
}
