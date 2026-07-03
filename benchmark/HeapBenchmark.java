package benchmark;

import model.*;
import sync.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Benchmark sequencial vs paralelo do simulador de heap.
 *
 * ── Metodologia ──────────────────────────────────────────────────────────────
 * 1. Entradas idênticas: array de requisições gerado uma única vez, compartilhado
 *    por ambos os modos. Garante comparação justa.
 * 2. Zero E/S no caminho quente: nenhum print entre início e fim do cronômetro.
 * 3. Warmup: WARMUP_ROUNDS rodadas descartadas antes da medição (aquece o JIT).
 * 4. Múltiplas rodadas: MEASURE_ROUNDS medições; reporta mín/méd/máx.
 * 5. Cronômetro: System.nanoTime() — contador de hardware, imune a NTP.
 *
 * ── Semáforos utilizados (modo paralelo) ─────────────────────────────────────
 *   heapMutex     (BinarySemaphore) — já interno ao WorstFit; protege heap + free list
 *   queueMutex    (BinarySemaphore) — protege queueIndex (próxima requisição da fila)
 *   countersMutex (BinarySemaphore) — protege served[], rejected[], randoms[]
 *
 * Nenhum AtomicInteger ou synchronized é utilizado.
 *
 * ── Ponto de entrada para a API ──────────────────────────────────────────────
 * HeapApiServer chama executarComparativo(params) e recebe um BenchmarkResult
 * pronto para serializar em JSON. O benchmark não sabe que existe HTTP.
 */
public class HeapBenchmark {

    // ── Parâmetros fixos ──────────────────────────────────────────────────────
    private static final int    THREAD_COUNT      = 8;
    private static final int    WARMUP_ROUNDS     = 5;
    private static final int    MEASURE_ROUNDS    = 10;
    private static final double RANDOM_FREE_TARGET = 0.30;

    // =========================================================================
    // Tipos públicos
    // =========================================================================

    /** Parâmetros de entrada do benchmark — recebidos da API ou do main(). */
    public static final class BenchmarkParams {
        public final int heapKb;
        public final int totalRequests;
        public final int minBytes;
        public final int maxBytes;

        public BenchmarkParams(int heapKb, int totalRequests, int minBytes, int maxBytes) {
            this.heapKb        = heapKb;
            this.totalRequests = totalRequests;
            this.minBytes      = minBytes;
            this.maxBytes      = maxBytes;
        }
    }

    /**
     * Resultado completo do comparativo sequencial vs paralelo.
     * Preenchido em memória pelo benchmark; HeapApiServer serializa em JSON.
     */
    public static final class BenchmarkResult {
        // Parâmetros usados
        public final int heapKb, totalRequests, minBytes, maxBytes;
        public final int threadCount, warmupRounds, measureRounds;

        // Latência (ms)
        public final double seqLatencyMinMs, seqLatencyAvgMs, seqLatencyMaxMs;
        public final double parLatencyMinMs, parLatencyAvgMs, parLatencyMaxMs;

        // Throughput (req/s)
        public final long seqThroughputMin, seqThroughputAvg, seqThroughputMax;
        public final long parThroughputMin, parThroughputAvg, parThroughputMax;

        // Métricas da última rodada
        public final int seqServed, seqRejected, seqRandoms;
        public final int parServed, parRejected, parRandoms;

        // Speedup (> 1 = paralelo mais rápido)
        public final double latencySpeedup;
        public final double throughputSpeedup;

        BenchmarkResult(BenchmarkParams p, long[] seq, long[] par,
                        RoundResult seqLast, RoundResult parLast) {
            this.heapKb        = p.heapKb;
            this.totalRequests = p.totalRequests;
            this.minBytes      = p.minBytes;
            this.maxBytes      = p.maxBytes;
            this.threadCount   = THREAD_COUNT;
            this.warmupRounds  = WARMUP_ROUNDS;
            this.measureRounds = MEASURE_ROUNDS;

            seqLatencyMinMs = seq[0] / 1_000_000.0;
            seqLatencyAvgMs = seq[2] / 1_000_000.0;
            seqLatencyMaxMs = seq[1] / 1_000_000.0;
            parLatencyMinMs = par[0] / 1_000_000.0;
            parLatencyAvgMs = par[2] / 1_000_000.0;
            parLatencyMaxMs = par[1] / 1_000_000.0;

            seqThroughputMin = seq[3]; seqThroughputAvg = seq[5]; seqThroughputMax = seq[4];
            parThroughputMin = par[3]; parThroughputAvg = par[5]; parThroughputMax = par[4];

            seqServed = seqLast.served; seqRejected = seqLast.rejected; seqRandoms = seqLast.randoms;
            parServed = parLast.served; parRejected = parLast.rejected; parRandoms = parLast.randoms;

            latencySpeedup    = par[2] == 0 ? 0.0 : (double) seq[2] / par[2];
            throughputSpeedup = seq[5] == 0 ? 0.0 : (double) par[5] / seq[5];
        }
    }

