package br.com.marianojunior.h2studio.core;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Driver;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Localiza os jars do H2 disponíveis e carrega cada um em um ClassLoader
 * isolado, permitindo abrir bancos de versões diferentes na mesma janela.
 * <p>
 * Pastas pesquisadas (nesta ordem):
 * <ol>
 *   <li>{@code drivers/} ao lado do h2studio.jar (instalação)</li>
 *   <li>{@code target/drivers/} (execução pelo NetBeans / mvn)</li>
 *   <li>{@code ~/.h2studio/drivers/} (drivers extras adicionados pelo usuário)</li>
 * </ol>
 */
public final class DriverRegistry {

    /** Um jar de driver H2 encontrado em disco. */
    public record DriverJar(Path path, int major, int minor, int patch) {

        public DriverFamily family() {
            return DriverFamily.fromVersion(major, minor);
        }

        public String version() {
            return major + "." + minor + "." + patch;
        }

        @Override
        public String toString() {
            return "H2 " + version() + " (" + path.getFileName() + ")";
        }
    }

    private static final Pattern JAR_NAME = Pattern.compile("h2-(\\d+)\\.(\\d+)\\.(\\d+)\\.jar", Pattern.CASE_INSENSITIVE);

    private static final DriverRegistry INSTANCE = new DriverRegistry();

    private final Map<Path, Driver> loaded = new ConcurrentHashMap<>();
    private volatile List<DriverJar> jars;

    public static DriverRegistry get() {
        return INSTANCE;
    }

    private DriverRegistry() {
    }

    public static Path userDir() {
        return Path.of(System.getProperty("user.home"), ".h2studio");
    }

    public List<Path> searchDirs() {
        Set<Path> dirs = new LinkedHashSet<>();
        String override = System.getProperty("h2studio.drivers");
        if (override != null && !override.isBlank()) {
            dirs.add(Path.of(override));
        }
        Path code = codeLocation();
        if (code != null) {
            Path base = Files.isRegularFile(code) ? code.getParent() : code; // jar ou target/classes
            if (base != null) {
                dirs.add(base.resolve("drivers"));
                if (base.getParent() != null) {
                    dirs.add(base.getParent().resolve("drivers"));
                }
            }
        }
        dirs.add(Path.of("target", "drivers").toAbsolutePath());
        dirs.add(userDir().resolve("drivers"));
        return new ArrayList<>(dirs);
    }

    private static Path codeLocation() {
        try {
            URL url = DriverRegistry.class.getProtectionDomain().getCodeSource().getLocation();
            return Path.of(url.toURI()).toAbsolutePath().normalize();
        } catch (URISyntaxException | RuntimeException e) {
            return null;
        }
    }

    /** Lista todos os drivers encontrados, do mais novo para o mais antigo. */
    public synchronized List<DriverJar> list() {
        if (jars == null) {
            rescan();
        }
        return jars;
    }

    public synchronized void rescan() {
        Map<Path, DriverJar> found = new java.util.LinkedHashMap<>();
        for (Path dir : searchDirs()) {
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> s = Files.list(dir)) {
                s.forEach(p -> {
                    Matcher m = JAR_NAME.matcher(p.getFileName().toString());
                    if (m.matches()) {
                        Path real = p.toAbsolutePath().normalize();
                        found.putIfAbsent(real, new DriverJar(real,
                                Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                                Integer.parseInt(m.group(3))));
                    }
                });
            } catch (IOException ignored) {
                // pasta inacessível: apenas ignora
            }
        }
        List<DriverJar> list = new ArrayList<>(found.values());
        list.sort(Comparator.comparingInt(DriverJar::major)
                .thenComparingInt(DriverJar::minor)
                .thenComparingInt(DriverJar::patch).reversed());
        jars = List.copyOf(list);
    }

    /** Driver mais novo disponível para uma família. */
    public Optional<DriverJar> best(DriverFamily family) {
        return list().stream().filter(j -> j.family() == family).findFirst();
    }

    /** Carrega (uma única vez) o driver de um jar em ClassLoader próprio. */
    public Driver load(DriverJar jar) throws Exception {
        Driver d = loaded.get(jar.path());
        if (d != null) {
            return d;
        }
        synchronized (loaded) {
            d = loaded.get(jar.path());
            if (d == null) {
                URLClassLoader cl = new URLClassLoader("h2-" + jar.version(),
                        new URL[]{jar.path().toUri().toURL()}, ClassLoader.getPlatformClassLoader());
                Class<?> c = Class.forName("org.h2.Driver", true, cl);
                d = (Driver) c.getDeclaredConstructor().newInstance();
                loaded.put(jar.path(), d);
            }
            return d;
        }
    }
}
