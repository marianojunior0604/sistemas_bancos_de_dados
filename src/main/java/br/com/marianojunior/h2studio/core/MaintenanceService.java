package br.com.marianojunior.h2studio.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Operações de manutenção do arquivo do banco: conversão de versão, compactação,
 * restauração de backup e script SQL completo.
 * <p>
 * Compactar e restaurar exigem o banco FECHADO (pela tela e pelo sistema do cliente);
 * a janela principal fecha as abas/conexões antes de chamar estes métodos.
 */
public final class MaintenanceService {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    private MaintenanceService() {
    }

    // =====================================================================
    // Backups
    // =====================================================================

    public record BackupFile(Path path, LocalDateTime when, long size, String kind) {
    }

    /** Backups do banco, do mais recente para o mais antigo. */
    public static List<BackupFile> listBackups(ConnectionConfig cfg) throws IOException {
        Path dir = BackupService.backupDir(cfg);
        List<BackupFile> list = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return list;
        }
        try (Stream<Path> s = Files.list(dir)) {
            for (Path p : s.filter(x -> x.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip")).toList()) {
                String n = p.getFileName().toString();
                LocalDateTime when;
                try {
                    when = LocalDateTime.parse(n.substring(0, 19), TS);
                } catch (RuntimeException e) {
                    when = LocalDateTime.ofInstant(Files.getLastModifiedTime(p).toInstant(), java.time.ZoneId.systemDefault());
                }
                String kind = n.contains("antes-de-restaurar") ? "Cópia antes de restaurar"
                        : n.contains("antes-da-conversao") ? "Original antes da conversão"
                        : n.contains("antes-de-compactar") ? "Antes de compactar"
                        : "Backup";
                list.add(new BackupFile(p, when, Files.size(p), kind));
            }
        }
        list.sort(Comparator.comparing(BackupFile::when).reversed());
        return list;
    }

    /** Compacta um arquivo do banco (fechado) num .zip, como o BACKUP TO faria. */
    public static Path zipFile(Path dbFile, Path zip) throws IOException {
        Files.createDirectories(zip.getParent());
        try (OutputStream out = Files.newOutputStream(zip); ZipOutputStream z = new ZipOutputStream(out)) {
            z.putNextEntry(new ZipEntry(dbFile.getFileName().toString()));
            Files.copy(dbFile, z);
            z.closeEntry();
        }
        return zip;
    }

    private static Path safetyZip(ConnectionConfig cfg, Path dbFile, String suffix) throws IOException {
        Path zip = BackupService.backupDir(cfg).resolve(LocalDateTime.now().format(TS) + "_" + suffix + ".zip");
        return zipFile(dbFile, zip);
    }

    /** Garante que nenhum outro programa está com o banco aberto (abre e fecha em modo exclusivo). */
    static void ensureNotInUse(ConnectionFactory factory, ConnectionConfig cfg) throws DbOpenException {
        ConnectionConfig c = cfg.copy();
        c.setMode(ConnectionConfig.Mode.NORMAL);
        try (DbSession s = factory.open(c)) {
            s.isClosed(); // só testa
        }
    }

    public record RestoreResult(Path restoredFile, Path safetyCopy) {
    }

