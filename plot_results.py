"""Графіки, схеми та зведена таблиця для звіту: python plot_results.py (потрібен matplotlib).

Читає results/benchmark.csv і results/benchmark_threads.csv, пише results/*.png і results/analysis.csv.
"""
import csv
from collections import defaultdict
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.patches import FancyBboxPatch

RESULTS = Path(__file__).parent / "results"
PHYSICAL, LOGICAL = 6, 12  # AMD Ryzen 5 5600
BALANCE_P = 12
COLORS = ["#2a6fdb", "#e07b39", "#3a9d5d", "#c94040", "#7d5bbe"]

plt.rcParams.update({"font.size": 10, "axes.grid": True, "grid.alpha": 0.3, "savefig.dpi": 200,
                     "savefig.bbox": "tight", "font.family": "DejaVu Sans"})


def read(name):
    with open(RESULTS / name, encoding="utf-8") as f:
        return list(csv.DictReader(f))


rows = read("benchmark.csv")
threads_rows = read("benchmark_threads.csv")
files = list(dict.fromkeys(r["file"] for r in rows))
size_label = {f: f"{float(next(r['size_mb'] for r in rows if r['file'] == f)):.0f} МБ" for f in files}
big = files[-1]


def series(file, schedule="dynamic"):
    data = [r for r in rows if r["file"] == file and r["version"] == "par" and r["schedule"] == schedule]
    return [int(r["threads"]) for r in data], data


def t1(file):
    return next(float(r["time_ms"]) for r in rows if r["file"] == file and r["version"] == "seq")


def structural_f(file):
    """Послідовна частка f за структурою програми: (злиття локальних мап + вибір топу) / T1."""
    r = next(r for r in rows if r["file"] == file and r["schedule"] == "dynamic" and r["threads"] == str(LOGICAL))
    return (float(r["merge_ms"]) + float(r["top_ms"])) / t1(file)


def amdahl(f, p):
    return 1 / (f + (1 - f) / p)


def core_lines(ax):
    for x, label in ((PHYSICAL, "6 фіз. ядер"), (LOGICAL, "12 лог. ядер")):
        ax.axvline(x, color="grey", ls=":", lw=1)
        ax.text(x + 0.2, ax.get_ylim()[1] * 0.5, label, fontsize=8, color="grey", va="top", rotation=90)


# Прискорення з лінією S = p і кривою Амдала.
fig, ax = plt.subplots(figsize=(7, 4.5))
pmax = max(int(r["threads"]) for r in rows)
ax.plot([1, pmax], [1, pmax], "k--", lw=1, label="ідеальне S = p")
f_big = structural_f(big)
ps = list(range(1, pmax + 1))
ax.plot(ps, [amdahl(f_big, p) for p in ps], color="grey", lw=1.2, label=f"Амдал, f = {f_big:.4f} ({size_label[big]})")
for i, file in enumerate(files):
    p, data = series(file)
    ax.plot(p, [float(r["speedup"]) for r in data], "o-", color=COLORS[i], label=size_label[file])
ax.set(xlabel="Кількість потоків p", ylabel="Прискорення S(p) = T₁ / T(p)", title="Прискорення (розподіл через чергу)",
       ylim=(0, pmax + 1), xlim=(0, pmax + 1))
core_lines(ax)
ax.legend(fontsize=8)
fig.savefig(RESULTS / "speedup.png")

# Ефективність.
fig, ax = plt.subplots(figsize=(7, 4.5))
ax.axhline(1, color="k", ls="--", lw=1, label="ідеальне E = 1")
ax.plot(ps, [amdahl(f_big, p) / p for p in ps], color="grey", lw=1.2, label="Амдал")
for i, file in enumerate(files):
    p, data = series(file)
    ax.plot(p, [float(r["efficiency"]) for r in data], "o-", color=COLORS[i], label=size_label[file])
ax.set(xlabel="Кількість потоків p", ylabel="Ефективність E(p) = S(p) / p", title="Ефективність", ylim=(0, 1.15))
core_lines(ax)
ax.legend(fontsize=8)
fig.savefig(RESULTS / "efficiency.png")

# Час T(p); логарифмічна шкала, бо обсяги різняться в 100 разів.
fig, ax = plt.subplots(figsize=(7, 4.5))
for i, file in enumerate(files):
    p, data = series(file)
    ax.plot(p, [float(r["time_ms"]) / 1000 for r in data], "o-", color=COLORS[i], label=size_label[file])
    ax.axhline(t1(file) / 1000, color=COLORS[i], ls=":", lw=1)
ax.set(xlabel="Кількість потоків p", ylabel="Час обчислень T(p), с (лог. шкала)", yscale="log",
       title="Час обчислень (пунктир - послідовна версія T₁)")
ax.legend(fontsize=8)
fig.savefig(RESULTS / "time.png")

