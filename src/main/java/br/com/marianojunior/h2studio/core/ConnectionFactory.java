package br.com.marianojunior.h2studio.core;

import br.com.marianojunior.h2studio.core.DbOpenException.Reason;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Abre bancos H2 escolhendo o driver correto para o formato do arquivo.
 * <p>
 * Regras de segurança:
 * <ul>
 *   <li>Sempre usa IFEXISTS=TRUE: nunca cria um banco vazio por engano.</li>
 *   <li>Nunca usa um driver de outra família: um H2 mais novo pode recusar
 *       o arquivo (ou, em versões futuras, convertê-lo) e o sistema do cliente
 *       deixaria de abrir o banco.</li>
 * </ul>
 */
public final class ConnectionFactory {

    // Códigos de erro do H2 (org.h2.api.ErrorCode)
    static final int WRONG_USER_OR_PASSWORD = 28000;
    static final int DATABASE_ALREADY_OPEN = 90020;
    static final int FILE_CORRUPTED = 90030;
    static final int FILE_VERSION_ERROR = 90048;
    static final int FILE_ENCRYPTION_ERROR = 90049;
    static final int WRONG_PASSWORD_FORMAT = 90050;
    static final int DATABASE_NOT_FOUND = 90013;
    static final int DATABASE_NOT_FOUND_IFEXISTS = 90146;
    static final int SCHEMA_NOT_FOUND = 90079;

    /** Opções que o usuário não pode sobrescrever (são controladas pelo próprio programa). */
    private static final java.util.Set<String> RESERVED = java.util.Set.of("IFEXISTS", "CIPHER", "ACCESS_MODE_DATA",
            "AUTO_SERVER", "MV_STORE");

    private static final java.util.regex.Pattern MISSING_SCHEMA =
            java.util.regex.Pattern.compile("Schema \"([^\"]+)\" not found");

    private final DriverRegistry registry;

    public ConnectionFactory() {
        this(DriverRegistry.get());
    }

    public ConnectionFactory(DriverRegistry registry) {
        this.registry = registry;
    }

    /** Monta a URL JDBC para a configuração (sem usuário/senha). */
    public static String buildUrl(ConnectionConfig cfg, H2FileInspector.Info info, DriverRegistry.DriverJar jar) {
        StringBuilder url = new StringBuilder("jdbc:h2:file:").append(info.basePath());
        url.append(";IFEXISTS=TRUE");
        if (info.kind() == H2FileInspector.Kind.PAGESTORE && jar.major() == 1) {
            url.append(";MV_STORE=FALSE");
        }
        if (cfg.isEncrypted()) {
            url.append(";CIPHER=AES");
        }
        switch (cfg.getMode()) {
            case READ_ONLY -> url.append(";ACCESS_MODE_DATA=r");
            case AUTO_SERVER -> url.append(";AUTO_SERVER=TRUE");
            default -> { }
        }
        String extra = sanitizeOptions(cfg.getUrlOptions());
        if (!extra.isEmpty()) {
            url.append(';').append(extra);
        }
        return url.toString();
    }

