package br.com.marianojunior.h2studio.core;

import java.nio.file.Path;

/**
 * Configuração de uma conexão (salva em ~/.h2studio): um arquivo H2 ou, a partir da
 * versão 1.1, um servidor (PostgreSQL, MySQL/MariaDB, Firebird, SQL Server) ou arquivo SQLite.
 */
public class ConnectionConfig {

    public enum Mode {
        /** Abre normalmente (exclusivo: o sistema do cliente precisa estar fechado). */
        NORMAL("Normal"),
        /** Abre sem permitir gravação. */
        READ_ONLY("Somente leitura"),
        /** AUTO_SERVER=TRUE: compartilha o banco com o sistema, se ele também usar AUTO_SERVER. */
        AUTO_SERVER("Servidor automático (AUTO_SERVER)");

        private final String label;

        Mode(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private String id;
    private String name;
    private String filePath;
    private String user = "sa";
    private String password = "";
    private boolean rememberPassword;
    private boolean encrypted;
    private String filePassword = "";
    private Mode mode = Mode.NORMAL;
    /** Caminho de um jar específico; null = escolher automaticamente pelo formato do arquivo. */
    private String driverJar;
    private long lastOpened;
    /**
     * Opções extras da URL JDBC, iguais às usadas pelo sistema que criou o banco
     * (ex.: "MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE"). Algumas opções do H2 2.x não
     * ficam gravadas no arquivo e precisam ser repetidas a cada abertura.
     */
    private String urlOptions = "";

    // ---------- outros bancos ----------
    private DbType type = DbType.H2;
    private String host = "";
    private int port;
    /** Nome do banco; no Firebird o caminho/alias no servidor; no SQLite o arquivo. */
    private String database = "";
    /** Propriedades JDBC extras "chave=valor;chave=valor". */
    private String properties = "";
    /** Charset da conexão (Firebird: WIN1252, UTF8, NONE...). */
    private String charset = "";

    /** Preenchido quando a sessão é uma cópia temporária aberta porque o original estava em uso. */
    private transient String copyOf;

    public ConnectionConfig() {
    }

    public static ConnectionConfig forFile(Path file) {
        ConnectionConfig c = new ConnectionConfig();
        c.setFilePath(file.toAbsolutePath().normalize().toString());
        c.setName(defaultName(file));
        return c;
    }

    public static String defaultName(Path file) {
        String n = file.getFileName().toString();
        String lower = n.toLowerCase();
        if (lower.endsWith(H2FileInspector.MV_EXT)) {
            n = n.substring(0, n.length() - H2FileInspector.MV_EXT.length());
        } else if (lower.endsWith(H2FileInspector.PAGE_EXT)) {
            n = n.substring(0, n.length() - H2FileInspector.PAGE_EXT.length());
        }
        Path parent = file.toAbsolutePath().getParent();
        return parent != null && parent.getFileName() != null
                ? n + " (" + parent.getFileName() + ")"
                : n;
    }

    public ConnectionConfig copy() {
        ConnectionConfig c = new ConnectionConfig();
        c.id = id;
        c.name = name;
        c.filePath = filePath;
        c.user = user;
        c.password = password;
        c.rememberPassword = rememberPassword;
        c.encrypted = encrypted;
        c.filePassword = filePassword;
        c.mode = mode;
        c.driverJar = driverJar;
        c.lastOpened = lastOpened;
        c.urlOptions = urlOptions;
        c.copyOf = copyOf;
        c.type = type;
        c.host = host;
        c.port = port;
        c.database = database;
        c.properties = properties;
        c.charset = charset;
        return c;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }
    public String getUser() { return user; }
    public void setUser(String user) { this.user = user; }
    public String getPassword() { return password == null ? "" : password; }
    public void setPassword(String password) { this.password = password; }
    public boolean isRememberPassword() { return rememberPassword; }
    public void setRememberPassword(boolean rememberPassword) { this.rememberPassword = rememberPassword; }
    public boolean isEncrypted() { return encrypted; }
    public void setEncrypted(boolean encrypted) { this.encrypted = encrypted; }
    public String getFilePassword() { return filePassword == null ? "" : filePassword; }
    public void setFilePassword(String filePassword) { this.filePassword = filePassword; }
    public Mode getMode() { return mode == null ? Mode.NORMAL : mode; }
    public void setMode(Mode mode) { this.mode = mode; }
    public String getDriverJar() { return driverJar; }
    public void setDriverJar(String driverJar) { this.driverJar = driverJar; }
    public long getLastOpened() { return lastOpened; }
    public void setLastOpened(long lastOpened) { this.lastOpened = lastOpened; }
    public String getUrlOptions() { return urlOptions == null ? "" : urlOptions; }
    public void setUrlOptions(String urlOptions) { this.urlOptions = urlOptions; }
    public String getCopyOf() { return copyOf; }
    public void setCopyOf(String copyOf) { this.copyOf = copyOf; }

    public DbType getType() { return type == null ? DbType.H2 : type; }
    public void setType(DbType type) { this.type = type; }
    public String getHost() { return host == null ? "" : host; }
    public void setHost(String host) { this.host = host; }
    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }
    public String getDatabase() { return database == null ? "" : database; }
    public void setDatabase(String database) { this.database = database; }
    public String getProperties() { return properties == null ? "" : properties; }
    public void setProperties(String properties) { this.properties = properties; }
    public String getCharset() { return charset == null ? "" : charset; }
    public void setCharset(String charset) { this.charset = charset; }

    public boolean isH2() {
        return getType() == DbType.H2;
    }

    /** Arquivo do banco (H2 ou SQLite); null para servidores. */
    public Path file() {
        if (getType() == DbType.SQLITE) {
            return getDatabase().isBlank() ? null : Path.of(getDatabase());
        }
        return filePath == null ? null : Path.of(filePath);
    }

    /** O arquivo existe (servidores sempre "existem"). */
    public boolean fileExists() {
        if (!getType().isFile()) {
            return true;
        }
        Path f = file();
        return f != null && java.nio.file.Files.isRegularFile(f);
    }

    /** Identifica o mesmo banco (evita abrir duas vezes e duplicar nos recentes). */
    public String targetKey() {
        if (isH2()) {
            return "h2|" + (filePath == null ? "" : Path.of(filePath).toAbsolutePath().normalize());
        }
        if (getType() == DbType.SQLITE) {
            return "sqlite|" + (file() == null ? "" : file().toAbsolutePath().normalize());
        }
        return getType().name() + "|" + getHost().trim().toLowerCase(java.util.Locale.ROOT) + ":"
                + (port > 0 ? port : getType().defaultPort()) + "|" + getDatabase().trim() + "|" + getUser();
    }

    /** Texto curto de onde fica o banco: caminho do arquivo ou "host:porta/banco". */
    public String location() {
        if (isH2()) {
            return filePath;
        }
        if (getType() == DbType.SQLITE) {
            return getDatabase();
        }
        String db = getDatabase().isBlank() ? "" : "/" + getDatabase();
        return getHost() + ":" + (port > 0 ? port : getType().defaultPort()) + db;
    }

    @Override
    public String toString() {
        return name;
    }
}
