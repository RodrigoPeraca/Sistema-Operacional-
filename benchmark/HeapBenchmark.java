package benchmark;

import model.*;
import sync.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Benchmark científico: WorstFitUnsafe vs WorstFitSynchronized vs
 * WorstFitPartitioned.
 *
 * ═══════════════════════════════════════════════════════════════════════════════
 * METODOLOGIA
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * 1. ENTRADAS IDÊNTICAS
 * As requisições são geradas UMA ÚNICA VEZ antes do benchmark e compartilhadas
 * entre todas as versões. Garante que diferenças nos resultados refletem apenas
 * o algoritmo, não variação nos dados de entrada.
 *
 * 2. ZERO E/S NO CAMINHO QUENTE
 * Nenhuma operação de I/O entre início e fim do cronômetro. Prints e relatórios
 * ocorrem apenas após System.nanoTime() registrar o fim.
 *
 * 3. WARMUP OBRIGATÓRIO (JIT)
 * WARMUP_ROUNDS rodadas descartadas antes da medição. Garante que o JIT compile
 * e otimize os caminhos quentes antes de medir.
 *
 * 4. MÚLTIPLAS RODADAS COM AGREGAÇÃO ESTATÍSTICA
 * MEASURE_ROUNDS rodadas medidas. Reporta mínimo, média e máximo para detectar
 * variância. Média é a métrica principal para comparação.
 *
 * 5. CRONÔMETRO: System.nanoTime()
 * Baseado no contador de hardware (TSC no x86). Imune a ajustes de NTP,
 * resolução de nanosegundos.
 *
 * 6. FILA DE REQUISIÇÕES SEM MUTEX (AtomicInteger)
 * PROBLEMA ANTERIOR: queueMutex (BinarySemaphore com spin+sleep) adicionava
 * latência artificial a cada requisição — cada thread aguardava até 1ms para
 * pegar o próximo índice, distorcendo completamente a medição.
 * SOLUÇÃO: AtomicInteger com getAndIncrement() — operação CAS lock-free.
 * Justificativa: a fila não é parte do algoritmo de heap; deve ser invisível
 * na medição. AtomicInteger é a primitiva correta para contadores
 * compartilhados
 * de baixa contenção.
 *
 * 7. CONTADORES POR THREAD (SEM MUTEX)
 * PROBLEMA ANTERIOR: countersMutex adicionava 1 aquisição de mutex por
 * requisição após o processamento, contaminando a medição.
 * SOLUÇÃO: cada thread mantém seus próprios contadores locais (int[]). Ao
 * final,
 * o thread principal agrega via join(). Zero contenção nos contadores.
 *
 * 8. DIVISÃO ESTÁTICA DAS REQUISIÇÕES (Partitioned)
 * Cada thread recebe um bloco fixo de requisições = total / PART_THREAD_COUNT.
 * Elimina completamente a disputa pela fila no modo Partitioned.
 * Combina com afinidade por segmento: thread-i processa bloco-i e aloca em
 * seg-i.
 *
 * 9. COMPARAÇÕES VÁLIDAS
 * - Unsafe (1 thread) vs Synchronized (N threads, 1 mutex):
 * mede overhead de sincronização. VÁLIDA.
 * - Synchronized (N threads, 1 mutex) vs Partitioned (N threads, N mutexes):
 * mede ganho do particionamento com mesmo número de threads. VÁLIDA.
 * - Unsafe vs Partitioned: REMOVIDA dos speedups principais.
 * Motivo: compara 1 thread vs N threads — diferença de threads confunde
 * a interpretação. Mantida apenas como referência.
 *
 * 10. ESCALABILIDADE AUTOMÁTICA
 * main() executa automaticamente múltiplas configurações de heap e requisições,
 * gerando tabela com throughput, speedup e eficiência paralela.
 *
 * ═══════════════════════════════════════════════════════════════════════════════
 * SEMÁFOROS DO PROJETO (preservados onde fazem sentido)
 * ═══════════════════════════════════════════════════════════════════════════════
 * heapMutex (BinarySemaphore) — interno ao WorstFitSynchronized: protege heap
 * segMutex[] (BinarySemaphore) — internos ao WorstFitPartitioned: por segmento
 * AtomicInteger para fila e contadores de benchmark — NÃO são parte do
 * algoritmo
 */