    /** Resultado de uma única rodada — preenchido em memória, sem E/S. */
    static final class RoundResult {
        final long wallClockNs;
        final int  served, rejected, randoms;

        RoundResult(long wallClockNs, int served, int rejected, int randoms) {
            this.wallClockNs = wallClockNs;
            this.served      = served;
            this.rejected    = rejected;
            this.randoms     = randoms;
        }

        double throughputPerSec() {
            return wallClockNs == 0 ? 0 : served / (wallClockNs / 1_000_000_000.0);
        }
    }

    // =========================================================================
    // Ponto de entrada público — chamado pela API e pelo main()
    // =========================================================================

    /**
     * @deprecated Use main() ou implemente a chamada diretamente com os 3 métodos.
     * Este método foi removido porque usava os antigos runSequential/runParallel.
     */
    @Deprecated
    public static BenchmarkResult executarComparativo(BenchmarkParams params) {
        throw new UnsupportedOperationException(
            "Método removido. Use main(String[]) para 3 versões ou chame diretamente:\n" +
            "  - runSequentialUnsafe(requests, params)\n" +
            "  - runParallelSynchronized(requests, params)\n" +
            "  - runParallelPartitioned(requests, params)"
        );
    }

    // =========================================================================
    // Geração de entradas
    // =========================================================================

    /** Gera requisições uma única vez — ambos os modos recebem exatamente este array. */
    static Requisitor_Memoria[] generateRequests(BenchmarkParams p) {
        Requisitor_Memoria[] reqs = new Requisitor_Memoria[p.totalRequests];
        int range = p.maxBytes - p.minBytes;
        for (int i = 0; i < p.totalRequests; i++) {
            int size = p.minBytes + (range > 0 ? (int)(Math.random() * (range + 1)) : 0);
            reqs[i] = new Requisitor_Memoria(size);
        }
        return reqs;
    }

    // =========================================================================
    // Núcleo: processar uma requisição (sem E/S, sem lock próprio)
    // =========================================================================
    // Modo SEQUENCIAL — WorstFitUnsafe (sem sincronização)
    // =========================================================================

    static RoundResult runSequentialUnsafe(Requisitor_Memoria[] requests, BenchmarkParams p) {
        WorstFitUnsafe wf = new WorstFitUnsafe(new Heap((p.heapKb * 1024) / 4));
        GerenciadorLiberacaoUnsafe gerenciador = new GerenciadorLiberacaoUnsafe(wf);

        int served = 0, rejected = 0, randoms = 0;

        // ── início do cronômetro ──
        long start = System.nanoTime();

        for (Requisitor_Memoria req : requests) {
            int r = processRequestUnsafe(wf, gerenciador, req);
            if      (r ==  1) { served++;  }
            else if (r ==  2) { served++;  randoms++; }
            else if (r == -1) { rejected++; randoms++; }
        }

        long elapsed = System.nanoTime() - start;
        // ── fim do cronômetro ──

        return new RoundResult(elapsed, served, rejected, randoms);
    }

    static int processRequestUnsafe(WorstFitUnsafe wf, GerenciadorLiberacaoUnsafe gerenciador,
                                     Requisitor_Memoria req) {
        int result = wf.allocate(req.getSize(), req.getId());
        if (result >= 0) return 1;
        GerenciadorLiberacao.RelatorioLiberacao rel = gerenciador.executarLiberacaoRandomica();
        if (rel.getBlocosLiberados() == 0) return -1;
        result = wf.allocate(req.getSize(), req.getId());
        return result >= 0 ? 2 : -1;
    }