# Прискорення залежно від обсягу даних при p = кількість логічних ядер.
fig, ax = plt.subplots(figsize=(6, 4))
values = [float(next(r["speedup"] for r in series(f)[1] if r["threads"] == str(LOGICAL))) for f in files]
bars = ax.bar([size_label[f] for f in files], values, color=COLORS[:len(files)])
ax.bar_label(bars, fmt="%.2f")
ax.set(xlabel="Обсяг даних", ylabel=f"Прискорення S({LOGICAL})", title=f"Вплив обсягу даних на прискорення (p = {LOGICAL})")
fig.savefig(RESULTS / "size_speedup.png")

# Статичний і динамічний розподіл: прискорення на найбільшому файлі.
fig, ax = plt.subplots(figsize=(7, 4.5))
ax.plot([1, pmax], [1, pmax], "k--", lw=1, label="ідеальне S = p")
for schedule, color, label in (("dynamic", COLORS[0], "динамічний (черга блоків)"), ("static", COLORS[3], "статичний (рівні діапазони)")):
    p, data = series(big, schedule)
    ax.plot(p, [float(r["speedup"]) for r in data], "o-", color=color, label=label)
ax.set(xlabel="Кількість потоків p", ylabel="Прискорення S(p)", title=f"Статичний і динамічний розподіл, {size_label[big]}",
       ylim=(0, pmax + 1))
core_lines(ax)
ax.legend(fontsize=8)
fig.savefig(RESULTS / "schedule_speedup.png")

# Баланс навантаження: час кожного потоку в одному запуску.
fig, ax = plt.subplots(figsize=(8, 4.5))
width = 0.4
for k, (schedule, color, label) in enumerate((("static", COLORS[3], "статичний"), ("dynamic", COLORS[2], "динамічний (черга)"))):
    data = [r for r in threads_rows if r["file"] == big and r["schedule"] == schedule and r["threads"] == str(BALANCE_P)]
    times = [float(r["time_ms"]) / 1000 for r in data]
    ratio = max(times) / (sum(times) / len(times))
    ax.bar([int(r["thread"]) + (k - 0.5) * width for r in data], times, width, color=color,
           label=f"{label}: T_max = {max(times):.2f} с, T_max/T_avg = {ratio:.3f}")
ax.set(xlabel="Номер потоку", ylabel="Час роботи потоку, с", xticks=range(BALANCE_P),
       title=f"Баланс навантаження, {size_label[big]}, p = {BALANCE_P}")
ax.legend(fontsize=8, loc="lower right")
fig.savefig(RESULTS / "balance.png")

# Метрика Карпа-Флатта проти f за структурою алгоритму.
fig, ax = plt.subplots(figsize=(7, 4.5))
for i, file in enumerate(files):
    p, data = series(file)
    ax.plot(p[1:], [float(r["karp_flatt"]) for r in data[1:]], "o-", color=COLORS[i], label=f"Карп-Флатт, {size_label[file]}")
    ax.axhline(structural_f(file), color=COLORS[i], ls=":", lw=1)
ax.set(xlabel="Кількість потоків p", ylabel="Експериментальна послідовна частка e(p)",
       title="Метрика Карпа-Флатта (пунктир - f за структурою алгоритму)")
core_lines(ax)
ax.legend(fontsize=8)
fig.savefig(RESULTS / "karp_flatt.png")

# Зведена таблиця для звіту.
with open(RESULTS / "analysis.csv", "w", newline="", encoding="utf-8") as f:
    out = csv.writer(f)
    out.writerow(["file", "size_mb", "schedule", "threads", "time_ms", "speedup", "efficiency", "karp_flatt",
                  "f_structural", "amdahl_speedup", "imbalance"])
    for r in rows:
        if r["version"] != "par":
            continue
        fs = structural_f(r["file"])
        out.writerow([r["file"], r["size_mb"], r["schedule"], r["threads"], r["time_ms"], r["speedup"], r["efficiency"],
                      r["karp_flatt"], f"{fs:.5f}", f"{amdahl(fs, int(r['threads'])):.3f}", r["imbalance"]])


# Схеми: декомпозиція і взаємодія потоків.
def box(ax, x, y, w, h, text, color, fs=8.5):
    ax.add_patch(FancyBboxPatch((x - w / 2, y - h / 2), w, h, boxstyle="round,pad=0.02,rounding_size=0.08",
                                fc=color, ec="#33415c", lw=1.2))
    ax.text(x, y, text, ha="center", va="center", fontsize=fs, wrap=True)


def arrow(ax, x1, y1, x2, y2, color="#33415c", text=None):
    ax.annotate("", (x2, y2), (x1, y1), arrowprops=dict(arrowstyle="-|>", color=color, lw=1.2))
    if text:
        ax.text((x1 + x2) / 2 + 0.1, (y1 + y2) / 2, text, fontsize=7.5, color=color)