public class HeapBenchmark {

    // ── Configuração do benchmark ─────────────────────────────────────────────
    static final int SYNC_THREAD_COUNT = 4; // Synchronized
    static final int PART_THREAD_COUNT = WorstFitPartitioned.NUM_SEGMENTS; // Partitioned = 4
    static final int WARMUP_ROUNDS = 5;
    static final int MEASURE_ROUNDS = 10;

    // ── Configurações de escalabilidade ──────────────────────────────────────
    private static final int[] HEAP_SIZES_KB = { 64, 256, 1024 };
    private static final int[] REQUEST_COUNTS = { 10_000, 50_000, 100_000 };

    // =========================================================================
    // Tipos públicos
    // =========================================================================

    public static final class BenchmarkParams {
        public final int heapKb, totalRequests, minBytes, maxBytes;

        public BenchmarkParams(int heapKb, int totalRequests, int minBytes, int maxBytes) {
            this.heapKb = heapKb;
            this.totalRequests = totalRequests;
            this.minBytes = minBytes;
            this.maxBytes = maxBytes;
        }
    }

    public static final class BenchmarkResult {
        public final int heapKb, totalRequests, minBytes, maxBytes;
        public final int threadCount, warmupRounds, measureRounds;
        public final double seqLatencyMinMs, seqLatencyAvgMs, seqLatencyMaxMs;
        public final double parLatencyMinMs, parLatencyAvgMs, parLatencyMaxMs;
        public final long seqThroughputMin, seqThroughputAvg, seqThroughputMax;
        public final long parThroughputMin, parThroughputAvg, parThroughputMax;
        public final int seqServed, seqRejected, seqRandoms;
        public final int parServed, parRejected, parRandoms;
        public final double latencySpeedup, throughputSpeedup;

        BenchmarkResult(BenchmarkParams p, long[] seq, long[] par,
                RoundResult seqLast, RoundResult parLast) {
            this.heapKb = p.heapKb;
            this.totalRequests = p.totalRequests;
            this.minBytes = p.minBytes;
            this.maxBytes = p.maxBytes;
            this.threadCount = SYNC_THREAD_COUNT;
            this.warmupRounds = WARMUP_ROUNDS;
            this.measureRounds = MEASURE_ROUNDS;
            seqLatencyMinMs = seq[0] / 1e6;
            seqLatencyAvgMs = seq[2] / 1e6;
            seqLatencyMaxMs = seq[1] / 1e6;
            parLatencyMinMs = par[0] / 1e6;
            parLatencyAvgMs = par[2] / 1e6;
            parLatencyMaxMs = par[1] / 1e6;
            seqThroughputMin = seq[3];
            seqThroughputAvg = seq[5];
            seqThroughputMax = seq[4];
            parThroughputMin = par[3];
            parThroughputAvg = par[5];
            parThroughputMax = par[4];
            seqServed = seqLast.served;
            seqRejected = seqLast.rejected;
            seqRandoms = seqLast.randoms;
            parServed = parLast.served;
            parRejected = parLast.rejected;
            parRandoms = parLast.randoms;
            latencySpeedup = par[2] == 0 ? 0.0 : (double) seq[2] / par[2];
            throughputSpeedup = seq[5] == 0 ? 0.0 : (double) par[5] / seq[5];
        }
    }

    static final class RoundResult {
        final long wallClockNs;
        final int served, rejected, randoms;

        RoundResult(long ns, int sv, int rj, int rn) {
            wallClockNs = ns;
            served = sv;
            rejected = rj;
            randoms = rn;
        }

        double throughputPerSec() {
            return wallClockNs == 0 ? 0 : served / (wallClockNs / 1_000_000_000.0);
        }
    }

    // =========================================================================
    // Compatibilidade com HeapApiServer
    // =========================================================================

    @Deprecated
    public static BenchmarkResult executarComparativo(BenchmarkParams params) {
        Requisitor_Memoria[] reqs = generateRequests(params);

        RoundResult[] seqR = runWithWarmup(
                r -> runSequentialUnsafe(r, params),
                reqs);

        RoundResult[] parR = runWithWarmup(
                r -> runParallelPartitioned(r, params),
                reqs);

        return new BenchmarkResult(
                params,
                aggregate(seqR),
                aggregate(parR),
                seqR[seqR.length - 1],
                parR[parR.length - 1]);
    }
    // =========================================================================
    // Geração de entradas
    // =========================================================================

