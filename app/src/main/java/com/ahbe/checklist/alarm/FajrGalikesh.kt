package com.ahbe.checklist.alarm

import android.icu.util.ULocale
import android.icu.util.Calendar as IcuCalendar
import java.time.*
import java.util.Locale

/** Offline Fajr schedule for Galikesh, exactly transcribed from the user's PDF:
 * 1405/08/01 through 1406/08/24, local standard time in Iran.
 * Source is calculated using Tehran University's stated method; the PDF itself
 * explicitly says it is not an officially issued University of Tehran timetable.
 */
object FajrGalikesh {
    val ZONE: ZoneId = ZoneId.of("Asia/Tehran")
    private val times: Map<Int, List<String>> = mapOf(
        140508 to "0438 0439 0440 0440 0441 0442 0443 0444 0445 0446 0447 0448 0449 0449 0450 0451 0452 0453 0454 0455 0456 0457 0458 0459 0459 0500 0501 0502 0503 0504".split(' '),
        140509 to "0505 0506 0507 0507 0508 0509 0510 0511 0512 0512 0513 0514 0515 0516 0516 0517 0518 0519 0519 0520 0521 0522 0522 0523 0523 0524 0525 0525 0526 0526".split(' '),
        140510 to "0527 0527 0528 0528 0529 0529 0529 0530 0530 0530 0530 0531 0531 0531 0531 0531 0531 0531 0532 0532 0531 0531 0531 0531 0531 0531 0531 0530 0530 0530".split(' '),
        140511 to "0530 0529 0529 0528 0528 0527 0527 0526 0526 0525 0525 0524 0523 0522 0522 0521 0520 0519 0518 0518 0517 0516 0515 0514 0513 0512 0511 0510 0509 0507".split(' '),
        140512 to "0506 0505 0504 0503 0502 0500 0459 0458 0456 0455 0454 0452 0451 0450 0448 0447 0445 0444 0442 0441 0440 0438 0436 0435 0433 0432 0430 0429 0427".split(' '),
        140601 to "0426 0424 0422 0421 0419 0417 0416 0414 0413 0411 0409 0408 0406 0404 0403 0401 0359 0358 0356 0354 0352 0351 0349 0347 0346 0344 0342 0341 0339 0337 0336".split(' '),
        140602 to "0334 0333 0331 0329 0328 0326 0325 0323 0321 0320 0318 0317 0315 0314 0312 0311 0310 0308 0307 0305 0304 0303 0301 0300 0259 0258 0256 0255 0254 0253 0252".split(' '),
        140603 to "0251 0250 0249 0248 0247 0246 0245 0244 0243 0243 0242 0241 0241 0240 0240 0239 0239 0238 0238 0237 0237 0237 0237 0237 0237 0236 0236 0236 0237 0237 0237".split(' '),
        140604 to "0237 0237 0238 0238 0238 0239 0239 0240 0240 0241 0241 0242 0243 0244 0244 0245 0246 0247 0248 0249 0250 0251 0252 0253 0254 0255 0256 0257 0258 0259 0300".split(' '),
        140605 to "0301 0303 0304 0305 0306 0307 0308 0310 0311 0312 0313 0315 0316 0317 0318 0320 0321 0322 0323 0324 0326 0327 0328 0329 0330 0332 0333 0334 0335 0336 0338".split(' '),
        140606 to "0339 0340 0341 0342 0343 0344 0345 0347 0348 0349 0350 0351 0352 0353 0354 0355 0356 0357 0358 0359 0400 0401 0402 0403 0404 0405 0406 0407 0408 0409 0410".split(' '),
        140607 to "0411 0412 0413 0414 0414 0415 0416 0417 0418 0419 0420 0421 0422 0423 0423 0424 0425 0426 0427 0428 0429 0430 0431 0431 0432 0433 0434 0435 0436 0437".split(' '),
        140608 to "0438 0438 0439 0440 0441 0442 0443 0444 0445 0446 0447 0447 0448 0449 0450 0451 0452 0453 0454 0455 0456 0456 0457 0458".split(' ')
    )
    private fun persianParts(date: LocalDate): Pair<Int, Int> {
        val calendar = IcuCalendar.getInstance(
            android.icu.util.TimeZone.getTimeZone("Asia/Tehran"),
            ULocale("@calendar=persian")
        )
        calendar.timeInMillis = date.atTime(12, 0).atZone(ZONE).toInstant().toEpochMilli()
        val yearMonth = calendar.get(IcuCalendar.YEAR) * 100 +
            calendar.get(IcuCalendar.MONTH) + 1
        return yearMonth to calendar.get(IcuCalendar.DAY_OF_MONTH)
    }
    fun timeFor(date: LocalDate): LocalTime? {
        val (yearMonth, day) = persianParts(date)
        val fourDigits = times[yearMonth]?.getOrNull(day - 1) ?: return null
        return LocalTime.of(fourDigits.take(2).toInt(), fourDigits.drop(2).toInt())
    }
    fun dateLabel(epochMillis: Long): String {
        val date = Instant.ofEpochMilli(epochMillis).atZone(ZONE).toLocalDate()
        val (yearMonth, day) = persianParts(date)
        return String.format(Locale.US, "%04d/%02d/%02d", yearMonth / 100, yearMonth % 100, day)
    }
    fun next(item: AlarmItem, now: Long): Long? {
        val today = Instant.ofEpochMilli(now).atZone(ZONE).toLocalDate()
        // The first day in the provided timetable is 2026-10-23 (1405/08/01).
        val first = maxOf(today, LocalDate.of(2026, 10, 23))
        for (index in 0..390) {
            val date = first.plusDays(index.toLong())
            val (yearMonth, day) = persianParts(date)
            if (yearMonth > 140608 || (yearMonth == 140608 && day > 24)) break
            val digits = times[yearMonth]?.getOrNull(day - 1) ?: continue
            val weekdayBit = (date.dayOfWeek.value + 1) % 7
            if (item.days and (1 shl weekdayBit) == 0) continue
            val ring = date.atTime(digits.take(2).toInt(), digits.drop(2).toInt())
                .atZone(ZONE).toInstant().toEpochMilli()
            if (ring > now + 1000L) return ring
        }
        return null
    }
}
