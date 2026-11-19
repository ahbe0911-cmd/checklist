package com.ahbe.novi

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Select -> silently convert -> preview status -> explicit Send.
 * Dismissing before Send discards the temporary ZIP without adding a chat message.
 */
class NoviZipModal(
    private val activity: NoviActivity,
    private val chatId: String,
    private val initialTitle: String,
    private val send: (PhotoZipArchive.Archive) -> Unit
) {
    companion object { const val PICK_PHOTOS = 4101 }

    private var dialog: AlertDialog? = null
    private var titleField: EditText? = null
    private var status: TextView? = null
    private var picker: Button? = null
    private var sendButton: Button? = null
    private var draftId: String? = null
    private var processing = false
    private var closed = false

    private fun dp(value: Int) = (activity.resources.displayMetrics.density * value + .5f).toInt()

    fun show() {
        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(20), dp(12), dp(20), dp(18))
        }
        layout.addView(TextView(activity).apply {
            text = "نام فایل ZIP"
            textSize = 14f
        })
        val title = EditText(activity).apply {
            setText(PhotoZipArchive.safeTitle(initialTitle))
            setSingleLine()
            selectAll()
        }
        titleField = title
        layout.addView(title, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        val choose = Button(activity).apply {
            text = "▧  انتخاب چند عکس از گالری"
            isAllCaps = false
        }
        picker = choose
        layout.addView(choose, LinearLayout.LayoutParams(-1, -2))
        layout.addView(TextView(activity).apply {
            text = "عکس‌های HEIC / HEIF و سایر فرمت‌های پشتیبانی‌شده به JPG تبدیل می‌شوند؛ سقف هر JPG برابر ۴۹۵ کیلوبایت است."
            textSize = 12f
            setPadding(0, dp(12), 0, dp(7))
        })
        val state = TextView(activity).apply {
            text = "هنوز عکسی انتخاب نشده است."
            textSize = 13f
            gravity = Gravity.CENTER
        }
        status = state
        layout.addView(state, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        val modal = AlertDialog.Builder(activity)
            .setTitle("بسته عکس JPG / ZIP")
            .setView(layout)
            .setNegativeButton("انصراف", null)
            .setPositiveButton("ارسال", null)
            .create()
        dialog = modal
        modal.setCancelable(false)
        choose.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "image/*"
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
            runCatching { activity.startActivityForResult(intent, PICK_PHOTOS) }
                .onFailure {
                    Toast.makeText(activity, "انتخاب عکس در دسترس نیست", Toast.LENGTH_LONG).show()
                }
        }
        modal.setOnDismissListener {
            closed = true
            draftId?.let { PhotoZipArchive.delete(activity, it) }
            draftId = null
            dialog = null
        }
        modal.show()
        val sendNow = modal.getButton(AlertDialog.BUTTON_POSITIVE)
        sendButton = sendNow
        sendNow.isEnabled = false
        sendNow.setOnClickListener {
            if (processing) return@setOnClickListener
            val fileId = draftId ?: return@setOnClickListener
            val enteredName = PhotoZipArchive.safeTitle(title.text.toString())
            if (!PhotoZipArchive.rename(activity, fileId, enteredName)) return@setOnClickListener
            val entry = PhotoZipArchive.find(activity, fileId) ?: return@setOnClickListener
            draftId = null
            send(entry)
            modal.dismiss()
        }
        modal.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
            if (!processing) modal.dismiss()
        }
    }

    fun receive(data: Intent?) {
        if (closed || processing) return
        val selected = mutableListOf<Uri>()
        data?.clipData?.let { clips ->
            for (i in 0 until clips.itemCount) selected.add(clips.getItemAt(i).uri)
        }
        data?.data?.let { selected.add(it) }
        val unique = selected.distinctBy { it.toString() }
        if (unique.isEmpty()) return
        if (unique.size > 100) {
            status?.text = "حداکثر ۱۰۰ عکس در یک بسته قابل انتخاب است."
            return
        }
        draftId?.let { PhotoZipArchive.delete(activity, it) }
        draftId = null
        processing = true
        picker?.isEnabled = false
        titleField?.isEnabled = false
        sendButton?.isEnabled = false
        status?.text = "عملیات در حال انجام است…"
        Thread {
            val result = runCatching {
                PhotoZipArchive.create(activity, chatId, "بسته عکس‌ها", unique)
            }
            activity.runOnUiThread {
                if (closed || activity.isFinishing || activity.isDestroyed) {
                    result.getOrNull()?.let { PhotoZipArchive.delete(activity, it.id) }
                    return@runOnUiThread
                }
                processing = false
                picker?.isEnabled = true
                titleField?.isEnabled = true
                if (result.isSuccess) {
                    val ready = result.getOrThrow()
                    draftId = ready.id
                    status?.text = "✓ " + ready.photoCount + " عکس آماده شد؛ برای ثبت «ارسال» را بزنید."
                    sendButton?.isEnabled = true
                } else {
                    status?.text = "تبدیل انجام نشد: " +
                        (result.exceptionOrNull()?.message ?: "خطای نامشخص")
                }
            }
        }.start()
    }

    fun dismiss() {
        // Closing while a background conversion runs is safe: its result is discarded.
        dialog?.dismiss()
        closed = true
    }

    fun isVisible() = dialog != null
}