    /**
     * Gera todas as requisições antes do benchmark.
     * IDs são únicos (via idMutex do Requisitor_Memoria).
     * Este método é chamado FORA do cronômetro.
     */
    static Requisitor_Memoria[] generateRequests(BenchmarkParams p) {
        Requisitor_Memoria[] reqs = new Requisitor_Memoria[p.totalRequests];
        int range = p.maxBytes - p.minBytes;
        for (int i = 0; i < p.totalRequests; i++) {
            int size = p.minBytes + (range > 0 ? (int) (Math.random() * (range + 1)) : 0);
            reqs[i] = new Requisitor_Memoria(size);
        }
        return reqs;
    }

    // =========================================================================
    // VERSÃO 1 — Sequencial com WorstFitUnsafe (linha de base)
    // =========================================================================

    static RoundResult runSequentialUnsafe(Requisitor_Memoria[] requests, BenchmarkParams p) {
        WorstFitUnsafe wf = new WorstFitUnsafe(new Heap((p.heapKb * 1024) / 4));
        GerenciadorLiberacaoUnsafe ger = new GerenciadorLiberacaoUnsafe(wf);
        int served = 0, rejected = 0, randoms = 0;

        long start = System.nanoTime();
        for (Requisitor_Memoria req : requests) {
            int r = processRequestUnsafe(wf, ger, req);
            if (r == 1) {
                served++;
            } else if (r == 2) {
                served++;
                randoms++;
            } else if (r == -1) {
                rejected++;
                randoms++;
            }
        }
        long elapsed = System.nanoTime() - start;

        return new RoundResult(elapsed, served, rejected, randoms);
    }

    static int processRequestUnsafe(WorstFitUnsafe wf, GerenciadorLiberacaoUnsafe ger,
            Requisitor_Memoria req) {
        int result = wf.allocate(req.getSize(), req.getId());
        if (result >= 0)
            return 1;
        GerenciadorLiberacao.RelatorioLiberacao rel = ger.executarLiberacaoRandomica();
        if (rel.getBlocosLiberados() == 0)
            return -1;
        result = wf.allocate(req.getSize(), req.getId());
        return result >= 0 ? 2 : -1;
    }

    // =========================================================================
    // VERSÃO 2 — Paralelo com WorstFitSynchronized (1 mutex global)
    // =========================================================================