    // =========================================================================
    // Modo SEQUENCIAL (antigo — manter para compatibilidade com processRequest legado)
    // =========================================================================

    // =========================================================================
    // Agregação e relatório
    // =========================================================================

    /** [0]=minNs [1]=maxNs [2]=avgNs [3]=minTp [4]=maxTp [5]=avgTp */
    static long[] aggregate(RoundResult[] results) {
        long minNs = Long.MAX_VALUE, maxNs = Long.MIN_VALUE, sumNs = 0;
        long minTp = Long.MAX_VALUE, maxTp = Long.MIN_VALUE, sumTp = 0;
        for (RoundResult r : results) {
            minNs = Math.min(minNs, r.wallClockNs);
            maxNs = Math.max(maxNs, r.wallClockNs);
            sumNs += r.wallClockNs;
            long tp = (long) r.throughputPerSec();
            minTp = Math.min(minTp, tp); maxTp = Math.max(maxTp, tp); sumTp += tp;
        }
        return new long[]{ minNs, maxNs, sumNs / results.length,
                           minTp, maxTp, sumTp / results.length };
    }

    @FunctionalInterface
    interface BenchmarkRunner { RoundResult run(Requisitor_Memoria[] requests); }

    static RoundResult[] runWithWarmup(BenchmarkRunner runner, Requisitor_Memoria[] requests) {
        for (int i = 0; i < WARMUP_ROUNDS; i++) runner.run(requests);
        RoundResult[] results = new RoundResult[MEASURE_ROUNDS];
        for (int i = 0; i < MEASURE_ROUNDS; i++) results[i] = runner.run(requests);
        return results;
    }

    // =========================================================================
    // Main — teste standalone sem API
    // =========================================================================

    public static void main(String[] args) {
        int totalRequests = 50_000;  // padrão
        if (args.length > 0) {
            try {
                totalRequests = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                System.err.println("Uso: java benchmark.HeapBenchmark [totalRequests]");
                System.err.println("Exemplo: java benchmark.HeapBenchmark 50000");
                System.exit(1);
            }
        }
        BenchmarkParams params = new BenchmarkParams(64, totalRequests, 16, 256);

        System.out.println("╔═══════════════════════════════════════════════════════╗");
        System.out.println("║  BENCHMARK: 3 VERSÕES (Unsafe, Synchronized, Partitioned)║");
        System.out.println("╠═══════════════════════════════════════════════════════╣");
        System.out.printf( "║  Heap        : %-36d KB║%n", params.heapKb);
        System.out.printf( "║  Requisições : %-37d  ║%n", params.totalRequests);
        System.out.printf( "║  Tam. req    : %d - %-31d B ║%n", params.minBytes, params.maxBytes);
        System.out.printf( "║  Threads     : %-37d  ║%n", THREAD_COUNT);
        System.out.printf( "║  Warmup      : %-37d  ║%n", WARMUP_ROUNDS);
        System.out.printf( "║  Medições    : %-37d  ║%n", MEASURE_ROUNDS);
        System.out.println("╚═══════════════════════════════════════════════════════╝");
        System.out.println("\nGerando requisições e executando benchmarks...\n");

        // Gera requisições uma única vez para todas as 3 versões
        Requisitor_Memoria[] requests = generateRequests(params);

        System.out.println("▶ Versão 1: WorstFitUnsafe (Sequencial, sem sincronização)");
        RoundResult[] unsafeSeqResults = runWithWarmup(p -> runSequentialUnsafe(p, params), requests);
        long[] unsafeAgg = aggregate(unsafeSeqResults);

        System.out.println("▶ Versão 2: WorstFitSynchronized (Paralelo, 1 mutex)");
        RoundResult[] syncParResults = runWithWarmup(p -> runParallelSynchronized(p, params), requests);
        long[] syncAgg = aggregate(syncParResults);

        System.out.println("▶ Versão 3: WorstFitPartitioned (Paralelo, N mutexes)");
        RoundResult[] partParResults = runWithWarmup(p -> runParallelPartitioned(p, params), requests);
        long[] partAgg = aggregate(partParResults);

        printComparativeResults(
            unsafeAgg, unsafeSeqResults[unsafeSeqResults.length - 1],
            syncAgg, syncParResults[syncParResults.length - 1],
            partAgg, partParResults[partParResults.length - 1]
        );
    }

