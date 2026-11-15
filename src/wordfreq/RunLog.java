package wordfreq;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Детальний журнал одного запуску (results/run_log.txt, перезаписується щоразу): параметри й система,
 * блоки, робота кожного потоку, злиття, метрики з формулами, перевірка збігу, обидва топи і журнал кожного блоку.
 * У консоль виводиться лише короткий підсумок, усе детальне - сюди.
 */
final class RunLog {

    private RunLog() {}

    static void write(Path log, String mode, List<Path> files, int requestedKb, int blockKb, int topN, int threads,
                      boolean dynamic, Corpus corpus, long readNanos, long warmNanos,
                      WordCounter.Result seq, WordCounter.Result par, long differences) throws IOException {
        List<String> out = new ArrayList<>();
        out.add("ЖУРНАЛ ЗАПУСКУ: частота слів у текстовому корпусі (топ-" + topN + ")");
        out.add("Час запуску: " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        out.add(f("Система: Java %s (%s), %s %s, логічних ядер: %d, максимум купи JVM: %d МБ",
                System.getProperty("java.version"), System.getProperty("java.vendor"), System.getProperty("os.name"),
                System.getProperty("os.arch"), Main.CORES, Runtime.getRuntime().maxMemory() >> 20));
        out.add(f("Параметри: режим=%s, потоків=%d, розподіл=%s, топ=%d, блок: задано %d КБ, використано %d КБ",
                mode, threads, dynamic ? "dynamic" : "static", topN, requestedKb, blockKb));
        out.add("Усі часи - System.nanoTime(); час у таблицях потоків і блоків відраховано від початку етапу підрахунку.");

        out.add("");
        out.add("1. ЧИТАННЯ ДАНИХ (окремо від обчислень)");
        for (Path file : files) out.add(f("  файл %s: %,d байт", file, Files.size(file)));
        int min = corpus.blocks().stream().mapToInt(b -> b.length).min().orElse(0);
        int max = corpus.blocks().stream().mapToInt(b -> b.length).max().orElse(0);
        out.add(f("  блоків: %,d, розмір блоку: мін %,d, сер %,d, макс %,d байт (кожен блок обрізано по пробілу)",
                corpus.blocks().size(), min, corpus.bytes() / Math.max(1, corpus.blocks().size()), max));
        out.add(f("  час читання: %.3f мс", readNanos / 1e6));
        out.add(f("  прогрів JVM (по одному проходу кожної версії, не входить у вимір): %.3f мс", warmNanos / 1e6));

        if (seq != null) {
            out.add("");
            out.add("2. ПОСЛІДОВНА ВЕРСІЯ (еталон, потік " + seq.parts().get(0).name() + ")");
            out.add(f("  підрахунок слів: %.3f мс", seq.mapNanos() / 1e6));
            out.add(f("  вибір топ-%d: %.3f мс", topN, seq.topNanos() / 1e6));
            out.add(f("  T_seq = %.3f мс; слів: %,d; унікальних: %,d", seq.totalNanos() / 1e6, seq.words(), seq.counts().size()));
        }

        if (par != null) {
            out.add("");
            out.add(f("3. ПАРАЛЕЛЬНА ВЕРСІЯ: ExecutorService (newFixedThreadPool(%d)), %s", threads, dynamic
                    ? "блоки беруться з ConcurrentLinkedQueue (динамічний розподіл)"
                    : "кожен потік обробляє свій неперервний діапазон блоків (статичний розподіл)"));
            out.add("  Кожен потік рахує слова у власний HashMap (без замків); після invokeAll головний потік зливає мапи.");
            out.add("  потік | ім'я потоку        | старт, мс | кінець, мс | час, мс  | блоків | МБ      | слів        | унікальних");
            for (WordCounter.Part p : par.parts()) {
                out.add(f("  %5d | %-18s | %9.3f | %10.3f | %8.3f | %6d | %7.2f | %,11d | %,10d",
                        p.thread(), p.name(), rel(p.startNanos(), par), rel(p.endNanos(), par), p.nanos() / 1e6,
                        p.blocks(), p.bytes() / 1048576.0, p.words(), p.unique()));
            }
            long tMax = par.parts().stream().mapToLong(WordCounter.Part::nanos).max().orElse(0);
            double tAvg = par.parts().stream().mapToLong(WordCounter.Part::nanos).average().orElse(0);
            out.add(f("  T_max = %.3f мс, T_avg = %.3f мс, дисбаланс T_max / T_avg = %.4f", tMax / 1e6, tAvg / 1e6, tMax / tAvg));
            out.add(f("  етап підрахунку (від invokeAll до отримання всіх Future, бар'єр): %.3f мс", par.mapNanos() / 1e6));
            out.add(f("  злиття %d локальних мап (послідовно, головний потік): розміри %s -> загальна мапа %,d слів, %.3f мс",
                    par.parts().size(), par.parts().stream().map(p -> String.valueOf(p.unique())).toList(),
                    par.counts().size(), par.mergeNanos() / 1e6));
            out.add(f("  вибір топ-%d: %.3f мс", topN, par.topNanos() / 1e6));
            out.add(f("  T_par = підрахунок + злиття + вибір топу = %.3f мс; слів: %,d", par.totalNanos() / 1e6, par.words()));
        }

        if (seq != null && par != null) {
            double s = (double) seq.totalNanos() / par.totalNanos();
            double sequentialShare = (double) (par.mergeNanos() + par.topNanos()) / par.totalNanos();
            out.add("");
            out.add("4. МЕТРИКИ (час без читання файлу)");
            out.add(f("  S = T_seq / T_par = %.3f / %.3f = %.4f", seq.totalNanos() / 1e6, par.totalNanos() / 1e6, s));
            out.add(f("  E = S / p = %.4f / %d = %.4f", s, threads, s / threads));
            if (threads > 1) out.add(f("  Карп-Флатт e = (1/S - 1/p) / (1 - 1/p) = %.5f", Main.karpFlatt(s, threads)));
            out.add(f("  частка послідовної частини в T_par (злиття + топ) = %.5f", sequentialShare));

            out.add("");
            out.add("5. ПЕРЕВІРКА КОРЕКТНОСТІ");
            out.add(f("  слів: послідовна %,d, паралельна %,d", seq.words(), par.words()));
            out.add(f("  унікальних: послідовна %,d, паралельна %,d", seq.counts().size(), par.counts().size()));
            out.add(f("  слів з різною частотою (повне порівняння мап): %d", differences));
            out.add("  топ-" + topN + " однаковий: " + (seq.top().equals(par.top()) ? "так" : "ні"));
            out.add("  висновок: " + (differences == 0 && seq.top().equals(par.top()) ? "результати збігаються" : "РЕЗУЛЬТАТИ НЕ ЗБІГАЮТЬСЯ"));
        }

        out.add("");
        out.add("6. ТОП-" + topN + (seq != null && par != null ? ": послідовна | паралельна" : ""));
        WordCounter.Result any = seq != null ? seq : par;
        for (int i = 0; i < any.top().size(); i++) {
            String line = f("  %3d. %-14s %,12d", i + 1, any.top().get(i).getKey(), any.top().get(i).getValue());
            if (seq != null && par != null) {
                Map.Entry<String, Long> e = par.top().get(i);
                line += f("  | %-14s %,12d", e.getKey(), e.getValue());
            }
            out.add(line);
        }

        if (par != null) {
            out.add("");
            out.add("7. ЖУРНАЛ БЛОКІВ ПАРАЛЕЛЬНОЇ ВЕРСІЇ (у порядку початку обробки)");
            out.add("  блок  | потік | початок, мс | кінець, мс | тривалість, мс | байтів");
            List<long[]> events = new ArrayList<>(); // {блок, потік, початок, кінець}
            for (WordCounter.Part p : par.parts()) {
                for (long[] e : p.events()) events.add(new long[]{e[0], p.thread(), e[1], e[2]});
            }
            events.sort(Comparator.comparingLong(e -> e[2]));
            for (long[] e : events) {
                out.add(f("  %5d | %5d | %11.3f | %10.3f | %14.3f | %,d", e[0], e[1], rel(e[2], par), rel(e[3], par),
                        (e[3] - e[2]) / 1e6, corpus.blocks().get((int) e[0]).length));
            }
        }

        if (log.getParent() != null) Files.createDirectories(log.getParent());
        Files.write(log, out, StandardCharsets.UTF_8);
    }

    /** Час у мс від початку етапу підрахунку - щоб потоки й блоки мали спільну шкалу часу. */
    private static double rel(long nanos, WordCounter.Result r) {
        return (nanos - r.startNanos()) / 1e6;
    }

    private static String f(String format, Object... args) {
        return String.format(Locale.ROOT, format, args);
    }
}
