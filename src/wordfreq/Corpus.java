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
        byte[] buffer = new byte[blockSize]; // Один багаторазовий буфер (256 КБ) на все читання, щоб не створювати зайве сміття для GC
        for (Path file : files) {
            try (InputStream in = Files.newInputStream(file)) {
                // Скільки валідних байтів зараз у буфері (на початку там може бути "хвіст" з попереднього кроку).
                int filled = 0;
                while (true) {
                    // Крок 1: Дочитуємо з файлу у буфер після вже наявного хвоста (buffer[filled .. blockSize-1]).
                    // readNBytes читає рівно стільки, скільки просимо, або менше — лише якщо файл скінчився.
                    filled += in.readNBytes(buffer, filled, blockSize - filled);

                    // Крок 2: Якщо буфер заповнений не повністю — це точно кінець файлу (EOF).
                    boolean end = filled < blockSize;
                    if (filled == 0) break; // файл повністю прочитаний, більше байтів немає

                    // Крок 3: Шукаємо безпечну точку розрізу.
                    // Якщо це кінець файлу — забираємо весь залишок як є.
                    // Якщо буфер повний — відступаємо назад до останнього пробілу, щоб не розірвати слово.
                    int cut = end ? filled : cutPoint(buffer, filled);

                    // Крок 4: Копіюємо цілу частину [0 .. cut) у готовий блок і додаємо до списку задач.
                    blocks.add(Arrays.copyOf(buffer, cut));
                    bytes += cut;

                    // Крок 5: Рятуємо обрізаний "хвіст" слова [cut .. filled) — переносимо його на початок буфера (індекс 0).
                    // На наступній ітерації нове читання з диска допише продовження слова одразу після цього хвоста.
                    System.arraycopy(buffer, cut, buffer, 0, filled - cut);
                    filled -= cut; // тепер у буфері залишилась тільки довжина хвоста

                    if (end) break;
                }
            }
        }
        return new Corpus(blocks, bytes);
    }

    /**
     * Точка розрізу блоку: позиція одразу після останнього пробілу, табуляції або переходу рядка.
     * Байти ASCII ніколи не трапляються всередині багатобайтового символу UTF-8, тому розріз безпечний.
     *
     * Приклад (буфер на 12 символів, текст "кіт спить вдома"):
     * 1) Прочитали: "кіт спить вд" (слово "вдома" обірвалось на "вд").
     * 2) cutPoint іде з кінця назад -> знаходить пробіл після "спить ".
     * 3) У blocks іде "кіт спить ", а хвіст "вд" через arraycopy зсувається на початок буфера.
     * 4) На наступному кроці дочитуємо "ома" після "вд" -> отримуємо ціле слово "вдома".
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
