package br.com.marianojunior.h2studio.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Consumer;

/**
 * Uma janela só: quando o H2 Studio já está aberto e o usuário dá dois cliques num banco
 * (ou usa "Abrir com"), a segunda execução entrega o arquivo para a primeira e termina.
 * <p>
 * A primeira instância escuta numa porta local (127.0.0.1) e grava porta + uma senha
 * aleatória em {@code ~/.h2studio/instancia}. Só quem consegue ler esse arquivo (o próprio
 * usuário) consegue mandar arquivos para ela.
 */
public final class SingleInstance {

    private static final String HELLO = "H2STUDIO1";
    private static ServerSocket server;

    private SingleInstance() {
    }

    static Path lockFile() {
        return DriverRegistry.userDir().resolve("instancia");
    }

    /**
     * Tenta entregar os argumentos a uma instância já aberta.
     *
     * @return true se outra instância recebeu (esta deve terminar)
     */
    public static boolean handOff(List<String> args) {
        Path f = lockFile();
        try {
            if (!Files.isRegularFile(f)) {
                return false;
            }
            List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
            if (lines.size() < 2) {
                return false;
            }
            int port = Integer.parseInt(lines.get(0).trim());
            String token = lines.get(1).trim();
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 700);
                s.setSoTimeout(2000);
                Writer w = new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8);
                w.write(HELLO + "\n" + token + "\n");
                for (String a : args) {
                    w.write(Path.of(a).toAbsolutePath().toString() + "\n");
                }
                w.write("\n");
                w.flush();
                BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                return "OK".equals(r.readLine());
            }
        } catch (IOException | RuntimeException e) {
            return false; // ninguém escutando (arquivo velho de uma execução que fechou)
        }
    }

    /**
     * Passa a receber arquivos de execuções seguintes. {@code onFiles} é chamado numa thread
     * própria (use Platform.runLater); lista vazia = só trazer a janela para a frente.
     */
    public static synchronized void listen(Consumer<List<String>> onFiles) {
        if (server != null) {
            return;
        }
        try {
            server = new ServerSocket(0, 10, InetAddress.getLoopbackAddress());
            byte[] b = new byte[16];
            new SecureRandom().nextBytes(b);
            String token = HexFormat.of().formatHex(b);
            Path f = lockFile();
            Files.createDirectories(f.getParent());
            Files.writeString(f, server.getLocalPort() + "\n" + token + "\n", StandardCharsets.UTF_8);
            Thread t = new Thread(() -> loop(token, onFiles), "h2studio-instancia");
            t.setDaemon(true);
            t.start();
        } catch (IOException e) {
            server = null; // sem instância única: segue normalmente
        }
    }

    private static void loop(String token, Consumer<List<String>> onFiles) {
        while (server != null && !server.isClosed()) {
            try (Socket s = server.accept()) {
                s.setSoTimeout(2000);
                BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                if (!HELLO.equals(r.readLine()) || !token.equals(r.readLine())) {
                    continue;
                }
                List<String> files = new ArrayList<>();
                for (String line; (line = r.readLine()) != null && !line.isEmpty(); ) {
                    files.add(line);
                }
                Writer w = new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8);
                w.write("OK\n");
                w.flush();
                onFiles.accept(files);
            } catch (IOException | RuntimeException e) {
                // conexão quebrada: espera a próxima
            }
        }
    }

    /** Ao fechar: apaga o arquivo para a próxima execução não tentar falar com ela. */
    public static synchronized void stop() {
        if (server == null) {
            return;
        }
        try {
            server.close();
            Files.deleteIfExists(lockFile());
        } catch (IOException ignored) {
            // nada a fazer
        }
        server = null;
    }
}