    /**
     * Substitui o arquivo do banco pelo de um backup (.zip do H2 Studio / BACKUP TO, ou um
     * .mv.db/.h2.db). Antes, guarda o arquivo atual em "…_antes-de-restaurar.zip".
     */
    public static RestoreResult restore(ConnectionFactory factory, ConnectionConfig cfg, Path source,
                                        Consumer<String> log) throws Exception {
        Path current = cfg.file();
        log.accept("Verificando se o banco está livre…");
        ensureNotInUse(factory, cfg);

        String base = H2FileInspector.basePath(current);
        Path tmp = Path.of(base + ".restaurando");
        String ext;
        log.accept("Lendo " + source.getFileName() + "…");
        String srcName = source.getFileName().toString().toLowerCase(Locale.ROOT);
        if (srcName.endsWith(".zip")) {
            try (ZipFile zf = new ZipFile(source.toFile())) {
                ZipEntry entry = zf.stream()
                        .filter(e -> !e.isDirectory() && (e.getName().toLowerCase(Locale.ROOT).endsWith(H2FileInspector.MV_EXT)
                                || e.getName().toLowerCase(Locale.ROOT).endsWith(H2FileInspector.PAGE_EXT)))
                        .findFirst().orElseThrow(() -> new IOException("O .zip não contém um arquivo de banco H2 (.mv.db ou .h2.db)."));
                ext = entry.getName().toLowerCase(Locale.ROOT).endsWith(H2FileInspector.MV_EXT) ? H2FileInspector.MV_EXT : H2FileInspector.PAGE_EXT;
                try (InputStream in = zf.getInputStream(entry)) {
                    Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } else if (srcName.endsWith(H2FileInspector.MV_EXT) || srcName.endsWith(H2FileInspector.PAGE_EXT)) {
            ext = srcName.endsWith(H2FileInspector.MV_EXT) ? H2FileInspector.MV_EXT : H2FileInspector.PAGE_EXT;
            Files.copy(source, tmp, StandardCopyOption.REPLACE_EXISTING);
        } else {
            throw new IOException("Escolha um backup .zip ou um arquivo .mv.db / .h2.db.");
        }
        try {
            if (H2FileInspector.inspect(tmp).kind() == H2FileInspector.Kind.UNKNOWN) {
                throw new IOException("O arquivo do backup não é um banco H2 válido.");
            }
            log.accept("Guardando uma cópia do banco atual…");
            Path safety = safetyZip(cfg, current, "antes-de-restaurar");
            Path target = Path.of(base + ext);
            log.accept("Substituindo o arquivo…");
            move(tmp, target);
            if (!target.equals(current)) {
                Files.deleteIfExists(current); // o tipo de arquivo mudou (.h2.db ↔ .mv.db); o antigo está no zip
            }
            log.accept("Restaurado.");
            return new RestoreResult(target, safety);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.FileSystemException e) {
            throw new IOException("Não foi possível substituir " + to.getFileName()
                    + ": o arquivo está em uso por outro programa. Feche o sistema e tente de novo.", e);
        }
    }

    // =====================================================================
    // Compactar
    // =====================================================================

    public record CompactResult(long before, long after, Path backup) {
    }

    /** Backup + SHUTDOWN COMPACT (o H2 reescreve o arquivo sem o espaço livre). */
    public static CompactResult compact(ConnectionFactory factory, ConnectionConfig cfg, boolean backup,
                                        Consumer<String> log) throws Exception {
        ConnectionConfig c = cfg.copy();
        c.setMode(ConnectionConfig.Mode.NORMAL);
        log.accept("Abrindo o banco em modo exclusivo…");
        DbSession s = factory.open(c);
        Path file = s.config().file();
        long before = Files.size(file);
        Path bk = null;
        try {
            if (backup) {
                log.accept("Fazendo backup…");
                bk = s.runNow(x -> BackupService.backup(x, cfg, AppPreferences.backupsToKeep()));
            }
            log.accept("Compactando (pode demorar em bancos grandes)…");
            s.runNow(x -> {
                try (Statement st = x.createStatement()) {
                    st.execute("SHUTDOWN COMPACT");
                }
                return null;
            });
        } finally {
            s.close();
        }
        long after = Files.size(file);
        log.accept("Concluído.");
        return new CompactResult(before, after, bk);
    }

    // =====================================================================
    // Converter versão
    // =====================================================================

    public record TableCount(String table, long source, long target) {
        public boolean matches() {
            return source == target;
        }
    }

    public record ConvertResult(Path file, List<TableCount> tables, long millis) {
        public long totalRows() {
            return tables.stream().mapToLong(TableCount::target).sum();
        }

        public List<TableCount> mismatches() {
            return tables.stream().filter(t -> !t.matches()).toList();
        }
    }

    /** Caminho sugerido para o arquivo convertido: loja.mv.db → loja_h2-2.3.mv.db */
    public static Path suggestTarget(Path source, DriverRegistry.DriverJar jar) {
        String base = H2FileInspector.basePath(source);
        return Path.of(base + "_h2-" + jar.major() + "." + jar.minor() + H2FileInspector.MV_EXT);
    }

    /**
     * Converte o banco para o formato de outra versão do H2, criando um arquivo NOVO.
     * O original não é alterado. Funciona com o banco aberto (o SCRIPT lê uma foto consistente).
     *
     * @param src conexão do banco de origem (executado na thread da sessão)
     */
    public static ConvertResult convert(Connection src, DriverRegistry.DriverJar sourceDriver, ConnectionFactory factory,
                                        ConnectionConfig cfg, DriverRegistry.DriverJar target, Path targetFile,
                                        Consumer<String> log) throws Exception {
        long t0 = System.currentTimeMillis();
        String targetBase = H2FileInspector.basePath(targetFile);
        for (String ext : List.of(H2FileInspector.MV_EXT, H2FileInspector.PAGE_EXT)) {
            if (Files.exists(Path.of(targetBase + ext))) {
                throw new IOException("Já existe o arquivo " + Path.of(targetBase + ext).getFileName()
                        + ". Escolha outro nome ou apague o arquivo.");
            }
        }
        Path dir = DriverRegistry.userDir().resolve("tmp");
        Files.createDirectories(dir);
        Path script = dir.resolve("conversao-" + LocalDateTime.now().format(TS) + ".sql");
        boolean ok = false;
        try {
            log.accept("1/4 Exportando o banco com o H2 " + sourceDriver.version() + "…");
            try (Statement st = src.createStatement()) {
                st.execute("SCRIPT TO '" + script.toString().replace("'", "''") + "' CHARSET 'UTF-8'");
            }
            log.accept("2/4 Criando o banco novo com o H2 " + target.version() + "…");
            List<TableCount> counts = new ArrayList<>();
            try (Connection dst = factory.createDatabase(cfg, target, targetBase)) {
                try (Statement st = dst.createStatement()) {
                    st.execute("RUNSCRIPT FROM '" + script.toString().replace("'", "''") + "' CHARSET 'UTF-8'"
                            + (sourceDriver.major() == 1 ? " FROM_1X" : ""));
                }
                log.accept("3/4 Conferindo a quantidade de linhas de cada tabela…");
                for (String schema : MetadataService.schemas(src, false)) {
                    for (Meta.TableInfo t : MetadataService.tables(src, schema)) {
                        if (t.view()) {
                            continue;
                        }
                        long a = count(src, t);
                        long b;
                        try {
                            b = count(dst, t);
                        } catch (SQLException e) {
                            b = -1; // tabela não chegou
                        }
                        counts.add(new TableCount(t.schema() + "." + t.name(), a, b));
                    }
                }
            }
            ok = counts.stream().allMatch(TableCount::matches);
            if (!ok) {
                throw new IOException("A conferência falhou: tabelas com quantidade diferente: "
                        + counts.stream().filter(x -> !x.matches()).map(x -> x.table() + " (" + x.source() + " → " + x.target() + ")").toList());
            }
            log.accept("4/4 Concluído.");
            return new ConvertResult(Path.of(targetBase + H2FileInspector.MV_EXT), counts, System.currentTimeMillis() - t0);
        } finally {
            Files.deleteIfExists(script);
            if (!ok) {
                for (String ext : List.of(H2FileInspector.MV_EXT, H2FileInspector.PAGE_EXT, ".trace.db")) {
                    try {
                        Files.deleteIfExists(Path.of(targetBase + ext));
                    } catch (IOException ignored) {
                        // pode estar preso ainda; o usuário pode apagar depois
                    }
                }
            }
        }
    }

    private static long count(Connection c, Meta.TableInfo t) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + t.qualified())) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /**
     * Coloca o arquivo convertido no lugar do original (ambos fechados). O original vai
     * para os backups como "…_antes-da-conversao.zip".
     *
     * @return caminho final do banco (sempre .mv.db)
     */
    public static Path replaceOriginal(ConnectionFactory factory, ConnectionConfig cfg, Path converted,
                                       Consumer<String> log) throws Exception {
        Path original = cfg.file();
        log.accept("Verificando se o banco original está livre…");
        ensureNotInUse(factory, cfg);
        log.accept("Guardando o original nos backups…");
        safetyZip(cfg, original, "antes-da-conversao");
        Path target = Path.of(H2FileInspector.basePath(original) + H2FileInspector.MV_EXT);
        log.accept("Substituindo pelo convertido…");
        move(converted, target);
        if (!target.equals(original)) {
            Files.deleteIfExists(original); // era .h2.db (PageStore); o original está no zip
        }
        return target;
    }

    // =====================================================================
    // Script SQL completo
    // =====================================================================

    /** SCRIPT TO: estrutura (e dados) do banco inteiro num arquivo .sql, legível e portável. */
    public static void dumpScript(Connection c, Path out, boolean withData) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute("SCRIPT " + (withData ? "" : "NODATA ") + "TO '" + out.toString().replace("'", "''") + "' CHARSET 'UTF-8'");
        }
    }
}