    static void printComparativeResults(
            long[] unsafeAgg, RoundResult unsafeLast,
            long[] syncAgg, RoundResult syncLast,
            long[] partAgg, RoundResult partLast) {

        System.out.println("\n┌─────────────────────────────────────────────────────────────────┐");
        System.out.println("│  VERSÃO 1: WorstFitUnsafe (Sequencial, sem mutex)              │");
        System.out.println("├──────────────────┬────────────────────────────────────────────┤");
        System.out.printf( "│  Latência média  │ %7.2f ms                                  │%n", unsafeAgg[2] / 1_000_000.0);
        System.out.printf( "│  Throughput méd  │ %,7d req/s                              │%n", unsafeAgg[5]);
        System.out.printf( "│  Atendidas       │ %-6d                                      │%n", unsafeLast.served);
        System.out.printf( "│  Rejeitadas      │ %-6d                                      │%n", unsafeLast.rejected);
        System.out.printf( "│  RANDOM acionado │ %-6d                                      │%n", unsafeLast.randoms);
        System.out.println("├─────────────────────────────────────────────────────────────────┤");
        System.out.println("│  VERSÃO 2: WorstFitSynchronized (Paralelo, 1 heapMutex)        │");
        System.out.println("├──────────────────┬────────────────────────────────────────────┤");
        System.out.printf( "│  Latência média  │ %7.2f ms                                  │%n", syncAgg[2] / 1_000_000.0);
        System.out.printf( "│  Throughput méd  │ %,7d req/s                              │%n", syncAgg[5]);
        System.out.printf( "│  Atendidas       │ %-6d                                      │%n", syncLast.served);
        System.out.printf( "│  Rejeitadas      │ %-6d                                      │%n", syncLast.rejected);
        System.out.printf( "│  RANDOM acionado │ %-6d                                      │%n", syncLast.randoms);
        System.out.println("├─────────────────────────────────────────────────────────────────┤");
        System.out.println("│  VERSÃO 3: WorstFitPartitioned (Paralelo, N mutexes)          │");
        System.out.println("├──────────────────┬────────────────────────────────────────────┤");
        System.out.printf( "│  Latência média  │ %7.2f ms                                  │%n", partAgg[2] / 1_000_000.0);
        System.out.printf( "│  Throughput méd  │ %,7d req/s                              │%n", partAgg[5]);
        System.out.printf( "│  Atendidas       │ %-6d                                      │%n", partLast.served);
        System.out.printf( "│  Rejeitadas      │ %-6d                                      │%n", partLast.rejected);
        System.out.printf( "│  RANDOM acionado │ %-6d                                      │%n", partLast.randoms);
        System.out.println("╠═════════════════════════════════════════════════════════════════╣");

        double speedupSync  = (double) unsafeAgg[2] / syncAgg[2];
        double speedupPart  = (double) unsafeAgg[2] / partAgg[2];
        double speedupSyncVsPart = (double) syncAgg[2] / partAgg[2];

        System.out.printf( "║ Speedup Unsafe→Synchronized: %6.2fx  %-22s         ║%n",
            speedupSync, speedupSync < 1 ? "(Sync mais lento)" : "(Sync mais rápido)");
        System.out.printf( "║ Speedup Unsafe→Partitioned  : %6.2fx  %-22s         ║%n",
            speedupPart, speedupPart < 1 ? "(Part mais lento)" : "(Part mais rápido)");
        System.out.printf( "║ Speedup Synchronized→Partitioned: %6.2fx  %-16s         ║%n",
            speedupSyncVsPart, speedupSyncVsPart < 1 ? "(Part mais lento)" : "(Part mais rápido)");
        System.out.println("╚═════════════════════════════════════════════════════════════════╝");
    }

