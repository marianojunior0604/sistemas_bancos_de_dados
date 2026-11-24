package br.com.marianojunior.h2studio.ui;

import br.com.marianojunior.h2studio.core.TableEditor;
import br.com.marianojunior.h2studio.core.ValueFormatter;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Objects;

/** Uma linha da grade, com o valor original, o atual e o estado da edição. */
final class GridRow {

    enum State { CLEAN, MODIFIED, NEW, DELETED }

    /** Valores lidos do banco (null em linhas novas). */
    final Object[] original;
    /** Valores atuais (editados ou não). */
    final Object[] values;
    /** _ROWID_ quando a tabela não tem chave primária. */
    final Object rowId;
    final BitSet changed = new BitSet();
    State state;
    /** Marcada depois de uma falha ao salvar. */
    boolean error;
    /** Estado anterior à exclusão (para desfazer). */
    private State beforeDelete;

    private GridRow(Object[] original, Object[] values, Object rowId, State state) {
        this.original = original;
        this.values = values;
        this.rowId = rowId;
        this.state = state;
    }

    static GridRow loaded(Object[] data, Object rowId) {
        return new GridRow(data, Arrays.copyOf(data, data.length), rowId, State.CLEAN);
    }

    static GridRow created(int columns) {
        Object[] v = new Object[columns];
        Arrays.fill(v, TableEditor.DEFAULT);
        return new GridRow(null, v, null, State.NEW);
    }

    boolean isNew() {
        return state == State.NEW;
    }

    boolean isDeleted() {
        return state == State.DELETED;
    }

    boolean isPending() {
        return state != State.CLEAN;
    }

    /** Altera o valor de uma coluna; volta a "limpo" se ficar igual ao original. */
    void set(int col, Object value) {
        values[col] = value;
        error = false;
        if (isNew()) {
            changed.set(col, value != TableEditor.DEFAULT);
            return;
        }
        if (sameValue(original[col], value)) {
            changed.clear(col);
        } else {
            changed.set(col);
        }
        if (state != State.DELETED) {
            state = changed.isEmpty() ? State.CLEAN : State.MODIFIED;
        }
    }

    void restore(int col) {
        if (isNew()) {
            set(col, TableEditor.DEFAULT);
        } else {
            set(col, original[col]);
        }
    }

    void markDeleted() {
        if (state != State.DELETED) {
            beforeDelete = state;
            state = State.DELETED;
        }
    }

    /** Desfaz todas as alterações da linha (inclusive a exclusão). */
    void revert() {
        if (original != null) {
            System.arraycopy(original, 0, values, 0, values.length);
        }
        changed.clear();
        error = false;
        state = original == null ? State.NEW : State.CLEAN;
        beforeDelete = null;
    }

    void undelete() {
        if (state == State.DELETED) {
            state = beforeDelete != null ? beforeDelete : State.CLEAN;
        }
    }

    /**
     * Compara pelo texto exibido: a data lida (java.sql.Date) e a digitada
     * (LocalDate) são tipos diferentes, mas o usuário vê o mesmo valor.
     */
    static boolean sameValue(Object a, Object b) {
        if (a == null || b == null) {
            return a == b;
        }
        if (a instanceof byte[] x && b instanceof byte[] y) {
            return Arrays.equals(x, y);
        }
        return Objects.equals(a, b) || ValueFormatter.format(a).equals(ValueFormatter.format(b));
    }
}
