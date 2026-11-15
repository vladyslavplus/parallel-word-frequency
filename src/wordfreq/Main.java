package wordfreq;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Точка входу. З аргументами - пари --параметр значення. Без аргументів - програма питає кожен параметр
 * у консолі, Enter залишає значення за замовчуванням. Далі обирається режим і запускається потрібна частина.
 */
public final class Main {

    /** Кількість логічних процесорів (ядра × hyper-threading; Ryzen 5 5600: 6 × 2 = 12) - p за замовчуванням. */
    static final int CORES = Runtime.getRuntime().availableProcessors();
    /** Назва тестового корпусу corpus_<N>mb.txt: такий файл генерується автоматично, якщо його немає. */
    private static final Pattern GENERATED_CORPUS = Pattern.compile("corpus_(\\d+)mb\\.txt");

    /** Режим -> параметри, які він використовує (саме їх програма питає в консолі). */
    private static final Map<String, List<String>> KEYS = Map.of(
            "both", List.of("input", "output", "threads", "schedule", "top", "block-kb", "overwrite"),
            "seq", List.of("input", "output", "top", "block-kb", "overwrite"),
            "par", List.of("input", "output", "threads", "schedule", "top", "block-kb", "overwrite"),
            "generate", List.of("size-mb", "output", "seed"),
            "bench", List.of("input", "threads", "runs", "warmup", "top", "block-kb", "csv", "overwrite"),
            "check", List.of("input"));

    /** Підписи параметрів для діалогу в консолі. */
    private static final Map<String, String> LABELS = Map.ofEntries(
            Map.entry("input", "Вхідні файли UTF-8 (через кому)"),
            Map.entry("output", "Вихідний файл"),
            Map.entry("threads", "Кількість потоків"),
            Map.entry("schedule", "Розподіл блоків: dynamic (черга) або static (рівні діапазони)"),
            Map.entry("top", "Скільки слів у топі"),
            Map.entry("block-kb", "Розмір блоку, КБ"),
            Map.entry("size-mb", "Розмір файлу, МБ"),
            Map.entry("seed", "Зерно генератора"),
            Map.entry("runs", "Вимірів на точку (медіана)"),
            Map.entry("warmup", "Прогрівних запусків"),
            Map.entry("csv", "CSV з результатами"),
            Map.entry("overwrite", "Перезаписати попередні результати: так/ні або yes/no (ні - нова папка run_<дата_час>)"));

    private Main() {}

