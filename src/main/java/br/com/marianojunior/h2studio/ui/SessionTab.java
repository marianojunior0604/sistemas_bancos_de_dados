package br.com.marianojunior.h2studio.ui;

import br.com.marianojunior.h2studio.core.DbSession;

/** Aba ligada a um banco aberto (tabela ou editor SQL). */
public interface SessionTab {

    DbSession session();

    /** Há algo que se perderia ao fechar (alterações não salvas, transação aberta...). */
    boolean hasPendingChanges();

    /** Descrição curta do que está pendente, para o aviso ao fechar. */
    String pendingDescription();

    /** F5 */
    void refresh();

    /** Chamado quando a aba é fechada (libera recursos). */
    default void dispose() {
    }
}
