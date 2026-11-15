package wordfreq;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Самоперевірка коректності (режим check): виділення слів, розрізання блоків і збіг паралельної версії
 * з послідовною при p = 1, 2, 3 (непарне), 8 і p, більшому за кількість ядер, для обох способів розподілу.
 */
final class SelfCheck {

    private SelfCheck() {}

    static void run(List<Path> files) throws Exception {
        // 1. Виділення слів: регістр, апостроф усередині слова, цифри й розділові знаки як роздільники.
        Map<String, Long> words = new HashMap<>();
        long n = WordCounter.countWords("Don't STOP, п'ять П’ять! ab'' 'x 123 x2y".getBytes(StandardCharsets.UTF_8), words);
        check(n == 8 && words.equals(Map.of("don't", 1L, "stop", 1L, "п'ять", 2L, "ab", 1L, "x", 2L, "y", 1L)),
                "токенізація: " + words);

        // 2. Розріз блоку: по пробілу, а без пробілу - не посередині символу UTF-8.
        byte[] text = "аб вг".getBytes(StandardCharsets.UTF_8);
        check(Corpus.cutPoint(text, text.length) == 5, "розрізання блоку по пробілу");
        byte[] noSpace = "абвг".getBytes(StandardCharsets.UTF_8);
        check(Corpus.cutPoint(noSpace, 3) == 2, "розрізання не ділить символ UTF-8");

        // 3. Результат не залежить від розміру блоку: блоки по 1 КБ дають багато розрізів, еталон - один великий блок.
        WordCounter.Result reference = WordCounter.sequential(Corpus.read(files, 64 << 20).blocks(), 100);
        List<byte[]> blocks = Corpus.read(files, 1024).blocks();
        check(WordCounter.sequential(blocks, 100).counts().equals(reference.counts()), "розмір блоку змінює результат");

        // 4. Паралельна версія = послідовна; кожен блок оброблено рівно один раз; повторний запуск дає те саме.
        for (boolean dynamic : new boolean[]{true, false}) {
            for (int p : new int[]{1, 2, 3, 8, 2 * Main.CORES + 1}) {
                for (int repeat = 0; repeat < 2; repeat++) {
                    WordCounter.Result par = WordCounter.parallel(blocks, p, dynamic, 100);
                    check(par.counts().equals(reference.counts()) && par.top().equals(reference.top()),
                            "паралельна версія p=" + p + (dynamic ? " dynamic" : " static"));
                    check(par.parts().stream().mapToInt(WordCounter.Part::blocks).sum() == blocks.size(),
                            "кожен блок оброблено рівно один раз, p=" + p);
                }
            }
        }
        System.out.printf("Самоперевірка пройдена: %d слів, %d блоків по 1 КБ, p = 1, 2, 3, 8, %d (dynamic і static).%n",
                reference.words(), blocks.size(), 2 * Main.CORES + 1);
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new IllegalStateException("самоперевірка не пройдена: " + what);
    }
}
