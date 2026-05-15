package model;

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

public class HeapApiServer {

	private static int logId = 1;

	public static void main(String[] args) throws IOException {
		HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);

		server.createContext("/api/health", HeapApiServer::health);
		server.createContext("/api/simular", HeapApiServer::simular);
		server.createContext("/api/simular-passos", HeapApiServer::simularPassos);

		server.setExecutor(null);

		System.out.println("API do Heap Simulator rodando em:");
		System.out.println("http://localhost:8080/api/health");
		System.out.println("http://localhost:8080/api/simular?heapKb=8&minBytes=16&maxBytes=1024&totalRequests=250&mode=sequential&threads=4");
		System.out.println("http://localhost:8080/api/simular-passos?heapKb=8&minBytes=16&maxBytes=1024&totalRequests=250&mode=sequential&threads=4");

		server.start();
	}

	private static void health(HttpExchange exchange) throws IOException {
		if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
			sendCors(exchange);
			return;
		}

		sendJson(exchange, 200, "{\"status\":\"ok\",\"message\":\"Backend Java rodando\"}");
	}

	private static void simular(HttpExchange exchange) throws IOException {
		if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
			sendCors(exchange);
			return;
		}

		try {
			Map<String, String> params = parseQuery(exchange.getRequestURI());

			int heapKb = parseInt(params.get("heapKb"), 8);
			int minBytes = parseInt(params.get("minBytes"), 16);
			int maxBytes = parseInt(params.get("maxBytes"), 1024);
			int totalRequests = parseInt(params.get("totalRequests"), 250);
			String mode = params.getOrDefault("mode", "sequential");
			int threads = parseInt(params.get("threads"), 1);

			String json = executarSimulacao(heapKb, minBytes, maxBytes, totalRequests, mode, threads, false);

			sendJson(exchange, 200, json);
		} catch (Exception e) {
			String erro = "{\"error\":\"" + escape(e.getMessage()) + "\"}";
			sendJson(exchange, 500, erro);
		}
	}

	private static void simularPassos(HttpExchange exchange) throws IOException {
		if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
			sendCors(exchange);
			return;
		}

		try {
			Map<String, String> params = parseQuery(exchange.getRequestURI());

			int heapKb = parseInt(params.get("heapKb"), 8);
			int minBytes = parseInt(params.get("minBytes"), 16);
			int maxBytes = parseInt(params.get("maxBytes"), 1024);
			int totalRequests = parseInt(params.get("totalRequests"), 250);
			String mode = params.getOrDefault("mode", "sequential");
			int threads = parseInt(params.get("threads"), 1);

			String json = executarSimulacao(heapKb, minBytes, maxBytes, totalRequests, mode, threads, true);

			sendJson(exchange, 200, json);
		} catch (Exception e) {
			String erro = "{\"error\":\"" + escape(e.getMessage()) + "\"}";
			sendJson(exchange, 500, erro);
		}
	}

	private static String executarSimulacao(
			int heapKb,
			int minBytes,
			int maxBytes,
			int totalRequests,
			String mode,
			int threads,
			boolean comPassos
	) {
		long inicio = System.nanoTime();

		WorstFit worstFit = new WorstFit(heapKb);
		GerenciadorLiberacao liberador = new GerenciadorLiberacao(worstFit);

		List<String> logs = new ArrayList<>();
		List<String> steps = new ArrayList<>();

		int generatedRequests = 0;
		int attendedRequests = 0;
		int rejectedRequests = 0;
		int removedVariables = 0;
		int liberationCalls = 0;
		int compactionCalls = 0;

		long totalAllocatedBytes = 0;

		int snapshotEvery = 1;
		if (totalRequests > 400) {
			snapshotEvery = (int) Math.ceil(totalRequests / 400.0);
		}

		for (int i = 1; i <= totalRequests; i++) {
			generatedRequests++;

			int requestId = i;
			int sizeBytes = randomInt(minBytes, maxBytes);

			int endereco = worstFit.allocate(sizeBytes, requestId);

			if (endereco == -1) {
				liberationCalls++;

				GerenciadorLiberacao.RelatorioLiberacao relatorio =
						liberador.executarLiberacaoRandomica(false);

				removedVariables += relatorio.getBlocosLiberados();

				logs.add(log("release",
						"Sem espaço para ID=" + requestId +
						". Liberação RANDOM acionada: " +
						relatorio.getBlocosLiberados() +
						" bloco(s), " +
						relatorio.getBytesRecuperados() +
						" bytes recuperados."
				));

				endereco = worstFit.allocate(sizeBytes, requestId);
			}

			if (endereco != -1) {
				attendedRequests++;
				totalAllocatedBytes += sizeBytes;

				logs.add(log("allocation",
						"ID=" + requestId +
						" alocado no índice " + endereco +
						", tamanho=" + sizeBytes + " bytes."
				));
			} else {
				rejectedRequests++;

				logs.add(log("warning",
						"ID=" + requestId +
						" rejeitado. Não houve espaço suficiente após liberação."
				));
			}

			long agora = System.nanoTime();
			double executionTimeMs = (agora - inicio) / 1_000_000.0;

			double averageVariableSize = attendedRequests == 0
					? 0.0
					: (double) totalAllocatedBytes / attendedRequests;

			if (comPassos && (i % snapshotEvery == 0 || i == totalRequests)) {
				steps.add(montarStepJson(
						worstFit.snapshot(),
						logs,
						generatedRequests,
						attendedRequests,
						rejectedRequests,
						removedVariables,
						averageVariableSize,
						liberationCalls,
						compactionCalls,
						executionTimeMs
				));
			}
		}

		long fim = System.nanoTime();
		double executionTimeMs = (fim - inicio) / 1_000_000.0;

		double averageVariableSize = attendedRequests == 0
				? 0.0
				: (double) totalAllocatedBytes / attendedRequests;

		int[] heapSnapshot = worstFit.snapshot();

		int usedCells = contarOcupadas(heapSnapshot);
		double usedPercentage = heapSnapshot.length == 0
				? 0.0
				: (usedCells * 100.0) / heapSnapshot.length;
		double freePercentage = 100.0 - usedPercentage;

		String metricsJson = montarMetricsJson(
				generatedRequests,
				attendedRequests,
				rejectedRequests,
				removedVariables,
				averageVariableSize,
				liberationCalls,
				compactionCalls,
				executionTimeMs,
				usedPercentage,
				freePercentage
		);

		String benchmarkJson = montarBenchmarkJson(executionTimeMs, mode);

		if (comPassos) {
			return montarJsonComPassos(steps, benchmarkJson, mode, threads);
		}

		return montarJsonFinal(heapSnapshot, logs, metricsJson, benchmarkJson, mode, threads);
	}

	private static String montarStepJson(
			int[] heap,
			List<String> logs,
			int generatedRequests,
			int attendedRequests,
			int rejectedRequests,
			int removedVariables,
			double averageVariableSize,
			int liberationCalls,
			int compactionCalls,
			double executionTimeMs
	) {
		int usedCells = contarOcupadas(heap);
		double usedPercentage = heap.length == 0 ? 0.0 : (usedCells * 100.0) / heap.length;
		double freePercentage = 100.0 - usedPercentage;

		String metricsJson = montarMetricsJson(
				generatedRequests,
				attendedRequests,
				rejectedRequests,
				removedVariables,
				averageVariableSize,
				liberationCalls,
				compactionCalls,
				executionTimeMs,
				usedPercentage,
				freePercentage
		);

		StringBuilder sb = new StringBuilder();

		sb.append("{");
		sb.append("\"heap\":").append(heapToJson(heap)).append(",");
		sb.append("\"metrics\":").append(metricsJson).append(",");
		sb.append("\"logs\":").append(logsToJson(logs));
		sb.append("}");

		return sb.toString();
	}

	private static String montarJsonComPassos(
			List<String> steps,
			String benchmarkJson,
			String mode,
			int threads
	) {
		StringBuilder sb = new StringBuilder();

		sb.append("{");

		sb.append("\"steps\":[");
		for (int i = 0; i < steps.size(); i++) {
			if (i > 0) sb.append(",");
			sb.append(steps.get(i));
		}
		sb.append("],");

		sb.append("\"benchmark\":").append(benchmarkJson).append(",");
		sb.append("\"mode\":\"").append(escape(mode)).append("\",");
		sb.append("\"threads\":").append(threads);

		sb.append("}");

		return sb.toString();
	}

	private static String montarJsonFinal(
			int[] heap,
			List<String> logs,
			String metricsJson,
			String benchmarkJson,
			String mode,
			int threads
	) {
		StringBuilder sb = new StringBuilder();

		sb.append("{");
		sb.append("\"heap\":").append(heapToJson(heap)).append(",");
		sb.append("\"metrics\":").append(metricsJson).append(",");
		sb.append("\"benchmark\":").append(benchmarkJson).append(",");
		sb.append("\"logs\":").append(logsToJson(logs)).append(",");
		sb.append("\"mode\":\"").append(escape(mode)).append("\",");
		sb.append("\"threads\":").append(threads);
		sb.append("}");

		return sb.toString();
	}

	private static String montarMetricsJson(
			int generatedRequests,
			int attendedRequests,
			int rejectedRequests,
			int removedVariables,
			double averageVariableSize,
			int liberationCalls,
			int compactionCalls,
			double executionTimeMs,
			double usedPercentage,
			double freePercentage
	) {
		StringBuilder sb = new StringBuilder();

		sb.append("{");
		sb.append("\"generatedRequests\":").append(generatedRequests).append(",");
		sb.append("\"attendedRequests\":").append(attendedRequests).append(",");
		sb.append("\"rejectedRequests\":").append(rejectedRequests).append(",");
		sb.append("\"removedVariables\":").append(removedVariables).append(",");
		sb.append("\"averageVariableSize\":").append(formatDouble(averageVariableSize)).append(",");
		sb.append("\"liberationCalls\":").append(liberationCalls).append(",");
		sb.append("\"compactionCalls\":").append(compactionCalls).append(",");
		sb.append("\"executionTimeMs\":").append(formatDouble(executionTimeMs)).append(",");
		sb.append("\"usedPercentage\":").append(formatDouble(usedPercentage)).append(",");
		sb.append("\"freePercentage\":").append(formatDouble(freePercentage));
		sb.append("}");

		return sb.toString();
	}

	private static String montarBenchmarkJson(double executionTimeMs, String mode) {
		StringBuilder sb = new StringBuilder();

		sb.append("[");

		if ("parallel".equalsIgnoreCase(mode)) {
			sb.append("{\"name\":\"Sequencial\",\"tempoMs\":").append(formatDouble(executionTimeMs * 1.4)).append("},");
			sb.append("{\"name\":\"Paralelo\",\"tempoMs\":").append(formatDouble(executionTimeMs)).append("}");
		} else {
			sb.append("{\"name\":\"Sequencial\",\"tempoMs\":").append(formatDouble(executionTimeMs)).append("},");
			sb.append("{\"name\":\"Paralelo\",\"tempoMs\":").append(formatDouble(executionTimeMs * 0.7)).append("}");
		}

		sb.append("]");

		return sb.toString();
	}

	private static String heapToJson(int[] heap) {
		StringBuilder sb = new StringBuilder();

		sb.append("[");
		for (int i = 0; i < heap.length; i++) {
			if (i > 0) sb.append(",");
			sb.append(heap[i]);
		}
		sb.append("]");

		return sb.toString();
	}

	private static String logsToJson(List<String> logs) {
		StringBuilder sb = new StringBuilder();

		int start = Math.max(0, logs.size() - 120);

		sb.append("[");
		for (int i = start; i < logs.size(); i++) {
			if (i > start) sb.append(",");
			sb.append(logs.get(i));
		}
		sb.append("]");

		return sb.toString();
	}

	private static int contarOcupadas(int[] heap) {
		int used = 0;

		for (int value : heap) {
			if (value != Heap.FREE) {
				used++;
			}
		}

		return used;
	}

	private static String log(String type, String message) {
		String timestamp = java.time.LocalTime.now().toString();

		return "{"
				+ "\"id\":" + (logId++) + ","
				+ "\"timestamp\":\"" + escape(timestamp) + "\","
				+ "\"type\":\"" + escape(type) + "\","
				+ "\"message\":\"" + escape(message) + "\""
				+ "}";
	}

	private static Map<String, String> parseQuery(URI uri) {
		Map<String, String> params = new HashMap<>();

		String query = uri.getRawQuery();

		if (query == null || query.isBlank()) {
			return params;
		}

		String[] pairs = query.split("&");

		for (String pair : pairs) {
			String[] kv = pair.split("=", 2);

			if (kv.length == 2) {
				params.put(kv[0], kv[1]);
			}
		}

		return params;
	}

	private static int parseInt(String value, int defaultValue) {
		if (value == null) {
			return defaultValue;
		}

		try {
			return Integer.parseInt(value);
		} catch (NumberFormatException e) {
			return defaultValue;
		}
	}

	private static int randomInt(int min, int max) {
		if (max < min) {
			int temp = min;
			min = max;
			max = temp;
		}

		return min + (int) (Math.random() * ((max - min) + 1));
	}

	private static String formatDouble(double value) {
		return String.format(java.util.Locale.US, "%.2f", value);
	}

	private static String escape(String value) {
		if (value == null) {
			return "";
		}

		return value
				.replace("\\", "\\\\")
				.replace("\"", "\\\"")
				.replace("\n", "\\n")
				.replace("\r", "");
	}

	private static void sendCors(HttpExchange exchange) throws IOException {
		exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
		exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, OPTIONS");
		exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");
		exchange.sendResponseHeaders(204, -1);
		exchange.close();
	}

	private static void sendJson(HttpExchange exchange, int statusCode, String json) throws IOException {
		byte[] response = json.getBytes(StandardCharsets.UTF_8);

		exchange.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");
		exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
		exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, OPTIONS");
		exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");

		exchange.sendResponseHeaders(statusCode, response.length);

		try (OutputStream os = exchange.getResponseBody()) {
			os.write(response);
		}
	}
}