    public static void main(String[] args) {
        try {
            Map<String, String> options = args.length == 0 ? askInConsole() : parseArgs(args);
            run(options);
        } catch (IllegalArgumentException | IOException | UncheckedIOException e) {
            System.err.println("Помилка: " + e.getMessage());
            System.exit(1);
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    /** Значення параметра за замовчуванням (залежить від режиму); невідомий параметр - помилка. */
    private static String defaultFor(String mode, String key) {
        return switch (key) {
            case "input" -> mode.equals("bench")
                    ? "data/corpus_10mb.txt,data/corpus_100mb.txt,data/corpus_1000mb.txt"
                    : mode.equals("check") ? "data/sample.txt" : "data/corpus_100mb.txt";
            case "output" -> mode.equals("generate") ? "data/corpus_100mb.txt" : "results/top100.txt";
            case "threads" -> mode.equals("bench") ? benchThreads() : String.valueOf(CORES);
            case "schedule" -> "dynamic";
            case "top" -> "100";
            case "block-kb" -> "256";
            case "size-mb" -> "100";
            case "seed" -> "42";
            case "runs" -> "5";
            case "warmup" -> "2";
            case "csv" -> "results/benchmark.csv";
            case "overwrite" -> "так";
            default -> throw new IllegalArgumentException("невідомий параметр --" + key);
        };
    }

    /**
     * Список p для серії експериментів: 1, 2, 4, 8, ... до 2 × кількість логічних ядер, плюс саме число ядер.
     * LinkedHashSet прибирає повтори, потім список сортується: для 12 ядер - 1,2,4,8,12,16,24.
     */
    private static String benchThreads() {
        Set<Integer> threads = new LinkedHashSet<>();
        for (int p = 1; p <= 2 * CORES; p *= 2) threads.add(p);
        threads.add(CORES);
        threads.add(2 * CORES);
        return threads.stream().sorted().map(String::valueOf).collect(Collectors.joining(","));
    }

    /** Діалог у консолі: спершу режим, потім лише параметри цього режиму. */
    private static Map<String, String> askInConsole() {
        Scanner in = new Scanner(System.in);
        System.out.println("Частота слів у текстовому корпусі (топ-N). Логічних ядер: " + CORES);
        System.out.println("Режими: both - послідовна + паралельна з порівнянням, seq - послідовна, par - паралельна,");
        System.out.println("        generate - генератор тестових даних, bench - серія експериментів, check - самоперевірка");
        Map<String, String> options = new HashMap<>();
        String mode = ask(in, "Режим", "both");
        if (!KEYS.containsKey(mode)) throw new IllegalArgumentException("невідомий режим: " + mode);
        options.put("mode", mode);
        for (String key : KEYS.get(mode)) options.put(key, ask(in, LABELS.get(key), defaultFor(mode, key)));
        return options;
    }

    private static String ask(Scanner in, String label, String fallback) {
        System.out.print(label + " [за замовчуванням: " + fallback + "]: ");
        String line = in.hasNextLine() ? in.nextLine().trim() : "";
        return line.isEmpty() ? fallback : line;
    }

    /** Розбір аргументів командного рядка: пари --параметр значення; невідомий параметр - помилка. */
    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> options = new HashMap<>();
        for (int i = 0; i < args.length; i += 2) {
            if (!args[i].startsWith("--") || i + 1 >= args.length) {
                throw new IllegalArgumentException("очікується пара --параметр значення, отримано: " + args[i]);
            }
            String key = args[i].substring(2);
            if (key.equals("generate")) { // скорочена форма з методички: --generate <МБ>
                options.put("mode", "generate");
                key = "size-mb";
            } else if (!key.equals("mode")) {
                defaultFor("both", key); // кидає помилку для невідомого параметра
            }
            options.put(key, args[i + 1]);
        }
        String mode = options.getOrDefault("mode", "both");
        if (!KEYS.containsKey(mode)) throw new IllegalArgumentException("невідомий режим: " + mode);
        options.put("mode", mode);
        return options;
    }

    /** Головний диспетчер: доповнює параметри значеннями за замовчуванням і запускає обраний режим. */
    private static void run(Map<String, String> options) throws Exception {
        String mode = options.get("mode");
        Map<String, String> o = new HashMap<>();
        for (String key : KEYS.get(mode)) o.put(key, options.getOrDefault(key, defaultFor(mode, key)));
        if (o.containsKey("overwrite") && !overwrite(o.get("overwrite"))) {
            // --overwrite ні: попередні результати не чіпаємо, цей запуск пишемо в <папка результатів>/run_<дата_час>/.
            String run = "run_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            for (String key : List.of("output", "csv")) {
                if (!o.containsKey(key)) continue;
                Path path = Path.of(o.get(key));
                Path dir = path.getParent() == null ? Path.of(run) : path.getParent().resolve(run);
                o.put(key, dir.resolve(path.getFileName()).toString());
            }
            System.out.println("Попередні результати не змінюються; результати цього запуску - у папці з назвою " + run);
        }

        switch (mode) {
            case "generate" -> {
                Path output = Path.of(o.get("output"));
                long start = System.nanoTime();
                long bytes = Generator.generate(output, positiveDouble(o, "size-mb"), Long.parseLong(o.get("seed")));
                System.out.printf("Згенеровано %s: %.1f МБ за %.2f с%n", output, bytes / 1048576.0, seconds(System.nanoTime() - start));
            }
            case "bench" -> Benchmark.run(inputFiles(o), threadList(o.get("threads")), positiveInt(o, "runs"),
                    Integer.parseInt(o.get("warmup")), blockKb(o) * 1024, positiveInt(o, "top"),
                    Path.of(o.get("csv")));
            case "check" -> SelfCheck.run(inputFiles(o));
            default -> count(mode, o);
        }
    }