def canvas(w, h):
    fig, ax = plt.subplots(figsize=(w, h))
    ax.set(xlim=(0, w * 1.6), ylim=(0, h * 1.6))
    ax.axis("off")
    return fig, ax


fig, ax = canvas(10, 6)
box(ax, 2, 8.7, 3.2, 0.9, "Файли UTF-8 (до 1 ГБ)", "#e8e8e8")
box(ax, 8.3, 8.7, 6.6, 0.9, "Читання блоками по 256 КБ, розріз по пробілу\n(послідовно, головний потік, час T_read)", "#f9d9b6")
arrow(ax, 3.6, 8.7, 5.0, 8.7)
blocks_x = [2.2, 4.4, 6.6, 8.8, 11, 13.2]
for i, x in enumerate(blocks_x):
    box(ax, x, 6.9, 1.8, 0.7, "блок " + ("…" if i == 4 else ("B" + ("ₙ" if i == 5 else str(i + 1)))), "#fbecc0")
    arrow(ax, 8, 8.25, x, 7.25, "#999999")
ax.text(14.6, 6.9, "незалежні\nпідзадачі", fontsize=8, va="center")
for i, x in enumerate([2.7, 7.7, 12.7]):
    box(ax, x, 5.1, 4.6, 1.0, ["Потік 1", "Потік 2", "Потік p"][i] + ": декодування UTF-8,\nвиділення слів → локальний HashMap", "#cfe0fb", 7.5)
for bx, wx in zip(blocks_x, [2.7, 2.7, 7.7, 7.7, 12.7, 12.7]):
    arrow(ax, bx, 6.55, wx, 5.6, "#999999")
ax.plot([0.4, 14.3], [4.2, 4.2], color="#c94040", lw=2.5)
ax.text(14.4, 4.2, "бар'єр (invokeAll)\nточка синхронізації", fontsize=8, color="#c94040", va="center")
for x in [2.7, 7.7, 12.7]:
    arrow(ax, x, 4.6, 7.7, 3.25)
box(ax, 7.7, 2.8, 7.4, 0.9, "Злиття локальних словників (reduction, головний потік)", "#d3ecd8")
box(ax, 7.7, 1.4, 7.4, 0.9, "Сортування, вибір топ-100 → вихідний файл", "#d3ecd8")
arrow(ax, 7.7, 2.35, 7.7, 1.85)
ax.text(0.3, 6.0, "Map: паралельно", fontsize=9, color="#2a6fdb", weight="bold")
ax.text(0.2, 2.1, "Reduce:\nпослідовно (f)", fontsize=9, color="#3a9d5d", weight="bold", va="center")
ax.set_title("Схема декомпозиції за даними (map-reduce)", fontsize=12, weight="bold")
fig.savefig(RESULTS / "decomposition.png")

fig, ax = canvas(10, 6)
box(ax, 2.2, 8.6, 3.2, 0.9, "Вхідні файли", "#e8e8e8")
box(ax, 8, 8.6, 7.0, 0.9, "Головний потік (main): читання, поділ на блоки,\nстворення пулу, таймери System.nanoTime()", "#f9d9b6")
arrow(ax, 3.8, 8.6, 4.5, 8.6)
box(ax, 7.6, 6.7, 8.6, 0.9, "ConcurrentLinkedQueue<Integer> - черга номерів блоків\n(потокобезпечна, без замків; poll() видає номер рівно одному потоку)", "#fbecc0", 7.5)
arrow(ax, 8, 8.15, 8, 7.15)
box(ax, 14.0, 6.7, 3.0, 1.6, "Синхронізація:\n• черга (CAS)\n• локальні мапи\n• invokeAll / Future\n• reduction у main", "white", 7.5)
workers = [(2.4, "#2a6fdb"), (6.1, "#e07b39"), (9.9, "#3a9d5d"), (13.6, "#c94040")]
for i, (x, c) in enumerate(workers):
    name = "Робочий потік " + ("p" if i == 3 else str(i + 1))
    box(ax, x, 4.4, 3.2, 1.3, f"{name}\nExecutorService\nлокальний HashMap,\nвласний таймер tᵢ", c + "40")
    arrow(ax, 8, 6.25, x, 5.05, "#888888")
    arrow(ax, x, 3.75, 8, 2.75, "#3a9d5d")
ax.text(8.4, 5.6, "poll()", fontsize=8, color="#888888")
ax.text(10.6, 3.2, "Future<Map> (результат потоку)", fontsize=8, color="#3a9d5d")
box(ax, 8, 2.3, 8.6, 0.9, "main: merge локальних мап → топ-100 → файл, звіт часу і балансу", "#d3ecd8", 7.5)
ax.set_title("Схема взаємодії потоків (master-worker з пулом і чергою блоків)", fontsize=12, weight="bold")
fig.savefig(RESULTS / "architecture.png")

print("Графіки та analysis.csv записано в", RESULTS)