    /**
     * Limpa as opções digitadas pelo usuário: aceita "a=1;b=2", com ou sem ';' no início,
     * e remove as que o programa controla (IFEXISTS, CIPHER, modo de acesso...).
     */
    public static String sanitizeOptions(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String part : raw.trim().split(";")) {
            String p = part.trim();
            if (p.isEmpty() || !p.contains("=")) {
                continue;
            }
            String key = p.substring(0, p.indexOf('=')).trim().toUpperCase(java.util.Locale.ROOT);
            if (!RESERVED.contains(key)) {
                out.add(p);
            }
        }
        return String.join(";", out);
    }

    private static boolean hasOption(String options, String key) {
        for (String part : sanitizeOptions(options).split(";")) {
            if (part.toUpperCase(java.util.Locale.ROOT).startsWith(key + "=")) {
                return true;
            }
        }
        return false;
    }

    /**
     * No H2 2.x, DATABASE_TO_LOWER / DATABASE_TO_UPPER não ficam gravados no arquivo.
     * Um banco criado com DATABASE_TO_LOWER=TRUE (comum com MODE=PostgreSQL) falha ao abrir
     * com "Schema \"public\" not found". Descobre qual opção falta pelo nome do schema.
     */
    static String missingCaseOption(SQLException e, String currentOptions) {
        if (e.getErrorCode() != SCHEMA_NOT_FOUND || e.getMessage() == null) {
            return null;
        }
        java.util.regex.Matcher m = MISSING_SCHEMA.matcher(e.getMessage());
        if (!m.find()) {
            return null;
        }
        String schema = m.group(1);
        boolean lower = schema.equals(schema.toLowerCase(java.util.Locale.ROOT))
                && !schema.equals(schema.toUpperCase(java.util.Locale.ROOT));
        if (lower && !hasOption(currentOptions, "DATABASE_TO_LOWER")) {
            return "DATABASE_TO_LOWER=TRUE";
        }
        if (!lower && !hasOption(currentOptions, "DATABASE_TO_UPPER")) {
            return "DATABASE_TO_UPPER=FALSE";
        }
        return null;
    }

    public DbSession open(ConnectionConfig cfg) throws DbOpenException {
        if (!cfg.isH2()) {
            return ServerConnector.open(cfg);
        }
        Path file = cfg.file();
        if (!Files.isRegularFile(file)) {
            throw new DbOpenException(Reason.NOT_FOUND, "Arquivo não encontrado:\n" + file, null);
        }
        H2FileInspector.Info info;
        try {
            info = H2FileInspector.inspect(file);
        } catch (IOException e) {
            // No Windows o arquivo pode estar bloqueado até para leitura
            throw new DbOpenException(Reason.LOCKED,
                    "Não foi possível ler o arquivo. Ele provavelmente está aberto por outro programa.", e);
        }
        if (info.kind() == H2FileInspector.Kind.UNKNOWN) {
            throw new DbOpenException(Reason.NOT_H2, "O arquivo não parece ser um banco de dados H2.", null);
        }
        if (info.kind() == H2FileInspector.Kind.ENCRYPTED) {
            cfg.setEncrypted(true);
        }

        List<DriverRegistry.DriverJar> candidates = candidates(cfg, info);
        if (candidates.isEmpty()) {
            String fam = info.family() != null ? info.family().label() : "este arquivo";
            throw new DbOpenException(Reason.NO_DRIVER,
                    "Nenhum driver disponível para " + fam + ".\n"
                    + "Coloque o jar do H2 correspondente em:\n" + DriverRegistry.userDir().resolve("drivers"), null);
        }

        // H2 2.x: DATABASE_TO_LOWER não fica gravado no arquivo; detecta antes de abrir
        if (info.kind() == H2FileInspector.Kind.MVSTORE && info.format() >= 2
                && !hasOption(cfg.getUrlOptions(), "DATABASE_TO_LOWER")
                && Boolean.TRUE.equals(H2FileInspector.usesLowerCaseSchema(file))) {
            String cur = sanitizeOptions(cfg.getUrlOptions());
            cfg.setUrlOptions(cur.isEmpty() ? "DATABASE_TO_LOWER=TRUE" : cur + ";DATABASE_TO_LOWER=TRUE");
        }

        DbOpenException last = null;
        for (DriverRegistry.DriverJar jar : candidates) {
            try {
                return openWith(cfg, info, jar);
            } catch (DbOpenException e) {
                last = e;
                // Só tenta o próximo driver quando o erro for de versão (bancos criptografados)
                if (e.reason() != Reason.WRONG_VERSION) {
                    throw e;
                }
            }
        }
        throw last;
    }

    private List<DriverRegistry.DriverJar> candidates(ConnectionConfig cfg, H2FileInspector.Info info) {
        List<DriverRegistry.DriverJar> list = new ArrayList<>();
        if (cfg.getDriverJar() != null) {
            registry.list().stream()
                    .filter(j -> j.path().toString().equals(cfg.getDriverJar()))
                    .findFirst()
                    .ifPresent(list::add);
            if (!list.isEmpty()) {
                return list;
            }
        }
        if (info.family() != null) {
            registry.best(info.family()).ifPresent(list::add);
        } else {
            // criptografado: não dá para ler o formato, tenta do mais novo ao mais antigo
            for (DriverFamily f : new DriverFamily[]{DriverFamily.V2_2_PLUS, DriverFamily.V2_1, DriverFamily.V1_4}) {
                registry.best(f).ifPresent(list::add);
            }
        }
        return list;
    }

    private DbSession openWith(ConnectionConfig cfg, H2FileInspector.Info info, DriverRegistry.DriverJar jar)
            throws DbOpenException {
        Driver driver;
        try {
            driver = registry.load(jar);
        } catch (Exception e) {
            throw new DbOpenException(Reason.NO_DRIVER, "Falha ao carregar o driver " + jar.path(), e);
        }
        Properties props = connectProps(cfg);
        String originalOptions = cfg.getUrlOptions();
        boolean retriedForSchema = false;
        for (int attempt = 0; ; attempt++) {
            String url = buildUrl(cfg, info, jar);
            try {
                Connection c = driver.connect(url, props);
                if (c == null) {
                    throw new DbOpenException(Reason.OTHER, "O driver não aceitou a URL " + url, null);
                }
                boolean ro = cfg.getMode() == ConnectionConfig.Mode.READ_ONLY;
                if (ro) {
                    c.setReadOnly(true);
                }
                // a opção descoberta fica salva na conexão para as próximas vezes
                return new DbSession(cfg, info, jar, c, ro, url, props);
            } catch (SQLException e) {
                String missing = attempt < 2 ? missingCaseOption(e, cfg.getUrlOptions()) : null;
                if (missing != null) {
                    String cur = sanitizeOptions(cfg.getUrlOptions());
                    cfg.setUrlOptions(cur.isEmpty() ? missing : cur + ";" + missing);
                    retriedForSchema = true;
                    continue;
                }
                if (retriedForSchema && e.getErrorCode() == DATABASE_ALREADY_OPEN) {
                    // O H2 2.1 não libera o arquivo depois de uma abertura que falhou:
                    // a opção certa já foi descoberta, mas só funciona reiniciando o programa.
                    throw new DbOpenException(Reason.URL_OPTIONS,
                            "Este banco precisa da opção " + cfg.getUrlOptions() + " na URL (já foi preenchida).\n"
                            + "O H2 " + jar.version() + " deixou o arquivo preso após a primeira tentativa: "
                            + "feche e abra o H2 Studio novamente e abra o banco pela lista de recentes.", e);
                }
                cfg.setUrlOptions(originalOptions);
                throw translate(e, jar);
            }
        }
    }

    /** Usuário e senha no formato do H2 ("senhaArquivo senhaUsuario" quando criptografado). */
    static Properties connectProps(ConnectionConfig cfg) {
        Properties props = new Properties();
        props.setProperty("user", cfg.getUser() == null ? "" : cfg.getUser());
        String pwd = cfg.getPassword();
        if (cfg.isEncrypted()) {
            pwd = cfg.getFilePassword() + " " + pwd;
        }
        props.setProperty("password", pwd);
        return props;
    }

    /**
     * Cria um banco NOVO (sem IFEXISTS) com o driver informado, com as mesmas opções,
     * usuário, senha e criptografia da configuração. Usado pela conversão de versão.
     */
    public Connection createDatabase(ConnectionConfig cfg, DriverRegistry.DriverJar jar, String basePath) throws Exception {
        Driver d = registry.load(jar);
        StringBuilder url = new StringBuilder("jdbc:h2:file:").append(basePath);
        if (cfg.isEncrypted()) {
            url.append(";CIPHER=AES");
        }
        String extra = sanitizeOptions(cfg.getUrlOptions());
        if (!extra.isEmpty()) {
            url.append(';').append(extra);
        }
        Connection c = d.connect(url.toString(), connectProps(cfg));
        if (c == null) {
            throw new SQLException("O driver não aceitou a URL " + url);
        }
        return c;
    }

    static DbOpenException translate(SQLException e, DriverRegistry.DriverJar jar) {
        return switch (e.getErrorCode()) {
            case DATABASE_ALREADY_OPEN -> new DbOpenException(Reason.LOCKED,
                    "O banco está aberto por outro programa (provavelmente o sistema que usa este arquivo).", e);
            case WRONG_USER_OR_PASSWORD -> new DbOpenException(Reason.AUTH, "Usuário ou senha incorretos.", e);
            case FILE_ENCRYPTION_ERROR, WRONG_PASSWORD_FORMAT -> new DbOpenException(Reason.FILE_PASSWORD,
                    "Senha de criptografia do arquivo incorreta.", e);
            case FILE_VERSION_ERROR, FILE_CORRUPTED -> new DbOpenException(Reason.WRONG_VERSION,
                    "O driver H2 " + jar.version() + " não reconhece a versão deste arquivo.", e);
            case DATABASE_NOT_FOUND, DATABASE_NOT_FOUND_IFEXISTS -> new DbOpenException(Reason.NOT_FOUND,
                    "Banco não encontrado.", e);
            case SCHEMA_NOT_FOUND -> new DbOpenException(Reason.URL_OPTIONS,
                    "O banco foi criado com opções de URL que precisam ser repetidas ao abrir "
                    + "(ex.: MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE).\n"
                    + "Informe em \"Opções da URL\" as mesmas opções usadas pelo sistema.", e);
            default -> new DbOpenException(Reason.OTHER, e.getMessage(), e);
        };
    }

    /**
     * Abre outra conexão ao mesmo banco (mesmo driver, mesma URL). O H2 embutido permite
     * várias conexões no mesmo processo; cada aba de SQL usa a sua para ter transação própria.
     */
    public DbSession openSibling(DbSession main) throws DbOpenException {
        if (!main.isH2()) {
            return ServerConnector.openSibling(main);
        }
        try {
            Driver d = registry.load(main.driver());
            Connection c = d.connect(main.jdbcUrl(), main.connectProps());
            if (main.isReadOnly()) {
                c.setReadOnly(true);
            }
            return new DbSession(main.config(), main.fileInfo(), main.driver(), c, main.isReadOnly(),
                    main.jdbcUrl(), main.connectProps());
        } catch (DbOpenException e) {
            throw e;
        } catch (SQLException e) {
            throw translate(e, main.driver());
        } catch (Exception e) {
            throw new DbOpenException(Reason.OTHER, e.getMessage(), e);
        }
    }

    /**
     * Copia o arquivo para uma pasta temporária e abre a cópia somente leitura.
     * Usado quando o original está travado pelo sistema do cliente.
     */
    public DbSession openCopy(ConnectionConfig cfg) throws DbOpenException {
        Path src = cfg.file();
        Path dir = DriverRegistry.userDir().resolve("copias")
                .resolve(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")));
        Path dst = dir.resolve(src.getFileName());
        try {
            Files.createDirectories(dir);
            Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new DbOpenException(Reason.LOCKED,
                    "Não foi possível copiar o arquivo: o Windows bloqueia a leitura enquanto o sistema"
                    + " está usando o banco. Feche o sistema e tente novamente.", e);
        }
        ConnectionConfig copy = cfg.copy();
        copy.setFilePath(dst.toString());
        copy.setMode(ConnectionConfig.Mode.READ_ONLY);
        copy.setCopyOf(src.toString());
        return open(copy);
    }

    /** Apaga cópias temporárias com mais de 2 dias. */
    public static void cleanOldCopies() {
        Path root = DriverRegistry.userDir().resolve("copias");
        if (!Files.isDirectory(root)) {
            return;
        }
        long limit = System.currentTimeMillis() - 2L * 24 * 3600 * 1000;
        try (var dirs = Files.list(root)) {
            dirs.filter(Files::isDirectory).forEach(d -> {
                try {
                    if (Files.getLastModifiedTime(d).toMillis() < limit) {
                        try (var files = Files.walk(d)) {
                            files.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                                try {
                                    Files.deleteIfExists(p);
                                } catch (IOException ignored) {
                                    // em uso: fica para a próxima
                                }
                            });
                        }
                    }
                } catch (IOException ignored) {
                    // ignora
                }
            });
        } catch (IOException ignored) {
            // ignora
        }
    }

    /** Usado pelo diálogo de exceção para mostrar detalhes técnicos. */
    public static String sqlDetails(Throwable t) {
        StringBuilder sb = new StringBuilder();
        while (t != null) {
            if (t instanceof SQLException s) {
                sb.append("[").append(s.getErrorCode()).append("/").append(s.getSQLState()).append("] ");
            }
            sb.append(t.getClass().getSimpleName()).append(": ").append(t.getMessage()).append('\n');
            t = t.getCause();
        }
        return sb.toString().trim();
    }
}
