package wordfreq;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Генератор тестового корпусу заданого розміру.
 * Слова - псевдослова з українських складів. Частота слова за законом Ципфа (~ 1/ранг), як у живій мові:
 * друге за частотою слово трапляється вдвічі рідше за перше, третє - втричі і т. д.
 * Речення починаються з великої літери, містять коми й крапки, щоб перевірити виділення слів.
 */
final class Generator {

    private static final String[] SYLLABLES = {
            "ка", "ро", "ні", "ти", "ла", "ве", "мо", "за", "пі", "до", "ру", "се", "на", "ко", "ли",
            "ба", "ги", "де", "жу", "зо", "ми", "по", "со", "ту", "фа", "хо", "це", "чи", "ша", "ю",
            "я", "єм", "їж", "ін", "ст", "пр", "м'я", "п'ю", "об", "ад"};
    private static final int VOCABULARY = 50_000; // розмір словника (кількість різних слів)

    private Generator() {}

    /** Записує у файл output приблизно megabytes МБ тексту. Однаковий seed дає однаковий файл. */
    static long generate(Path output, double megabytes, long seed) throws IOException {
        Random random = new Random(seed);
        String[] words = vocabulary(random);
        // Накопичені ваги 1/1, 1/1 + 1/2, ...: випадкове число з [0, sum) двійковим пошуком дає ранг слова.
        double[] cumulative = new double[words.length];
        double sum = 0;
        for (int rank = 0; rank < words.length; rank++) {
            sum += 1.0 / (rank + 1);
            cumulative[rank] = sum;
        }

        long target = (long) (megabytes * 1024 * 1024), written = 0;
        if (output.getParent() != null) Files.createDirectories(output.getParent());
        try (BufferedWriter out = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
            StringBuilder line = new StringBuilder();
            while (written < target) {
                // Одне речення: від 5 до 15 слів.
                line.setLength(0);
                int length = 5 + random.nextInt(11);
                for (int i = 0; i < length; i++) {
                    int rank = Arrays.binarySearch(cumulative, random.nextDouble() * sum);
                    String word = words[rank < 0 ? -rank - 1 : rank];
                    if (i == 0) word = Character.toUpperCase(word.charAt(0)) + word.substring(1);
                    line.append(word).append(i == length - 1 ? ".\n" : random.nextInt(8) == 0 ? ", " : " ");
                }
                out.append(line);
                written += line.toString().getBytes(StandardCharsets.UTF_8).length;
            }
        }
        return written;
    }

    /** Словник унікальних псевдослів, найкоротші першими: у справжньому тексті короткі слова найчастіші. */
    private static String[] vocabulary(Random random) {
        Set<String> unique = new LinkedHashSet<>();
        while (unique.size() < VOCABULARY) {
            StringBuilder word = new StringBuilder();
            int syllables = 1 + random.nextInt(4);
            for (int i = 0; i < syllables; i++) word.append(SYLLABLES[random.nextInt(SYLLABLES.length)]);
            unique.add(word.toString());
        }
        List<String> words = new ArrayList<>(unique);
        words.sort(Comparator.comparingInt(String::length)); // стабільне сортування: той самий seed - той самий порядок
        return words.toArray(String[]::new);
    }
}
