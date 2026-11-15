package wordfreq;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Підрахунок частоти слів: послідовна версія (еталон) і паралельна за схемою map-reduce.
 * Map: кожен потік рахує слова своїх блоків у власну локальну мапу (без спільних даних і замків).
 * Reduce: після завершення всіх потоків головний потік один раз зливає локальні мапи в одну.
 */
final class WordCounter {

    /**
     * Звіт про роботу одного потоку (або всієї послідовної версії).
     * startNanos / endNanos - System.nanoTime() на початку й у кінці роботи потоку;
     * blocks, bytes, words - скільки блоків, байтів і слів оброблено;
     * unique - скільки різних слів у локальній мапі потоку;
     * events - журнал блоків: {номер блоку, початок, кінець} для run_log.txt.
     */
    record Part(int thread, String name, long startNanos, long endNanos, int blocks, long bytes, long words,
                int unique, List<long[]> events) {
        long nanos() { return endNanos - startNanos; }
    }

    /**
     * Результат одного запуску.
     * counts - частота кожного слова; top - топ-N; parts - звіти потоків;
     * startNanos - початок етапу підрахунку (від нього рахуються часи в журналі);
     * mapNanos - підрахунок, mergeNanos - злиття, topNanos - вибір топу.
     */
    record Result(Map<String, Long> counts, List<Map.Entry<String, Long>> top, List<Part> parts,
                  long startNanos, long mapNanos, long mergeNanos, long topNanos) {
        /** Час обчислень без читання файлу: саме він іде в T_seq і T_par. */
        long totalNanos() { return mapNanos + mergeNanos + topNanos; }
        long words() { return parts.stream().mapToLong(Part::words).sum(); }
    }

