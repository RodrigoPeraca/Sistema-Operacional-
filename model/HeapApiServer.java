package model;

import benchmark.HeapBenchmark;
import benchmark.HeapBenchmark.BenchmarkParams;
import benchmark.HeapBenchmark.BenchmarkResult;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

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
 * GET /api/health — verifica se o servidor está no ar
 * GET /api/simular-passos — simulação sequencial com snapshots passo a passo
 * GET /api/benchmark — comparativo REAL sequencial vs paralelo via
 * HeapBenchmark
 *
 * O endpoint /api/benchmark substitui o antigo montarBenchmarkJson() que
 * fabricava dados multiplicando o tempo por constantes fixas. Agora os
 * dois modos são executados de verdade com as mesmas entradas.
 *
 * Parâmetros comuns (query string):
 * heapKb — tamanho da heap em KB (padrão: 8)
 * minBytes — tamanho mínimo da requisição (padrão: 16)
 * maxBytes — tamanho máximo da requisição (padrão: 1024)
 * totalRequests — número de requisições (padrão: 250)
 */
public class HeapApiServer {

    private static final AtomicInteger logId = new AtomicInteger(1);

    public static void main(String[] args) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);

        server.createContext("/api/health", HeapApiServer::health);
        server.createContext("/api/simular-passos", HeapApiServer::simularPassos);
        server.createContext("/api/simular-paralelo-passos", HeapApiServer::simularParaleloPassos);
        server.createContext("/api/benchmark", HeapApiServer::benchmark);

        server.setExecutor(null);

        System.out.println("API rodando em http://localhost:8080");
        System.out.println("Endpoints:");
        System.out.println("  GET /api/health");
        System.out.println("  GET /api/simular-passos?heapKb=8&minBytes=16&maxBytes=1024&totalRequests=250");
        System.out.println("  GET /api/benchmark?heapKb=64&minBytes=16&maxBytes=256&totalRequests=5000");
        System.out.println();
        System.out.println("AVISO: /api/benchmark roda warmup + medição real (pode levar alguns segundos).");
        System.out.println("  GET /api/simular-paralelo-passos?heapKb=8&minBytes=16&maxBytes=1024&totalRequests=40");
        server.start();
    }

    // ── Handlers ──────────────────────────────────────────────────────────────

    private static void health(HttpExchange ex) throws IOException {
        if (cors(ex))
            return;
        sendJson(ex, 200, "{\"status\":\"ok\",\"message\":\"Backend Java rodando\"}");
    }

    private static void simularPassos(HttpExchange ex) throws IOException {
        if (cors(ex))
            return;
        try {
            Map<String, String> p = parseQuery(ex.getRequestURI());
            int heapKb = parseInt(p.get("heapKb"), 8);
            int minBytes = parseInt(p.get("minBytes"), 16);
            int maxBytes = parseInt(p.get("maxBytes"), 1024);
            int totalRequests = parseInt(p.get("totalRequests"), 250);

            String json = executarSimulacaoComPassos(heapKb, minBytes, maxBytes, totalRequests);
            sendJson(ex, 200, json);
        } catch (Exception e) {
            sendJson(ex, 500, "{\"error\":\"" + escape(e.getMessage()) + "\"}");
        }
    }

    private static void simularParaleloPassos(HttpExchange ex) throws IOException {
        if (cors(ex))
            return;
        try {
            Map<String, String> p = parseQuery(ex.getRequestURI());

            int heapKb = parseInt(p.get("heapKb"), 8);
            int minBytes = parseInt(p.get("minBytes"), 16);
            int maxBytes = parseInt(p.get("maxBytes"), 1024);
            int totalRequests = parseInt(p.get("totalRequests"), 40);

            String json = executarSimulacaoParalelaComPassos(
                    heapKb,
                    minBytes,
                    maxBytes,
                    totalRequests);

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
        if (cors(ex))
            return;
        try {
            Map<String, String> p = parseQuery(ex.getRequestURI());
            BenchmarkParams params = new BenchmarkParams(
                    parseInt(p.get("heapKb"), 64),
                    parseInt(p.get("totalRequests"), 5_000),
                    parseInt(p.get("minBytes"), 16),
                    parseInt(p.get("maxBytes"), 256));

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
    // ── Simulação paralela REAL com snapshots (endpoint simular-paralelo-passos)
    // ──
    // Esta rotina NÃO é benchmark. Ela gera frames para visualização.
    // A execução usa WorstFitPartitioned + GerenciadorLiberacaoPartitioned reais.

    private static String executarSimulacaoParalelaComPassos(
            int heapKb, int minBytes, int maxBytes, int totalRequests) {

        final int threadCount = WorstFitPartitioned.NUM_SEGMENTS;
        final int segmentCount = WorstFitPartitioned.NUM_SEGMENTS;

        int visualRequests = Math.max(1, totalRequests);
        WorstFitPartitioned wf = new WorstFitPartitioned(heapKb);
        GerenciadorLiberacaoPartitioned liberador = new GerenciadorLiberacaoPartitioned(wf);

        List<String> logs = Collections.synchronizedList(new ArrayList<>());
        List<String> steps = new ArrayList<>();

        int heapCells = wf.getCapacity();
        int segmentCells = Math.max(1, (heapCells + segmentCount - 1) / segmentCount);

        int requestId = 1;
        int frame = 1;
        int round = 1;

        for (int base = 0; base < visualRequests; base += threadCount) {
            int batchSize = Math.min(threadCount, visualRequests - base);

            int[] threadIds = new int[batchSize];
            int[] primarySegments = new int[batchSize];
            int[] finalSegments = new int[batchSize];
            int[] requestIds = new int[batchSize];
            int[] sizeBytes = new int[batchSize];
            String[] statuses = new String[batchSize];
            String[] messages = new String[batchSize];

            for (int i = 0; i < batchSize; i++) {
                int threadId = i;
                int primarySegment = threadId % segmentCount;

                int generatedSize = deterministicSize(
                        minBytes,
                        maxBytes,
                        requestId,
                        threadId,
                        round);

                threadIds[i] = threadId;
                primarySegments[i] = primarySegment;
                finalSegments[i] = primarySegment;
                requestIds[i] = requestId;
                sizeBytes[i] = generatedSize;

                statuses[i] = "waiting";
                messages[i] = "Thread " + threadId +
                        " recebeu ID=" + requestId +
                        " e tentará primeiro o Segmento " + primarySegment + ".";

                requestId++;
            }

            logs.add(logEntry("system",
                    "Rodada " + round + ": threads receberam novas requisições."));

            steps.add(buildParallelStepJson(
                    frame++,
                    wf.snapshot(),
                    segmentCount,
                    logs,
                    threadEventsToJson(threadIds, finalSegments, requestIds, sizeBytes, statuses, messages)));

            for (int i = 0; i < batchSize; i++) {
                statuses[i] = "locked";
                messages[i] = "Thread " + threadIds[i] +
                        " iniciou allocate() real no WorstFitPartitioned com afinidade no Segmento " +
                        primarySegments[i] + ".";
            }

            logs.add(logEntry("system",
                    "Rodada " + round + ": threads part-worker-0..3 iniciadas."));

            steps.add(buildParallelStepJson(
                    frame++,
                    wf.snapshot(),
                    segmentCount,
                    logs,
                    threadEventsToJson(threadIds, finalSegments, requestIds, sizeBytes, statuses, messages)));

            Thread[] workers = new Thread[batchSize];

            for (int i = 0; i < batchSize; i++) {
                final int tid = i;

                workers[i] = new Thread(() -> {
                    int endereco = wf.allocate(sizeBytes[tid], requestIds[tid]);

                    if (endereco >= 0) {
                        int actualSegment = segmentIdFromIndex(
                                endereco,
                                segmentCells,
                                segmentCount);

                        finalSegments[tid] = actualSegment;
                        statuses[tid] = actualSegment == primarySegments[tid]
                                ? "allocated"
                                : "fallback";

                        messages[tid] = "Thread " + threadIds[tid] +
                                " alocou ID=" + requestIds[tid] +
                                " no Segmento " + actualSegment +
                                " usando WorstFitPartitioned real.";

                        logs.add(logEntry("allocation", messages[tid]));
                        return;
                    }

                    logs.add(logEntry("release",
                            "Thread " + threadIds[tid] +
                                    " não encontrou espaço para ID=" + requestIds[tid] +
                                    " e acionou RANDOM real."));

                    GerenciadorLiberacao.RelatorioLiberacao rel = liberador.executarLiberacaoRandomica();

                    endereco = wf.allocate(sizeBytes[tid], requestIds[tid]);

                    if (endereco >= 0) {
                        int actualSegment = segmentIdFromIndex(
                                endereco,
                                segmentCells,
                                segmentCount);

                        finalSegments[tid] = actualSegment;
                        statuses[tid] = "fallback";

                        messages[tid] = "Thread " + threadIds[tid] +
                                " recuperou " + rel.getBytesRecuperados() +
                                " bytes com RANDOM e alocou ID=" + requestIds[tid] +
                                " no Segmento " + actualSegment + ".";

                        logs.add(logEntry("allocation", messages[tid]));
                    } else {
                        statuses[tid] = "failed";

                        messages[tid] = "Thread " + threadIds[tid] +
                                " rejeitou ID=" + requestIds[tid] +
                                " mesmo após RANDOM real.";

                        logs.add(logEntry("warning", messages[tid]));
                    }
                }, "part-worker-" + i);
            }

            for (Thread t : workers) {
                t.start();
            }

            for (Thread t : workers) {
                try {
                    t.join();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            logs.add(logEntry("system",
                    "Rodada " + round + ": processamento paralelo real finalizado."));

            steps.add(buildParallelStepJson(
                    frame++,
                    wf.snapshot(),
                    segmentCount,
                    logs,
                    threadEventsToJson(threadIds, finalSegments, requestIds, sizeBytes, statuses, messages)));

            for (int i = 0; i < batchSize; i++) {
                statuses[i] = "released";
                messages[i] = "Thread " + threadIds[i] +
                        " finalizou e liberou sua região crítica.";
            }

            logs.add(logEntry("system",
                    "Rodada " + round + ": regiões críticas liberadas."));

            steps.add(buildParallelStepJson(
                    frame++,
                    wf.snapshot(),
                    segmentCount,
                    logs,
                    threadEventsToJson(threadIds, finalSegments, requestIds, sizeBytes, statuses, messages)));

            round++;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("{\"steps\":[");
        for (int i = 0; i < steps.size(); i++) {
            if (i > 0)
                sb.append(",");
            sb.append(steps.get(i));
        }
        sb.append("],");
        sb.append("\"mode\":\"parallel-real-visual\",");
        sb.append("\"threads\":").append(threadCount).append(",");
        sb.append("\"segments\":").append(segmentCount).append(",");
        sb.append("\"heapKb\":").append(heapKb).append(",");
        sb.append("\"visualRequests\":").append(visualRequests);
        sb.append("}");

        return sb.toString();
    }

    private static int deterministicSize(
            int minBytes, int maxBytes, int requestId, int threadId, int round) {

        if (maxBytes < minBytes) {
            maxBytes = minBytes;
        }

        int range = maxBytes - minBytes + 1;
        if (range <= 0)
            return minBytes;

        int value = Math.abs((requestId * 37) + (threadId * 53) + (round * 17));
        return minBytes + (value % range);
    }

    private static int segmentIdFromIndex(int index, int segmentCells, int segmentCount) {
        if (index < 0)
            return 0;
        return Math.min(index / segmentCells, segmentCount - 1);
    }

    private static String buildParallelStepJson(
            int frame,
            int[] heapSnapshot,
            int segmentCount,
            List<String> logs,
            String threadsJson) {

        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"frame\":").append(frame).append(",");
        sb.append("\"segments\":").append(segmentsFromHeapToJson(heapSnapshot, segmentCount)).append(",");
        sb.append("\"threads\":").append(threadsJson).append(",");
        sb.append("\"logs\":").append(logsToJson(logs));
        sb.append("}");
        return sb.toString();
    }

    private static String segmentsFromHeapToJson(int[] heapSnapshot, int segmentCount) {
        int heapCells = heapSnapshot.length;
        int segmentCells = Math.max(1, (heapCells + segmentCount - 1) / segmentCount);

        StringBuilder sb = new StringBuilder("[");
        for (int segmentId = 0; segmentId < segmentCount; segmentId++) {
            if (segmentId > 0)
                sb.append(",");

            int start = segmentId * segmentCells;
            int end = Math.min(start + segmentCells, heapCells);

            int used = 0;
            int free = 0;

            for (int i = start; i < end; i++) {
                if (heapSnapshot[i] == Heap.FREE) {
                    free++;
                } else {
                    used++;
                }
            }

            int total = Math.max(1, used + free);
            double usedPct = used * 100.0 / total;
            double freePct = 100.0 - usedPct;

            sb.append("{");
            sb.append("\"segmentId\":").append(segmentId).append(",");
            sb.append("\"mutexId\":").append(segmentId).append(",");
            sb.append("\"usedCells\":").append(used).append(",");
            sb.append("\"freeCells\":").append(free).append(",");
            sb.append("\"usedPercentage\":").append(fmt(usedPct)).append(",");
            sb.append("\"freePercentage\":").append(fmt(freePct));
            sb.append("}");
        }
        return sb.append("]").toString();
    }

    private static String threadEventsToJson(
            int[] threadIds,
            int[] segmentIds,
            int[] requestIds,
            int[] sizeBytes,
            String[] statuses,
            String[] messages) {

        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < threadIds.length; i++) {
            if (i > 0)
                sb.append(",");

            sb.append("{");
            sb.append("\"threadId\":").append(threadIds[i]).append(",");
            sb.append("\"segmentId\":").append(segmentIds[i]).append(",");
            sb.append("\"mutexId\":").append(segmentIds[i]).append(",");
            sb.append("\"requestId\":").append(requestIds[i]).append(",");
            sb.append("\"sizeBytes\":").append(sizeBytes[i]).append(",");
            sb.append("\"status\":\"").append(escape(statuses[i])).append("\",");
            sb.append("\"message\":\"").append(escape(messages[i])).append("\"");
            sb.append("}");
        }
        return sb.append("]").toString();
    }
    // ── Simulação sequencial com snapshots (endpoint simular-passos) ──────────

    private static String executarSimulacaoComPassos(
            int heapKb, int minBytes, int maxBytes, int totalRequests) {

        long inicio = System.nanoTime();

        WorstFit wf = new WorstFit(heapKb);
        GerenciadorLiberacao liberador = new GerenciadorLiberacao(wf);

        List<String> logs = new ArrayList<>();
        List<String> steps = new ArrayList<>();

        int generatedRequests = 0, attendedRequests = 0;
        int rejectedRequests = 0, removedVariables = 0, liberationCalls = 0;
        long totalAllocatedBytes = 0;

        int snapshotEvery = totalRequests > 400
                ? (int) Math.ceil(totalRequests / 400.0)
                : 1;

        for (int i = 1; i <= totalRequests; i++) {
            generatedRequests++;
            int requestId = i;
            int sizeBytes = minBytes + (int) (Math.random() * (maxBytes - minBytes + 1));

            int endereco = wf.allocate(sizeBytes, requestId);

            if (endereco == -1) {
                liberationCalls++;
                GerenciadorLiberacao.RelatorioLiberacao rel = liberador.executarLiberacaoRandomica(false);
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
            if (i > 0)
                sb.append(",");
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
        for (int v : heap)
            if (v != Heap.FREE)
                usedCells++;
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
            if (i > 0)
                sb.append(",");
            sb.append(heap[i]);
        }
        return sb.append("]").toString();
    }

    private static String logsToJson(List<String> logs) {
        List<String> copy;

        synchronized (logs) {
            int start = Math.max(0, logs.size() - 120);
            copy = new ArrayList<>(logs.subList(start, logs.size()));
        }

        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < copy.size(); i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append(copy.get(i));
        }

        return sb.append("]").toString();
    }

    private static String logEntry(String type, String message) {
        return "{\"id\":" + logId.getAndIncrement() + "," +
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
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
    }

    private static Map<String, String> parseQuery(URI uri) {
        Map<String, String> map = new HashMap<>();
        String q = uri.getRawQuery();
        if (q == null || q.isBlank())
            return map;
        for (String pair : q.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2)
                map.put(kv[0], kv[1]);
        }
        return map;
    }

    private static int parseInt(String v, int def) {
        if (v == null)
            return def;
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.US, "%.2f", v);
    }

    private static String escape(String v) {
        if (v == null)
            return "";
        return v.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "");
    }
}
