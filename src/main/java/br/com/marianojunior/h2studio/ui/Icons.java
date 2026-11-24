package br.com.marianojunior.h2studio.ui;

import javafx.scene.Node;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.FillRule;
import javafx.scene.shape.SVGPath;

/** Ícones vetoriais simples (16x16), sem depender de arquivos de imagem. */
public final class Icons {

    private static final String DATABASE = "M8 1C4.1 1 1 2.3 1 4v8c0 1.7 3.1 3 7 3s7-1.3 7-3V4c0-1.7-3.1-3-7-3z"
            + "M8 2.5c3.3 0 5.5 1 5.5 1.5S11.3 5.5 8 5.5 2.5 4.5 2.5 4 4.7 2.5 8 2.5z";
    private static final String TABLE = "M1 2h14v12H1z M2 6v3h5V6z M8 6v3h6V6z M2 10v3h5v-3z M8 10v3h6v-3z";
    private static final String VIEW = "M8 3C4 3 1 8 1 8s3 5 7 5 7-5 7-5-3-5-7-5z M8 5.2a2.8 2.8 0 1 1 0 5.6 2.8 2.8 0 0 1 0-5.6z";
    private static final String FOLDER = "M1 3h5l2 2h7v9H1z";
    private static final String SCHEMA = "M1 2h6v5H1z M9 2h6v5H9z M1 9h6v5H1z M9 9h6v5H9z";
    private static final String KEY = "M5 4a4 4 0 1 0 3.9 5H11v2h2V9h2V7H8.9A4 4 0 0 0 5 4z M4 6.7a1.3 1.3 0 1 1 0 2.6 1.3 1.3 0 0 1 0-2.6z";
    private static final String LINK = "M1 7h9.5L7.8 4.3 9 3.1 13.9 8 9 12.9l-1.2-1.2L10.5 9H1z";
    private static final String LOCK = "M4 7V5a4 4 0 0 1 8 0v2h1v8H3V7z M6 7h4V5a2 2 0 0 0-4 0z";
    private static final String REFRESH = "M13.6 2.4A7 7 0 1 0 15 8h-2a5 5 0 1 1-1.5-3.5L9 7h6V1z";
    private static final String OPEN = "M1 3h5l2 2h6v2H4.5L2 13V3z M4.8 8H16l-2.6 6H2z";
    private static final String SQL = "M3 1h7l3 3v11H3z M4.5 6.5h7v1h-7z M4.5 9h7v1h-7z M4.5 11.5h5v1h-5z";
    private static final String PLAY = "M4 2.5v11L13 8z";
    private static final String PLAY_ALL = "M2 2.5v11L8 8z M8 2.5v11L14 8z";
    private static final String DESIGN = "M1 2h14v12H1z M2 5v8h4V5z M7 5v8h7V5z M3 6.5h2v1H3z M3 8.5h2v1H3z M8 6.5h5v1H8z M8 8.5h5v1H8z";
    private static final String CLOSE = "M3.4 2 8 6.6 12.6 2 14 3.4 9.4 8 14 12.6 12.6 14 8 9.4 3.4 14 2 12.6 6.6 8 2 3.4z";

    private Icons() {
    }

    private static Node make(String path, String styleClass, double scale) {
        SVGPath p = new SVGPath();
        p.setContent(path);
        p.setFillRule(FillRule.EVEN_ODD);
        p.getStyleClass().addAll("icon", styleClass);
        p.setScaleX(scale);
        p.setScaleY(scale);
        StackPane box = new StackPane(p);
        double size = 16 * scale;
        box.setMinSize(size, size);
        box.setPrefSize(size, size);
        box.setMaxSize(size, size);
        return box;
    }

    public static Node database() { return make(DATABASE, "icon-db", 1); }
    public static Node databaseLarge() { return make(DATABASE, "icon-db", 3); }
    public static Node table() { return make(TABLE, "icon-table", 1); }
    public static Node view() { return make(VIEW, "icon-view", 1); }
    public static Node folder() { return make(FOLDER, "icon-folder", 1); }
    public static Node schema() { return make(SCHEMA, "icon-schema", 0.85); }
    public static Node key() { return make(KEY, "icon-key", 0.9); }
    public static Node link() { return make(LINK, "icon-link", 0.85); }
    public static Node lock() { return make(LOCK, "icon-lock", 0.8); }
    public static Node refresh() { return make(REFRESH, "icon-tool", 0.9); }
    public static Node open() { return make(OPEN, "icon-tool", 0.95); }
    public static Node close() { return make(CLOSE, "icon-tool", 0.75); }
    public static Node play() { return make(PLAY, "icon-play", 0.8); }
    public static Node playAll() { return make(PLAY_ALL, "icon-play-all", 0.8); }
    public static Node design() { return make(DESIGN, "icon-design", 0.9); }
    public static Node sql() { return make(SQL, "icon-sql", 0.9); }
}
