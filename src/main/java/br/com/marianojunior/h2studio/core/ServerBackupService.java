package br.com.marianojunior.h2studio.core;

import br.com.marianojunior.h2studio.core.NativeTools.Tool;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Backup e restauração dos outros bancos com as ferramentas de cada um:
 * <ul>
 *   <li>PostgreSQL: pg_dump (formato custom, .backup) e pg_restore / psql;</li>
 *   <li>MySQL/MariaDB: mysqldump (.sql.gz) e mysql;</li>
 *   <li>Firebird: gbak (.fbk);</li>
 *   <li>SQL Server: BACKUP/RESTORE DATABASE pelo próprio servidor (o .bak fica no servidor);</li>
 *   <li>SQLite: VACUUM INTO (cópia consistente, sem ferramenta externa).</li>
 * </ul>
 * Senhas nunca vão na linha de comando: variável de ambiente (PGPASSWORD, ISC_PASSWORD) ou
 * arquivo temporário de opções do MySQL, apagado no fim.
 */
public final class ServerBackupService {

    /** Andamento: linhas de texto da ferramenta e pedido de cancelamento. */
    public interface Progress {
        void line(String text);

        boolean cancelled();
    }

    /** Um backup listado: arquivo local ou, no SQL Server, caminho no servidor. */
    public record BackupFile(String location, Path localFile, long size, Instant time, boolean onServer) {
        public String name() {
            return localFile != null ? localFile.getFileName().toString()
                    : location.substring(Math.max(location.lastIndexOf('\\'), location.lastIndexOf('/')) + 1);
        }

        public boolean safetyCopy() {
            return name().contains("_antes-de-restaurar");
        }
    }

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    private ServerBackupService() {
    }

    // =====================================================================
    // Onde ficam
    // =====================================================================

    /** Pasta local dos backups desta conexão (~/.h2studio/backups/POSTGRESQL-loja@servidor-xxxx). */
    public static Path backupDir(ConnectionConfig cfg) {
        String base = SqlHistory.forDatabase(cfg).file().getFileName().toString().replaceAll("\\.sql$", "");
        return DriverRegistry.userDir().resolve("backups").resolve(base);
    }

    /** Extensão do arquivo de backup do tipo. */
    public static String extension(DbType t) {
        return switch (t) {
            case POSTGRESQL -> ".backup";
            case MYSQL, MARIADB -> ".sql.gz";
            case FIREBIRD, FIREBIRD_25 -> ".fbk";
            case SQLSERVER -> ".bak";
            case SQLITE -> ".sqlite";
            case H2 -> ".zip";
        };
    }

    /** Ferramentas necessárias para fazer (e restaurar) backup deste tipo. */
    public static List<Tool> toolsFor(DbType t) {
        return switch (t) {
            case POSTGRESQL -> List.of(Tool.PG_DUMP, Tool.PG_RESTORE, Tool.PSQL);
            case MYSQL, MARIADB -> List.of(Tool.MYSQLDUMP, Tool.MYSQL);
            case FIREBIRD, FIREBIRD_25 -> List.of(Tool.GBAK);
            default -> List.of();
        };
    }

    /** Nome do arquivo: banco_2026-11-24_17-30-00[_sufixo].ext */
    static String fileName(DbType t, String database, String suffix) {
        String db = database == null || database.isBlank() ? "banco" : database;
        db = db.replace('\\', '/');
        db = db.substring(db.lastIndexOf('/') + 1).replaceAll("(?i)\\.(fdb|gdb|db|sqlite3?|db3)$", "");
        db = db.replaceAll("[^A-Za-z0-9._-]", "_");
        return db + "_" + LocalDateTime.now().format(STAMP) + (suffix == null ? "" : "_" + suffix) + extension(t);
    }

