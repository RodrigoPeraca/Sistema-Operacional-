package model;

import benchmark.HeapBenchmark;
import benchmark.HeapBenchmark.BenchmarkParams;
import benchmark.HeapBenchmark.BenchmarkResult;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Servidor HTTP que expõe o simulador de heap via API REST.
 *
 * Endpoints:
 *   GET /api/health          — verifica se o servidor está no ar
 *   GET /api/simular-passos  — simulação sequencial com snapshots passo a passo
 *   GET /api/benchmark       — comparativo REAL sequencial vs paralelo via HeapBenchmark
 *
 * O endpoint /api/benchmark substitui o antigo montarBenchmarkJson() que
 * fabricava dados multiplicando o tempo por constantes fixas. Agora os
 * dois modos são executados de verdade com as mesmas entradas.
 *
 * Parâmetros comuns (query string):
 *   heapKb         — tamanho da heap em KB         (padrão: 8)
 *   minBytes       — tamanho mínimo da requisição  (padrão: 16)
 *   maxBytes       — tamanho máximo da requisição  (padrão: 1024)
 *   totalRequests  — número de requisições         (padrão: 250)
 */
public class HeapApiServer {

    private static int logId = 1;

    public static void main(String[] args) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);

        server.createContext("/api/health",         HeapApiServer::health);
        server.createContext("/api/simular-passos", HeapApiServer::simularPassos);
        server.createContext("/api/benchmark",      HeapApiServer::benchmark);

        server.setExecutor(null);

        System.out.println("API rodando em http://localhost:8080");
        System.out.println("Endpoints:");
        System.out.println("  GET /api/health");
        System.out.println("  GET /api/simular-passos?heapKb=8&minBytes=16&maxBytes=1024&totalRequests=250");
        System.out.println("  GET /api/benchmark?heapKb=64&minBytes=16&maxBytes=256&totalRequests=5000");
        System.out.println();
        System.out.println("AVISO: /api/benchmark roda warmup + medição real (pode levar alguns segundos).");

        server.start();
    }

    // ── Handlers ──────────────────────────────────────────────────────────────

    private static void health(HttpExchange ex) throws IOException {
        if (cors(ex)) return;
        sendJson(ex, 200, "{\"status\":\"ok\",\"message\":\"Backend Java rodando\"}");
    }

    private static void simularPassos(HttpExchange ex) throws IOException {
        if (cors(ex)) return;
        try {
            Map<String, String> p = parseQuery(ex.getRequestURI());
            int heapKb        = parseInt(p.get("heapKb"),        8);
            int minBytes      = parseInt(p.get("minBytes"),      16);
            int maxBytes      = parseInt(p.get("maxBytes"),    1024);
            int totalRequests = parseInt(p.get("totalRequests"), 250);

            String json = executarSimulacaoComPassos(heapKb, minBytes, maxBytes, totalRequests);
            sendJson(ex, 200, json);
        } catch (Exception e) {
            sendJson(ex, 500, "{\"error\":\"" + escape(e.getMessage()) + "\"}");
        }
    }

    /**
     * Executa comparativo REAL sequencial vs paralelo via HeapBenchmark.
     * Retorna BenchmarkResult serializado em JSON — sem dados fabricados.
     *
     * Aviso: pode levar vários segundos dependendo dos parâmetros,
     * pois executa warmup + múltiplas rodadas de medição.
     */
    private static void benchmark(HttpExchange ex) throws IOException {
        if (cors(ex)) return;
        try {
            Map<String, String> p = parseQuery(ex.getRequestURI());
            BenchmarkParams params = new BenchmarkParams(
                parseInt(p.get("heapKb"),          64),
                parseInt(p.get("totalRequests"), 5_000),
                parseInt(p.get("minBytes"),         16),
                parseInt(p.get("maxBytes"),        256)
            );

            // Executa o benchmark de verdade — ambos os modos com as mesmas entradas
            BenchmarkResult result = HeapBenchmark.executarComparativo(params);

            sendJson(ex, 200, serializeBenchmarkResult(result));
        } catch (Exception e) {
            sendJson(ex, 500, "{\"error\":\"" + escape(e.getMessage()) + "\"}");
        }
    }

    // ── Serialização do BenchmarkResult em JSON (sem biblioteca externa) ──────

    private static String serializeBenchmarkResult(BenchmarkResult r) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");

        // Parâmetros
        sb.append("\"params\":{");
        sb.append("\"heapKb\":").append(r.heapKb).append(",");
        sb.append("\"totalRequests\":").append(r.totalRequests).append(",");
        sb.append("\"minBytes\":").append(r.minBytes).append(",");
        sb.append("\"maxBytes\":").append(r.maxBytes).append(",");
        sb.append("\"threadCount\":").append(r.threadCount).append(",");
        sb.append("\"warmupRounds\":").append(r.warmupRounds).append(",");
        sb.append("\"measureRounds\":").append(r.measureRounds);
        sb.append("},");

        // Sequencial
        sb.append("\"sequencial\":{");
        sb.append("\"latenciaMinMs\":").append(fmt(r.seqLatencyMinMs)).append(",");
        sb.append("\"latenciaMediaMs\":").append(fmt(r.seqLatencyAvgMs)).append(",");
        sb.append("\"latenciaMaxMs\":").append(fmt(r.seqLatencyMaxMs)).append(",");
        sb.append("\"throughputMin\":").append(r.seqThroughputMin).append(",");
        sb.append("\"throughputMedio\":").append(r.seqThroughputAvg).append(",");
        sb.append("\"throughputMax\":").append(r.seqThroughputMax).append(",");
        sb.append("\"atendidas\":").append(r.seqServed).append(",");
        sb.append("\"rejeitadas\":").append(r.seqRejected).append(",");
        sb.append("\"randomAcionado\":").append(r.seqRandoms);
        sb.append("},");

        // Paralelo
        sb.append("\"paralelo\":{");
        sb.append("\"latenciaMinMs\":").append(fmt(r.parLatencyMinMs)).append(",");
        sb.append("\"latenciaMediaMs\":").append(fmt(r.parLatencyAvgMs)).append(",");
        sb.append("\"latenciaMaxMs\":").append(fmt(r.parLatencyMaxMs)).append(",");
        sb.append("\"throughputMin\":").append(r.parThroughputMin).append(",");
        sb.append("\"throughputMedio\":").append(r.parThroughputAvg).append(",");
        sb.append("\"throughputMax\":").append(r.parThroughputMax).append(",");
        sb.append("\"atendidas\":").append(r.parServed).append(",");
        sb.append("\"rejeitadas\":").append(r.parRejected).append(",");
        sb.append("\"randomAcionado\":").append(r.parRandoms);
        sb.append("},");

        // Speedup
        sb.append("\"speedup\":{");
        sb.append("\"latencia\":").append(fmt(r.latencySpeedup)).append(",");
        sb.append("\"throughput\":").append(fmt(r.throughputSpeedup));
        sb.append("}");

        sb.append("}");
        return sb.toString();
    }

    // ── Simulação sequencial com snapshots (endpoint simular-passos) ──────────

    private static String executarSimulacaoComPassos(
            int heapKb, int minBytes, int maxBytes, int totalRequests) {

        long inicio = System.nanoTime();

        WorstFit             wf         = new WorstFit(heapKb);
        GerenciadorLiberacao liberador  = new GerenciadorLiberacao(wf);

        List<String> logs  = new ArrayList<>();
        List<String> steps = new ArrayList<>();

        int generatedRequests = 0, attendedRequests = 0;
        int rejectedRequests  = 0, removedVariables = 0, liberationCalls = 0;
        long totalAllocatedBytes = 0;

        int snapshotEvery = totalRequests > 400
            ? (int) Math.ceil(totalRequests / 400.0) : 1;

        for (int i = 1; i <= totalRequests; i++) {
            generatedRequests++;
            int requestId = i;
            int sizeBytes = minBytes + (int)(Math.random() * (maxBytes - minBytes + 1));

            int endereco = wf.allocate(sizeBytes, requestId);

            if (endereco == -1) {
                liberationCalls++;
                GerenciadorLiberacao.RelatorioLiberacao rel =
                        liberador.executarLiberacaoRandomica(false);
                removedVariables += rel.getBlocosLiberados();
                logs.add(logEntry("release",
                    "Sem espaço para ID=" + requestId +
                    ". RANDOM: " + rel.getBlocosLiberados() +
                    " bloco(s), " + rel.getBytesRecuperados() + " bytes recuperados."));
                endereco = wf.allocate(sizeBytes, requestId);
            }

            if (endereco != -1) {
                attendedRequests++;
                totalAllocatedBytes += sizeBytes;
                logs.add(logEntry("allocation",
                    "ID=" + requestId + " → índice " + endereco +
                    ", " + sizeBytes + " bytes."));
            } else {
                rejectedRequests++;
                logs.add(logEntry("warning",
                    "ID=" + requestId + " rejeitado após RANDOM."));
            }

            if (i % snapshotEvery == 0 || i == totalRequests) {
                double elapsed = (System.nanoTime() - inicio) / 1_000_000.0;
                double avgSize = attendedRequests == 0 ? 0.0
                    : (double) totalAllocatedBytes / attendedRequests;
                steps.add(buildStepJson(wf.snapshot(), logs,
                    generatedRequests, attendedRequests, rejectedRequests,
                    removedVariables, avgSize, liberationCalls, elapsed));
            }
        }

        double totalMs = (System.nanoTime() - inicio) / 1_000_000.0;

        StringBuilder sb = new StringBuilder();
        sb.append("{\"steps\":[");
        for (int i = 0; i < steps.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(steps.get(i));
        }
        sb.append("],");
        sb.append("\"executionTimeMs\":").append(fmt(totalMs)).append(",");
        sb.append("\"mode\":\"sequential\",");
        sb.append("\"threads\":1}");
        return sb.toString();
    }

    // ── Construtores de JSON ──────────────────────────────────────────────────

    private static String buildStepJson(int[] heap, List<String> logs,
            int generated, int attended, int rejected, int removed,
            double avgSize, int libCalls, double elapsedMs) {

        int usedCells = 0;
        for (int v : heap) if (v != Heap.FREE) usedCells++;
        double usedPct = heap.length == 0 ? 0.0 : usedCells * 100.0 / heap.length;

        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"heap\":").append(heapToJson(heap)).append(",");
        sb.append("\"metrics\":{");
        sb.append("\"generatedRequests\":").append(generated).append(",");
        sb.append("\"attendedRequests\":").append(attended).append(",");
        sb.append("\"rejectedRequests\":").append(rejected).append(",");
        sb.append("\"removedVariables\":").append(removed).append(",");
        sb.append("\"averageVariableSize\":").append(fmt(avgSize)).append(",");
        sb.append("\"liberationCalls\":").append(libCalls).append(",");
        sb.append("\"executionTimeMs\":").append(fmt(elapsedMs)).append(",");
        sb.append("\"usedPercentage\":").append(fmt(usedPct)).append(",");
        sb.append("\"freePercentage\":").append(fmt(100.0 - usedPct));
        sb.append("},");
        sb.append("\"logs\":").append(logsToJson(logs));
        sb.append("}");
        return sb.toString();
    }

    private static String heapToJson(int[] heap) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < heap.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(heap[i]);
        }
        return sb.append("]").toString();
    }

    private static String logsToJson(List<String> logs) {
        int start = Math.max(0, logs.size() - 120);
        StringBuilder sb = new StringBuilder("[");
        for (int i = start; i < logs.size(); i++) {
            if (i > start) sb.append(",");
            sb.append(logs.get(i));
        }
        return sb.append("]").toString();
    }

    private static String logEntry(String type, String message) {
        return "{\"id\":" + (logId++) + "," +
               "\"timestamp\":\"" + escape(java.time.LocalTime.now().toString()) + "\"," +
               "\"type\":\"" + escape(type) + "\"," +
               "\"message\":\"" + escape(message) + "\"}";
    }

    // ── Utilitários HTTP ──────────────────────────────────────────────────────

    private static boolean cors(HttpExchange ex) throws IOException {
        if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) {
            ex.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
            ex.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, OPTIONS");
            ex.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");
            ex.sendResponseHeaders(204, -1);
            ex.close();
            return true;
        }
        return false;
    }

    private static void sendJson(HttpExchange ex, int status, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");
        ex.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        ex.sendResponseHeaders(status, body.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(body); }
    }

    private static Map<String, String> parseQuery(URI uri) {
        Map<String, String> map = new HashMap<>();
        String q = uri.getRawQuery();
        if (q == null || q.isBlank()) return map;
        for (String pair : q.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2) map.put(kv[0], kv[1]);
        }
        return map;
    }

    private static int parseInt(String v, int def) {
        if (v == null) return def;
        try { return Integer.parseInt(v); } catch (NumberFormatException e) { return def; }
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.US, "%.2f", v);
    }

    private static String escape(String v) {
        if (v == null) return "";
        return v.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "");
    }
}
