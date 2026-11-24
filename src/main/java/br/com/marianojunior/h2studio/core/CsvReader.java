package br.com.marianojunior.h2studio.core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Lê um arquivo CSV inteiro: detecta a codificação (UTF-8 ou Windows-1252, a do Excel
 * antigo) e o separador (; , ou TAB), e entende campos entre aspas com quebras de linha.
 */
public final class CsvReader {

    /** Conteúdo lido. {@code lines[i]} é o número da linha no arquivo onde o registro i começa. */
    public record Data(List<String[]> records, int[] lines, Charset charset, char separator) {
    }

    private CsvReader() {
    }

    public static Charset detectCharset(byte[] bytes) {
        int start = hasBom(bytes) ? 3 : 0;
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, start, bytes.length - start));
            return StandardCharsets.UTF_8;
        } catch (CharacterCodingException e) {
            return DataExporter.windows1252();
        }
    }

    private static boolean hasBom(byte[] b) {
        return b.length >= 3 && (b[0] & 0xFF) == 0xEF && (b[1] & 0xFF) == 0xBB && (b[2] & 0xFF) == 0xBF;
    }

    public static String decode(byte[] bytes, Charset cs) {
        int start = cs.equals(StandardCharsets.UTF_8) && hasBom(bytes) ? 3 : 0;
        return new String(bytes, start, bytes.length - start, cs);
    }

    /** Separador mais frequente na primeira linha (fora de aspas). Empate: ponto e vírgula. */
    public static char detectSeparator(String text) {
        int semi = 0;
        int comma = 0;
        int tab = 0;
        boolean q = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"') {
                q = !q;
            } else if (!q && (c == '\n' || c == '\r')) {
                break;
            } else if (!q) {
                if (c == ';') {
                    semi++;
                } else if (c == ',') {
                    comma++;
                } else if (c == '\t') {
                    tab++;
                }
            }
        }
        if (tab > semi && tab > comma) {
            return '\t';
        }
        return comma > semi ? ',' : ';';
    }

    /**
     * @param charset   null = detectar
     * @param separator 0 = detectar
     */
    public static Data read(Path file, Charset charset, char separator) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        Charset cs = charset != null ? charset : detectCharset(bytes);
        String text = decode(bytes, cs);
        char sep = separator != 0 ? separator : detectSeparator(text);
        return parse(text, cs, sep);
    }

    static Data parse(String text, Charset cs, char sep) {
        List<String[]> records = new ArrayList<>();
        List<Integer> lines = new ArrayList<>();
        List<String> fields = new ArrayList<>();
        StringBuilder f = new StringBuilder();
        boolean inQuotes = false;
        boolean wasQuoted = false;
        int line = 1;
        int recordLine = 1;
        int n = text.length();
        for (int i = 0; i < n; i++) {
            char c = text.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < n && text.charAt(i + 1) == '"') {
                        f.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    if (c == '\n') {
                        line++;
                    }
                    f.append(c);
                }
                continue;
            }
            if (c == '"' && f.length() == 0 && !wasQuoted) {
                inQuotes = true;
                wasQuoted = true;
            } else if (c == sep) {
                fields.add(f.toString());
                f.setLength(0);
                wasQuoted = false;
            } else if (c == '\r' || c == '\n') {
                if (c == '\r' && i + 1 < n && text.charAt(i + 1) == '\n') {
                    i++;
                }
                fields.add(f.toString());
                f.setLength(0);
                wasQuoted = false;
                addRecord(records, lines, fields, recordLine);
                fields.clear();
                line++;
                recordLine = line;
            } else {
                f.append(c);
            }
        }
        if (f.length() > 0 || !fields.isEmpty() || wasQuoted) {
            fields.add(f.toString());
            addRecord(records, lines, fields, recordLine);
        }
        int[] ln = new int[lines.size()];
        for (int i = 0; i < ln.length; i++) {
            ln[i] = lines.get(i);
        }
        return new Data(records, ln, cs, sep);
    }

    private static void addRecord(List<String[]> records, List<Integer> lines, List<String> fields, int line) {
        // linhas totalmente vazias são ignoradas
        if (fields.size() == 1 && fields.get(0).isEmpty()) {
            return;
        }
        records.add(fields.toArray(new String[0]));
        lines.add(line);
    }
}