    static RoundResult runParallelSynchronized(Requisitor_Memoria[] requests, BenchmarkParams p) {
        WorstFitSynchronized wf = new WorstFitSynchronized(new Heap((p.heapKb * 1024) / 4));
        GerenciadorLiberacaoSynchronized gerenciador = new GerenciadorLiberacaoSynchronized(wf);

        Semaphore.BinarySemaphore queueMutex    = new Semaphore.BinarySemaphore();
        Semaphore.BinarySemaphore countersMutex = new Semaphore.BinarySemaphore();

        int[] queueIndex = {0};
        int[] served   = {0};
        int[] rejected = {0};
        int[] randoms  = {0};

        Thread[] threads = new Thread[THREAD_COUNT];
        for (int t = 0; t < THREAD_COUNT; t++) {
            threads[t] = new Thread(() -> {
                while (true) {
                    Requisitor_Memoria req;
                    try {
                        queueMutex.acquire();
                        try {
                            if (queueIndex[0] >= requests.length) return;
                            req = requests[queueIndex[0]++];
                        } finally {
                            queueMutex.release();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt(); return;
                    }

                    int r = processRequestSynchronized(wf, gerenciador, req);

                    try {
                        countersMutex.acquire();
                        try {
                            if      (r ==  1) { served[0]++;  }
                            else if (r ==  2) { served[0]++;  randoms[0]++; }
                            else if (r == -1) { rejected[0]++; randoms[0]++; }
                        } finally {
                            countersMutex.release();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt(); return;
                    }
                }
            }, "sync-worker-" + t);
        }

        long start = System.nanoTime();
        for (Thread t : threads) t.start();
        for (Thread t : threads) {
            try { t.join(); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        long elapsed = System.nanoTime() - start;

        return new RoundResult(elapsed, served[0], rejected[0], randoms[0]);
    }

    static int processRequestSynchronized(WorstFitSynchronized wf, GerenciadorLiberacaoSynchronized gerenciador,
                                           Requisitor_Memoria req) {
        int result = wf.allocate(req.getSize(), req.getId());
        if (result >= 0) return 1;
        GerenciadorLiberacao.RelatorioLiberacao rel = gerenciador.executarLiberacaoRandomica();
        if (rel.getBlocosLiberados() == 0) return -1;
        result = wf.allocate(req.getSize(), req.getId());
        return result >= 0 ? 2 : -1;
    }

    static RoundResult runParallelPartitioned(Requisitor_Memoria[] requests, BenchmarkParams p) {
        WorstFitPartitioned wf = new WorstFitPartitioned(new Heap((p.heapKb * 1024) / 4));
        GerenciadorLiberacaoPartitioned gerenciador = new GerenciadorLiberacaoPartitioned(wf);

        Semaphore.BinarySemaphore queueMutex    = new Semaphore.BinarySemaphore();
        Semaphore.BinarySemaphore countersMutex = new Semaphore.BinarySemaphore();

        int[] queueIndex = {0};
        int[] served   = {0};
        int[] rejected = {0};
        int[] randoms  = {0};

        Thread[] threads = new Thread[THREAD_COUNT];
        for (int t = 0; t < THREAD_COUNT; t++) {
            threads[t] = new Thread(() -> {
                while (true) {
                    Requisitor_Memoria req;
                    try {
                        queueMutex.acquire();
                        try {
                            if (queueIndex[0] >= requests.length) return;
                            req = requests[queueIndex[0]++];
                        } finally {
                            queueMutex.release();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt(); return;
                    }

                    int r = processRequestPartitioned(wf, gerenciador, req);

                    try {
                        countersMutex.acquire();
                        try {
                            if      (r ==  1) { served[0]++;  }
                            else if (r ==  2) { served[0]++;  randoms[0]++; }
                            else if (r == -1) { rejected[0]++; randoms[0]++; }
                        } finally {
                            countersMutex.release();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt(); return;
                    }
                }
            }, "part-worker-" + t);
        }

        long start = System.nanoTime();
        for (Thread t : threads) t.start();
        for (Thread t : threads) {
            try { t.join(); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        long elapsed = System.nanoTime() - start;

        return new RoundResult(elapsed, served[0], rejected[0], randoms[0]);
    }

    static int processRequestPartitioned(WorstFitPartitioned wf, GerenciadorLiberacaoPartitioned gerenciador,
                                          Requisitor_Memoria req) {
        int result = wf.allocate(req.getSize(), req.getId());
        if (result >= 0) return 1;
        GerenciadorLiberacao.RelatorioLiberacao rel = gerenciador.executarLiberacaoRandomica();
        if (rel.getBlocosLiberados() == 0) return -1;
        result = wf.allocate(req.getSize(), req.getId());
        return result >= 0 ? 2 : -1;
    }
}
