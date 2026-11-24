package br.com.marianojunior.h2studio.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Histórico de instruções executadas, um arquivo por banco em
 * {@code ~/.h2studio/historico/}. O arquivo é SQL válido (cada entrada começa com
 * um comentário {@code -- @@ data | OK | resumo}), então pode ser aberto em qualquer editor.
 */
public final class SqlHistory {

    public record Entry(LocalDateTime when, boolean ok, String info, String sql) {
    }

    private static final String MARK = "-- @@ ";
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final int KEEP = 500;

    private final Path file;

    public SqlHistory(Path file) {
        this.file = file;
    }

    public static SqlHistory forDatabase(ConnectionConfig cfg) {
        Path dir = DriverRegistry.userDir().resolve("historico");
        if (!cfg.isH2()) {
            // servidor/SQLite: nome legível + hash do destino (tipo, servidor, banco, usuário)
            String base = (cfg.getType().name() + "-" + (cfg.getType().isFile() && cfg.file() != null
                    ? cfg.file().getFileName().toString() : cfg.getDatabase() + "@" + cfg.getHost()))
                    .replaceAll("[^A-Za-z0-9._@-]", "_");
            String hash = Integer.toHexString(cfg.targetKey().toLowerCase(java.util.Locale.ROOT).hashCode());
            return new SqlHistory(dir.resolve(base + "-" + hash + ".sql"));
        }
        return new SqlHistory(dir.resolve(BackupService.backupDir(cfg).getFileName().toString() + ".sql"));
    }

    public Path file() {
        return file;
    }

    public synchronized void add(String sql, boolean ok, String info) {
        String body = sql.strip();
        if (body.isEmpty()) {
            return;
        }
        String entry = MARK + LocalDateTime.now().format(TS) + " | " + (ok ? "OK" : "ERRO") + " | "
                + info.replace('\n', ' ') + "\n" + body + (body.endsWith(";") ? "" : ";") + "\n";
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, entry, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            if (Files.size(file) > 2_000_000) {
                trim();
            }
        } catch (IOException e) {
            System.err.println("Não foi possível gravar o histórico: " + e.getMessage());
        }
    }

    /** Entradas da mais recente para a mais antiga. */
    public synchronized List<Entry> list() {
        List<Entry> out = new ArrayList<>();
        if (!Files.isRegularFile(file)) {
            return out;
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return out;
        }
        LocalDateTime when = null;
        boolean ok = true;
        String info = "";
        StringBuilder sql = new StringBuilder();
        for (String line : lines) {
            if (line.startsWith(MARK)) {
                if (when != null) {
                    out.add(new Entry(when, ok, info, sql.toString().strip()));
                }
                String[] p = line.substring(MARK.length()).split(" \\| ", 3);
                try {
                    when = LocalDateTime.parse(p[0].trim(), TS);
                } catch (RuntimeException e) {
                    when = LocalDateTime.MIN;
                }
                ok = p.length < 2 || p[1].trim().equals("OK");
                info = p.length > 2 ? p[2].trim() : "";
                sql.setLength(0);
            } else if (when != null) {
                sql.append(line).append('\n');
            }
        }
        if (when != null) {
            out.add(new Entry(when, ok, info, sql.toString().strip()));
        }
        Collections.reverse(out);
        return out;
    }

    private void trim() throws IOException {
        List<Entry> all = list();
        List<Entry> keep = new ArrayList<>(all.subList(0, Math.min(KEEP, all.size())));
        Collections.reverse(keep);
        StringBuilder sb = new StringBuilder();
        for (Entry e : keep) {
            sb.append(MARK).append(e.when().format(TS)).append(" | ").append(e.ok() ? "OK" : "ERRO").append(" | ")
                    .append(e.info()).append('\n').append(e.sql()).append('\n');
        }
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
    }
}