    /** Backups desta conexão, mais recente primeiro. */
    public static List<BackupFile> list(DbSession s, String database) throws Exception {
        if (s.type() == DbType.SQLSERVER) {
            return s.runNow(c -> listSqlServer(c, database));
        }
        List<BackupFile> out = new ArrayList<>();
        Path dir = backupDir(s.config());
        if (!Files.isDirectory(dir)) {
            return out;
        }
        String ext = extension(s.type());
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) {
                String n = p.getFileName().toString();
                if (Files.isRegularFile(p) && (n.endsWith(ext) || n.endsWith(".sql"))) {
                    out.add(new BackupFile(p.toString(), p, Files.size(p), Files.getLastModifiedTime(p).toInstant(), false));
                }
            }
        }
        out.sort((a, b) -> b.time().compareTo(a.time()));
        return out;
    }

    // =====================================================================
    // Backup
    // =====================================================================

    /**
     * Faz o backup. {@code database} é o banco (no MySQL, quando a conexão abrange o servidor
     * inteiro); {@code target} é o arquivo de destino, ou null para a pasta padrão.
     * Devolve o local do backup (no SQL Server, o caminho no servidor).
     */
    public static String backup(DbSession s, String database, Path target, String suffix, Progress p) throws Exception {
        ConnectionConfig cfg = s.config();
        DbType type = s.type();
        String db = database != null && !database.isBlank() ? database : cfg.getDatabase();
        if (type == DbType.SQLSERVER) {
            return backupSqlServer(s, db, suffix, p);
        }
        Path out = target != null ? target : backupDir(cfg).resolve(fileName(type, db, suffix));
        Files.createDirectories(out.toAbsolutePath().getParent());
        Path tmp = out.resolveSibling(out.getFileName() + ".parcial");
        try {
            switch (type) {
                case SQLITE -> backupSqlite(s, tmp, p);
                case POSTGRESQL -> backupPostgres(cfg, db, tmp, p);
                case MYSQL, MARIADB -> backupMysql(s, db, tmp, p);
                case FIREBIRD, FIREBIRD_25 -> backupFirebird(cfg, tmp, p);
                default -> throw new IllegalArgumentException("Backup de " + type + " é feito pela tela do H2.");
            }
            if (!Files.isRegularFile(tmp) || Files.size(tmp) == 0) {
                throw new IOException("A ferramenta terminou sem gerar o arquivo de backup.");
            }
            Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING);
            p.line("Backup concluído: " + out.getFileName() + " (" + JdbcDrivers.size(Files.size(out)) + ")");
            return out.toString();
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static void backupSqlite(DbSession s, Path out, Progress p) throws Exception {
        p.line("Copiando o banco (VACUUM INTO)…");
        String path = out.toAbsolutePath().toString().replace("'", "''");
        s.runNow(c -> {
            try (Statement st = c.createStatement()) {
                st.execute("VACUUM INTO '" + path + "'");
            }
            return null;
        });
    }

    private static void backupPostgres(ConnectionConfig cfg, String db, Path out, Progress p) throws Exception {
        Path exe = require(Tool.PG_DUMP);
        List<String> cmd = new ArrayList<>(List.of(exe.toString(), "--format=custom", "--verbose",
                "--file=" + out.toAbsolutePath()));
        cmd.addAll(pgConn(cfg, db.isBlank() ? "postgres" : db));
        check(NativeTools.run(cmd, pgEnv(cfg), null, null, p::line, p::cancelled), "pg_dump");
    }

    private static void backupMysql(DbSession s, String db, Path out, Progress p) throws Exception {
        if (db.isBlank()) {
            throw new IOException("Escolha qual banco do servidor vai para o backup.");
        }
        ConnectionConfig cfg = s.config();
        Path exe = require(Tool.MYSQLDUMP);
        Path opts = mysqlOptionsFile(cfg);
        try {
            List<String> cmd = new ArrayList<>(List.of(exe.toString(), "--defaults-extra-file=" + opts,
                    "--single-transaction", "--routines", "--triggers", "--events", "--hex-blob",
                    "--default-character-set=utf8mb4"));
            // mysqldump 8 contra MariaDB ou MySQL 5: a tabela column_statistics não existe lá
            String ver = NativeTools.version(Tool.MYSQLDUMP, exe);
            if (!ver.contains("MariaDB") && NativeTools.majorVersion(ver.replaceFirst("(?i).*?Ver\\s+", "")) >= 8
                    && (s.type() == DbType.MARIADB || s.productVersion().startsWith("5."))) {
                cmd.add("--column-statistics=0");
            }
            cmd.add(db);
            p.line("Gerando o dump de " + db + "…");
            try (OutputStream os = new GZIPOutputStream(Files.newOutputStream(out), 64 * 1024)) {
                check(NativeTools.run(cmd, Map.of(), null, os, p::line, p::cancelled), "mysqldump");
            }
        } finally {
            Files.deleteIfExists(opts);
        }
    }

    private static void backupFirebird(ConnectionConfig cfg, Path out, Progress p) throws Exception {
        Path exe = require(Tool.GBAK);
        List<String> cmd = List.of(exe.toString(), "-b", "-v", "-g", firebirdTarget(cfg, cfg.getDatabase()),
                out.toAbsolutePath().toString());
        check(NativeTools.run(cmd, fbEnv(cfg), null, null, p::line, p::cancelled), "gbak");
    }

    private static String backupSqlServer(DbSession s, String db, String suffix, Progress p) throws Exception {
        if (db.isBlank()) {
            throw new IOException("A conexão precisa indicar o banco para fazer backup no SQL Server.");
        }
        return s.runNow(c -> {
            String dir = sqlServerBackupDir(c);
            String sep = dir.contains("/") && !dir.contains("\\") ? "/" : "\\";
            String file = (dir.endsWith(sep) ? dir : dir + sep) + fileName(DbType.SQLSERVER, db, suffix);
            p.line("O SQL Server vai gravar o backup no próprio servidor:\n" + file);
            String sql = "BACKUP DATABASE " + DbType.SQLSERVER.quote(db) + " TO DISK = N'" + file.replace("'", "''")
                    + "' WITH COPY_ONLY, INIT, CHECKSUM, STATS = 10";
            boolean auto = c.getAutoCommit();
            c.setAutoCommit(true); // BACKUP não roda dentro de transação
            try (Statement st = c.createStatement()) {
                st.execute(sql);
                for (java.sql.SQLWarning w = st.getWarnings(); w != null; w = w.getNextWarning()) {
                    p.line(w.getMessage());
                }
            } finally {
                c.setAutoCommit(auto);
            }
            return file;
        });
    }

    /** Pasta padrão de backup da instância (SQL Server 2012+). */
    static String sqlServerBackupDir(Connection c) throws SQLException {
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT CAST(SERVERPROPERTY('InstanceDefaultBackupPath') AS NVARCHAR(400))")) {
            if (rs.next() && rs.getString(1) != null && !rs.getString(1).isBlank()) {
                return rs.getString(1);
            }
        }
        throw new SQLException("Não foi possível descobrir a pasta de backup do SQL Server (versão anterior a 2012?).");
    }

    private static List<BackupFile> listSqlServer(Connection c, String db) throws SQLException {
        List<BackupFile> out = new ArrayList<>();
        String sql = "SELECT TOP 50 f.physical_device_name, s.backup_finish_date, s.backup_size "
                + "FROM msdb.dbo.backupset s JOIN msdb.dbo.backupmediafamily f ON s.media_set_id = f.media_set_id "
                + "WHERE s.database_name = ? AND s.type = 'D' ORDER BY s.backup_finish_date DESC";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, db);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    java.sql.Timestamp ts = rs.getTimestamp(2);
                    out.add(new BackupFile(rs.getString(1), null, rs.getLong(3),
                            ts == null ? Instant.EPOCH : ts.toInstant(), true));
                }
            }
        }
        return out;
    }

    // =====================================================================
    // Restauração
    // =====================================================================

    /**
     * Restaura {@code backup} em {@code targetDb}:
     * <ul>
     *   <li>o próprio banco (substitui; quem chama faz antes um backup de segurança), ou</li>
     *   <li>um banco novo, criado aqui (PostgreSQL, MySQL, SQL Server) ou pelo gbak (Firebird, caminho
     *       no servidor). No SQLite o "banco novo" é outro arquivo.</li>
     * </ul>
     */
    public static void restore(DbSession s, String backup, String sourceDb, String targetDb, boolean replace, Progress p)
            throws Exception {
        ConnectionConfig cfg = s.config();
        switch (s.type()) {
            case POSTGRESQL -> restorePostgres(s, Path.of(backup), targetDb, replace, p);
            case MYSQL, MARIADB -> restoreMysql(s, Path.of(backup), targetDb, replace, p);
            case FIREBIRD, FIREBIRD_25 -> restoreFirebird(cfg, Path.of(backup), targetDb, replace, p);
            case SQLSERVER -> restoreSqlServer(s, backup, targetDb, p);
            case SQLITE -> restoreSqlite(Path.of(backup), Path.of(targetDb), p);
            default -> throw new IllegalArgumentException(s.type().toString());
        }
    }

    private static void restorePostgres(DbSession s, Path file, String db, boolean replace, Progress p) throws Exception {
        ConnectionConfig cfg = s.config();
        if (!replace) {
            createDatabase(s, DbType.POSTGRESQL, db, p);
        }
        boolean plain = file.getFileName().toString().endsWith(".sql");
        List<String> cmd = new ArrayList<>();
        if (plain) {
            cmd.add(require(Tool.PSQL).toString());
            cmd.addAll(List.of("-v", "ON_ERROR_STOP=1", "--single-transaction", "--file=" + file.toAbsolutePath()));
            cmd.addAll(pgConn(cfg, db));
        } else {
            cmd.add(require(Tool.PG_RESTORE).toString());
            cmd.addAll(List.of("--verbose", "--no-owner", "--no-privileges", "--single-transaction", "--exit-on-error"));
            if (replace) {
                cmd.addAll(List.of("--clean", "--if-exists"));
            }
            cmd.addAll(pgConn(cfg, db));
            cmd.add(file.toAbsolutePath().toString());
        }
        check(NativeTools.run(cmd, pgEnv(cfg), null, null, p::line, p::cancelled), plain ? "psql" : "pg_restore");
    }

    private static void restoreMysql(DbSession s, Path file, String db, boolean replace, Progress p) throws Exception {
        if (!replace) {
            createDatabase(s, s.type(), db, p);
        }
        Path exe = require(Tool.MYSQL);
        Path opts = mysqlOptionsFile(s.config());
        try {
            List<String> cmd = List.of(exe.toString(), "--defaults-extra-file=" + opts,
                    "--default-character-set=utf8mb4", db);
            p.line("Executando o dump em " + db + "…");
            InputStream raw = new BufferedInputStream(Files.newInputStream(file));
            InputStream in = file.getFileName().toString().endsWith(".gz") ? new GZIPInputStream(raw, 64 * 1024) : raw;
            check(NativeTools.run(cmd, Map.of(), in, null, p::line, p::cancelled), "mysql");
        } finally {
            Files.deleteIfExists(opts);
        }
    }

    private static void restoreFirebird(ConnectionConfig cfg, Path file, String db, boolean replace, Progress p)
            throws Exception {
        Path exe = require(Tool.GBAK);
        List<String> cmd = List.of(exe.toString(), replace ? "-rep" : "-c", "-v", file.toAbsolutePath().toString(),
                firebirdTarget(cfg, db));
        check(NativeTools.run(cmd, fbEnv(cfg), null, null, p::line, p::cancelled), "gbak");
    }

    private static void restoreSqlServer(DbSession s, String bakOnServer, String newDb, Progress p) throws Exception {
        s.runNow(c -> {
            boolean auto = c.getAutoCommit();
            c.setAutoCommit(true);
            try {
                // arquivos do backup: dá nomes novos ao lado dos originais para não sobrescrever o banco atual
                List<String> moves = new ArrayList<>();
                try (Statement st = c.createStatement();
                     ResultSet rs = st.executeQuery("RESTORE FILELISTONLY FROM DISK = N'" + bakOnServer.replace("'", "''") + "'")) {
                    int n = 0;
                    while (rs.next()) {
                        String logical = rs.getString("LogicalName");
                        String physical = rs.getString("PhysicalName");
                        String type = rs.getString("Type");
                        int cut = Math.max(physical.lastIndexOf('\\'), physical.lastIndexOf('/'));
                        String dir = cut >= 0 ? physical.substring(0, cut + 1) : "";
                        String ext = "L".equals(type) ? "_log.ldf" : n == 0 ? ".mdf" : "_" + n + ".ndf";
                        if (!"L".equals(type)) {
                            n++;
                        }
                        moves.add("MOVE N'" + logical.replace("'", "''") + "' TO N'"
                                + (dir + newDb + ext).replace("'", "''") + "'");
                    }
                }
                String sql = "RESTORE DATABASE " + DbType.SQLSERVER.quote(newDb) + " FROM DISK = N'"
                        + bakOnServer.replace("'", "''") + "' WITH " + String.join(", ", moves) + ", STATS = 10";
                p.line("Restaurando como " + newDb + "…");
                try (Statement st = c.createStatement()) {
                    st.execute(sql);
                    for (java.sql.SQLWarning w = st.getWarnings(); w != null; w = w.getNextWarning()) {
                        p.line(w.getMessage());
                    }
                }
            } finally {
                c.setAutoCommit(auto);
            }
            return null;
        });
    }

    private static void restoreSqlite(Path backup, Path target, Progress p) throws IOException {
        p.line("Copiando " + backup.getFileName() + " para " + target + "…");
        Files.createDirectories(target.toAbsolutePath().getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".parcial");
        Files.copy(backup, tmp, StandardCopyOption.REPLACE_EXISTING);
        for (String suffix : new String[]{"-wal", "-shm", "-journal"}) {
            Files.deleteIfExists(target.resolveSibling(target.getFileName() + suffix));
        }
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
    }

    /** CREATE DATABASE; recusa se o banco já existir (restaurar "como novo" nunca sobrescreve). */
    static void createDatabase(DbSession s, DbType type, String db, Progress p) throws Exception {
        if (db == null || db.isBlank()) {
            throw new IOException("Informe o nome do banco novo.");
        }
        p.line("Criando o banco " + db + "…");
        s.runNow(c -> {
            boolean auto = c.getAutoCommit();
            c.setAutoCommit(true);
            try (Statement st = c.createStatement()) {
                st.execute("CREATE DATABASE " + type.quote(db));
            } catch (SQLException e) {
                throw new SQLException("Não foi possível criar o banco " + db + ": " + e.getMessage().split("\n")[0], e);
            } finally {
                c.setAutoCommit(auto);
            }
            return null;
        });
    }

    // =====================================================================
    // Auxiliares
    // =====================================================================

    static Path require(Tool t) throws IOException {
        Path p = NativeTools.find(t);
        if (p == null) {
            throw new MissingToolException(t);
        }
        return p;
    }

    /** A ferramenta não foi encontrada: a tela oferece para indicar onde ela está. */
    public static final class MissingToolException extends IOException {
        private final Tool tool;

        public MissingToolException(Tool tool) {
            super("Não encontrei o " + tool.exe() + " (" + tool.product() + "). Indique onde ele está em "
                    + "\"Ferramentas do banco\" ou instale as ferramentas cliente do " + tool.product() + ".");
            this.tool = tool;
        }

        public Tool tool() {
            return tool;
        }
    }

    private static void check(NativeTools.Run r, String tool) throws IOException {
        if (r.exitCode() != 0) {
            throw new IOException(tool + " terminou com erro (código " + r.exitCode() + "):\n" + r.tail());
        }
    }

    private static List<String> pgConn(ConnectionConfig cfg, String db) {
        int port = cfg.getPort() > 0 ? cfg.getPort() : DbType.POSTGRESQL.defaultPort();
        return List.of("--host=" + cfg.getHost(), "--port=" + port, "--username=" + cfg.getUser(),
                "--no-password", "--dbname=" + db);
    }

    private static Map<String, String> pgEnv(ConnectionConfig cfg) {
        Map<String, String> env = new HashMap<>();
        env.put("PGPASSWORD", cfg.getPassword());
        env.put("PGCONNECT_TIMEOUT", "15");
        env.put("PGAPPNAME", "H2 Studio");
        return env;
    }

    private static Map<String, String> fbEnv(ConnectionConfig cfg) {
        Map<String, String> env = new HashMap<>();
        env.put("ISC_USER", cfg.getUser());
        env.put("ISC_PASSWORD", cfg.getPassword());
        return env;
    }

    /** host/porta:caminho, o formato que o gbak aceita do Firebird 2.5 em diante. */
    static String firebirdTarget(ConnectionConfig cfg, String path) {
        int port = cfg.getPort() > 0 ? cfg.getPort() : 3050;
        return cfg.getHost() + "/" + port + ":" + path;
    }

    /** Arquivo de opções do MySQL com host, porta, usuário e senha (só o dono lê; apagado no fim). */
    static Path mysqlOptionsFile(ConnectionConfig cfg) throws IOException {
        Path f = Files.createTempFile("h2studio-mysql", ".cnf");
        try {
            Files.setPosixFilePermissions(f, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) {
            // Windows: a pasta temporária já é do próprio usuário
        }
        int port = cfg.getPort() > 0 ? cfg.getPort() : 3306;
        String content = "[client]\nhost=" + cfg.getHost() + "\nport=" + port + "\nuser=" + escapeCnf(cfg.getUser())
                + "\npassword=" + escapeCnf(cfg.getPassword()) + "\n";
        Files.writeString(f, content, StandardCharsets.UTF_8);
        return f;
    }

    private static String escapeCnf(String v) {
        String s = v == null ? "" : v;
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /** "loja_restaurado_20261124" */
    public static String suggestNewName(DbType type, String db) {
        String base = db == null || db.isBlank() ? "banco" : db;
        if (type.isFirebird()) {
            String lower = base.toLowerCase(Locale.ROOT);
            int dot = lower.lastIndexOf('.');
            String ext = dot > Math.max(lower.lastIndexOf('/'), lower.lastIndexOf('\\')) ? base.substring(dot) : ".fdb";
            String stem = dot > Math.max(lower.lastIndexOf('/'), lower.lastIndexOf('\\')) ? base.substring(0, dot) : base;
            return stem + "_RESTAURADO" + ext;
        }
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        return base + "_restaurado_" + stamp;
    }

    /** Mensagem de alerta quando a ferramenta é mais antiga que o servidor (pg_dump recusa). */
    public static String versionWarning(DbSession s, Tool tool, Path exe) {
        if (exe == null || s.type() != DbType.POSTGRESQL) {
            return null;
        }
        int toolMajor = NativeTools.majorVersion(NativeTools.version(tool, exe));
        int serverMajor = NativeTools.majorVersion(s.productVersion());
        if (toolMajor > 0 && serverMajor > 0 && toolMajor < serverMajor) {
            return "O " + tool.exe() + " encontrado é da versão " + toolMajor + " e o servidor é " + serverMajor
                    + ". O pg_dump precisa ser da mesma versão do servidor ou mais nova.";
        }
        return null;
    }

    /** Consome a saída sem fazer nada (para testes). */
    public static Progress silent(Consumer<String> sink, BooleanSupplier cancelled) {
        return new Progress() {
            @Override
            public void line(String text) {
                if (sink != null) {
                    sink.accept(text);
                }
            }

            @Override
            public boolean cancelled() {
                return cancelled != null && cancelled.getAsBoolean();
            }
        };
    }
}
