package br.com.marianojunior.h2studio.core;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Uma conexão aberta com um banco (H2 ou, a partir da versão 1.1, outro SGBD por JDBC).
 * <p>
 * Toda operação JDBC passa por {@link #submit}, que executa em uma thread
 * exclusiva da sessão: a conexão JDBC nunca é usada por duas threads ao mesmo
 * tempo e a interface gráfica nunca trava esperando o banco.
 */
public final class DbSession implements AutoCloseable {

    @FunctionalInterface
    public interface SqlWork<T> {
        T run(Connection c) throws Exception;
    }

    private final ConnectionConfig config;
    private final H2FileInspector.Info fileInfo;
    private final DriverRegistry.DriverJar driver;
    private final Connection connection;
    private final String productVersion;
    private final boolean readOnly;
    private final ExecutorService executor;
    /** URL e propriedades usadas na abertura (para abrir conexões extras, ex.: editor SQL). */
    private final String jdbcUrl;
    private final java.util.Properties connectProps;
    private volatile boolean closed;
    /** Já foi feito backup automático nesta sessão (é feito antes do primeiro salvamento). */
    private volatile boolean backedUp;
    private final DbType type;
    /** Driver JDBC dos outros bancos (null no H2). */
    private final java.sql.Driver jdbcDriver;
    private final String productName;

    DbSession(ConnectionConfig config, H2FileInspector.Info fileInfo, DriverRegistry.DriverJar driver,
              Connection connection, boolean readOnly, String jdbcUrl, java.util.Properties connectProps)
            throws SQLException {
        this(config, DbType.H2, fileInfo, driver, null, connection, readOnly, jdbcUrl, connectProps);
    }

    /** Sessão de um banco que não é H2. */
    DbSession(ConnectionConfig config, java.sql.Driver jdbcDriver, Connection connection, boolean readOnly,
              String jdbcUrl, java.util.Properties connectProps) throws SQLException {
        this(config, config.getType(), null, null, jdbcDriver, connection, readOnly, jdbcUrl, connectProps);
    }

    private DbSession(ConnectionConfig config, DbType type, H2FileInspector.Info fileInfo,
                      DriverRegistry.DriverJar driver, java.sql.Driver jdbcDriver, Connection connection,
                      boolean readOnly, String jdbcUrl, java.util.Properties connectProps) throws SQLException {
        this.type = type;
        this.jdbcDriver = jdbcDriver;
        this.productName = connection.getMetaData().getDatabaseProductName();
        this.jdbcUrl = jdbcUrl;
        this.connectProps = connectProps;
        this.config = config;
        this.fileInfo = fileInfo;
        this.driver = driver;
        this.connection = connection;
        this.readOnly = readOnly;
        this.productVersion = connection.getMetaData().getDatabaseProductVersion();
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "db-" + config.getName());
            t.setDaemon(true);
            return t;
        });
    }

    public <T> CompletableFuture<T> submit(SqlWork<T> work) {
        if (closed) {
            return CompletableFuture.failedFuture(new IllegalStateException("Conexão fechada"));
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                return work.run(connection);
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new java.util.concurrent.CompletionException(e);
            }
        }, executor);
    }

    /** Executa direto na thread atual (uso em testes e em tarefas já em background). */
    public <T> T runNow(SqlWork<T> work) throws Exception {
        return work.run(connection);
    }

    public ConnectionConfig config() {
        return config;
    }

    public DbType type() {
        return type;
    }

    public boolean isH2() {
        return type == DbType.H2;
    }

    java.sql.Driver jdbcDriver() {
        return jdbcDriver;
    }

    /** Nome do produto informado pelo banco, ex.: "PostgreSQL". */
    public String productName() {
        return productName;
    }

    /** "H2 2.3.232", "PostgreSQL 16.4", "MariaDB 10.11.6"... */
    public String engineLabel() {
        if (isH2()) {
            return "H2 " + driver.version();
        }
        String v = productVersion == null ? "" : productVersion.trim();
        // o MariaDB informa "5.5.5-10.11.6-MariaDB-0+deb12u1": fica só a versão real
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^5\\.5\\.5-(\\d+\\.\\d+\\.\\d+)").matcher(v);
        if (m.find()) {
            v = m.group(1);
        }
        int cut = v.indexOf(' ');
        if (type != DbType.SQLSERVER && cut > 0 && v.length() > 20) {
            v = v.substring(0, cut);
        }
        String name = productName == null || productName.isBlank() ? type.label() : productName;
        if (name.equals("Microsoft SQL Server")) {
            name = "SQL Server";
        }
        return name + " " + v;
    }

    public H2FileInspector.Info fileInfo() {
        return fileInfo;
    }

    public DriverRegistry.DriverJar driver() {
        return driver;
    }

    /** Versão informada pelo próprio banco, ex.: "2.3.232 (2024-08-11)". */
    public String productVersion() {
        return productVersion;
    }

    public boolean isReadOnly() {
        return readOnly;
    }

    public boolean isCopy() {
        return config.getCopyOf() != null;
    }

    String jdbcUrl() {
        return jdbcUrl;
    }

    java.util.Properties connectProps() {
        return connectProps;
    }

    /** Executa direto (sem fila). Usado só para Statement.cancel() vindo de outra thread. */
    public Connection rawConnection() {
        return connection;
    }

    public boolean isBackedUp() {
        return backedUp;
    }

    public void setBackedUp(boolean backedUp) {
        this.backedUp = backedUp;
    }

    public boolean isClosed() {
        return closed;
    }

    public String displayName() {
        return config.getName() + (isCopy() ? " [cópia]" : "");
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        executor.shutdown();
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            connection.close();
        } catch (SQLException ignored) {
            // nada a fazer
        }
    }
}
