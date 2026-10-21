import { useEffect, useState } from "react";
import {
  CalendarDays,
  CheckCircle2,
  Clock3,
  ClipboardList,
  Landmark,
  ListTodo,
  Moon,
  Plus,
  RefreshCw,
  Sparkles,
  Trash2,
  X,
  AlertCircle,
  ExternalLink,
} from "lucide-react";
import { getCurrentMonthEvents } from "./eventsService";

const STORAGE_KEY = "checklist-items-v1";
const faNumber = new Intl.NumberFormat("fa-IR");

function loadTasks() {
  try {
    const value = localStorage.getItem(STORAGE_KEY);
    const tasks = value ? JSON.parse(value) : [];
    return Array.isArray(tasks) ? tasks : [];
  } catch {
    return [];
  }
}

function makeId() {
  return typeof crypto !== "undefined" && crypto.randomUUID
    ? crypto.randomUUID()
    : String(Date.now()) + "-" + Math.random().toString(36).slice(2);
}

function EventGroup({ title, events, kind }) {
  const Icon = kind === "national" ? Landmark : Moon;

  return (
    <section className="event-group">
      <div className="group-heading">
        <span className={"group-icon " + kind}><Icon size={20} /></span>
        <h3>{title}</h3>
        <span className="count-pill">{faNumber.format(events.length)}</span>
      </div>

      {events.length === 0 ? (
        <p className="empty-category">مناسبتی در این دسته برای این ماه ثبت نشده.</p>
      ) : (
        <ul className="event-list">
          {events.map((event) => (
            <li className="event-row" key={event.id}>
              <span className="event-day">
                <small>روز</small>
                <strong>{faNumber.format(event.day)}</strong>
              </span>
              <div className="event-copy">
                <p>{event.title}</p>
                {event.isHoliday && <span className="holiday-label">تعطیل رسمی</span>}
              </div>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

function EventsModal({ onClose }) {
  const [data, setData] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [retry, setRetry] = useState(0);

  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);
    setError("");

    getCurrentMonthEvents(controller.signal)
      .then(setData)
      .catch((err) => {
        if (err.name !== "AbortError") {
          setError(err.message || "خطا در دریافت اطلاعات تقویم.");
        }
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false);
      });

    return () => controller.abort();
  }, [retry]);

  useEffect(() => {
    const oldOverflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";

    function onKeyDown(event) {
      if (event.key === "Escape") onClose();
    }

    window.addEventListener("keydown", onKeyDown);
    return () => {
      document.body.style.overflow = oldOverflow;
      window.removeEventListener("keydown", onKeyDown);
    };
  }, [onClose]);

  const national = data ? data.events.filter((event) => event.category === "national") : [];
  const religious = data ? data.events.filter((event) => event.category === "religious") : [];
  const monthTitle = data
    ? data.monthName + " " + faNumber.format(data.year)
    : "ماه جاری";

  return (
    <div className="modal-backdrop" onMouseDown={(event) => {
      if (event.target === event.currentTarget) onClose();
    }}>
      <section className="modal-card" role="dialog" aria-modal="true" aria-labelledby="events-title">
        <header className="modal-header">
          <div className="modal-heading">
            <span className="modal-icon"><CalendarDays size={23} /></span>
            <div>
              <p className="eyebrow">تقویم شمسی ایران</p>
              <h2 id="events-title">مناسبت‌های {monthTitle}</h2>
              {data && <p className="today-line">امروز: {faNumber.format(data.today)} {data.monthName}</p>}
            </div>
          </div>
          <button className="icon-button" onClick={onClose} aria-label="بستن تقویم"><X size={20} /></button>
        </header>

        <div className="modal-content">
          {loading && (
            <div className="state-message">
              <RefreshCw className="spin" size={30} />
              <p>در حال دریافت مناسبت‌های این ماه...</p>
            </div>
          )}

          {!loading && error && (
            <div className="error-panel">
              <AlertCircle size={30} />
              <p>{error}</p>
              <button className="primary-button retry-button" onClick={() => setRetry((value) => value + 1)}>
                <RefreshCw size={16} /> تلاش دوباره
              </button>
            </div>
          )}

          {!loading && !error && data && (
            <div className="event-content">
              <div className="month-summary">
                <div>
                  <strong>مناسبت‌های این ماه</strong>
                  <p>تفکیک‌شده بر اساس نوع مناسبت</p>
                </div>
                <span>{faNumber.format(data.events.length)} مورد</span>
              </div>
              <EventGroup title="مناسبت‌های ملی" events={national} kind="national" />
              <EventGroup title="مناسبت‌های مذهبی" events={religious} kind="religious" />
              {data.events.length === 0 && (
                <p className="empty-category">مناسبتی برای نمایش در این ماه پیدا نشد.</p>
              )}
            </div>
          )}
        </div>

        <footer className="modal-footer">
          <span>منبع: Persian-Calendar.com</span>
          <a href="https://www.persian-calendar.com/developers" target="_blank" rel="noreferrer">
            مستندات API <ExternalLink size={13} />
          </a>
        </footer>
      </section>
    </div>
  );
}

export default function App() {
  const [tasks, setTasks] = useState(loadTasks);
  const [input, setInput] = useState("");
  const [eventsOpen, setEventsOpen] = useState(false);

  useEffect(() => {
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(tasks));
    } catch (error) {
      console.warn("ذخیره‌سازی در مرورگر در دسترس نیست.", error);
    }
  }, [tasks]);

  function addTask(event) {
    event.preventDefault();
    const text = input.trim();
    if (!text) return;
    setTasks((current) => [{ id: makeId(), text, completed: false }, ...current]);
    setInput("");
  }

  function toggleTask(id) {
    setTasks((current) => current.map((task) =>
      task.id === id ? { ...task, completed: !task.completed } : task
    ));
  }

  function deleteTask(id) {
    setTasks((current) => current.filter((task) => task.id !== id));
  }

  const completed = tasks.filter((task) => task.completed).length;
  const pending = tasks.length - completed;

  const stats = [
    { label: "کل کارها", value: tasks.length, icon: ListTodo, tone: "indigo" },
    { label: "انجام‌شده", value: completed, icon: CheckCircle2, tone: "green" },
    { label: "باقی‌مانده", value: pending, icon: Clock3, tone: "amber" },
  ];

  return (
    <main className="app-shell" dir="rtl">
      <div className="page-container">
        <header className="app-header">
          <div>
            <div className="brand-line">
              <span className="brand-mark"><Sparkles size={19} /></span>
              <span>برنامه‌ریز شخصی</span>
            </div>
            <h1>چک‌لیست من</h1>
            <p className="subtitle">کارهات رو مرتب کن و قدم‌به‌قدم پیش برو.</p>
          </div>
          <button className="calendar-button" onClick={() => setEventsOpen(true)}>
            <CalendarDays size={20} />
            تقویم مناسبت‌ها
          </button>
        </header>

        <section className="stats-grid" aria-label="آمار چک‌لیست">
          {stats.map((stat) => {
            const Icon = stat.icon;
            return (
              <article className="stat-card" key={stat.label}>
                <span className={"stat-icon " + stat.tone}><Icon size={22} /></span>
                <div>
                  <p>{stat.label}</p>
                  <strong>{faNumber.format(stat.value)}</strong>
                </div>
              </article>
            );
          })}
        </section>

        <section className="checklist-card">
          <div className="section-heading">
            <div>
              <h2>کارهای من</h2>
              <p>کار جدید اضافه کن یا وضعیت کارهای قبلی رو تغییر بده.</p>
            </div>
            <span className="section-icon"><ClipboardList size={22} /></span>
          </div>

          <form className="add-form" onSubmit={addTask}>
            <label className="sr-only" htmlFor="new-task">عنوان کار جدید</label>
            <input
              id="new-task"
              value={input}
              onChange={(event) => setInput(event.target.value)}
              placeholder="مثلاً: مطالعه، خرید یا تماس..."
              maxLength={300}
            />
            <button className="add-button" type="submit" disabled={!input.trim()}>
              <Plus size={19} /> افزودن کار
            </button>
          </form>

          {tasks.length === 0 ? (
            <div className="empty-tasks">
              <ClipboardList size={36} />
              <strong>هنوز کاری اضافه نکردی</strong>
              <p>اولین کار رو وارد کن تا شروع کنیم.</p>
            </div>
          ) : (
            <ul className="task-list">
              {tasks.map((task) => (
                <li className={"task-row" + (task.completed ? " is-complete" : "")} key={task.id}>
                  <input
                    type="checkbox"
                    checked={task.completed}
                    onChange={() => toggleTask(task.id)}
                    aria-label={"تغییر وضعیت " + task.text}
                  />
                  <span className="task-text">{task.text}</span>
                  {task.completed && <CheckCircle2 className="completed-icon" size={18} />}
                  <button className="delete-button" type="button" onClick={() => deleteTask(task.id)} aria-label={"حذف " + task.text} title="حذف کار">
                    <Trash2 size={17} />
                  </button>
                </li>
              ))}
            </ul>
          )}
        </section>

        <p className="privacy-note">کارها روی همین دستگاه ذخیره می‌شن و به حساب کاربری نیاز ندارن.</p>
      </div>

      {eventsOpen && <EventsModal onClose={() => setEventsOpen(false)} />}
    </main>
  );
}