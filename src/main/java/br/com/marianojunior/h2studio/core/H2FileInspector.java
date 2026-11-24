package br.com.marianojunior.h2studio.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lê o cabeçalho de um arquivo H2 (sem abrir o banco) para descobrir
 * o tipo de armazenamento e qual família de driver deve ser usada.
 * <p>
 * Cabeçalhos conhecidos:
 * <ul>
 *   <li>MVStore: texto {@code H:2,block:...,format:N,...} — N=1 (1.4), 2 (2.0/2.1), 3 (2.2+)</li>
 *   <li>PageStore (.h2.db): {@code -- H2 0.5/B --}</li>
 *   <li>Criptografado (CIPHER=AES): {@code H2encrypt}</li>
 * </ul>
 */
public final class H2FileInspector {

    public enum Kind {
        MVSTORE, PAGESTORE, ENCRYPTED, UNKNOWN
    }

    /**
     * Resultado da inspeção.
     *
     * @param kind   tipo de armazenamento
     * @param format formato MVStore (0 quando não se aplica)
     * @param family família de driver sugerida (null quando não dá para saber)
     * @param basePath caminho sem a extensão (.mv.db / .h2.db), usado na URL JDBC
     */
    public record Info(Kind kind, int format, DriverFamily family, String basePath) {

        public String description() {
            return switch (kind) {
                case MVSTORE -> family != null
                        ? family.label() + " (formato " + format + ")"
                        : "Formato MVStore " + format + " desconhecido";
                case PAGESTORE -> "H2 1.4.x ou anterior (PageStore .h2.db)";
                case ENCRYPTED -> "Banco criptografado: versão definida ao abrir";
                case UNKNOWN -> "Não parece ser um banco H2";
            };
        }
    }

    public static final String MV_EXT = ".mv.db";
    public static final String PAGE_EXT = ".h2.db";

    private static final Pattern FORMAT = Pattern.compile("[,:]?format:([0-9a-fA-F]+)");

    private H2FileInspector() {
    }

    public static boolean isH2File(Path p) {
        String n = p.getFileName().toString().toLowerCase();
        return n.endsWith(MV_EXT) || n.endsWith(PAGE_EXT);
    }

    /** Remove a extensão .mv.db / .h2.db (a URL JDBC do H2 não leva extensão). */
    public static String basePath(Path file) {
        String s = file.toAbsolutePath().normalize().toString();
        String lower = s.toLowerCase();
        if (lower.endsWith(MV_EXT)) {
            return s.substring(0, s.length() - MV_EXT.length());
        }
        if (lower.endsWith(PAGE_EXT)) {
            return s.substring(0, s.length() - PAGE_EXT.length());
        }
        return s;
    }

    /** Limite de leitura na procura pelo nome do schema (bancos enormes não são lidos inteiros). */
    private static final long CASE_SCAN_LIMIT = 512L * 1024 * 1024;

    private static final Pattern META_SCHEMA = Pattern.compile("(?:CREATE|ALTER) [A-Z ]{1,40}?\"(public|PUBLIC)\"\\.");

    /**
     * Descobre, sem abrir o banco, se ele foi criado com DATABASE_TO_LOWER=TRUE.
     * <p>
     * No H2 2.x essa opção não fica gravada no arquivo e precisa ser repetida na URL;
     * sem ela a abertura falha com {@code Schema "public" not found}. A estrutura do
     * banco é guardada como comandos SQL legíveis dentro do .mv.db, então basta
     * procurar o primeiro {@code CREATE ... "public".} ou {@code "PUBLIC".}.
     *
     * @return {@code true} se o schema principal está em minúsculas, {@code false}
     *         se está em maiúsculas, {@code null} se não deu para saber
     */
    public static Boolean usesLowerCaseSchema(Path file) {
        byte[] buf = new byte[1 << 20];
        String carry = "";
        long read = 0;
        try (InputStream in = Files.newInputStream(file)) {
            int n;
            while (read < CASE_SCAN_LIMIT && (n = in.readNBytes(buf, 0, buf.length)) > 0) {
                read += n;
                String chunk = carry + new String(buf, 0, n, StandardCharsets.ISO_8859_1);
                Matcher m = META_SCHEMA.matcher(chunk);
                if (m.find()) {
                    return m.group(1).equals("public");
                }
                // mantém o final do bloco para não perder um comando partido ao meio
                carry = chunk.length() > 200 ? chunk.substring(chunk.length() - 200) : chunk;
            }
        } catch (IOException e) {
            return null;
        }
        return null;
    }

    public static Info inspect(Path file) throws IOException {
        String base = basePath(file);
        byte[] head = new byte[512];
        int n;
        try (InputStream in = Files.newInputStream(file)) {
            n = in.readNBytes(head, 0, head.length);
        }
        String text = new String(head, 0, n, StandardCharsets.ISO_8859_1);

        if (text.startsWith("H2encrypt")) {
            return new Info(Kind.ENCRYPTED, 0, null, base);
        }
        if (text.startsWith("-- H2 0.5/B --")) {
            return new Info(Kind.PAGESTORE, 0, DriverFamily.V1_4, base);
        }
        if (text.startsWith("H:2,")) {
            Matcher m = FORMAT.matcher(text);
            // o cabeçalho é um mapa chave:valor em hexadecimal
            int format = m.find() ? Integer.parseInt(m.group(1), 16) : 1;
            return new Info(Kind.MVSTORE, format, DriverFamily.fromMvStoreFormat(format), base);
        }
        return new Info(Kind.UNKNOWN, 0, null, base);
    }
}
