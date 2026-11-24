package br.com.marianojunior.h2studio.core;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Tipos de banco suportados.
 * <p>
 * H2 continua com o fluxo próprio (arquivo, detecção de versão, drivers embutidos).
 * Os demais são abertos por JDBC com o driver baixado sob demanda do Maven Central
 * (ou um .jar escolhido pelo usuário) e carregado em ClassLoader isolado.
 */
public enum DbType {

    H2("H2 (arquivo)", 0, "sa", false, true, null),

    POSTGRESQL("PostgreSQL", 5432, "postgres", true, false, new JdbcDrivers.Spec("PostgreSQL JDBC",
            "org.postgresql.Driver",
            List.of(new JdbcDrivers.Artifact("org.postgresql", "postgresql", "42.7.4", null, 1_086_687)))),

    MYSQL("MySQL", 3306, "root", true, false, JdbcDrivers.MARIADB_SPEC),

    MARIADB("MariaDB", 3306, "root", true, false, JdbcDrivers.MARIADB_SPEC),

    FIREBIRD("Firebird 3, 4 ou 5", 3050, "SYSDBA", true, false, new JdbcDrivers.Spec("Jaybird 5",
            "org.firebirdsql.jdbc.FBDriver",
            List.of(new JdbcDrivers.Artifact("org.firebirdsql.jdbc", "jaybird", "5.0.6.java11", null, 1_514_847)))),

    FIREBIRD_25("Firebird 2.5", 3050, "SYSDBA", true, false, new JdbcDrivers.Spec("Jaybird 4",
            "org.firebirdsql.jdbc.FBDriver",
            List.of(new JdbcDrivers.Artifact("org.firebirdsql.jdbc", "jaybird", "4.0.10.java11", null, 1_329_532),
                    new JdbcDrivers.Artifact("javax.resource", "connector-api", "1.5", null, 36_479)))),

    SQLSERVER("SQL Server", 1433, "sa", true, false, new JdbcDrivers.Spec("Microsoft JDBC (mssql-jdbc)",
            "com.microsoft.sqlserver.jdbc.SQLServerDriver",
            List.of(new JdbcDrivers.Artifact("com.microsoft.sqlserver", "mssql-jdbc", "12.8.1.jre11", null, 1_476_685)))),

    SQLITE("SQLite (arquivo)", 0, "", false, true, new JdbcDrivers.Spec("SQLite JDBC (xerial)",
            "org.sqlite.JDBC",
            List.of(new JdbcDrivers.Artifact("org.xerial", "sqlite-jdbc", "3.46.1.3", null, 14_127_503))));

    private final String label;
    private final int defaultPort;
    private final String defaultUser;
    private final boolean server;
    private final boolean file;
    private final JdbcDrivers.Spec driver;

    DbType(String label, int defaultPort, String defaultUser, boolean server, boolean file, JdbcDrivers.Spec driver) {
        this.label = label;
        this.defaultPort = defaultPort;
        this.defaultUser = defaultUser;
        this.server = server;
        this.file = file;
        this.driver = driver;
    }

    public String label() {
        return label;
    }

    public int defaultPort() {
        return defaultPort;
    }

    public String defaultUser() {
        return defaultUser;
    }

    /** Conecta em host/porta (MySQL, PostgreSQL, Firebird, SQL Server). */
    public boolean isServer() {
        return server;
    }

    /** Banco em um arquivo local (H2, SQLite). */
    public boolean isFile() {
        return file;
    }

    /** Nome curto para a árvore e listas. */
    public String shortLabel() {
        return switch (this) {
            case FIREBIRD, FIREBIRD_25 -> "Firebird";
            case SQLITE -> "SQLite";
            case H2 -> "H2";
            default -> label;
        };
    }

    public boolean isFirebird() {
        return this == FIREBIRD || this == FIREBIRD_25;
    }

    /** Driver JDBC usado (null para H2, que tem gerenciamento próprio). */
    public JdbcDrivers.Spec driver() {
        return driver;
    }

    /** Tipos que aparecem no diálogo "Conectar a banco" (o H2 tem diálogo próprio). */
    public static List<DbType> connectable() {
        return List.of(POSTGRESQL, MYSQL, MARIADB, FIREBIRD, FIREBIRD_25, SQLSERVER, SQLITE);
    }

