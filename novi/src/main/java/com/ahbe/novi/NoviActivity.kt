package com.ahbe.novi

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.UUID

/** A separate Novi APK with private chats, voice messages and photo ZIPs, no alarms. */
class NoviActivity : Activity() {
    private companion object {
        const val SAVE_ZIP = 4102
        const val REQUEST_MIC = 4103
    }

    private val indigo = Color.rgb(91, 84, 223)
    private val ink = Color.rgb(31, 37, 61)
    private val muted = Color.rgb(112, 118, 142)
    private val surface = Color.rgb(250, 250, 255)
    private val pale = Color.rgb(239, 238, 255)
    private val outline = Color.rgb(225, 225, 241)

    private lateinit var chats: MutableList<NoviStore.Chat>
    private lateinit var screen: LinearLayout
    private var selectedId: String? = null
    private var zipModal: NoviZipModal? = null
    private var savingZipId: String? = null
    private var recorder: MediaRecorder? = null
    private var recordingFile: File? = null
    private var recordingChatId: String? = null
    private var pendingVoiceFile: File? = null
    private var pendingVoiceChatId: String? = null
    private var voiceDuration: Long = 0L
    private var recordingStart: Long = 0L
    private var player: MediaPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = surface
        window.navigationBarColor = surface
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        chats = NoviStore.load(this)
        selectedId = savedInstanceState?.getString("selected_chat")
        drawScreen()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("selected_chat", selectedId)
        super.onSaveInstanceState(outState)
    }

    @Deprecated("Novi handles thread navigation")
    override fun onBackPressed() {
        if (zipModal?.isVisible() == true) {
            zipModal?.dismiss()
            zipModal = null
        } else if (selectedId != null) {
            if (recorder != null) finishRecording()
            selectedId = null
            drawScreen()
        } else super.onBackPressed()
    }

    override fun onStop() {
        // Recording is local and foreground-only; no microphone foreground service is used.
        if (recorder != null) finishRecording()
        super.onStop()
    }

    override fun onDestroy() {
        zipModal?.dismiss()
        recorder?.let { runCatching { it.stop() }; it.release() }
        recordingFile?.delete()
        pendingVoiceFile?.delete()
        player?.release()
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (resources.displayMetrics.density * value + .5f).toInt()

    private fun shape(fill: Int, radius: Int, stroke: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(radius).toFloat()
            if (stroke != null) setStroke(dp(1), stroke)
        }

    private fun label(value: String, size: Float = 14f, color: Int = ink,
                      bold: Boolean = false): TextView = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        gravity = Gravity.CENTER_VERTICAL
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun action(value: String, filled: Boolean = false): Button =
        Button(this).apply {
            text = value
            textSize = 12f
            isAllCaps = false
            minWidth = 0
            minHeight = 0
            minimumWidth = 0
            minimumHeight = 0
            setPadding(dp(8), dp(6), dp(8), dp(6))
            setTextColor(if (filled) Color.WHITE else indigo)
            background = shape(if (filled) indigo else pale, 12)
        }

    private fun notice(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun persist() = NoviStore.save(this, chats)

    private fun drawScreen() {
        screen = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setBackgroundColor(surface)
        }
        setContentView(screen)
        val chat = chats.firstOrNull { it.id == selectedId }
        if (chat == null) {
            selectedId = null
            drawHome()
        } else drawConversation(chat)
    }

    private fun drawHome() {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(19), dp(22), dp(19), dp(15))
        }
        screen.addView(header, LinearLayout.LayoutParams(-1, -2))
        val brand = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        header.addView(brand, LinearLayout.LayoutParams(0, -2, 1f))
        brand.addView(label("Novi", 28f, indigo, true))
        brand.addView(label("گفت‌وگوهای شخصی", 12f, muted))
        val create = action("＋ گفت‌وگوی جدید", true)
        header.addView(create, LinearLayout.LayoutParams(-2, dp(42)))
        create.setOnClickListener { createChat() }
        val scroll = ScrollView(this)
        screen.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(17), dp(12), dp(17), dp(19))
        }
        scroll.addView(list)
        if (chats.isEmpty()) {
            list.addView(label("هنوز گفت‌وگویی ساخته نشده است.\nبرای شروع، «گفت‌وگوی جدید» را بزنید.",
                16f, muted).apply {
                gravity = Gravity.CENTER
                setPadding(dp(10), dp(70), dp(10), dp(10))
            })
        }
        chats.asReversed().forEach { chat ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(12), dp(12), dp(12))
                background = shape(Color.WHITE, 16, outline)
            }
            list.addView(row, LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(11)
            })
            val summary = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            row.addView(summary, LinearLayout.LayoutParams(0, -2, 1f))
            summary.addView(label(chat.title, 17f, ink, true))
            val last = chat.messages.lastOrNull()
            summary.addView(label(when (last?.kind) {
                "audio" -> "🎙 پیام صوتی"
                "zip" -> "📦 " + last.text + ".zip"
                "text" -> last.text.take(70)
                else -> "گفت‌وگوی خالی"
            }, 12f, muted).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            summary.setOnClickListener { selectedId = chat.id; drawScreen() }
            val enter = action("باز کردن")
            row.addView(enter, LinearLayout.LayoutParams(-2, dp(40)))
            enter.setOnClickListener { selectedId = chat.id; drawScreen() }
            val manage = action("⋮")
            row.addView(manage, LinearLayout.LayoutParams(dp(37), dp(40)).apply {
                marginStart = dp(4)
            })
            manage.setOnClickListener { showChatOptions(chat) }
        }
    }

    private fun createChat() {
        val entry = EditText(this).apply {
            hint = "مثلاً جمع‌خانی"
            setSingleLine()
            setPadding(dp(20), dp(10), dp(20), dp(10))
        }
        AlertDialog.Builder(this).setTitle("گفت‌وگوی جدید")
            .setView(entry).setNegativeButton("انصراف", null)
            .setPositiveButton("ایجاد") { _, _ ->
                val name = entry.text.toString().trim()
                if (name.isNotEmpty()) {
                    val chat = NoviStore.Chat(title = name)
                    chats.add(chat)
                    persist()
                    selectedId = chat.id
                    drawScreen()
                }
            }.show()
    }

    private fun showChatOptions(chat: NoviStore.Chat) {
        AlertDialog.Builder(this).setTitle(chat.title)
            .setItems(arrayOf("تغییر نام", "حذف گفت‌وگو")) { _, selected ->
                if (selected == 0) {
                    val input = EditText(this).apply {
                        setText(chat.title)
                        selectAll()
                        setSingleLine()
                    }
                    AlertDialog.Builder(this).setTitle("تغییر نام گفت‌وگو")
                        .setView(input).setNegativeButton("انصراف", null)
                        .setPositiveButton("ذخیره") { _, _ ->
                            val name = input.text.toString().trim()
                            if (name.isNotBlank()) {
                                chat.title = name
                                persist()
                                drawScreen()
                            }
                        }.show()
                } else {
                    AlertDialog.Builder(this).setTitle("حذف گفت‌وگو")
                        .setMessage("همه پیام‌ها و فایل‌های پیوست این گفت‌وگو حذف شوند؟")
                        .setNegativeButton("انصراف", null)
                        .setPositiveButton("حذف") { _, _ ->
                            PhotoZipArchive.deleteForNote(this, chat.id)
                            chat.messages.filter { it.kind == "audio" }.forEach {
                                audioFile(it.fileId)?.delete()
                            }
                            chats.removeAll { it.id == chat.id }
                            if (selectedId == chat.id) selectedId = null
                            persist()
                            drawScreen()
                        }.show()
                }
            }.show()
    }

    private fun drawConversation(chat: NoviStore.Chat) {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(13), dp(14), dp(13))
            setBackgroundColor(Color.WHITE)
        }
        screen.addView(header, LinearLayout.LayoutParams(-1, -2))
        val back = action("‹  بازگشت")
        header.addView(back, LinearLayout.LayoutParams(-2, dp(42)))
        back.setOnClickListener {
            if (recorder != null) finishRecording()
            selectedId = null
            drawScreen()
        }
        header.addView(label(chat.title, 20f, ink, true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(0, -2, 1f))
        val options = action("⋮")
        header.addView(options, LinearLayout.LayoutParams(dp(40), dp(42)))
        options.setOnClickListener { showChatOptions(chat) }

        val scroll = ScrollView(this).apply { isFillViewport = true }
        screen.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val messages = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(15), dp(12), dp(15), dp(17))
        }
        scroll.addView(messages)
        if (chat.messages.isEmpty()) {
            messages.addView(label("این گفت‌وگو هنوز پیامی ندارد.\nپیام بنویسید یا صدا و بسته عکس ارسال کنید.",
                15f, muted).apply {
                gravity = Gravity.CENTER
                setPadding(dp(10), dp(45), dp(10), 0)
            })
        }
        chat.messages.forEach { message -> addMessageCard(chat, message, messages) }
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }

        val compose = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(7), dp(7), dp(7), dp(7))
            background = shape(Color.WHITE, 16, outline)
        }
        screen.addView(compose, LinearLayout.LayoutParams(-1, -2).apply {
            marginStart = dp(12)
            marginEnd = dp(12)
            topMargin = dp(7)
            bottomMargin = dp(11)
        })
        val field = EditText(this).apply {
            hint = if (pendingVoiceChatId == chat.id && pendingVoiceFile != null)
                "صدا آماده ارسال است…" else "یک پیام بنویسید..."
            textSize = 14f
            setSingleLine()
            background = null
            imeOptions = EditorInfo.IME_ACTION_SEND
            setPadding(dp(7), 0, dp(7), 0)
        }
        compose.addView(field, LinearLayout.LayoutParams(0, dp(44), 1f))
        // The new ZIP button is immediately adjacent to the existing mic control.
        val imageZip = ImageButton(this).apply {
            setImageResource(R.drawable.ic_gallery_zip)
            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            imageTintList = ColorStateList.valueOf(indigo)
            background = shape(pale, 12)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            contentDescription = "انتخاب عکس و ساخت فایل ZIP"
        }
        compose.addView(imageZip, LinearLayout.LayoutParams(dp(44), dp(44)).apply {
            marginStart = dp(5)
        })
        imageZip.setOnClickListener { showPhotoZipModal(chat) }
        val microphone = ImageButton(this).apply {
            setImageResource(R.drawable.ic_mic_novi)
            imageTintList = ColorStateList.valueOf(
                if (recorder == null) indigo else Color.rgb(204, 45, 62))
            background = shape(if (recorder == null) pale else Color.rgb(255, 226, 229), 12)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            contentDescription = if (recorder == null) "شروع ضبط صدا" else "پایان ضبط صدا"
        }
        compose.addView(microphone, LinearLayout.LayoutParams(dp(44), dp(44)).apply {
            marginStart = dp(5)
        })
        microphone.setOnClickListener { toggleVoice(chat) }
        val send = action("➤", true)
        compose.addView(send, LinearLayout.LayoutParams(dp(43), dp(44)).apply {
            marginStart = dp(5)
        })
        val submit = {
            if (recorder != null) {
                finishRecording()
                drawScreen()
            } else if (pendingVoiceChatId == chat.id && pendingVoiceFile != null) {
                val file = pendingVoiceFile!!
                chat.messages.add(NoviStore.Message(kind = "audio", text = "پیام صوتی",
                    fileId = file.name, durationMs = voiceDuration))
                pendingVoiceFile = null
                pendingVoiceChatId = null
                persist()
                drawScreen()
            } else {
                val typed = field.text.toString().trim()
                if (typed.isNotBlank()) {
                    chat.messages.add(NoviStore.Message(kind = "text", text = typed))
                    persist()
                    drawScreen()
                }
            }
        }
        send.setOnClickListener { submit() }
        field.setOnEditorActionListener { _, _, _ -> submit(); true }
        if (recorder != null && recordingChatId == chat.id) {
            screen.addView(label("● ضبط صدا در حال انجام است. برای توقف، میکروفون را بزنید.",
                12f, Color.rgb(195, 55, 63)), LinearLayout.LayoutParams(-1, -2).apply {
                marginStart = dp(16); marginEnd = dp(16); bottomMargin = dp(8)
            })
        } else if (pendingVoiceFile != null && pendingVoiceChatId == chat.id) {
            screen.addView(label("✓ صدا آماده است؛ برای ثبت روی «➤» بزنید.",
                12f, indigo), LinearLayout.LayoutParams(-1, -2).apply {
                marginStart = dp(16); marginEnd = dp(16); bottomMargin = dp(8)
            })
        }
    }

    private fun addMessageCard(chat: NoviStore.Chat, msg: NoviStore.Message, list: LinearLayout) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(13), dp(11), dp(13), dp(11))
            background = shape(if (msg.kind == "text") pale else Color.WHITE, 14,
                if (msg.kind == "text") null else outline)
        }
        list.addView(card, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(10)
        })
        when (msg.kind) {
            "text" -> card.addView(label(msg.text, 15f, ink))
            "audio" -> {
                card.addView(label("🎙 پیام صوتی", 15f, ink, true))
                val actions = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                }
                card.addView(actions, LinearLayout.LayoutParams(-1, -2).apply {
                    topMargin = dp(8)
                })
                val play = action("▶ پخش")
                actions.addView(play, LinearLayout.LayoutParams(0, dp(39), 1f))
                play.setOnClickListener { playVoice(msg) }
                val share = action("اشتراک‌گذاری")
                actions.addView(share, LinearLayout.LayoutParams(0, dp(39), 1f).apply {
                    marginStart = dp(6)
                })
                share.setOnClickListener { shareVoice(msg) }
            }
            "zip" -> {
                val archive = PhotoZipArchive.find(this, msg.fileId)
                card.addView(label("📦 " + PhotoZipArchive.exportName(archive?.title ?: msg.text),
                    15f, ink, true))
                card.addView(label(if (archive != null)
                    archive.photoCount.toString() + " عکس JPG · " +
                        ((archive.bytes + 1023L) / 1024L).toString() + " KB"
                    else "فایل ZIP پیدا نشد", 12f, muted),
                    LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
                val actions = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutDirection = View.LAYOUT_DIRECTION_RTL
                }
                card.addView(actions, LinearLayout.LayoutParams(-1, -2).apply {
                    topMargin = dp(8)
                })
                val share = action("اشتراک‌گذاری")
                actions.addView(share, LinearLayout.LayoutParams(0, dp(39), 1f))
                share.setOnClickListener { shareZip(msg.fileId) }
                val export = action("ذخیره")
                actions.addView(export, LinearLayout.LayoutParams(0, dp(39), 1f).apply {
                    marginStart = dp(5)
                })
                export.setOnClickListener { chooseZipSave(msg.fileId) }
                val more = action("⋮")
                actions.addView(more, LinearLayout.LayoutParams(dp(42), dp(39)).apply {
                    marginStart = dp(5)
                })
                more.setOnClickListener { zipOptions(chat, msg) }
            }
        }
    }

    private fun showPhotoZipModal(chat: NoviStore.Chat) {
        if (zipModal?.isVisible() == true) return
        zipModal = NoviZipModal(this, chat.id, chat.title) { created ->
            val actualChat = chats.firstOrNull { it.id == chat.id }
            if (actualChat == null) {
                PhotoZipArchive.delete(this, created.id)
            } else {
                actualChat.messages.add(NoviStore.Message(kind = "zip",
                    text = created.title, fileId = created.id))
                persist()
                selectedId = chat.id
                drawScreen()
            }
            zipModal = null
        }
        zipModal?.show()
    }

    private fun zipOptions(chat: NoviStore.Chat, msg: NoviStore.Message) {
        AlertDialog.Builder(this).setTitle("مدیریت ZIP")
            .setItems(arrayOf("تغییر نام", "حذف فایل")) { _, choice ->
                if (choice == 0) {
                    val edit = EditText(this).apply {
                        setText(PhotoZipArchive.find(this@NoviActivity, msg.fileId)?.title
                            ?: msg.text)
                        selectAll()
                        setSingleLine()
                    }
                    AlertDialog.Builder(this).setTitle("تغییر نام ZIP").setView(edit)
                        .setNegativeButton("انصراف", null)
                        .setPositiveButton("ذخیره") { _, _ ->
                            val newName = edit.text.toString().trim()
                            if (newName.isNotBlank() &&
                                PhotoZipArchive.rename(this, msg.fileId, newName)) {
                                msg.text = PhotoZipArchive.safeTitle(newName)
                                persist()
                                drawScreen()
                            }
                        }.show()
                } else {
                    AlertDialog.Builder(this).setTitle("حذف فایل ZIP")
                        .setMessage("فایل ZIP از این گفت‌وگو حذف شود؟")
                        .setNegativeButton("انصراف", null)
                        .setPositiveButton("حذف") { _, _ ->
                            if (PhotoZipArchive.delete(this, msg.fileId)) {
                                chat.messages.removeAll { it.id == msg.id }
                                persist()
                                drawScreen()
                            } else notice("حذف فایل انجام نشد")
                        }.show()
                }
            }.show()
    }

    private fun shareZip(id: String) {
        runCatching {
            val uri = PhotoZipArchive.uri(this, id)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newUri(contentResolver, "Novi ZIP", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, "اشتراک‌گذاری فایل ZIP"))
        }.onFailure { notice("اشتراک‌گذاری ZIP در دسترس نیست") }
    }

    private fun chooseZipSave(id: String) {
        val archive = PhotoZipArchive.find(this, id) ?: return
        savingZipId = id
        val document = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/zip"
            putExtra(Intent.EXTRA_TITLE, PhotoZipArchive.exportName(archive.title))
        }
        runCatching { startActivityForResult(document, SAVE_ZIP) }
            .onFailure { notice("انتخاب محل ذخیره در دسترس نیست") }
    }

    private fun audioFile(filename: String): File? =
        if (Regex("^[0-9a-fA-F-]{36}\\.m4a$").matches(filename))
            File(File(filesDir, "novi_audio"), filename) else null

    private fun toggleVoice(chat: NoviStore.Chat) {
        if (recorder != null) {
            finishRecording()
            drawScreen()
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC)
            return
        }
        beginRecording(chat.id)
    }

    private fun beginRecording(chatId: String) {
        val folder = File(filesDir, "novi_audio").apply { mkdirs() }
        pendingVoiceFile?.delete()
        pendingVoiceFile = null
        pendingVoiceChatId = null
        val target = File(folder, UUID.randomUUID().toString() + ".m4a")
        @Suppress("DEPRECATION")
        val audio = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()
        val started = runCatching {
            audio.setAudioSource(MediaRecorder.AudioSource.MIC)
            audio.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            audio.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            audio.setAudioEncodingBitRate(96000)
            audio.setAudioSamplingRate(44100)
            audio.setOutputFile(target.absolutePath)
            audio.prepare()
            audio.start()
        }
        if (started.isFailure) {
            audio.release()
            target.delete()
            notice("شروع ضبط صدا ناموفق بود")
            return
        }
        recorder = audio
        recordingFile = target
        recordingChatId = chatId
        recordingStart = System.currentTimeMillis()
        drawScreen()
    }

    private fun finishRecording() {
        val active = recorder ?: return
        recorder = null
        val target = recordingFile
        val chatId = recordingChatId
        recordingFile = null
        recordingChatId = null
        val okay = runCatching { active.stop() }.isSuccess
        active.release()
        if (okay && target?.isFile == true && target.length() > 0L) {
            pendingVoiceFile = target
            pendingVoiceChatId = chatId
            voiceDuration = System.currentTimeMillis() - recordingStart
        } else {
            target?.delete()
            notice("فایل صوتی ضبط نشد")
        }
    }

    private fun playVoice(msg: NoviStore.Message) {
        val file = audioFile(msg.fileId)
        if (file == null || !file.isFile) { notice("فایل صوتی پیدا نشد"); return }
        player?.release()
        val current = MediaPlayer()
        runCatching {
            current.setDataSource(file.absolutePath)
            current.prepare()
            current.setOnCompletionListener {
                it.release()
                if (player === it) player = null
            }
            current.start()
            player = current
        }.onFailure { current.release(); notice("پخش صدا انجام نشد") }
    }

    private fun shareVoice(msg: NoviStore.Message) {
        runCatching {
            val uri = NoviAudioProvider.uri(this, msg.fileId, "پیام صوتی")
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "audio/mp4"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newUri(contentResolver, "Novi Voice", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, "اشتراک‌گذاری پیام صوتی"))
        }.onFailure { notice("امکان اشتراک‌گذاری صدا وجود ندارد") }
    }

    @Deprecated("Using Android document picker for local files")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            NoviZipModal.PICK_PHOTOS -> if (resultCode == RESULT_OK) zipModal?.receive(data)
            SAVE_ZIP -> {
                val source = savingZipId?.let { PhotoZipArchive.file(this, it) }
                savingZipId = null
                val target = if (resultCode == RESULT_OK) data?.data else null
                if (source != null && target != null) {
                    Thread {
                        val success = runCatching {
                            contentResolver.openOutputStream(target, "w")?.use { out ->
                                source.inputStream().use { it.copyTo(out) }
                            } ?: error("Unable to save file")
                        }.isSuccess
                        runOnUiThread {
                            notice(if (success) "فایل ZIP ذخیره شد" else "ذخیره ZIP انجام نشد")
                        }
                    }.start()
                }
            }
        }
    }

    @Deprecated("Android runtime permission callback")
    override fun onRequestPermissionsResult(requestCode: Int,
                                            permissions: Array<out String>,
                                            grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_MIC) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                selectedId?.let { beginRecording(it) }
            } else notice("برای ضبط صدا به مجوز میکروفون نیاز است")
        }
    }
}