    /**
     * CORREÇÕES aplicadas:
     *
     * 1. FILA SEM MUTEX -> AtomicInteger (lock-free)
     * Antes: queueMutex (BinarySemaphore spin+sleep) adicionava até 1ms por
     * requisição na fila — distorcia completamente a medição.
     * Depois: AtomicInteger.getAndIncrement() — instrução CAS atômica, sem spin,
     * sem sleep, sem contenção significativa.
     *
     * 2. CONTADORES POR THREAD (sem mutex)
     * Antes: countersMutex após cada requisição — contenção desnecessária.
     * Depois: cada thread tem served/rejected/randoms locais (int[3]).
     * Agregação feita pelo thread principal após join() de todas as threads.
     * Zero contenção nos contadores durante a medição.
     */
    static RoundResult runParallelSynchronized(Requisitor_Memoria[] requests, BenchmarkParams p) {
        WorstFitSynchronized wf = new WorstFitSynchronized(new Heap((p.heapKb * 1024) / 4));
        GerenciadorLiberacaoSynchronized ger = new GerenciadorLiberacaoSynchronized(wf);

        // Fila lock-free — não faz parte do algoritmo, não deve contaminar medição
        AtomicInteger queue = new AtomicInteger(0);

        // Contadores por thread — sem mutex, sem contenção
        int[][] localCounters = new int[SYNC_THREAD_COUNT][3]; // [thread][served, rejected, randoms]

        Thread[] threads = new Thread[SYNC_THREAD_COUNT];
        for (int t = 0; t < SYNC_THREAD_COUNT; t++) {
            final int tid = t;
            threads[t] = new Thread(() -> {
                int sv = 0, rj = 0, rn = 0;
                while (true) {
                    int idx = queue.getAndIncrement(); // CAS lock-free
                    if (idx >= requests.length)
                        break;

                    int r = processRequestSynchronized(wf, ger, requests[idx]);
                    if (r == 1) {
                        sv++;
                    } else if (r == 2) {
                        sv++;
                        rn++;
                    } else if (r == -1) {
                        rj++;
                        rn++;
                    }
                }
                localCounters[tid][0] = sv;
                localCounters[tid][1] = rj;
                localCounters[tid][2] = rn;
            }, "sync-worker-" + t);
        }

        long start = System.nanoTime();
        for (Thread t : threads)
            t.start();
        for (Thread t : threads) {
            try {
                t.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        long elapsed = System.nanoTime() - start;

        // Agregação após join — fora do cronômetro
        int served = 0, rejected = 0, randoms = 0;
        for (int[] c : localCounters) {
            served += c[0];
            rejected += c[1];
            randoms += c[2];
        }
        return new RoundResult(elapsed, served, rejected, randoms);
    }

    static int processRequestSynchronized(WorstFitSynchronized wf,
            GerenciadorLiberacaoSynchronized ger,
            Requisitor_Memoria req) {
        int result = wf.allocate(req.getSize(), req.getId());
        if (result >= 0)
            return 1;
        GerenciadorLiberacao.RelatorioLiberacao rel = ger.executarLiberacaoRandomica();
        if (rel.getBlocosLiberados() == 0)
            return -1;
        result = wf.allocate(req.getSize(), req.getId());
        return result >= 0 ? 2 : -1;
    }

    // =========================================================================
    // VERSÃO 3 — Paralelo com WorstFitPartitioned (N mutexes, afinidade por
    // segmento)
    // =========================================================================

    /**
     * ESTRATÉGIA: divisão estática das requisições por thread.
     *
     * Cada thread recebe um bloco fixo de índices:
     * thread-0 -> requisições [0, bloco)
     * thread-1 -> requisições [bloco, 2*bloco)
     * ...
     *
     * Vantagens:
     * 1. ZERO contenção na fila — não há fila compartilhada. Cada thread sabe
     * exatamente quais requisições processar sem nenhuma comunicação.
     * 2. AFINIDADE MÁXIMA — thread-i processa bloco-i E tem afinidade com seg-i
     * (via threadSegmentId() que lê "part-worker-N"). As requisições que thread-i
     * processa têm alta probabilidade de caber em seg-i, reduzindo fallback.
     * 3. CONTADORES LOCAIS — mesmo esquema do Synchronized: sem mutex, sem
     * contenção.
     *
     * Comparação justa com Synchronized:
     * - Mesmo número de threads (PART_THREAD_COUNT = SYNC_THREAD_COUNT = 4)
     * - Mesmas requisições
     * - Diferença isolada: 1 mutex global vs N mutexes por segmento
     */
    static RoundResult runParallelPartitioned(Requisitor_Memoria[] requests, BenchmarkParams p) {
        WorstFitPartitioned wf = new WorstFitPartitioned(new Heap((p.heapKb * 1024) / 4));
        GerenciadorLiberacaoPartitioned ger = new GerenciadorLiberacaoPartitioned(wf);

        int total = requests.length;
        int blockSize = (total + PART_THREAD_COUNT - 1) / PART_THREAD_COUNT;

        int[][] localCounters = new int[PART_THREAD_COUNT][3];

        Thread[] threads = new Thread[PART_THREAD_COUNT];
        for (int t = 0; t < PART_THREAD_COUNT; t++) {
            final int tid = t;
            final int start = tid * blockSize;
            final int end = Math.min(start + blockSize, total);

            threads[t] = new Thread(() -> {
                int sv = 0, rj = 0, rn = 0;
                for (int i = start; i < end; i++) {
                    int r = processRequestPartitioned(wf, ger, requests[i]);
                    if (r == 1) {
                        sv++;
                    } else if (r == 2) {
                        sv++;
                        rn++;
                    } else if (r == -1) {
                        rj++;
                        rn++;
                    }
                }
                localCounters[tid][0] = sv;
                localCounters[tid][1] = rj;
                localCounters[tid][2] = rn;
            }, "part-worker-" + t);
        }

        long start = System.nanoTime();
        for (Thread t : threads)
            t.start();
        for (Thread t : threads) {
            try {
                t.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        long elapsed = System.nanoTime() - start;

        int served = 0, rejected = 0, randoms = 0;
        for (int[] c : localCounters) {
            served += c[0];
            rejected += c[1];
            randoms += c[2];
        }
        return new RoundResult(elapsed, served, rejected, randoms);
    }

    static int processRequestPartitioned(WorstFitPartitioned wf,
            GerenciadorLiberacaoPartitioned ger,
            Requisitor_Memoria req) {
        int result = wf.allocate(req.getSize(), req.getId());
        if (result >= 0)
            return 1;
        GerenciadorLiberacao.RelatorioLiberacao rel = ger.executarLiberacaoRandomica();
        if (rel.getBlocosLiberados() == 0)
            return -1;
        result = wf.allocate(req.getSize(), req.getId());
        return result >= 0 ? 2 : -1;
    }

    // =========================================================================
    // Agregação estatística
    // =========================================================================

    /** Retorna [minNs, maxNs, avgNs, minTp, maxTp, avgTp] */
    static long[] aggregate(RoundResult[] results) {
        long minNs = Long.MAX_VALUE, maxNs = Long.MIN_VALUE, sumNs = 0;
        long minTp = Long.MAX_VALUE, maxTp = Long.MIN_VALUE, sumTp = 0;
        for (RoundResult r : results) {
            minNs = Math.min(minNs, r.wallClockNs);
            maxNs = Math.max(maxNs, r.wallClockNs);
            sumNs += r.wallClockNs;
            long tp = (long) r.throughputPerSec();
            minTp = Math.min(minTp, tp);
            maxTp = Math.max(maxTp, tp);
            sumTp += tp;
        }
        return new long[] { minNs, maxNs, sumNs / results.length, minTp, maxTp, sumTp / results.length };
    }

    @FunctionalInterface
    interface BenchmarkRunner {
        RoundResult run(Requisitor_Memoria[] requests);
    }

    static RoundResult[] runWithWarmup(BenchmarkRunner runner, Requisitor_Memoria[] requests) {
        for (int i = 0; i < WARMUP_ROUNDS; i++)
            runner.run(requests);
        RoundResult[] results = new RoundResult[MEASURE_ROUNDS];
        for (int i = 0; i < MEASURE_ROUNDS; i++)
            results[i] = runner.run(requests);
        return results;
    }

    // =========================================================================
    // Relatório de uma configuração
    // =========================================================================

    static void printResults(long[] uAgg, RoundResult uLast,
            long[] sAgg, RoundResult sLast,
            long[] pAgg, RoundResult pLast) {

        System.out.println("\n┌─────────────────────────────────────────────────────────────────┐");
        System.out.println("│  VERSÃO 1: WorstFitUnsafe (Sequencial, sem mutex)              │");
        System.out.println("├──────────────────┬────────────────────────────────────────────┤");
        System.out.printf("│  Latência média  │ %7.2f ms                                  │%n", uAgg[2] / 1e6);
        System.out.printf("│  Throughput méd  │ %,7d req/s                              │%n", uAgg[5]);
        System.out.printf("│  Atendidas       │ %-6d                                      │%n", uLast.served);
        System.out.printf("│  Rejeitadas      │ %-6d                                      │%n", uLast.rejected);
        System.out.printf("│  RANDOM acionado │ %-6d                                      │%n", uLast.randoms);

        System.out.println("├─────────────────────────────────────────────────────────────────┤");
        System.out.printf("│  VERSÃO 2: WorstFitSynchronized (Paralelo, %d threads, 1 mutex) │%n", SYNC_THREAD_COUNT);
        System.out.println("├──────────────────┬────────────────────────────────────────────┤");
        System.out.printf("│  Latência média  │ %7.2f ms                                  │%n", sAgg[2] / 1e6);
        System.out.printf("│  Throughput méd  │ %,7d req/s                              │%n", sAgg[5]);
        System.out.printf("│  Atendidas       │ %-6d                                      │%n", sLast.served);
        System.out.printf("│  Rejeitadas      │ %-6d                                      │%n", sLast.rejected);
        System.out.printf("│  RANDOM acionado │ %-6d                                      │%n", sLast.randoms);

        System.out.println("├─────────────────────────────────────────────────────────────────┤");
        System.out.printf("│  VERSÃO 3: WorstFitPartitioned (Paralelo, %d threads, N mutexes)│%n", PART_THREAD_COUNT);
        System.out.println("├──────────────────┬────────────────────────────────────────────┤");
        System.out.printf("│  Latência média  │ %7.2f ms                                  │%n", pAgg[2] / 1e6);
        System.out.printf("│  Throughput méd  │ %,7d req/s                              │%n", pAgg[5]);
        System.out.printf("│  Atendidas       │ %-6d                                      │%n", pLast.served);
        System.out.printf("│  Rejeitadas      │ %-6d                                      │%n", pLast.rejected);
        System.out.printf("│  RANDOM acionado │ %-6d                                      │%n", pLast.randoms);

        System.out.println("╠═════════════════════════════════════════════════════════════════╣");

        // Comparação 1: overhead de sincronização (mesma semântica, 1 thread vs N
        // threads)
        double sp1 = uAgg[2] == 0 ? 0 : (double) uAgg[2] / sAgg[2];
        System.out.printf("║ Unsafe->Synchronized   : %6.2fx  %-30s║%n",
                sp1, sp1 < 1 ? "(Sync mais lento — overhead mutex)" : "(Sync mais rápido — paralelismo)");

        // Comparação 2: ganho do particionamento (COMPARAÇÃO VÁLIDA — mesmo N de
        // threads)
        double sp2 = sAgg[2] == 0 ? 0 : (double) sAgg[2] / pAgg[2];
        System.out.printf("║ Synchronized->Partitioned: %6.2fx  %-28s║%n",
                sp2, sp2 > 1 ? "(Part mais rápido — menos contenção)" : "(Part mais lento)");

        // Referência: Unsafe vs Partitioned (threads diferentes — não usar como
        // speedup)
        double sp3 = uAgg[2] == 0 ? 0 : (double) uAgg[2] / pAgg[2];
        System.out.printf("║ Unsafe->Partitioned (ref): %6.2fx  %-27s║%n",
                sp3, "(threads diferentes — apenas referência)");

        // Eficiência paralela do Partitioned
        double effPart = sp3 / PART_THREAD_COUNT;
        System.out.printf("║ Eficiência paralela (Part): %5.1f%%   %-26s║%n",
                effPart * 100,
                effPart >= 0.7 ? "(boa — ≥70%)" : effPart >= 0.4 ? "(razoável)" : "(baixa — contenção alta)");

        System.out.println("╚═════════════════════════════════════════════════════════════════╝");
    }

    // =========================================================================
    // Tabela de escalabilidade
    // =========================================================================

    /**
     * Executa benchmark com múltiplas configurações e imprime tabela comparativa.
     * Permite avaliar comportamento com diferentes tamanhos de heap e cargas.
     */
    static void runScalabilityTable(int[] heapSizesKb, int[] requestCounts, int minB, int maxB) {
        System.out.println("\n╔═════════════════════════════════════════════════════════════════════════════════════╗");
        System.out.println("║                          TABELA DE ESCALABILIDADE                                   ║");
        System.out.println("╠════════╦════════╦══════════╦══════════╦══════════╦════════╦════════╦════════════════╣");
        System.out.println("║ Heap   ║  Reqs  ║ Unsafe   ║ Sync     ║ Part     ║ Sp     ║ Sp     ║ Efic. Part     ║");
        System.out.println("║  (KB)  ║        ║ (ms)     ║ (ms)     ║ (ms)     ║ U->S    ║ S->P    ║ (%)            ║");
        System.out.println("╠════════╬════════╬══════════╬══════════╬══════════╬════════╬════════╬════════════════╣");

        for (int heapKb : heapSizesKb) {
            for (int nReqs : requestCounts) {
                BenchmarkParams p = new BenchmarkParams(heapKb, nReqs, minB, maxB);
                Requisitor_Memoria[] reqs = generateRequests(p);

                long[] uAgg = aggregate(runWithWarmup(r -> runSequentialUnsafe(r, p), reqs));
                long[] sAgg = aggregate(runWithWarmup(r -> runParallelSynchronized(r, p), reqs));
                long[] pAgg = aggregate(runWithWarmup(r -> runParallelPartitioned(r, p), reqs));

                double spUS = uAgg[2] == 0 ? 0 : (double) uAgg[2] / sAgg[2];
                double spSP = sAgg[2] == 0 ? 0 : (double) sAgg[2] / pAgg[2];
                double eff = uAgg[2] == 0 ? 0 : ((double) uAgg[2] / pAgg[2]) / PART_THREAD_COUNT;

                System.out.printf("║ %6d ║ %6d ║ %8.1f ║ %8.1f ║ %8.1f ║ %6.2f ║ %6.2f ║ %14.1f ║%n",
                        heapKb, nReqs,
                        uAgg[2] / 1e6, sAgg[2] / 1e6, pAgg[2] / 1e6,
                        spUS, spSP, eff * 100);
            }
        }
        System.out.println("╚════════╩════════╩══════════╩══════════╩══════════╩════════╩════════╩════════════════╝");
        System.out.println("  Sp U->S = speedup Unsafe->Synchronized  |  Sp S->P = speedup Synchronized->Partitioned");
        System.out.println("  Efic. = speedup Unsafe->Partitioned / PART_THREAD_COUNT");
    }

    // =========================================================================
    // Main
    // =========================================================================

    public static void main(String[] args) {
        // Parâmetros via linha de comando: [totalRequests] [heapKb]
        int totalRequests = 50_000;
        int heapKb = 64;
        if (args.length >= 1) {
            try {
                totalRequests = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                System.err.println("totalRequests inválido");
                System.exit(1);
            }
        }
        if (args.length >= 2) {
            try {
                heapKb = Integer.parseInt(args[1]);
            } catch (NumberFormatException e) {
                System.err.println("heapKb inválido");
                System.exit(1);
            }
        }

        BenchmarkParams params = new BenchmarkParams(heapKb, totalRequests, 16, 256);

        System.out.println("╔═══════════════════════════════════════════════════════╗");
        System.out.println("║  BENCHMARK: 3 VERSÕES (Unsafe, Synchronized, Partitioned)║");
        System.out.println("╠═══════════════════════════════════════════════════════╣");
        System.out.printf("║  Heap           : %-34d KB║%n", params.heapKb);
        System.out.printf("║  Requisições    : %-37d  ║%n", params.totalRequests);
        System.out.printf("║  Tam. req       : %d - %-31d B ║%n", params.minBytes, params.maxBytes);
        System.out.printf("║  Threads (Sync) : %-37d  ║%n", SYNC_THREAD_COUNT);
        System.out.printf("║  Threads (Part) : %-37d  ║%n", PART_THREAD_COUNT);
        System.out.printf("║  Warmup         : %-37d  ║%n", WARMUP_ROUNDS);
        System.out.printf("║  Medições       : %-37d  ║%n", MEASURE_ROUNDS);
        System.out.println("╠═══════════════════════════════════════════════════════╣");
        System.out.println("║  Fila: AtomicInteger (lock-free, sem distorção)       ║");
        System.out.println("║  Contadores: por thread (sem mutex, sem contenção)    ║");
        System.out.println("╚═══════════════════════════════════════════════════════╝");
        System.out.println("\nGerando requisições e executando benchmarks...\n");

        Requisitor_Memoria[] requests = generateRequests(params);

        System.out.println("* Versão 1: WorstFitUnsafe  (Sequencial)");
        RoundResult[] uR = runWithWarmup(r -> runSequentialUnsafe(r, params), requests);

        System.out.println("* Versão 2: WorstFitSynchronized (Paralelo, 1 mutex)");
        RoundResult[] sR = runWithWarmup(r -> runParallelSynchronized(r, params), requests);

        System.out.println("* Versão 3: WorstFitPartitioned  (Paralelo, N mutexes)");
        RoundResult[] pR = runWithWarmup(r -> runParallelPartitioned(r, params), requests);

        printResults(aggregate(uR), uR[uR.length - 1],
                aggregate(sR), sR[sR.length - 1],
                aggregate(pR), pR[pR.length - 1]);

        // Tabela de escalabilidade automática
        System.out.println("\n* Executando tabela de escalabilidade...");
        runScalabilityTable(HEAP_SIZES_KB, REQUEST_COUNTS, 16, 256);
    }
}
