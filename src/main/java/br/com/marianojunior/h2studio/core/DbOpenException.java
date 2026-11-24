package br.com.marianojunior.h2studio.core;

/** Falha ao abrir um banco, já classificada para mostrar uma mensagem amigável. */
public class DbOpenException extends Exception {

    public enum Reason {
        /** O arquivo está aberto por outro programa (erro 90020). */
        LOCKED,
        /** O arquivo não existe. */
        NOT_FOUND,
        /** Usuário ou senha incorretos (28000). */
        AUTH,
        /** Senha de criptografia do arquivo incorreta (90049). */
        FILE_PASSWORD,
        /** O driver não é da versão do arquivo (90048). */
        WRONG_VERSION,
        /** Nenhum driver disponível para o formato do arquivo. */
        NO_DRIVER,
        /** O banco exige opções na URL (ex.: DATABASE_TO_LOWER=TRUE) que não foram informadas. */
        URL_OPTIONS,
        /** Arquivo não reconhecido como H2. */
        NOT_H2,
        /** Servidor inacessível (desligado, firewall, host ou porta errados). */
        UNREACHABLE,
        OTHER
    }

    private final Reason reason;

    public DbOpenException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
