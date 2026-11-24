package br.com.marianojunior.h2studio.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ferramentas de linha de comando dos bancos (pg_dump, mysqldump, gbak...): onde estão e como rodar.
 * <p>
 * O caminho pode ser escolhido pelo usuário (fica nas preferências); senão procura no PATH e
 * nas pastas onde os instaladores costumam colocar no Windows (e no Linux, para testes).
 */
public final class NativeTools {

    public enum Tool {
        PG_DUMP("pg_dump", "PostgreSQL"),
        PG_RESTORE("pg_restore", "PostgreSQL"),
        PSQL("psql", "PostgreSQL"),
        MYSQLDUMP("mysqldump", "MySQL/MariaDB"),
        MYSQL("mysql", "MySQL/MariaDB"),
        GBAK("gbak", "Firebird");

        private final String exe;
        private final String product;

        Tool(String exe, String product) {
            this.exe = exe;
            this.product = product;
        }

        public String exe() {
            return exe;
        }

        public String product() {
            return product;
        }

        String prefKey() {
            return "ferramenta." + exe;
        }

        /** Nomes alternativos (o MariaDB novo chama mariadb-dump / mariadb). */
        List<String> names() {
            return switch (this) {
                case MYSQLDUMP -> List.of("mysqldump", "mariadb-dump");
                case MYSQL -> List.of("mysql", "mariadb");
                default -> List.of(exe);
            };
        }
    }

    /** Saída de um processo terminado. */
    public record Run(int exitCode, List<String> lastLines) {
        public String tail() {
            return String.join("\n", lastLines);
        }
    }

    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    private NativeTools() {
    }

    /** Caminho escolhido pelo usuário (ou vazio). */
    public static String configured(Tool t) {
        return AppPreferences.get(t.prefKey(), "");
    }

    public static void configure(Tool t, Path p) {
        AppPreferences.set(t.prefKey(), p == null ? "" : p.toAbsolutePath().toString());
    }

    /** Localiza a ferramenta; null se não encontrar. */
    public static Path find(Tool t) {
        String conf = configured(t);
        if (!conf.isBlank()) {
            Path p = Path.of(conf);
            if (Files.isRegularFile(p)) {
                return p;
            }
        }
        // mesma pasta da outra ferramenta configurada (ex.: pg_restore ao lado do pg_dump escolhido)
        for (Tool other : Tool.values()) {
            if (other != t && other.product.equals(t.product) && !configured(other).isBlank()) {
                Path dir = Path.of(configured(other)).getParent();
                Path p = inDir(dir, t);
                if (p != null) {
                    return p;
                }
            }
        }
        String path = System.getenv("PATH");
        if (path != null) {
            for (String dir : path.split(java.io.File.pathSeparator)) {
                if (!dir.isBlank()) {
                    Path p = inDir(Path.of(dir.trim()), t);
                    if (p != null) {
                        return p;
                    }
                }
            }
        }
        for (Path dir : commonDirs(t)) {
            Path p = inDir(dir, t);
            if (p != null) {
                return p;
            }
        }
        return null;
    }

    private static Path inDir(Path dir, Tool t) {
        if (dir == null) {
            return null;
        }
        for (String n : t.names()) {
            Path p = dir.resolve(WINDOWS ? n + ".exe" : n);
            if (Files.isRegularFile(p)) {
                return p;
            }
        }
        return null;
    }

    /** Pastas de instalação comuns, da versão mais nova para a mais antiga. */
    static List<Path> commonDirs(Tool t) {
        List<Path> out = new ArrayList<>();
        List<Path> roots = new ArrayList<>();
        if (WINDOWS) {
            for (String env : new String[]{"ProgramFiles", "ProgramFiles(x86)", "ProgramW6432"}) {
                String v = System.getenv(env);
                if (v != null) {
                    roots.add(Path.of(v));
                }
            }
            roots.add(Path.of("C:\\"));
        } else {
            roots.add(Path.of("/usr/lib/postgresql"));
            roots.add(Path.of("/opt"));
            roots.add(Path.of("/usr/local"));
        }
        String product = t.product;
        for (Path root : roots) {
            if (product.equals("PostgreSQL")) {
                addVersions(out, root.resolve("PostgreSQL"), "bin"); // C:\Program Files\PostgreSQL\16\bin
                addVersions(out, root, "bin");                       // /usr/lib/postgresql/16/bin
            } else if (product.startsWith("MySQL")) {
                addVersions(out, root.resolve("MySQL"), "bin");      // MySQL\MySQL Server 8.0\bin
                addMatching(out, root, "MariaDB", "bin");            // MariaDB 10.11\bin
                addMatching(out, root, "xampp", "mysql\\bin".replace('\\', java.io.File.separatorChar));
                addMatching(out, root, "wamp", "");
            } else {
                addVersions(out, root.resolve("Firebird"), "");      // Firebird\Firebird_3_0
                addVersions(out, root.resolve("Firebird"), "bin");   // Firebird_2_5\bin
                addMatching(out, root, "Firebird", "bin");
            }
        }
        return out;
    }

