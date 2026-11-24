package br.com.marianojunior.h2studio.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Backup do banco com o comando {@code BACKUP TO} do próprio H2 (funciona com o
 * banco aberto e gera um .zip com o arquivo .mv.db / .h2.db consistente).
 * <p>
 * Os backups ficam em {@code ~/.h2studio/backups/<banco>/}. Para restaurar,
 * basta extrair o arquivo do .zip no lugar do original (com o sistema fechado).
 */
public final class BackupService {

    private BackupService() {
    }

    public static Path backupDir(ConnectionConfig cfg) {
        String file = cfg.getCopyOf() != null ? cfg.getCopyOf() : cfg.getFilePath();
        String base = Path.of(file).getFileName().toString().replaceAll("(?i)\\.(mv|h2)\\.db$", "");
        String safe = base.replaceAll("[^A-Za-z0-9._-]", "_");
        String hash = Integer.toHexString(Path.of(file).toAbsolutePath().normalize().toString().toLowerCase().hashCode());
        return DriverRegistry.userDir().resolve("backups").resolve(safe + "-" + hash);
    }

    /** Faz o backup e mantém só os {@code keep} mais recentes. */
    public static Path backup(Connection c, ConnectionConfig cfg, int keep) throws Exception {
        Path dir = backupDir(cfg);
        Files.createDirectories(dir);
        Path zip = dir.resolve(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")) + ".zip");
        try (Statement st = c.createStatement()) {
            st.execute("BACKUP TO '" + zip.toString().replace("'", "''") + "'");
        }
        if (!Files.isRegularFile(zip) || Files.size(zip) == 0) {
            throw new IOException("O arquivo de backup não foi gerado.");
        }
        prune(dir, keep);
        return zip;
    }

    static void prune(Path dir, int keep) {
        try (Stream<Path> s = Files.list(dir)) {
            List<Path> zips = s.filter(p -> p.getFileName().toString().endsWith(".zip"))
                    .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed())
                    .toList();
            for (int i = Math.max(1, keep); i < zips.size(); i++) {
                Files.deleteIfExists(zips.get(i));
            }
        } catch (IOException ignored) {
            // limpeza é opcional
        }
    }
}