    /**
     * Режими both / seq / par: читання файлів, прогрів, вимір послідовної та/або паралельної версії,
     * метрики S, E, Карп-Флатт, перевірка збігу, запис топів і детального журналу.
     */
    private static void count(String mode, Map<String, String> o) throws Exception {
        List<Path> files = inputFiles(o);
        int topN = positiveInt(o, "top");
        int threads = mode.equals("seq") ? 1 : positiveInt(o, "threads");
        boolean dynamic = !mode.equals("seq") && isDynamic(o.get("schedule"));
        boolean runSeq = !mode.equals("par"), runPar = !mode.equals("seq");

        // Малий файл: зменшуємо блок (не менше 4 КБ), щоб блоків вистачило на всі потоки (бажано 4 на потік).
        long totalBytes = 0;
        for (Path f : files) totalBytes += Files.size(f);
        int requestedKb = blockKb(o);
        int blockKb = runPar ? (int) Math.max(4, Math.min(requestedKb, totalBytes / (4L * threads) / 1024)) : requestedKb;

        long start = System.nanoTime();
        Corpus corpus = Corpus.read(files, blockKb * 1024);
        long readNanos = System.nanoTime() - start;
        int blocks = corpus.blocks().size();
        System.out.printf("Задача: частота слів, топ-%d. Файли: %s (%.1f МБ)%n", topN, files, corpus.bytes() / 1048576.0);
        if (blockKb < requestedKb) {
            System.out.printf("Розмір блоку зменшено з %d до %d КБ: файл малий, а блоків має вистачити на всі %d потоків.%n",
                    requestedKb, blockKb, threads);
        }
        System.out.printf("Блоків: %d по %d КБ (у середньому %.1f блоку на потік)%n", blocks, blockKb, (double) blocks / threads);
        if (runPar && blocks < threads) {
            System.out.printf("Увага: блоків менше, ніж потоків, %d потоків залишаться без роботи.%n", threads - blocks);
        }
        System.out.printf("Читання файлу (окремо від обчислень): %.3f с%n", seconds(readNanos));

        // Прогрів JIT: перший прохід компілює байт-код у машинний; без нього версія, що йде першою, виглядала б повільнішою.
        start = System.nanoTime();
        if (runSeq) WordCounter.sequential(corpus.blocks(), topN);
        if (runPar) WordCounter.parallel(corpus.blocks(), threads, dynamic, topN);
        long warmNanos = System.nanoTime() - start;
        System.out.printf("Прогрів JVM (по одному проходу кожної версії, не входить у вимір): %.3f с%n%n", seconds(warmNanos));

        WordCounter.Result seq = null, par = null;
        if (runSeq) {
            seq = WordCounter.sequential(corpus.blocks(), topN);
            System.out.printf("Послідовна версія: T_seq = %.3f с (підрахунок %.3f + вибір топу %.3f); слів: %,d, унікальних: %,d%n",
                    seconds(seq.totalNanos()), seconds(seq.mapNanos()), seconds(seq.topNanos()), seq.words(), seq.counts().size());
        }
        if (runPar) {
            par = WordCounter.parallel(corpus.blocks(), threads, dynamic, topN);
            System.out.printf("Паралельна версія (p=%d, %s): T_par = %.3f с (підрахунок %.3f + злиття %.3f + вибір топу %.3f)%n",
                    threads, dynamic ? "dynamic - черга блоків" : "static - рівні діапазони", seconds(par.totalNanos()),
                    seconds(par.mapNanos()), seconds(par.mergeNanos()), seconds(par.topNanos()));
            printParts(par);
        }

        if (seq != null && par != null) {
            double s = (double) seq.totalNanos() / par.totalNanos();
            System.out.println("Метрики (час без читання файлу):");
            System.out.printf("  прискорення  S = T_seq / T_par = %.3f / %.3f = %.2f%n", seconds(seq.totalNanos()), seconds(par.totalNanos()), s);
            System.out.printf("  ефективність E = S / p = %.2f / %d = %.3f%n", s, threads, s / threads);
            if (threads > 1) {
                System.out.printf("  Карп-Флатт   e = (1/S - 1/p) / (1 - 1/p) = %.4f%n", karpFlatt(s, threads));
            }
        }

        printTop(seq, par);
        Path output = Path.of(o.get("output"));
        List<Path> written = new ArrayList<>();
        if (seq != null && par != null) {
            long differences = differences(seq.counts(), par.counts());
            boolean same = differences == 0 && seq.top().equals(par.top());
            System.out.printf("Коректність: слів %,d / %,d, унікальних %,d / %,d, слів з різною частотою: %d, топ-%d однаковий: %s -> %s%n",
                    seq.words(), par.words(), seq.counts().size(), par.counts().size(), differences, topN,
                    seq.top().equals(par.top()) ? "так" : "ні", same ? "результати збігаються." : "РЕЗУЛЬТАТИ НЕ ЗБІГАЮТЬСЯ!");
            written.add(writeTop(withSuffix(output, "_seq"), files, seq));
            written.add(writeTop(withSuffix(output, "_par"), files, par));
            Path log = output.resolveSibling("run_log.txt");
            RunLog.write(log, mode, files, requestedKb, blockKb, topN, threads, dynamic, corpus, readNanos, warmNanos, seq, par, differences);
            System.out.println("Результати: " + written + "; детальний журнал: " + log);
            if (!same) System.exit(2);
        } else {
            written.add(writeTop(output, files, par != null ? par : seq));
            Path log = output.resolveSibling("run_log.txt");
            RunLog.write(log, mode, files, requestedKb, blockKb, topN, threads, dynamic, corpus, readNanos, warmNanos, seq, par, -1);
            System.out.println("Результат: " + written + "; детальний журнал: " + log);
        }
    }

