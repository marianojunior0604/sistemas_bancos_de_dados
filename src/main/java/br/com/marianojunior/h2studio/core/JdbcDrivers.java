package br.com.marianojunior.h2studio.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.sql.Driver;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

/**
 * Drivers JDBC dos outros bancos (PostgreSQL, MySQL/MariaDB, Firebird, SQL Server, SQLite).
 * <p>
 * Nenhum vem dentro do programa: na primeira conexão de cada tipo o jar é baixado do
 * Maven Central para {@code ~/.h2studio/drivers}, conferido pelo SHA-1 publicado no
 * próprio repositório, e carregado em um ClassLoader só dele (como os drivers H2).
 * Sem internet, o usuário pode copiar o jar para essa pasta ou escolher um .jar na conexão.
 */
public final class JdbcDrivers {

    /** Um arquivo do Maven Central. */
    public record Artifact(String group, String artifact, String version, String classifier, long approxSize) {

        public String fileName() {
            return artifact + "-" + version + (classifier == null ? "" : "-" + classifier) + ".jar";
        }

        public String url(String repo) {
            String base = repo.endsWith("/") ? repo : repo + "/";
            return base + group.replace('.', '/') + "/" + artifact + "/" + version + "/" + fileName();
        }
    }

    /** O driver de um tipo de banco: classe principal e jars necessários. */
    public record Spec(String label, String driverClass, List<Artifact> artifacts) {

        public long approxSize() {
            return artifacts.stream().mapToLong(Artifact::approxSize).sum();
        }

        /** Versão para exibir ("5.0.6", sem o sufixo ".java11" / ".jre11" do Maven). */
        public String version() {
            return artifacts.get(0).version().replaceAll("\\.(java|jre)\\d+$", "");
        }
    }

    /** MariaDB Connector/J (LGPL): conecta tanto no MariaDB quanto no MySQL. */
    static final Spec MARIADB_SPEC = new Spec("MariaDB Connector/J (também para MySQL)",
            "org.mariadb.jdbc.Driver",
            List.of(new Artifact("org.mariadb.jdbc", "mariadb-java-client", "3.4.1", null, 640_000)));

    /** Andamento do download. */
    @FunctionalInterface
    public interface Progress {
        void update(String file, long done, long total);
    }

    private static final Map<String, Driver> LOADED = new ConcurrentHashMap<>();

    private JdbcDrivers() {
    }

    /** Repositório Maven (pode ser trocado por -Dh2studio.maven=... para usar um espelho interno). */
    public static String repository() {
        return System.getProperty("h2studio.maven", "https://repo1.maven.org/maven2/");
    }

    /** Pasta onde os drivers baixados ficam. */
    public static Path installDir() {
        return DriverRegistry.userDir().resolve("drivers");
    }

    /** Procura o jar nas pastas de drivers (a do programa e a do usuário). */
    public static Path find(Artifact a) {
        for (Path dir : DriverRegistry.get().searchDirs()) {
            Path p = dir.resolve(a.fileName());
            if (Files.isRegularFile(p)) {
                return p.toAbsolutePath().normalize();
            }
        }
        return null;
    }

    public static List<Artifact> missing(Spec spec) {
        List<Artifact> list = new ArrayList<>();
        for (Artifact a : spec.artifacts()) {
            if (find(a) == null) {
                list.add(a);
            }
        }
        return list;
    }

    public static boolean isInstalled(Spec spec) {
        return missing(spec).isEmpty();
    }

