package wordfreq;

import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Серія експериментів (режим bench) для звіту.
 * Для кожного файлу: послідовна версія, потім паралельна для кожного p і обох способів розподілу.
 * Кожна точка - медіана runs вимірів після warmup прогрівних запусків. Результати пишуться в CSV.
 */
final class Benchmark {

    /** Один запуск версії, яку міряємо (послідовної або паралельної з конкретним p). */
    interface Run { WordCounter.Result get() throws Exception; }

    private Benchmark() {}

    /**
     * files - файли різного обсягу; threads - список p; runs - вимірів на точку; warmup - прогрівів;
     * csv - основна таблиця; поруч пишеться *_threads.csv із часом кожного потоку.
     */
    static void run(List<Path> files, int[] threads, int runs, int warmup, int blockSize, int topN, Path csv) throws Exception {
        if (csv.getParent() != null) Files.createDirectories(csv.getParent());
        Path threadsCsv = csv.resolveSibling(csv.getFileName().toString().replace(".csv", "") + "_threads.csv");
        try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(csv, StandardCharsets.UTF_8));
             PrintWriter perThread = new PrintWriter(Files.newBufferedWriter(threadsCsv, StandardCharsets.UTF_8))) {
            out.println("file,size_mb,version,schedule,threads,read_ms,time_ms,map_ms,merge_ms,top_ms,words,unique,speedup,efficiency,karp_flatt,imbalance,correct");
            perThread.println("file,size_mb,schedule,threads,thread,time_ms,blocks,words");

            for (Path file : files) {
                // 1. Читання файлу міряємо окремо: у час обчислень воно не входить.
                long start = System.nanoTime();
                Corpus corpus = Corpus.read(List.of(file), blockSize);
                double readMs = (System.nanoTime() - start) / 1e6;
                double sizeMb = corpus.bytes() / 1048576.0;
                System.out.printf("%n== %s: %.1f МБ, %d блоків, читання %.0f мс%n", file, sizeMb, corpus.blocks().size(), readMs);

                // 2. Послідовна версія: її медіанний час T1 - база для прискорення S = T1 / T(p).
                WordCounter.Result seq = median(runs, warmup, () -> WordCounter.sequential(corpus.blocks(), topN), null);
                double t1 = seq.totalNanos() / 1e6;
                System.out.printf("  seq        T = %9.1f мс%n", t1);
                out.println(row(file, sizeMb, "seq", "-", 1, readMs, seq, t1));

                // 3. Паралельна версія для кожного p, спершу з чергою (dynamic), потім із рівними діапазонами (static).
                for (String schedule : List.of("dynamic", "static")) {
                    for (int p : threads) {
                        WordCounter.Result par = median(runs, warmup,
                                () -> WordCounter.parallel(corpus.blocks(), p, schedule.equals("dynamic"), topN), seq);
                        out.println(row(file, sizeMb, "par", schedule, p, readMs, par, t1));
                        // Час кожного потоку медіанного запуску - для діаграми балансу навантаження.
                        for (WordCounter.Part part : par.parts()) {
                            perThread.println(String.format(Locale.ROOT, "%s,%.1f,%s,%d,%d,%.3f,%d,%d", file.getFileName(), sizeMb,
                                    schedule, p, part.thread(), part.nanos() / 1e6, part.blocks(), part.words()));
                        }
                        System.out.printf("  %-7s p=%2d T = %9.1f мс  S = %5.2f  дисбаланс = %.3f%n",
                                schedule, p, par.totalNanos() / 1e6, t1 / (par.totalNanos() / 1e6), imbalance(par));
                        out.flush(); // зберігаємо рядок одразу: довга серія не втратиться при перериванні
                        perThread.flush();
                    }
                }
            }
        }
        System.out.println("\nРезультати: " + csv + ", " + threadsCsv);
    }

    /**
     * Медіана вимірів: спершу warmup прогрівних запусків (JIT-компіляція, не міряються),
     * потім runs вимірів. Кожен вимір звіряється з еталоном (послідовною версією); при розбіжності - помилка.
     * Повертає запуск із медіанним часом.
     */
    private static WordCounter.Result median(int runs, int warmup, Run run, WordCounter.Result reference) throws Exception {
        for (int i = 0; i < warmup; i++) run.get();
        List<WordCounter.Result> results = new ArrayList<>();
        for (int i = 0; i < runs; i++) {
            WordCounter.Result r = run.get();
            if (reference != null && !(r.counts().equals(reference.counts()) && r.top().equals(reference.top()))) {
                throw new IllegalStateException("паралельний результат не збігається з послідовним");
            }
            results.add(r);
        }
        results.sort(Comparator.comparingLong(WordCounter.Result::totalNanos));
        return results.get(results.size() / 2);
    }

    /**
     * Один рядок CSV: час і його складові, S = T1 / T, E = S / p,
     * метрика Карпа-Флатта e = (1/S - 1/p) / (1 - 1/p) (для p > 1) та дисбаланс потоків.
     */
    private static String row(Path file, double sizeMb, String version, String schedule, int p, double readMs,
                              WordCounter.Result r, double t1) {
        double t = r.totalNanos() / 1e6;
        double s = t1 / t;
        String karpFlatt = p > 1 ? String.format(Locale.ROOT, "%.5f", (1 / s - 1.0 / p) / (1 - 1.0 / p)) : "";
        return String.format(Locale.ROOT, "%s,%.1f,%s,%s,%d,%.1f,%.3f,%.3f,%.3f,%.3f,%d,%d,%.4f,%.4f,%s,%.4f,%s",
                file.getFileName(), sizeMb, version, schedule, p, readMs, t, r.mapNanos() / 1e6, r.mergeNanos() / 1e6,
                r.topNanos() / 1e6, r.words(), r.counts().size(), s, s / p, karpFlatt, imbalance(r), true); // median() кидає помилку при розбіжності
    }

    /**
     * Коефіцієнт дисбалансу T_max / T_avg: час найдовшого потоку до середнього часу потоків.
     * 1.0 - ідеальний баланс; 1.2 - найповільніший потік працював на 20 % довше за середній.
     */
    static double imbalance(WordCounter.Result r) {
        long max = r.parts().stream().mapToLong(WordCounter.Part::nanos).max().orElse(0);
        double avg = r.parts().stream().mapToLong(WordCounter.Part::nanos).average().orElse(1);
        return max / avg;
    }
}
