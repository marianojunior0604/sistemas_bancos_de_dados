package br.com.marianojunior.h2studio.core;

/**
 * Famílias de driver H2 agrupadas pelo formato de arquivo que conseguem abrir.
 * <p>
 * Um driver de uma família NÃO abre arquivos de outra família (erro 90048),
 * por isso o formato gravado no cabeçalho do arquivo decide qual driver usar.
 */
public enum DriverFamily {

    /** H2 1.4.x — MVStore formato 1 e PageStore (.h2.db). */
    V1_4("H2 1.4.x", 1),
    /** H2 2.0.x / 2.1.x — MVStore formato 2. */
    V2_1("H2 2.0 / 2.1", 2),
    /** H2 2.2.x / 2.3.x e posteriores — MVStore formato 3. */
    V2_2_PLUS("H2 2.2 ou superior", 3);

    private final String label;
    private final int mvStoreFormat;

    DriverFamily(String label, int mvStoreFormat) {
        this.label = label;
        this.mvStoreFormat = mvStoreFormat;
    }

    public String label() {
        return label;
    }

    public int mvStoreFormat() {
        return mvStoreFormat;
    }

    /** Família correspondente a um formato MVStore lido do cabeçalho, ou null. */
    public static DriverFamily fromMvStoreFormat(int format) {
        for (DriverFamily f : values()) {
            if (f.mvStoreFormat == format) {
                return f;
            }
        }
        return null;
    }

    /** Família a que pertence uma versão de driver (ex.: 2, 1 → V2_1). */
    public static DriverFamily fromVersion(int major, int minor) {
        if (major <= 1) {
            return V1_4;
        }
        if (major == 2 && minor <= 1) {
            return V2_1;
        }
        return V2_2_PLUS;
    }
}
