import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.awt.Desktop;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.regex.*;
import java.util.stream.Collectors;

public class Main {

    record Registro(long id, String nome, String turma, String data, String status) {}

    static final List<Registro> registros = new ArrayList<>();
    static final Path ARQUIVO = Paths.get("dados", "frequencia.tsv");
    static long proximoId = 1;

    public static void main(String[] args) throws Exception {
        carregar();

        int portaBase = 8080;
        if (args.length > 0) portaBase = Integer.parseInt(args[0]);

        HttpServer server = null;
        int porta = portaBase;
        for (int i = 0; i < 20 && server == null; i++) {
            try {
                server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), portaBase + i), 0);
                porta = portaBase + i;
            } catch (BindException e) {
            }
        }
        if (server == null) {
            System.err.println("Nao foi possivel abrir nenhuma porta.");
            return;
        }

        server.createContext("/", Main::pagina);
        server.createContext("/api/registros", Main::apiRegistros);
        server.createContext("/api/turmas", Main::apiTurmas);
        server.createContext("/api/exportar", Main::apiExportar);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();

        String url = "http://localhost:" + porta;
        System.out.println("===========================================");
        System.out.println(" Controle de Frequencia rodando em: " + url);
        System.out.println(" Dados em: " + ARQUIVO.toAbsolutePath());
        System.out.println(" Feche esta janela para encerrar.");
        System.out.println("===========================================");
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(new URI(url));
            }
        } catch (Exception ignorado) {
        }
    }

    static void pagina(HttpExchange ex) throws IOException {
        if (!ex.getRequestURI().getPath().equals("/")) {
            responder(ex, 404, "text/plain; charset=utf-8", "Nao encontrado");
            return;
        }
        try (InputStream in = Main.class.getResourceAsStream("/index.html")) {
            if (in == null) {
                responder(ex, 500, "text/plain; charset=utf-8", "index.html nao encontrado");
                return;
            }
            responder(ex, 200, "text/html; charset=utf-8", new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    static void apiRegistros(HttpExchange ex) throws IOException {
        try {
            Map<String, String> q = query(ex);
            switch (ex.getRequestMethod()) {
                case "GET" -> {
                    List<Registro> lista = filtrar(q.getOrDefault("data", ""), q.getOrDefault("turma", ""));
                    responderJson(ex, 200, toJson(lista));
                }
                case "POST" -> {
                    String corpo = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    String nome = limpar(campo(corpo, "nome"));
                    String turma = limpar(campo(corpo, "turma"));
                    String data = campo(corpo, "data").trim();
                    String status = campo(corpo, "status").trim().toUpperCase();
                    if (nome.isEmpty()) { erro(ex, 400, "Informe o nome do aluno."); return; }
                    if (turma.isEmpty()) { erro(ex, 400, "Informe a turma."); return; }
                    if (!dataValida(data)) { erro(ex, 400, "Data invalida."); return; }
                    if (!status.equals("PRESENTE") && !status.equals("FALTOU")) { erro(ex, 400, "Status invalido."); return; }
                    synchronized (registros) {
                        boolean duplicado = registros.stream().anyMatch(r ->
                                r.nome().equalsIgnoreCase(nome) && r.turma().equalsIgnoreCase(turma) && r.data().equals(data));
                        if (duplicado) { erro(ex, 409, "Esse aluno ja tem registro nessa turma e data."); return; }
                        Registro r = new Registro(proximoId++, nome, turma, data, status);
                        registros.add(r);
                        salvar();
                        responderJson(ex, 201, toJson(r));
                    }
                }
                case "PUT" -> {
                    long id = Long.parseLong(q.getOrDefault("id", "-1"));
                    String status = campo(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8), "status").trim().toUpperCase();
                    if (!status.equals("PRESENTE") && !status.equals("FALTOU")) { erro(ex, 400, "Status invalido."); return; }
                    synchronized (registros) {
                        for (int i = 0; i < registros.size(); i++) {
                            Registro r = registros.get(i);
                            if (r.id() == id) {
                                Registro novo = new Registro(r.id(), r.nome(), r.turma(), r.data(), status);
                                registros.set(i, novo);
                                salvar();
                                responderJson(ex, 200, toJson(novo));
                                return;
                            }
                        }
                    }
                    erro(ex, 404, "Registro nao encontrado.");
                }
                case "DELETE" -> {
                    long id = Long.parseLong(q.getOrDefault("id", "-1"));
                    synchronized (registros) {
                        boolean removeu = registros.removeIf(r -> r.id() == id);
                        if (removeu) salvar();
                        if (removeu) responderJson(ex, 200, "{\"ok\":true}");
                        else erro(ex, 404, "Registro nao encontrado.");
                    }
                }
                default -> erro(ex, 405, "Metodo nao permitido.");
            }
        } catch (NumberFormatException e) {
            erro(ex, 400, "Parametro invalido.");
        } catch (Exception e) {
            e.printStackTrace();
            erro(ex, 500, "Erro interno: " + e.getMessage());
        }
    }

    static void apiTurmas(HttpExchange ex) throws IOException {
        List<String> turmas;
        synchronized (registros) {
            turmas = registros.stream().map(Registro::turma).distinct()
                    .sorted(String.CASE_INSENSITIVE_ORDER).collect(Collectors.toList());
        }
        String json = turmas.stream().map(t -> "\"" + esc(t) + "\"").collect(Collectors.joining(",", "[", "]"));
        responderJson(ex, 200, json);
    }

    static void apiExportar(HttpExchange ex) throws IOException {
        Map<String, String> q = query(ex);
        List<Registro> lista = filtrar(q.getOrDefault("data", ""), q.getOrDefault("turma", ""));
        StringBuilder sb = new StringBuilder("\uFEFFNome;Turma;Data;Status\r\n");
        for (Registro r : lista) {
            String[] d = r.data().split("-");
            sb.append(csv(r.nome())).append(';').append(csv(r.turma())).append(';')
                    .append(d[2]).append('/').append(d[1]).append('/').append(d[0]).append(';')
                    .append(r.status().equals("PRESENTE") ? "Presente" : "Faltou").append("\r\n");
        }
        ex.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"frequencia.csv\"");
        responder(ex, 200, "text/csv; charset=utf-8", sb.toString());
    }

    static List<Registro> filtrar(String data, String turma) {
        synchronized (registros) {
            return registros.stream()
                    .filter(r -> data.isEmpty() || r.data().equals(data))
                    .filter(r -> turma.isEmpty() || r.turma().equalsIgnoreCase(turma))
                    .sorted(Comparator.comparing(Registro::data).reversed()
                            .thenComparing(Registro::turma, String.CASE_INSENSITIVE_ORDER)
                            .thenComparing(Registro::nome, String.CASE_INSENSITIVE_ORDER))
                    .collect(Collectors.toList());
        }
    }

    static void carregar() {
        try {
            if (!Files.exists(ARQUIVO)) return;
            for (String linha : Files.readAllLines(ARQUIVO, StandardCharsets.UTF_8)) {
                String[] p = linha.split("\t", -1);
                if (p.length < 5) continue;
                long id = Long.parseLong(p[0]);
                registros.add(new Registro(id, p[1], p[2], p[3], p[4]));
                proximoId = Math.max(proximoId, id + 1);
            }
        } catch (Exception e) {
            System.err.println("Erro ao ler dados: " + e.getMessage());
        }
    }

    static void salvar() throws IOException {
        Files.createDirectories(ARQUIVO.getParent());
        List<String> linhas = new ArrayList<>();
        for (Registro r : registros) {
            linhas.add(r.id() + "\t" + r.nome() + "\t" + r.turma() + "\t" + r.data() + "\t" + r.status());
        }
        Path tmp = ARQUIVO.resolveSibling("frequencia.tmp");
        Files.write(tmp, linhas, StandardCharsets.UTF_8);
        Files.move(tmp, ARQUIVO, StandardCopyOption.REPLACE_EXISTING);
    }

    static boolean dataValida(String s) {
        try { LocalDate.parse(s); return true; } catch (Exception e) { return false; }
    }

    static String limpar(String s) {
        s = s.replaceAll("[\\t\\r\\n]+", " ").replaceAll("\\s{2,}", " ").trim();
        return s.length() > 100 ? s.substring(0, 100) : s;
    }

    static String csv(String s) {
        if (!s.isEmpty() && "=+-@".indexOf(s.charAt(0)) >= 0) s = "'" + s;
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    static Map<String, String> query(HttpExchange ex) {
        Map<String, String> m = new HashMap<>();
        String raw = ex.getRequestURI().getRawQuery();
        if (raw == null) return m;
        for (String par : raw.split("&")) {
            String[] kv = par.split("=", 2);
            m.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8),
                    kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "");
        }
        return m;
    }

    static String campo(String json, String chave) {
        Matcher m = Pattern.compile("\"" + chave + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(json);
        return m.find() ? desescapar(m.group(1)) : "";
    }

    static String desescapar(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                switch (n) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    case 'u' -> {
                        if (i + 4 < s.length()) {
                            sb.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                            i += 4;
                        }
                    }
                    default -> sb.append(n);
                }
            } else sb.append(c);
        }
        return sb.toString();
    }

    static String esc(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> { }
                case '\t' -> sb.append("\\t");
                default -> { if (c < 0x20) sb.append(String.format("\\u%04x", (int) c)); else sb.append(c); }
            }
        }
        return sb.toString();
    }

    static String toJson(Registro r) {
        return "{\"id\":" + r.id() + ",\"nome\":\"" + esc(r.nome()) + "\",\"turma\":\"" + esc(r.turma())
                + "\",\"data\":\"" + r.data() + "\",\"status\":\"" + r.status() + "\"}";
    }

    static String toJson(List<Registro> lista) {
        return lista.stream().map(Main::toJson).collect(Collectors.joining(",", "[", "]"));
    }

    static void erro(HttpExchange ex, int codigo, String msg) throws IOException {
        responderJson(ex, codigo, "{\"erro\":\"" + esc(msg) + "\"}");
    }

    static void responderJson(HttpExchange ex, int codigo, String json) throws IOException {
        responder(ex, codigo, "application/json; charset=utf-8", json);
    }

    static void responder(HttpExchange ex, int codigo, String tipo, String corpo) throws IOException {
        byte[] bytes = corpo.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", tipo);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(codigo, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }
}