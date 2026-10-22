package com.ahbe.checklist

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
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
import android.widget.TextView
import android.icu.util.PersianCalendar
import android.icu.util.Calendar as IcuCalendar
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
        val color: Int
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

    private lateinit var dateLabel: TextView
    private lateinit var progressText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var noteList: LinearLayout
    private lateinit var emptyState: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private val filterButtons = mutableMapOf<Filter, TextView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.WHITE
        window.navigationBarColor = surface
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        loadNotes()
        buildScreen()
        updateDate()
        renderNotes()
    }

    override fun onResume() {
        super.onResume()
        if (::dateLabel.isInitialized) updateDate()
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
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
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
        heading.addView(label("یادداشت‌های من", 25f, ink, true))

        val divider = View(this).apply { setBackgroundColor(softBorder) }
        heading.addView(divider, LinearLayout.LayoutParams(dp(1), dp(18)).apply {
            marginStart = dp(10)
            marginEnd = dp(10)
        })
        dateLabel = label("", 13f, muted, true)
        heading.addView(dateLabel)

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

        val searchInput = EditText(this).apply {
            hint = "جستجوی یادداشت..."
            textSize = 13f
            setTextColor(ink)
            setHintTextColor(Color.rgb(148, 163, 184))
            background = shape(Color.rgb(248, 250, 252), 12, softBorder)
            setSingleLine(true)
            setPadding(dp(11), 0, dp(11), 0)
            compoundDrawablePadding = dp(6)
            setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_search, 0, 0, 0)
        }
        searchAndFilters.addView(searchInput, LinearLayout.LayoutParams(0, dp(41), 1f).apply {
            marginEnd = dp(10)
        })
        searchInput.addTextChangedListener(object : TextWatcher {
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
        scroll.addView(noteList, ScrollView.LayoutParams(-1, -2))

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
            textSize = 15f
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
        bottom.addView(input, LinearLayout.LayoutParams(0, -1, 1f))
        val add = ImageButton(this).apply {
            setImageResource(R.drawable.ic_arrow_up)
            imageTintList = ColorStateList.valueOf(Color.WHITE)
            background = shape(ink, 12)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(10), dp(10), dp(10), dp(10))
            contentDescription = "افزودن یادداشت"
            setOnClickListener { addNote() }
        }
        bottom.addView(add, LinearLayout.LayoutParams(dp(45), dp(45)))
    }

    private fun updateFilters() {
        filterButtons.forEach { (key, tab) ->
            val selected = key == filter
            tab.setTextColor(if (selected) Color.WHITE else muted)
            tab.background = if (selected) shape(ink, 9) else null
        }
    }

    private fun updateDate() {
        val date = PersianCalendar()
        val day = date.get(IcuCalendar.DAY_OF_MONTH)
        val month = date.get(IcuCalendar.MONTH)
        val year = date.get(IcuCalendar.YEAR)
        val months = arrayOf(
            "فروردین", "اردیبهشت", "خرداد", "تیر", "مرداد", "شهریور",
            "مهر", "آبان", "آذر", "دی", "بهمن", "اسفند"
        )
        dateLabel.text = fa(day) + " " + months[month.coerceIn(0, 11)] + " " + fa(year)
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

        val textLabel = label(note.text, 15f, if (note.completed) muted else ink, !note.completed)
            .apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                if (note.completed) paintFlags = paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            }
        tapArea.addView(textLabel, LinearLayout.LayoutParams(0, -2, 1f))

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
                    obj.optInt("color", palette[i % palette.size])
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
            })
        }
        getSharedPreferences("checklist_native", MODE_PRIVATE)
            .edit().putString("optimized_notes", json.toString()).apply()
    }
}
