package wordfreq;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Корпус - увесь набір текстів, який аналізуємо (термін лінгвістики: «корпус текстів»).
 * Файли читаються потоково, порціями фіксованого розміру, і зберігаються як список блоків byte[].
 * Блок - незалежна підзадача для потоків. Межа блоку ніколи не розрізає слово чи символ UTF-8.
 * bytes - загальний обсяг усіх блоків.
 */
record Corpus(List<byte[]> blocks, long bytes) {

    /** Читає всі файли блоками по blockSize байт (буферизовано, без завантаження файлу в один рядок). */
    static Corpus read(List<Path> files, int blockSize) throws IOException {
        List<byte[]> blocks = new ArrayList<>();
        long bytes = 0;
        byte[] buffer = new byte[blockSize]; // один буфер на все читання
        for (Path file : files) {
            try (InputStream in = Files.newInputStream(file)) {
                int filled = 0; // скільки байтів уже в буфері; на початку може лежати «хвіст» попереднього блоку
                while (true) {
                    // Дочитуємо буфер до повного розміру.
                    filled += in.readNBytes(buffer, filled, blockSize - filled);
                    boolean end = filled < blockSize; // readNBytes повертає менше лише в кінці файлу
                    if (filled == 0) break;
                    // Ріжемо блок по останньому пробілу, щоб слово не потрапило у два блоки.
                    int cut = end ? filled : cutPoint(buffer, filled);
                    blocks.add(Arrays.copyOf(buffer, cut));
                    bytes += cut;
                    // Хвіст після розрізу переносимо на початок буфера: з нього почнеться наступний блок.
                    System.arraycopy(buffer, cut, buffer, 0, filled - cut);
                    filled -= cut;
                    if (end) break;
                }
            }
        }
        return new Corpus(blocks, bytes);
    }

    /**
     * Точка розрізу блоку: позиція одразу після останнього пробілу, табуляції або переходу рядка.
     * Байти ASCII ніколи не трапляються всередині багатобайтового символу UTF-8, тому розріз безпечний.
     */
    static int cutPoint(byte[] buffer, int length) {
        for (int i = length - 1; i > 0; i--) {
            byte b = buffer[i];
            if (b == ' ' || b == '\n' || b == '\r' || b == '\t') return i + 1;
        }
        // У всьому блоці немає пробілу: ріжемо хоча б так, щоб не розділити символ UTF-8
        // (байти виду 10xxxxxx - продовження символу, перед ними різати не можна).
        for (int i = length - 1; i > 0; i--) {
            if ((buffer[i] & 0xC0) != 0x80) return i;
        }
        return length;
    }
}