    /**
     * Порядок топу: спершу за спаданням частоти, при однаковій частоті - за алфавітом.
     * Завдяки цьому топ завжди однаковий і його можна порівнювати між версіями.
     */
    static final Comparator<Map.Entry<String, Long>> ORDER =
            Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey());

    private WordCounter() {}

    /** Послідовна версія (еталон): один потік проходить усі блоки по черзі в одну мапу. */
    static Result sequential(List<byte[]> blocks, int topN) {
        long start = System.nanoTime();
        Map<String, Long> counts = new HashMap<>();
        long bytes = 0, words = 0;
        for (byte[] block : blocks) {
            words += countWords(block, counts);
            bytes += block.length;
        }
        long end = System.nanoTime();
        Part part = new Part(0, Thread.currentThread().getName(), start, end, blocks.size(), bytes, words,
                counts.size(), List.of());

        long topStart = System.nanoTime();
        List<Map.Entry<String, Long>> top = top(counts, topN);
        // Злиття в послідовній версії немає, тому mergeNanos = 0.
        return new Result(counts, top, List.of(part), start, end - start, 0, System.nanoTime() - topStart);
    }

    /**
     * Паралельна версія (master-worker з пулом потоків).
     * dynamic = true: потоки беруть номери блоків зі спільної черги, хто звільнився - бере наступний;
     * dynamic = false: кожен потік заздалегідь отримує свій неперервний діапазон блоків однакової довжини.
     */
    static Result parallel(List<byte[]> blocks, int threads, boolean dynamic, int topN) throws Exception {
        // Пул із фіксованою кількістю потоків: потоки створюються один раз і виконують задачі.
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            // Потокобезпечна неблокувальна черга номерів блоків: poll() видає кожен номер рівно одному потоку.
            Queue<Integer> queue = new ConcurrentLinkedQueue<>();
            for (int i = 0; i < blocks.size(); i++) queue.add(i);
            List<Callable<Map<String, Long>>> tasks = new ArrayList<>();
            // Кожен потік пише лише у свою комірку parts[id]; головний потік читає їх після invokeAll.
            Part[] parts = new Part[threads];
            for (int t = 0; t < threads; t++) {
                int id = t;
                // Задача одного потоку (map): обробити свої блоки й повернути локальну мапу.
                tasks.add(() -> {
                    long start = System.nanoTime();
                    Map<String, Long> local = new HashMap<>(); // локальна мапа: інші потоки її не бачать, замки не потрібні
                    List<long[]> events = new ArrayList<>();
                    long bytes = 0, words = 0;
                    // Статичний розподіл: потік id обробляє блоки [from, to), межі рівномірні й для непарного p.
                    int[] next = {(int) ((long) id * blocks.size() / threads)};
                    int to = (int) ((long) (id + 1) * blocks.size() / threads);
                    while (true) {
                        // Наступний блок: із черги (dynamic) або наступний зі свого діапазону (static); null - роботи немає.
                        Integer index = dynamic ? queue.poll() : next[0] < to ? Integer.valueOf(next[0]++) : null;
                        if (index == null) break;
                        long blockStart = System.nanoTime();
                        words += countWords(blocks.get(index), local);
                        bytes += blocks.get(index).length;
                        events.add(new long[]{index, blockStart, System.nanoTime()});
                    }
                    parts[id] = new Part(id, Thread.currentThread().getName(), start, System.nanoTime(),
                            events.size(), bytes, words, local.size(), events);
                    return local;
                });
            }

            long start = System.nanoTime();
            // invokeAll запускає всі задачі й чекає, доки завершиться кожна, - це бар'єр між map і reduce.
            List<Future<Map<String, Long>>> futures = pool.invokeAll(tasks);
            List<Map<String, Long>> locals = new ArrayList<>();
            for (Future<Map<String, Long>> f : futures) locals.add(f.get());
            long mapNanos = System.nanoTime() - start;

            // Reduce: послідовна частина алгоритму, виконує головний потік.
            long mergeStart = System.nanoTime();
            Map<String, Long> counts = merge(locals);
            long mergeNanos = System.nanoTime() - mergeStart;

            long topStart = System.nanoTime();
            List<Map.Entry<String, Long>> top = top(counts, topN);
            return new Result(counts, top, List.of(parts), start, mapNanos, mergeNanos, System.nanoTime() - topStart);
        } finally {
            pool.shutdown(); // завершуємо потоки пулу навіть у разі помилки
        }
    }

    /**
     * Злиття (reduce): частоти з усіх локальних мап додаються в одну.
     * Як основу беремо найбільшу мапу, щоб переносити менше записів. Виконується один раз, одним потоком.
     */
    static Map<String, Long> merge(List<Map<String, Long>> locals) {
        Map<String, Long> result = locals.stream().max(Comparator.comparingInt(Map::size)).orElseGet(HashMap::new);
        for (Map<String, Long> local : locals) {
            if (local == result) continue;
            local.forEach((word, count) -> result.merge(word, count, Long::sum));
        }
        return result;
    }

    /** Топ-n слів за порядком ORDER; записи копіюються, щоб топ не залежав від подальших змін мапи. */
    static List<Map.Entry<String, Long>> top(Map<String, Long> counts, int n) {
        return counts.entrySet().stream().sorted(ORDER).limit(n).map(e -> Map.entry(e.getKey(), e.getValue())).toList();
    }

    /**
     * Виділяє слова з блоку і додає їх у мапу counts; повертає кількість слів у блоці.
     * Слово - неперервна послідовність літер Unicode у нижньому регістрі. Апостроф між двома літерами
     * залишається в слові (п'ять, don't); три різновиди апострофа зберігаються як '.
     */
    static long countWords(byte[] block, Map<String, Long> counts) {
        String text = new String(block, StandardCharsets.UTF_8); // декодування лише цього блоку, не всього файлу
        StringBuilder word = new StringBuilder(32);
        long words = 0;
        int n = text.length();
        for (int i = 0; i <= n; i++) {
            char c = i < n ? text.charAt(i) : ' '; // зайвий пробіл у кінці завершує останнє слово
            if (Character.isLetter(c)) {
                word.append(Character.toLowerCase(c));
            } else if (isApostrophe(c) && word.length() > 0 && i + 1 < n && Character.isLetter(text.charAt(i + 1))) {
                word.append('\'');
            } else if (word.length() > 0) {
                counts.merge(word.toString(), 1L, Long::sum); // +1 до частоти слова
                word.setLength(0);
                words++;
            }
        }
        return words;
    }

    private static boolean isApostrophe(char c) {
        return c == '\'' || c == '’' || c == 'ʼ';
    }
}
