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
        // Крок 1: Засікаємо початок обчислень (етап Map) і створюємо єдину мапу: слово -> кількість.
        long start = System.nanoTime();
        Map<String, Long> counts = new HashMap<>();
        long bytes = 0, words = 0;

        // Крок 2: Послідовно проходимо кожен блок і рахуємо слова у спільну мапу counts.
        for (byte[] block : blocks) {
            words += countWords(block, counts);
            bytes += block.length;
        }
        long end = System.nanoTime();

        // Крок 3: Фіксуємо статистику роботи цього єдиного потоку.
        Part part = new Part(0, Thread.currentThread().getName(), start, end, blocks.size(), bytes, words,
                counts.size(), List.of());

        // Крок 4: Окремо засікаємо вибір топ-N найчастіших слів (сортування записів мапи).
        long topStart = System.nanoTime();
        List<Map.Entry<String, Long>> top = top(counts, topN);
        return new Result(counts, top, List.of(part), start, end - start, 0, System.nanoTime() - topStart);
    }

    /**
     * Паралельна версія (master-worker з пулом потоків).
     * dynamic = true: потоки беруть номери блоків зі спільної черги, хто звільнився - бере наступний;
     * dynamic = false: кожен потік заздалегідь отримує свій неперервний діапазон блоків однакової довжини.
     */
    static Result parallel(List<byte[]> blocks, int threads, boolean dynamic, int topN) throws Exception {
        // Крок 1: Створюємо фіксований пул потоків (try-finally гарантує shutdown навіть при помилці).
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            // Крок 2: Готуємо неблокувальну чергу номерів блоків (для dynamic режиму) та масив звітів parts.
            Queue<Integer> queue = new ConcurrentLinkedQueue<>();
            for (int i = 0; i < blocks.size(); i++) queue.add(i);
            List<Callable<Map<String, Long>>> tasks = new ArrayList<>();
            Part[] parts = new Part[threads]; // кожен потік пише лише у свою комірку parts[id]

            // Крок 3: Формуємо по одній задачі (Map) на кожен потік.
            for (int t = 0; t < threads; t++) {
                int id = t;
                tasks.add(() -> {
                    long start = System.nanoTime();
                    Map<String, Long> local = new HashMap<>(); // локальна мапа потоку (без замків)
                    List<long[]> events = new ArrayList<>(); // журнал блоків для run_log.txt
                    long bytes = 0, words = 0;

                    // Статичний діапазон блоків [from, to) для потоку id (ділимо кількість блоків blocks.size(), не розмір у байтах!)
                    // Приклад (10 блоків на 3 потоки):
                    //   id=0: next=0*10/3=0, to=1*10/3=3 -> блоки [0, 1, 2] (3 шт.)
                    //   id=1: next=1*10/3=3, to=2*10/3=6 -> блоки [3, 4, 5] (3 шт.)
                    //   id=2: next=2*10/3=6, to=3*10/3=10 -> блоки [6, 7, 8, 9] (4 шт.)
                    int[] next = {(int) ((long) id * blocks.size() / threads)};
                    int to = (int) ((long) (id + 1) * blocks.size() / threads);

                    while (true) {
                        // Беремо наступний індекс блоку: dynamic -> із черги, static -> зі свого діапазону.
                        Integer index = dynamic ? queue.poll() : next[0] < to ? Integer.valueOf(next[0]++) : null;
                        if (index == null) break; // блоків більше немає
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

            // Крок 4: Запускаємо всі задачі й чекаємо завершення кожного потоку (бар'єр між Map і Reduce).
            long start = System.nanoTime();
            List<Future<Map<String, Long>>> futures = pool.invokeAll(tasks);
            List<Map<String, Long>> locals = new ArrayList<>();
            for (Future<Map<String, Long>> f : futures) locals.add(f.get());
            long mapNanos = System.nanoTime() - start;

            // Крок 5 (Reduce): Головний потік зливає локальні мапи в одну і рахує топ-N.
            long mergeStart = System.nanoTime();
            Map<String, Long> counts = merge(locals);
            long mergeNanos = System.nanoTime() - mergeStart;

            long topStart = System.nanoTime();
            List<Map.Entry<String, Long>> top = top(counts, topN);
            return new Result(counts, top, List.of(parts), start, mapNanos, mergeNanos, System.nanoTime() - topStart);
        } finally {
            pool.shutdown();
        }
    }

    /**
     * Злиття (reduce): частоти з усіх локальних мап додаються в одну.
     * Як основу беремо найбільшу мапу, щоб переносити менше записів. Виконується один раз, одним потоком.
     */
    static Map<String, Long> merge(List<Map<String, Long>> locals) {
        // Крок 1: Беремо найбільшу локальну мапу за основу (щоб не копіювати її записи даремно).
        Map<String, Long> result = locals.stream().max(Comparator.comparingInt(Map::size)).orElseGet(HashMap::new);
        // Крок 2: Переносимо слова з решти мап у result, сумуючи частоти однакових слів.
        for (Map<String, Long> local : locals) {
            if (local == result) continue; // пропускаємо саму базову мапу, щоб не подвоїти її слова
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
     *
     * Приклад: текст "Кіт, п'ять! кіт"
     * -> 'К','і','т' -> "кіт" (у мапу: кіт=1)
     * -> ',' (роздільник, ігноруємо)
     * -> "п'ять" (апостроф між 'п' і 'я' зберігся -> у мапу: п'ять=1)
     * -> "кіт" (уже є в мапі -> merge додає +1, стає кіт=2).
     */
    static long countWords(byte[] block, Map<String, Long> counts) {
        // Крок 1: Декодуємо з UTF-8 у String лише поточний 256 КБ блок (не весь гігабайтний файл одразу).
        String text = new String(block, StandardCharsets.UTF_8);

        // Крок 2: Багаторазовий буфер для складання одного слова (32 символи вистачить майже для будь-якого слова без розширень).
        StringBuilder word = new StringBuilder(32);
        long words = 0;
        int n = text.length();

        // Крок 3: Проходимо по всіх символах до i == n включно.
        // Коли i == n, штучно підставляємо пробіл ' ', щоб гарантовано завершити й записати останнє слово блоку.
        for (int i = 0; i <= n; i++) {
            char c = i < n ? text.charAt(i) : ' ';

            if (Character.isLetter(c)) {
                // Якщо це літера (латиниця, кирилиця тощо) — приводимо до нижнього регістру й доклеюємо до слова.
                word.append(Character.toLowerCase(c));
            } else if (isApostrophe(c) && word.length() > 0 && i + 1 < n && Character.isLetter(text.charAt(i + 1))) {
                // Якщо це апостроф І перед ним вже є літера І після нього йде літера (напр. "п'ять") —
                // зберігаємо його як стандартний '\'' (інакше, якщо це лапки з краю, він просто спрацює як роздільник).
                word.append('\'');
            } else if (word.length() > 0) {
                // Сюди потрапляємо, коли символ c — НЕ літера і НЕ апостроф всередині слова (тобто слово закінчилось).
                // Перевірка word.length() > 0 потрібна, щоб проігнорувати зайві пробіли/коми підряд (коли буфер порожній).
                String finishedWord = word.toString();
                // counts.merge(...) — це скорочений запис для:
                //   if (counts.containsKey(w)) counts.put(w, counts.get(w) + 1); else counts.put(w, 1L);
                counts.merge(finishedWord, 1L, Long::sum);
                // Очищаємо StringBuilder, щоб збирати наступне слово (пам'ять масиву не видаляється, а перевикористовується).
                word.setLength(0);
                words++; // збільшуємо загальний лічильник знайдених слів у цьому блоці
            }
        }
        return words;
    }

    private static boolean isApostrophe(char c) {
        return c == '\'' || c == '’' || c == 'ʼ';
    }
}
