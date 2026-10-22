package com.ahbe.checklist

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.CalendarContract
import java.util.TimeZone

/**
 * Writes an event into a writable phone calendar. Calendar colors stay native;
 * the checklist's own reminder remains an Android system notification.
 */
object PhoneCalendarStore {
    fun hasPermissions(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 23) return true
        return context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
            context.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED
    }

    fun saveEvent(context: Context, title: String, startAt: Long, oldEventId: Long?): Long? {
        if (!hasPermissions(context)) return null
        return try {
            val projection = arrayOf(
                CalendarContract.Calendars._ID,
                CalendarContract.Calendars.IS_PRIMARY,
                CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL
            )
            var chosenId: Long? = null
            var chosenPrimary = false
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                projection,
                "visible=1 AND calendar_access_level>=500",
                null,
                "isPrimary DESC, calendar_displayName ASC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(CalendarContract.Calendars._ID)
                val primaryCol = cursor.getColumnIndexOrThrow(CalendarContract.Calendars.IS_PRIMARY)
                while (cursor.moveToNext()) {
                    val isPrimary = cursor.getInt(primaryCol) == 1
                    if (chosenId == null || (isPrimary && !chosenPrimary)) {
                        chosenId = cursor.getLong(idCol)
                        chosenPrimary = isPrimary
                    }
                    if (chosenPrimary) break
                }
            }
            val calendarId = chosenId ?: return null
            val values = ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, calendarId)
                put(CalendarContract.Events.TITLE, title)
                put(CalendarContract.Events.DESCRIPTION, "یادآوری ثبت‌شده از برنامه چک‌لیست")
                put(CalendarContract.Events.DTSTART, startAt)
                put(CalendarContract.Events.DTEND, startAt + 15L * 60L * 1000L)
                put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
                put(CalendarContract.Events.HAS_ALARM, 0)
            }
            val uri: Uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
                ?: return null
            val newId = ContentUris.parseId(uri)
            if (oldEventId != null && oldEventId != newId) deleteEvent(context, oldEventId)
            newId
        } catch (_: SecurityException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: RuntimeException) {
            null
        }
    }

    fun deleteEvent(context: Context, eventId: Long?) {
        if (eventId == null || !hasPermissions(context)) return
        try {
            context.contentResolver.delete(
                ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId),
                null,
                null
            )
        } catch (_: SecurityException) {
        } catch (_: RuntimeException) {
        }
    }
}
