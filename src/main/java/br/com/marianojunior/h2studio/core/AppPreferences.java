package br.com.marianojunior.h2studio.core;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Preferências gerais em ~/.h2studio/preferencias.properties. */
public final class AppPreferences {

    private static final Path FILE = DriverRegistry.userDir().resolve("preferencias.properties");
    private static final Properties PROPS = new Properties();

    static {
        if (Files.isRegularFile(FILE)) {
            try (Reader r = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) {
                PROPS.load(r);
            } catch (IOException ignored) {
                // usa os padrões
            }
        }
    }

    private AppPreferences() {
    }

    /** Fazer backup antes de salvar a primeira alteração de cada conexão (padrão: sim). */
    public static boolean autoBackup() {
        return Boolean.parseBoolean(PROPS.getProperty("backup.automatico", "true"));
    }

    public static void setAutoBackup(boolean v) {
        PROPS.setProperty("backup.automatico", String.valueOf(v));
        save();
    }

    /** Quantos backups manter por banco (padrão: 10). */
    public static int backupsToKeep() {
        try {
            return Math.max(1, Integer.parseInt(PROPS.getProperty("backup.manter", "10")));
        } catch (NumberFormatException e) {
            return 10;
        }
    }

    public static String get(String key, String def) {
        return PROPS.getProperty(key, def);
    }

    public static void set(String key, String value) {
        PROPS.setProperty(key, value);
        save();
    }

    private static synchronized void save() {
        try {
            Files.createDirectories(FILE.getParent());
            try (Writer w = Files.newBufferedWriter(FILE, StandardCharsets.UTF_8)) {
                PROPS.store(w, "H2 Studio - preferencias");
            }
        } catch (IOException e) {
            System.err.println("Não foi possível salvar as preferências: " + e.getMessage());
        }
    }
}