    /** Вивід роботи кожного потоку і дисбалансу T_max / T_avg (1.0 - ідеальний баланс). */
    private static void printParts(WordCounter.Result r) {
        long max = 0, sum = 0;
        for (WordCounter.Part p : r.parts()) {
            System.out.printf("  потік %2d [%s]: %.3f с, блоків %,d (%.2f МБ), слів %,d, унікальних у локальній мапі %,d%n",
                    p.thread(), p.name(), seconds(p.nanos()), p.blocks(), p.bytes() / 1048576.0, p.words(), p.unique());
            max = Math.max(max, p.nanos());
            sum += p.nanos();
        }
        double avg = (double) sum / r.parts().size();
        System.out.printf("  T_max = %.3f с, T_avg = %.3f с, дисбаланс T_max / T_avg = %.3f%n", seconds(max), avg / 1e9, max / avg);
    }

    /** Топ-10 обох версій поруч (або одна колонка, якщо запускалась одна версія). */
    private static void printTop(WordCounter.Result seq, WordCounter.Result par) {
        System.out.println(seq != null && par != null ? "Топ-10:     послідовна                 | паралельна" : "Топ-10:");
        WordCounter.Result any = seq != null ? seq : par;
        for (int i = 0; i < Math.min(10, any.top().size()); i++) {
            String line = String.format("  %3d. %-12s %,12d", i + 1, any.top().get(i).getKey(), any.top().get(i).getValue());
            if (seq != null && par != null) {
                line += String.format("  | %-12s %,12d", par.top().get(i).getKey(), par.top().get(i).getValue());
            }
            System.out.println(line);
        }
    }

    /** Кількість слів, частота яких різниться між двома мапами (включно зі словами, що є лише в одній). */
    static long differences(Map<String, Long> a, Map<String, Long> b) {
        long differ = a.entrySet().stream().filter(e -> !e.getValue().equals(b.get(e.getKey()))).count();
        return differ + b.keySet().stream().filter(word -> !a.containsKey(word)).count();
    }