    private static void addVersions(List<Path> out, Path parent, String sub) {
        if (!Files.isDirectory(parent)) {
            return;
        }
        List<Path> dirs = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(parent)) {
            for (Path p : ds) {
                if (Files.isDirectory(p)) {
                    dirs.add(p);
                }
            }
        } catch (IOException ignored) {
            return;
        }
        dirs.sort(Comparator.comparing((Path p) -> versionKey(p.getFileName().toString())).reversed());
        for (Path d : dirs) {
            out.add(sub.isEmpty() ? d : d.resolve(sub));
        }
    }

    private static void addMatching(List<Path> out, Path root, String prefix, String sub) {
        if (!Files.isDirectory(root)) {
            return;
        }
        List<Path> dirs = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(root, prefix + "*")) {
            for (Path p : ds) {
                if (Files.isDirectory(p)) {
                    dirs.add(p);
                }
            }
        } catch (IOException ignored) {
            return;
        }
        dirs.sort(Comparator.comparing((Path p) -> versionKey(p.getFileName().toString())).reversed());
        for (Path d : dirs) {
            out.add(sub.isEmpty() ? d : d.resolve(sub));
        }
    }

    /** "MySQL Server 8.0" → "00008.00000"; ordena 16 depois de 9. */
    private static String versionKey(String name) {
        Matcher m = Pattern.compile("\\d+").matcher(name);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            sb.append(String.format("%06d.", Long.parseLong(m.group().length() > 9 ? m.group().substring(0, 9) : m.group())));
        }
        return sb.toString();
    }

    /** Primeira linha do "--version" (gbak usa -z). */
    public static String version(Tool t, Path exe) {
        try {
            List<String> cmd = List.of(exe.toString(), t == Tool.GBAK ? "-z" : "--version");
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            p.getOutputStream().close();
            String out;
            try (InputStream in = p.getInputStream()) {
                out = new String(in.readNBytes(4000), Charset.defaultCharset());
            }
            p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
            p.destroy();
            for (String line : out.split("\\R")) {
                if (!line.isBlank()) {
                    return line.trim();
                }
            }
        } catch (IOException | InterruptedException e) {
            return "";
        }
        return "";
    }

    /** Número principal da versão ("pg_dump (PostgreSQL) 16.4" → 16). */
    public static int majorVersion(String versionLine) {
        Matcher m = Pattern.compile("(\\d+)\\.(\\d+)").matcher(versionLine == null ? "" : versionLine);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    /**
     * Roda o processo, entregando cada linha de saída (stdout e stderr juntos, quando
     * {@code stdoutTo} é null) a {@code onLine}. Se {@code stdoutTo} vier, a saída padrão vai
     * para ele (ex.: mysqldump → arquivo .gz) e só a de erro é lida como texto.
     * Cancela destruindo o processo.
     */
    public static Run run(List<String> cmd, Map<String, String> env, InputStream stdin, OutputStream stdoutTo,
                          Consumer<String> onLine, BooleanSupplier cancelled) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.environment().putAll(env);
        pb.redirectErrorStream(stdoutTo == null);
        Process p = pb.start();
        Deque<String> tail = new ArrayDeque<>();
        Thread errReader = null;
        Thread copier = null;
        try {
            if (stdin != null) {
                copier = new Thread(() -> {
                    try (OutputStream os = p.getOutputStream(); stdin) {
                        stdin.transferTo(os);
                    } catch (IOException ignored) {
                        // o processo terminou antes (erro): a mensagem vem pela saída de erro
                    }
                }, "stdin-" + cmd.get(0));
                copier.setDaemon(true);
                copier.start();
            } else {
                p.getOutputStream().close();
            }
            InputStream text;
            if (stdoutTo != null) {
                InputStream err = p.getErrorStream();
                errReader = new Thread(() -> readLines(err, tail, onLine), "stderr-" + cmd.get(0));
                errReader.setDaemon(true);
                errReader.start();
                try (InputStream out = p.getInputStream()) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = out.read(buf)) > 0) {
                        if (cancelled != null && cancelled.getAsBoolean()) {
                            p.destroyForcibly();
                            throw new IOException("Cancelado.");
                        }
                        stdoutTo.write(buf, 0, n);
                    }
                }
                text = null;
            } else {
                text = p.getInputStream();
            }
            if (text != null) {
                Thread watcher = new Thread(() -> {
                    while (p.isAlive()) {
                        if (cancelled != null && cancelled.getAsBoolean()) {
                            p.destroyForcibly();
                            return;
                        }
                        try {
                            Thread.sleep(200);
                        } catch (InterruptedException e) {
                            return;
                        }
                    }
                }, "cancel-" + cmd.get(0));
                watcher.setDaemon(true);
                watcher.start();
                readLines(text, tail, onLine);
            }
            int code = p.waitFor();
            if (errReader != null) {
                errReader.join(5000);
            }
            if (cancelled != null && cancelled.getAsBoolean()) {
                throw new IOException("Cancelado.");
            }
            synchronized (tail) {
                return new Run(code, new ArrayList<>(tail));
            }
        } catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("Interrompido.", e);
        } finally {
            if (p.isAlive()) {
                p.destroyForcibly();
            }
        }
    }

    private static void readLines(InputStream in, Deque<String> tail, Consumer<String> onLine) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, Charset.defaultCharset()))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                synchronized (tail) {
                    tail.addLast(line);
                    if (tail.size() > 15) {
                        tail.removeFirst();
                    }
                }
                if (onLine != null) {
                    onLine.accept(line);
                }
            }
        } catch (IOException ignored) {
            // processo encerrado
        }
    }
}