    /** Baixa os jars que faltam. Cada arquivo é conferido pelo SHA-1 antes de ir para a pasta de drivers. */
    public static void download(Spec spec, Progress progress, BooleanSupplier cancelled) throws IOException {
        List<Artifact> todo = missing(spec);
        if (todo.isEmpty()) {
            return;
        }
        Files.createDirectories(installDir());
        HttpClient http = HttpClient.newBuilder()
                .proxy(ProxySelector.getDefault())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(20))
                .build();
        for (Artifact a : todo) {
            downloadOne(http, a, progress, cancelled);
        }
    }

    private static void downloadOne(HttpClient http, Artifact a, Progress progress, BooleanSupplier cancelled)
            throws IOException {
        String url = a.url(repository());
        String expected = fetchSha1(http, url + ".sha1");
        Path tmp = installDir().resolve(a.fileName() + ".parcial");
        try {
            HttpResponse<InputStream> resp = http.send(
                    HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(5)).GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() != 200) {
                resp.body().close();
                throw new IOException("O repositório respondeu " + resp.statusCode() + " para " + url);
            }
            long total = resp.headers().firstValueAsLong("Content-Length").orElse(a.approxSize());
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            long done = 0;
            try (InputStream in = resp.body(); OutputStream out = Files.newOutputStream(tmp)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (cancelled != null && cancelled.getAsBoolean()) {
                        throw new IOException("Download cancelado.");
                    }
                    out.write(buf, 0, n);
                    sha1.update(buf, 0, n);
                    done += n;
                    if (progress != null) {
                        progress.update(a.fileName(), done, total);
                    }
                }
            }
            String got = HexFormat.of().formatHex(sha1.digest());
            if (!got.equalsIgnoreCase(expected)) {
                throw new IOException("O arquivo baixado não confere com o SHA-1 publicado (" + a.fileName()
                        + "). Tente novamente; se repetir, a conexão pode estar sendo interceptada.");
            }
            Files.move(tmp, installDir().resolve(a.fileName()), StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Download interrompido.", e);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static String fetchSha1(HttpClient http, String url) throws IOException {
        try {
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(30)).GET().build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.US_ASCII));
            if (r.statusCode() != 200) {
                throw new IOException("O repositório respondeu " + r.statusCode() + " para " + url);
            }
            // o arquivo .sha1 pode ter "hash  nome-do-arquivo"
            String h = r.body().trim().split("\\s+")[0].toLowerCase(Locale.ROOT);
            if (!h.matches("[0-9a-f]{40}")) {
                throw new IOException("SHA-1 inválido em " + url);
            }
            return h;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Download interrompido.", e);
        } catch (java.net.ConnectException | java.net.http.HttpConnectTimeoutException e) {
            throw new IOException("Sem acesso ao Maven Central (" + repository() + ").", e);
        }
    }

    /** Carrega o driver de um tipo (os jars precisam estar instalados). */
    public static Driver load(Spec spec) throws Exception {
        List<Path> jars = new ArrayList<>();
        for (Artifact a : spec.artifacts()) {
            Path p = find(a);
            if (p == null) {
                throw new IllegalStateException("Driver não instalado: " + a.fileName());
            }
            jars.add(p);
        }
        return load(jars, spec.driverClass());
    }

    /**
     * Carrega um driver de jars escolhidos pelo usuário. Com {@code driverClass} null, usa
     * o primeiro java.sql.Driver declarado nos próprios jars (META-INF/services).
     */
    public static Driver load(List<Path> jars, String driverClass) throws Exception {
        String key = jars + "|" + driverClass;
        Driver d = LOADED.get(key);
        if (d != null) {
            return d;
        }
        synchronized (LOADED) {
            d = LOADED.get(key);
            if (d != null) {
                return d;
            }
            URL[] urls = new URL[jars.size()];
            for (int i = 0; i < urls.length; i++) {
                urls[i] = jars.get(i).toUri().toURL();
            }
            URLClassLoader cl = new URLClassLoader("jdbc-" + jars.get(0).getFileName(), urls,
                    ClassLoader.getPlatformClassLoader());
            d = driverClass != null ? instantiate(cl, driverClass) : null;
            if (d == null) {
                for (Driver candidate : ServiceLoader.load(Driver.class, cl)) {
                    if (candidate.getClass().getClassLoader() == cl) {
                        d = candidate;
                        break;
                    }
                }
            }
            if (d == null) {
                throw new IllegalStateException("Nenhum driver JDBC encontrado em " + jars);
            }
            LOADED.put(key, d);
            return d;
        }
    }

    private static Driver instantiate(ClassLoader cl, String cls) {
        try {
            return (Driver) Class.forName(cls, true, cl).getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException | LinkageError e) {
            return null; // jar de outro fornecedor: tenta pelo ServiceLoader
        }
    }

    /** "1,4 MB" */
    public static String size(long bytes) {
        if (bytes < 1024 * 1024) {
            return Math.max(1, bytes / 1024) + " KB";
        }
        return String.format(Locale.of("pt", "BR"), "%.1f MB", bytes / 1024.0 / 1024.0);
    }
}