    /** Метрика Карпа-Флатта: експериментальна оцінка послідовної частки f з виміряного прискорення. */
    static double karpFlatt(double speedup, int threads) {
        return (1 / speedup - 1.0 / threads) / (1 - 1.0 / threads);
    }

    /** results/top100.txt + "_seq" -> results/top100_seq.txt */
    private static Path withSuffix(Path file, String suffix) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return file.resolveSibling(dot < 0 ? name + suffix : name.substring(0, dot) + suffix + name.substring(dot));
    }

    /** Запис топу у файл UTF-8: заголовок і рядки «місце, слово, кількість» через табуляцію. */
    private static Path writeTop(Path output, List<Path> files, WordCounter.Result r) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("# Файли: " + files + "; слів: " + r.words() + "; унікальних: " + r.counts().size());
        lines.add("# місце\tслово\tкількість");
        for (int i = 0; i < r.top().size(); i++) {
            lines.add((i + 1) + "\t" + r.top().get(i).getKey() + "\t" + r.top().get(i).getValue());
        }
        if (output.getParent() != null) Files.createDirectories(output.getParent());
        Files.write(output, lines, StandardCharsets.UTF_8);
        return output;
    }

    /** Список вхідних файлів; відсутній тестовий корпус генерується, інший відсутній файл - помилка. */
    static List<Path> inputFiles(Map<String, String> o) {
        List<Path> files = Arrays.stream(o.get("input").split(",")).map(String::trim).filter(s -> !s.isEmpty()).map(Path::of).toList();
        if (files.isEmpty()) throw new IllegalArgumentException("не задано вхідних файлів");
        for (Path f : files) {
            if (Files.isRegularFile(f)) continue;
            // Відсутній corpus_<N>mb.txt генеруємо замість помилки; файли з іншими назвами мають існувати.
            Matcher corpus = GENERATED_CORPUS.matcher(f.getFileName().toString());
            if (!corpus.matches()) throw new IllegalArgumentException("файл не знайдено: " + f.toAbsolutePath());
            System.out.printf("Файл %s не знайдено, генерую тестовий корпус %s МБ (seed 42)...%n", f, corpus.group(1));
            try {
                Generator.generate(f, Integer.parseInt(corpus.group(1)), 42);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return files;
    }

    private static int[] threadList(String value) {
        int[] threads = Arrays.stream(value.split(",")).map(String::trim).mapToInt(Integer::parseInt).toArray();
        if (Arrays.stream(threads).anyMatch(p -> p < 1)) throw new IllegalArgumentException("кількість потоків має бути >= 1");
        return threads;
    }

    /** так/yes - перезаписати результати, ні/no - зберегти попередні. */
    private static boolean overwrite(String value) {
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "так", "yes", "y", "true", "1" -> true;
            case "ні", "no", "n", "false", "0" -> false;
            default -> throw new IllegalArgumentException("--overwrite має бути так/ні або yes/no: " + value);
        };
    }

    /** dynamic - черга блоків, static - рівні діапазони; інше значення - помилка. */
    private static boolean isDynamic(String schedule) {
        if (!schedule.equals("dynamic") && !schedule.equals("static")) {
            throw new IllegalArgumentException("розподіл має бути dynamic або static: " + schedule);
        }
        return schedule.equals("dynamic");
    }

    private static int positiveInt(Map<String, String> o, String key) {
        int value = Integer.parseInt(o.get(key).trim());
        if (value < 1) throw new IllegalArgumentException("--" + key + " має бути >= 1");
        return value;
    }

    private static int blockKb(Map<String, String> o) {
        int kb = positiveInt(o, "block-kb");
        if (kb > 1 << 20) throw new IllegalArgumentException("--block-kb має бути не більше 1048576 (1 ГБ)");
        return kb;
    }

    private static double positiveDouble(Map<String, String> o, String key) {
        double value = Double.parseDouble(o.get(key).trim());
        if (!(value > 0)) throw new IllegalArgumentException("--" + key + " має бути > 0");
        return value;
    }

    static double seconds(long nanos) {
        return nanos / 1e9;
    }
}
