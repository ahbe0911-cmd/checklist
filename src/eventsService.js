const API_BASE_URL = "https://www.persian-calendar.com/api";

async function fetchJson(url, signal) {
  let response;

  try {
    response = await fetch(url, {
      method: "GET",
      headers: { Accept: "application/json" },
      signal,
    });
  } catch (error) {
    if (error.name === "AbortError") throw error;
    throw new Error("اتصال به سرویس تقویم برقرار نشد. اینترنت را بررسی کن.");
  }

  if (!response.ok) {
    if (response.status === 429) {
      throw new Error("سهمیه درخواست‌های تقویم موقتاً تمام شده است.");
    }
    if (response.status === 403) {
      throw new Error("دسترسی به سرویس تقویم محدود شده است.");
    }
    throw new Error("دریافت مناسبت‌ها ناموفق بود (" + response.status + ").");
  }

  return response.json();
}

export async function getCurrentMonthEvents(signal) {
  const today = await fetchJson(
    API_BASE_URL + "/today?tz=Asia%2FTehran",
    signal
  );

  const year = Number(today && today.sh && today.sh.year);
  const month = Number(today && today.sh && today.sh.month);
  const day = Number(today && today.sh && today.sh.day);
  const monthName = today && today.sh && today.sh.month_name_fa;

  if (!Number.isInteger(year) || !Number.isInteger(month) ||
      month < 1 || month > 12 || !monthName) {
    throw new Error("تاریخ دریافت‌شده از سرویس معتبر نیست.");
  }

  const params = new URLSearchParams({
    year: String(year),
    month_sh: String(month),
  });

  const result = await fetchJson(
    API_BASE_URL + "/events?" + params.toString(),
    signal
  );

  const sourceEvents = Array.isArray(result && result.data)
    ? result.data
    : [];

  const events = sourceEvents
    .filter((event) => ["national", "religious"].includes(event.category))
    .map((event, index) => ({
      id: event.slug || String(year) + "-" + String(month) + "-" + String(event.day_sh) + "-" + String(index),
      day: Number(event.day_sh),
      title: String(event.title_fa || "").trim(),
      category: event.category,
      isHoliday: event.is_holiday === true,
    }))
    .filter((event) =>
      Number.isInteger(event.day) &&
      event.day >= 1 &&
      event.day <= 31 &&
      event.title.length > 0
    )
    .sort((a, b) => a.day - b.day || a.title.localeCompare(b.title, "fa"));

  return { year, month, monthName, today: day, events };
}