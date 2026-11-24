package br.com.marianojunior.h2studio.core;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.UUID;

/**
 * Guarda as conexões usadas recentemente em ~/.h2studio/conexoes.properties.
 * <p>
 * A senha só é gravada quando o usuário marca "Lembrar senha", e fica em
 * texto simples neste computador.
 */
public final class ConnectionStore {

    private static final int MAX_RECENTES = 60;

    private final Path file;
    private final List<ConnectionConfig> items = new ArrayList<>();

    public ConnectionStore() {
        this(DriverRegistry.userDir().resolve("conexoes.properties"));
    }

    public ConnectionStore(Path file) {
        this.file = file;
        load();
    }

    public synchronized List<ConnectionConfig> recent() {
        List<ConnectionConfig> list = new ArrayList<>();
        for (ConnectionConfig c : items) {
            list.add(c.copy());
        }
        list.sort(Comparator.comparingLong(ConnectionConfig::getLastOpened).reversed());
        return list;
    }

    /** Procura uma conexão salva para o mesmo arquivo. */
    public synchronized ConnectionConfig findByFile(Path path) {
        String p = path.toAbsolutePath().normalize().toString();
        for (ConnectionConfig c : items) {
            if (c.isH2() && samePath(c.getFilePath(), p)) {
                return c.copy();
            }
        }
        return null;
    }

    /** Conexão salva para o mesmo destino (mesmo arquivo, ou mesmo servidor/banco/usuário). */
    public synchronized ConnectionConfig findSame(ConnectionConfig cfg) {
        if (cfg.isH2()) {
            return cfg.file() == null ? null : findByFile(cfg.file());
        }
        String key = cfg.targetKey();
        for (ConnectionConfig c : items) {
            if (c.targetKey().equals(key)) {
                return c.copy();
            }
        }
        return null;
    }

    public synchronized void save(ConnectionConfig cfg) {
        if (cfg.getCopyOf() != null) {
            return; // cópias temporárias não são lembradas
        }
        ConnectionConfig c = cfg.copy();
        if (c.getId() == null) {
            ConnectionConfig existing = findSame(c);
            c.setId(existing != null ? existing.getId() : UUID.randomUUID().toString());
            cfg.setId(c.getId());
        }
        if (!c.isRememberPassword()) {
            c.setPassword("");
            c.setFilePassword("");
        }
        items.removeIf(x -> Objects.equals(x.getId(), c.getId()));
        items.add(c);
        items.sort(Comparator.comparingLong(ConnectionConfig::getLastOpened).reversed());
        while (items.size() > MAX_RECENTES) {
            items.remove(items.size() - 1);
        }
        write();
    }

    public synchronized void remove(ConnectionConfig cfg) {
        items.removeIf(x -> Objects.equals(x.getId(), cfg.getId()));
        write();
    }

    private static boolean samePath(String a, String b) {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        return windows ? a.equalsIgnoreCase(b) : a.equals(b);
    }

    private void load() {
        if (!Files.isRegularFile(file)) {
            return;
        }
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(r);
        } catch (IOException e) {
            return;
        }
        int count = Integer.parseInt(p.getProperty("count", "0"));
        for (int i = 0; i < count; i++) {
            String k = "c" + i + ".";
            String path = p.getProperty(k + "filePath", "");
            DbType type;
            try {
                type = DbType.valueOf(p.getProperty(k + "type", "H2"));
            } catch (IllegalArgumentException e) {
                continue; // tipo de uma versão mais nova do programa
            }
            if (type == DbType.H2 && path.isBlank()) {
                continue;
            }
            ConnectionConfig c = new ConnectionConfig();
            c.setType(type);
            c.setHost(p.getProperty(k + "host", ""));
            c.setPort(Integer.parseInt(p.getProperty(k + "port", "0")));
            c.setDatabase(p.getProperty(k + "database", ""));
            c.setProperties(p.getProperty(k + "properties", ""));
            c.setCharset(p.getProperty(k + "charset", ""));
            c.setId(p.getProperty(k + "id", UUID.randomUUID().toString()));
            c.setName(p.getProperty(k + "name", path));
            c.setFilePath(path.isBlank() ? null : path);
            c.setUser(p.getProperty(k + "user", "sa"));
            c.setPassword(p.getProperty(k + "password", ""));
            c.setRememberPassword(Boolean.parseBoolean(p.getProperty(k + "rememberPassword")));
            c.setEncrypted(Boolean.parseBoolean(p.getProperty(k + "encrypted")));
            c.setFilePassword(p.getProperty(k + "filePassword", ""));
            try {
                c.setMode(ConnectionConfig.Mode.valueOf(p.getProperty(k + "mode", "NORMAL")));
            } catch (IllegalArgumentException e) {
                c.setMode(ConnectionConfig.Mode.NORMAL);
            }
            String drv = p.getProperty(k + "driverJar", "");
            c.setDriverJar(drv.isBlank() ? null : drv);
            c.setLastOpened(Long.parseLong(p.getProperty(k + "lastOpened", "0")));
            c.setUrlOptions(p.getProperty(k + "urlOptions", ""));
            items.add(c);
        }
    }

    private void write() {
        Properties p = new Properties();
        p.setProperty("count", String.valueOf(items.size()));
        for (int i = 0; i < items.size(); i++) {
            ConnectionConfig c = items.get(i);
            String k = "c" + i + ".";
            p.setProperty(k + "id", c.getId());
            p.setProperty(k + "name", c.getName() == null ? "" : c.getName());
            p.setProperty(k + "filePath", c.getFilePath() == null ? "" : c.getFilePath());
            p.setProperty(k + "type", c.getType().name());
            p.setProperty(k + "host", c.getHost());
            p.setProperty(k + "port", String.valueOf(c.getPort()));
            p.setProperty(k + "database", c.getDatabase());
            p.setProperty(k + "properties", c.getProperties());
            p.setProperty(k + "charset", c.getCharset());
            p.setProperty(k + "user", c.getUser() == null ? "" : c.getUser());
            p.setProperty(k + "password", c.getPassword());
            p.setProperty(k + "rememberPassword", String.valueOf(c.isRememberPassword()));
            p.setProperty(k + "encrypted", String.valueOf(c.isEncrypted()));
            p.setProperty(k + "filePassword", c.getFilePassword());
            p.setProperty(k + "mode", c.getMode().name());
            p.setProperty(k + "driverJar", c.getDriverJar() == null ? "" : c.getDriverJar());
            p.setProperty(k + "lastOpened", String.valueOf(c.getLastOpened()));
            p.setProperty(k + "urlOptions", c.getUrlOptions());
        }
        try {
            Files.createDirectories(file.getParent());
            try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                p.store(w, "H2 Studio - conexoes recentes");
            }
        } catch (IOException e) {
            System.err.println("Não foi possível salvar as conexões: " + e.getMessage());
        }
    }
}
