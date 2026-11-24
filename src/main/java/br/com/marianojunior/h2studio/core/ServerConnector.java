package br.com.marianojunior.h2studio.core;

import br.com.marianojunior.h2studio.core.DbOpenException.Reason;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/** Abre conexões com os bancos que não são H2 (PostgreSQL, MySQL/MariaDB, Firebird, SQL Server, SQLite). */
public final class ServerConnector {

    private ServerConnector() {
    }

    public static DbSession open(ConnectionConfig cfg) throws DbOpenException {
        DbType type = cfg.getType();
        if (type == DbType.H2) {
            throw new IllegalArgumentException("H2 é aberto pelo ConnectionFactory");
        }
        if (type == DbType.SQLITE) {
            Path f = cfg.file();
            // o SQLite cria um arquivo vazio quando ele não existe: confere antes
            if (f == null || !Files.isRegularFile(f)) {
                throw new DbOpenException(Reason.NOT_FOUND, "Arquivo não encontrado:\n" + cfg.getDatabase(), null);
            }
        }
        Driver driver = driver(cfg);
        String url = type.buildUrl(cfg.getHost(), cfg.getPort(), cfg.getDatabase());
        Properties props = connectProps(cfg);
        try {
            // jar do MySQL Connector/J escolhido pelo usuário: usa o prefixo dele
            if (url.startsWith("jdbc:mariadb:") && !driver.acceptsURL(url)) {
                url = "jdbc:mysql:" + url.substring("jdbc:mariadb:".length());
            }
            Connection c = driver.connect(url, props);
            if (c == null) {
                throw new DbOpenException(Reason.NO_DRIVER,
                        "O driver escolhido não aceita conexões " + type.label() + " (" + url + ").", null);
            }
            boolean ro = cfg.getMode() == ConnectionConfig.Mode.READ_ONLY;
            if (ro) {
                try {
                    c.setReadOnly(true);
                } catch (SQLException ignored) {
                    // alguns drivers não suportam: a interface continua bloqueando a gravação
                }
            }
            return new DbSession(cfg, driver, c, ro, url, props);
        } catch (SQLException e) {
            throw translate(e, cfg);
        }
    }

    /** Outra conexão ao mesmo banco (para abas com transação própria). */
    public static DbSession openSibling(DbSession main) throws DbOpenException {
        try {
            Connection c = main.jdbcDriver().connect(main.jdbcUrl(), main.connectProps());
            if (main.isReadOnly()) {
                c.setReadOnly(true);
            }
            return new DbSession(main.config(), main.jdbcDriver(), c, main.isReadOnly(), main.jdbcUrl(),
                    main.connectProps());
        } catch (SQLException e) {
            throw translate(e, main.config());
        }
    }

    /** Driver da conexão: o .jar escolhido pelo usuário ou o padrão do tipo (precisa estar baixado). */
    static Driver driver(ConnectionConfig cfg) throws DbOpenException {
        try {
            String custom = cfg.getDriverJar();
            if (custom != null && !custom.isBlank()) {
                List<Path> jars = new ArrayList<>();
                for (String part : custom.split(";")) {
                    if (!part.isBlank()) {
                        Path p = Path.of(part.trim());
                        if (!Files.isRegularFile(p)) {
                            throw new DbOpenException(Reason.NO_DRIVER, "O driver escolhido não existe mais:\n" + p, null);
                        }
                        jars.add(p);
                    }
                }
                return JdbcDrivers.load(jars, cfg.getType().driver().driverClass());
            }
            JdbcDrivers.Spec spec = cfg.getType().driver();
            if (!JdbcDrivers.isInstalled(spec)) {
                throw new DbOpenException(Reason.NO_DRIVER, "O driver " + spec.label() + " ainda não foi baixado.", null);
            }
            return JdbcDrivers.load(spec);
        } catch (DbOpenException e) {
            throw e;
        } catch (Exception e) {
            throw new DbOpenException(Reason.NO_DRIVER, "Não foi possível carregar o driver: " + e.getMessage(), e);
        }
    }