    /** Nome do campo "banco" conforme o tipo. */
    public String databaseLabel() {
        return switch (this) {
            case SQLITE -> "Arquivo";
            default -> "Banco";
        };
    }

    /** Propriedades JDBC sugeridas ao criar uma conexão nova. */
    public String defaultProperties() {
        return switch (this) {
            // MySQL 8 (caching_sha2_password) sem SSL precisa buscar a chave pública do servidor
            case MYSQL -> "allowPublicKeyRetrieval=true";
            // servidores de rede local quase sempre usam certificado próprio
            case SQLSERVER -> "encrypt=false;trustServerCertificate=true";
            default -> "";
        };
    }

    /** Monta a URL JDBC (sem usuário e senha, que vão nas propriedades). */
    public String buildUrl(String host, int port, String database) {
        String h = host == null || host.isBlank() ? "localhost" : host.trim();
        int p = port > 0 ? port : defaultPort;
        String db = database == null ? "" : database.trim();
        return switch (this) {
            case POSTGRESQL -> "jdbc:postgresql://" + h + ":" + p + "/" + (db.isEmpty() ? "postgres" : db);
            case MYSQL, MARIADB -> "jdbc:mariadb://" + h + ":" + p + "/" + db;
            case FIREBIRD, FIREBIRD_25 -> "jdbc:firebirdsql://" + h + ":" + p + "/" + db;
            case SQLSERVER -> "jdbc:sqlserver://" + h + ":" + p + (db.isEmpty() ? "" : ";databaseName=" + db);
            case SQLITE -> "jdbc:sqlite:" + db;
            case H2 -> throw new IllegalStateException("H2 usa ConnectionFactory.buildUrl");
        };
    }

    /** Coloca um identificador entre aspas no padrão do banco. */
    public String quote(String identifier) {
        if (identifier == null) {
            return "";
        }
        return switch (this) {
            case MYSQL, MARIADB -> "`" + identifier.replace("`", "``") + "`";
            case SQLSERVER -> "[" + identifier.replace("]", "]]") + "]";
            default -> "\"" + identifier.replace("\"", "\"\"") + "\"";
        };
    }

    /** schema.tabela já com aspas; omite o schema quando o banco não tem (Firebird, SQLite). */
    public String qualified(String schema, String table) {
        return schema == null || schema.isEmpty() ? quote(table) : quote(schema) + "." + quote(table);
    }

    /** SELECT de amostra (100 linhas) na sintaxe do banco. */
    public String sampleSelect(String schema, String table) {
        String q = qualified(schema, table);
        return switch (this) {
            case FIREBIRD, FIREBIRD_25 -> "SELECT FIRST 100 * FROM " + q + ";";
            case SQLSERVER -> "SELECT TOP 100 * FROM " + q + ";";
            default -> "SELECT * FROM " + q + " LIMIT 100;";
        };
    }

    /** Schemas (ou bancos, no MySQL) criados pelo próprio SGBD. */
    private static final Set<String> SYSTEM_SCHEMAS = Set.of(
            "information_schema", "pg_catalog", "mysql", "performance_schema", "sys",
            "db_owner", "db_accessadmin", "db_securityadmin", "db_ddladmin", "db_backupoperator",
            "db_datareader", "db_datawriter", "db_denydatareader", "db_denydatawriter", "guest");

    public static boolean isSystemSchema(String name) {
        if (name == null) {
            return false;
        }
        String n = name.toLowerCase(Locale.ROOT);
        return SYSTEM_SCHEMAS.contains(n) || n.startsWith("pg_toast") || n.startsWith("pg_temp");
    }

    /** Descobre o tipo pelo nome do produto informado pelo driver. */
    public static DbType fromProductName(String product) {
        String p = product == null ? "" : product.toLowerCase(Locale.ROOT);
        if (p.contains("postgres")) {
            return POSTGRESQL;
        }
        if (p.contains("mariadb")) {
            return MARIADB;
        }
        if (p.contains("mysql")) {
            return MYSQL;
        }
        if (p.contains("firebird")) {
            return FIREBIRD;
        }
        if (p.contains("microsoft") || p.contains("sql server")) {
            return SQLSERVER;
        }
        if (p.contains("sqlite")) {
            return SQLITE;
        }
        return H2;
    }

    @Override
    public String toString() {
        return label;
    }
}
