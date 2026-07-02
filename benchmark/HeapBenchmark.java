package benchmark;

import model.Heap;
import model.GerenciadorLiberacao;
import model.Requisitor_Memoria;
import model.WorstFit;
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
     * Executa o comparativo completo: warmup + medição sequencial e paralela.
     * Retorna BenchmarkResult pronto para serialização.
     *
     * Este método é thread-safe: pode ser chamado por múltiplas requisições HTTP
     * simultâneas (cada chamada cria seus próprios objetos internos).
     */
    public static BenchmarkResult executarComparativo(BenchmarkParams params) {
        Requisitor_Memoria[] requests = generateRequests(params);

        RoundResult[] seqResults = runWithWarmup(p -> runSequential(p, params), requests);
        RoundResult[] parResults = runWithWarmup(p -> runParallel(p, params),   requests);

        long[] seqAgg = aggregate(seqResults);
        long[] parAgg = aggregate(parResults);

        return new BenchmarkResult(
            params, seqAgg, parAgg,
            seqResults[seqResults.length - 1],
            parResults[parResults.length - 1]
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

    /**
     * Tenta alocar; se falhar, aciona RANDOM e tenta de novo.
     * Retorna: 1=sucesso direto, 2=sucesso após RANDOM, -1=rejeitada.
     *
     * IMPORTANTE: chamado FORA de qualquer lock.
     * deallocate() dentro de executarLiberacaoRandomica() adquire heapMutex
     * internamente no WorstFit. allocate() também o faz. Não há reentrada.
     */
    static int processRequest(WorstFit wf, GerenciadorLiberacao gerenciador,
                               Requisitor_Memoria req) {
        int result = wf.allocate(req.getSize(), req.getId());
        if (result >= 0) return 1;

        // Falhou: aciona RANDOM (fora de qualquer lock — ver Javadoc do GerenciadorLiberacao)
        GerenciadorLiberacao.RelatorioLiberacao rel = gerenciador.executarLiberacaoRandomica();
        if (rel.getBlocosLiberados() == 0) return -1;

        result = wf.allocate(req.getSize(), req.getId());
        return result >= 0 ? 2 : -1;
    }

    // =========================================================================
    // Modo SEQUENCIAL — thread única, sem overhead de sincronização
    // =========================================================================

    static RoundResult runSequential(Requisitor_Memoria[] requests, BenchmarkParams p) {
        WorstFit             wf         = new WorstFit(new Heap((p.heapKb * 1024) / 4));
        GerenciadorLiberacao gerenciador = new GerenciadorLiberacao(wf);

        int served = 0, rejected = 0, randoms = 0;

        // ── início do cronômetro ──
        long start = System.nanoTime();

        for (Requisitor_Memoria req : requests) {
            int r = processRequest(wf, gerenciador, req);
            if      (r ==  1) { served++;  }
            else if (r ==  2) { served++;  randoms++; }
            else if (r == -1) { rejected++; randoms++; }
        }

        long elapsed = System.nanoTime() - start;
        // ── fim do cronômetro ──

        return new RoundResult(elapsed, served, rejected, randoms);
    }

    // =========================================================================
    // Modo PARALELO — 8 threads, 3 semáforos binários
    // =========================================================================

    static RoundResult runParallel(Requisitor_Memoria[] requests, BenchmarkParams p) {
        WorstFit             wf         = new WorstFit(new Heap((p.heapKb * 1024) / 4));
        GerenciadorLiberacao gerenciador = new GerenciadorLiberacao(wf);

        // ── Três regiões críticas — três mutexes independentes ────────────────
        // heapMutex: já interno ao WorstFit (protege heap + free list)
        // queueMutex: protege o índice da fila compartilhada
        // countersMutex: protege os contadores de resultado
        Semaphore.BinarySemaphore queueMutex    = new Semaphore.BinarySemaphore();
        Semaphore.BinarySemaphore countersMutex = new Semaphore.BinarySemaphore();
        // ─────────────────────────────────────────────────────────────────────

        // Fila protegida por queueMutex (int[] para captura em lambda)
        int[] queueIndex = {0};

        // Contadores protegidos por countersMutex
        int[] served   = {0};
        int[] rejected = {0};
        int[] randoms  = {0};
        
        // Debug: rastrear paralelismo
        AtomicInteger maxActiveThreads = new AtomicInteger(0);

        Thread[] threads = new Thread[THREAD_COUNT];
        AtomicInteger activeThreads = new AtomicInteger(0);  // debug: contar threads simultâneas
        for (int t = 0; t < THREAD_COUNT; t++) {
            threads[t] = new Thread(() -> {
                while (true) {

                    // ── RC 1: pegar próxima requisição da fila ────────────────
                    Requisitor_Memoria req;
                    try {
                        queueMutex.acquire();               // P(queueMutex)
                        try {
                            if (queueIndex[0] >= requests.length) return;
                            req = requests[queueIndex[0]++];
                        } finally {
                            queueMutex.release();           // V(queueMutex)
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt(); return;
                    }

                    // ── RC 2: alocar na heap (mutex interno ao WorstFit) ──────
                    activeThreads.incrementAndGet();
                    int active = activeThreads.get();
                    if (active > maxActiveThreads.get()) {
                        maxActiveThreads.set(active);
                    }
                    // processRequest chama wf.allocate() e gerenciador.executarLiberacaoRandomica()
                    // ambos adquirem/liberam heapMutex internamente — sem reentrada
                    int r = processRequest(wf, gerenciador, req);
                    activeThreads.decrementAndGet();

                    // ── RC 3: atualizar contadores ────────────────────────────
                    try {
                        countersMutex.acquire();            // P(countersMutex)
                        try {
                            if      (r ==  1) { served[0]++;  }
                            else if (r ==  2) { served[0]++;  randoms[0]++; }
                            else if (r == -1) { rejected[0]++; randoms[0]++; }
                        } finally {
                            countersMutex.release();        // V(countersMutex)
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt(); return;
                    }
                }
            }, "heap-worker-" + t);
        }

        // ── Cronômetro: ANTES de lançar as threads ────────────────────────────
        long start = System.nanoTime();

        for (Thread t : threads) t.start();
        for (Thread t : threads) {
            try { t.join(); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }

        long elapsed = System.nanoTime() - start;
        // ── Cronômetro: APÓS todas as threads terminarem ──────────────────────

        // Debug: mostrar paralelismo
        System.err.println("[DEBUG PARALELO] Máximo de threads simultâneos: " + maxActiveThreads.get() + "/" + THREAD_COUNT);

        return new RoundResult(elapsed, served[0], rejected[0], randoms[0]);
    }

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
        System.out.println("║      BENCHMARK: HEAP SEQUENCIAL vs PARALELO           ║");
        System.out.println("╠═══════════════════════════════════════════════════════╣");
        System.out.printf( "║  Heap        : %-36d KB║%n", params.heapKb);
        System.out.printf( "║  Requisições : %-37d  ║%n", params.totalRequests);
        System.out.printf( "║  Tam. req    : %d - %-31d B ║%n", params.minBytes, params.maxBytes);
        System.out.printf( "║  Threads     : %-37d  ║%n", THREAD_COUNT);
        System.out.printf( "║  Warmup      : %-37d  ║%n", WARMUP_ROUNDS);
        System.out.printf( "║  Medições    : %-37d  ║%n", MEASURE_ROUNDS);
        System.out.println("╚═══════════════════════════════════════════════════════╝");
        System.out.println("\nGerando requisições e executando benchmark...\n");

        BenchmarkResult r = executarComparativo(params);

        System.out.println("┌────────────────────────────────────────────────────────────┐");
        System.out.println("│                    SEQUENCIAL                              │");
        System.out.println("├─────────────────────────┬──────────────────────────────────┤");
        System.out.printf( "│  Latência  mín/méd/máx  │ %7.2f / %7.2f / %7.2f ms   │%n",
            r.seqLatencyMinMs, r.seqLatencyAvgMs, r.seqLatencyMaxMs);
        System.out.printf( "│  Throughput mín/méd/máx │ %,7d / %,7d / %,7d req/s│%n",
            r.seqThroughputMin, r.seqThroughputAvg, r.seqThroughputMax);
        System.out.printf( "│  Atendidas / Rejeitadas │ %-6d / %-3d                     │%n", r.seqServed, r.seqRejected);
        System.out.printf( "│  RANDOM acionado        │ %-4d vezes                       │%n",  r.seqRandoms);
        System.out.println("├────────────────────────────────────────────────────────────┤");
        System.out.println("│                     PARALELO                               │");
        System.out.println("├─────────────────────────┬──────────────────────────────────┤");
        System.out.printf( "│  Latência  mín/méd/máx  │ %7.2f / %7.2f / %7.2f ms   │%n",
            r.parLatencyMinMs, r.parLatencyAvgMs, r.parLatencyMaxMs);
        System.out.printf( "│  Throughput mín/méd/máx │ %,7d / %,7d / %,7d req/s│%n",
            r.parThroughputMin, r.parThroughputAvg, r.parThroughputMax);
        System.out.printf( "│  Atendidas / Rejeitadas │ %-6d / %-3d                     │%n", r.parServed, r.parRejected);
        System.out.printf( "│  RANDOM acionado        │ %-4d vezes                       │%n",  r.parRandoms);
        System.out.println("╠════════════════════════════════════════════════════════════╣");
        System.out.printf( "║  Speedup latência   : %5.2fx  %-24s     ║%n",
            r.latencySpeedup,
            r.latencySpeedup > 1 ? "(paralelo mais rápido)" : "(sequencial mais rápido)");
        System.out.printf( "║  Speedup throughput : %5.2fx  %-24s     ║%n",
            r.throughputSpeedup,
            r.throughputSpeedup > 1 ? "(paralelo mais rápido)" : "(sequencial mais rápido)");
        System.out.println("╠════════════════════════════════════════════════════════════╣");
        System.out.println("║  speedup < 1 = overhead de contenção > ganho de            ║");
        System.out.println("║  paralelismo (esperado quando RC domina o tempo)           ║");
        System.out.println("╚════════════════════════════════════════════════════════════╝");
    }
}