    /** Usuário, senha, propriedades extras e padrões sensatos de cada driver. */
    static Properties connectProps(ConnectionConfig cfg) {
        Properties p = new Properties();
        DbType type = cfg.getType();
        if (type != DbType.SQLITE) {
            p.setProperty("user", cfg.getUser() == null ? "" : cfg.getUser());
            p.setProperty("password", cfg.getPassword());
        }
        switch (type) {
            case POSTGRESQL -> {
                p.setProperty("connectTimeout", "10");
                p.setProperty("ApplicationName", "H2 Studio");
                // texto digitado na grade vai sem tipo: o servidor converte para uuid, json, enum...
                p.setProperty("stringtype", "unspecified");
            }
            case MYSQL, MARIADB -> p.setProperty("connectTimeout", "10000");
            case FIREBIRD, FIREBIRD_25 -> {
                p.setProperty("connectTimeout", "10");
                p.setProperty("encoding", cfg.getCharset().isBlank() ? "WIN1252" : cfg.getCharset());
            }
            case SQLSERVER -> {
                p.setProperty("loginTimeout", "10");
                p.setProperty("applicationName", "H2 Studio");
            }
            default -> { }
        }
        if (cfg.getMode() == ConnectionConfig.Mode.READ_ONLY) {
            switch (type) {
                // sem isso o pgjdbc só protege quando o autocommit está desligado
                case POSTGRESQL -> p.setProperty("readOnlyMode", "always");
                // o SQLite precisa abrir o arquivo já em modo leitura (SQLITE_OPEN_READONLY)
                case SQLITE -> p.setProperty("open_mode", "1");
                case SQLSERVER -> p.setProperty("applicationIntent", "ReadOnly");
                default -> { }
            }
        }
        // as propriedades digitadas pelo usuário têm a palavra final
        for (String part : cfg.getProperties().split(";")) {
            int eq = part.indexOf('=');
            if (eq > 0) {
                String k = part.substring(0, eq).trim();
                if (!k.equalsIgnoreCase("user") && !k.equalsIgnoreCase("password")) {
                    p.setProperty(k, part.substring(eq + 1).trim());
                }
            }
        }
        return p;
    }

    /** Converte o erro do driver em uma mensagem que diz o que conferir. */
    static DbOpenException translate(SQLException e, ConnectionConfig cfg) {
        String state = e.getSQLState() == null ? "" : e.getSQLState();
        int code = e.getErrorCode();
        String msg = e.getMessage() == null ? "" : e.getMessage();
        String low = msg.toLowerCase(Locale.ROOT);
        String where = cfg.getHost() + ":" + (cfg.getPort() > 0 ? cfg.getPort() : cfg.getType().defaultPort());

        boolean auth = state.startsWith("28") || code == 1045 || code == 18456 || code == 335544472
                || low.contains("password authentication failed") || low.contains("access denied")
                || low.contains("login failed");
        if (auth) {
            return new DbOpenException(Reason.AUTH, "Usuário ou senha incorretos.", e);
        }
        boolean notFound = state.equals("3D000") || code == 1049 || code == 4060
                || (cfg.getType().isFirebird() && (code == 335544344 || code == 335544734))
                || (low.contains("database") && low.contains("does not exist"))
                || low.contains("unknown database");
        if (notFound) {
            return new DbOpenException(Reason.NOT_FOUND, "O banco \"" + cfg.getDatabase() + "\" não foi encontrado em "
                    + where + ".\n\n" + firstLine(msg), e);
        }
        boolean unreachable = state.startsWith("08") || hasCause(e, java.net.ConnectException.class)
                || hasCause(e, java.net.UnknownHostException.class) || hasCause(e, java.net.SocketTimeoutException.class)
                || low.contains("connection refused") || low.contains("timed out") || code == 335544721;
        if (unreachable) {
            return new DbOpenException(Reason.UNREACHABLE, "Não foi possível conectar ao servidor " + where + ".\n\n"
                    + "Confira se o servidor está ligado, se o endereço e a porta estão certos e se o firewall "
                    + "libera a porta " + (cfg.getPort() > 0 ? cfg.getPort() : cfg.getType().defaultPort()) + ".\n\n"
                    + firstLine(msg), e);
        }
        if (cfg.getType() == DbType.FIREBIRD && (low.contains("unsupported on-disk structure")
                || low.contains("wire protocol") || low.contains("incompatible wire encryption"))) {
            return new DbOpenException(Reason.WRONG_VERSION, "O servidor parece ser Firebird 2.5 ou exige outra "
                    + "configuração. Tente o tipo \"Firebird 2.5\".\n\n" + firstLine(msg), e);
        }
        return new DbOpenException(Reason.OTHER, msg, e);
    }

    private static String firstLine(String s) {
        int i = s.indexOf('\n');
        return i < 0 ? s : s.substring(0, i);
    }

    private static boolean hasCause(Throwable t, Class<? extends Throwable> cls) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (cls.isInstance(c)) {
                return true;
            }
        }
        return false;
    }
}
