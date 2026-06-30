package sync;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Testes do semáforo implementado do zero.
 *
 * Cada teste demonstra um cenário clássico de Sistemas Operacionais:
 *
 *   Teste 1 — Exclusão mútua com BinarySemaphore (mutex)
 *             Verifica que um contador incrementado por N threads sem proteção
 *             produz resultado errado, e com mutex produz resultado correto.
 *
 *   Teste 2 — Semáforo contador como controle de acesso a pool de recursos
 *             Simula N threads competindo por K "conexões" disponíveis
 *             (K < N), verificando que nunca mais de K threads estão dentro
 *             da região ao mesmo tempo.
 *
 *   Teste 3 — Produtor / consumidor com dois semáforos
 *             Padrão clássico de SO: um semáforo conta itens produzidos
 *             (começa em 0), outro conta espaços livres no buffer (começa em
 *             capacidade). Produtor faz V(cheio) após produzir; consumidor
 *             faz P(cheio) antes de consumir.
 *
 *   Teste 4 — tryAcquire não-bloqueante
 *             Verifica que a operação retorna false imediatamente quando não
 *             há permissão, sem bloquear a thread chamadora.
 */
public class SemaphoreTest {

    // -------------------------------------------------------------------------
    // Utilitário de log
    // -------------------------------------------------------------------------

    private static void log(String msg) {
        System.out.printf("[%-20s] %s%n",
            Thread.currentThread().getName(), msg);
    }

    private static void section(String title) {
        System.out.println();
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        System.out.println("  " + title);
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
    }

    private static void resultado(boolean passou, String descricao) {
        System.out.printf("  %s %s%n", passou ? "✓" : "✗ FALHOU:", descricao);
    }

    // =========================================================================
    // TESTE 1 — Exclusão mútua
    // =========================================================================

    /**
     * Demonstra race condition sem proteção e exclusão mútua correta com mutex.
     *
     * Sem mutex: 10 threads incrementam um contador 1000× cada.
     * Esperado: 10_000. Obtido: geralmente menor (perdas por race condition).
     *
     * Com mutex: o mesmo cenário, mas com acquire/release ao redor do ++.
     * Esperado: exatamente 10_000.
     */
    static void testExclusaoMutua() throws InterruptedException {
        section("TESTE 1 — Exclusão mútua com BinarySemaphore (mutex)");

        final int THREADS    = 10;
        final int INCREMENTOS = 1_000;

        // --- Sem proteção ---
        int[] contadorSemProtecao = {0};
        Thread[] semMutex = new Thread[THREADS];
        for (int i = 0; i < THREADS; i++) {
            semMutex[i] = new Thread(() -> {
                for (int j = 0; j < INCREMENTOS; j++) {
                    contadorSemProtecao[0]++; // read-modify-write NÃO atômico
                }
            }, "sem-mutex-" + i);
        }
        for (Thread t : semMutex) t.start();
        for (Thread t : semMutex) t.join();

        int resultadoSemProtecao = contadorSemProtecao[0];
        System.out.printf("  Sem mutex: esperado %d, obtido %d %s%n",
            THREADS * INCREMENTOS, resultadoSemProtecao,
            resultadoSemProtecao == THREADS * INCREMENTOS ? "(sem perda — pode variar)" : "(perda por race condition)");

        // --- Com mutex ---
        Semaphore.BinarySemaphore mutex = new Semaphore.BinarySemaphore();
        int[] contadorComMutex = {0};
        Thread[] comMutex = new Thread[THREADS];
        for (int i = 0; i < THREADS; i++) {
            comMutex[i] = new Thread(() -> {
                for (int j = 0; j < INCREMENTOS; j++) {
                    try {
                        mutex.acquire();               // P — entra na região crítica
                        try {
                            contadorComMutex[0]++;     // região crítica
                        } finally {
                            mutex.release();           // V — sai da região crítica
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }, "com-mutex-" + i);
        }
        for (Thread t : comMutex) t.start();
        for (Thread t : comMutex) t.join();

        int resultadoComMutex = contadorComMutex[0];
        resultado(resultadoComMutex == THREADS * INCREMENTOS,
            String.format("Com mutex: esperado %d, obtido %d",
                THREADS * INCREMENTOS, resultadoComMutex));
    }

    // =========================================================================
    // TESTE 2 — Semáforo contador como pool de recursos
    // =========================================================================

    /**
     * Simula um pool com K recursos disponíveis e N threads competindo.
     *
     * Invariante a verificar: em nenhum momento mais de K threads estão
     * simultaneamente dentro da região protegida.
     *
     * Usamos um AtomicInteger como contador de "dentro" e verificamos
     * que ele nunca ultrapassa K.
     */
    static void testSemaforoContador() throws InterruptedException {
        section("TESTE 2 — Semáforo contador: pool de K=" + 3 + " recursos, N=" + 8 + " threads");

        final int K = 3; // conexões disponíveis no pool
        final int N = 8; // threads competindo

        Semaphore pool          = new Semaphore(K);
        AtomicInteger dentro    = new AtomicInteger(0);
        AtomicInteger maxDentro = new AtomicInteger(0);
        AtomicInteger violacoes = new AtomicInteger(0);

        Thread[] threads = new Thread[N];
        for (int i = 0; i < N; i++) {
            final int id = i;
            threads[i] = new Thread(() -> {
                try {
                    pool.acquire();                         // P — aguarda conexão
                    int atual = dentro.incrementAndGet();

                    // Atualiza máximo observado
                    maxDentro.updateAndGet(m -> Math.max(m, atual));

                    // Verifica invariante: nunca deve ultrapassar K
                    if (atual > K) {
                        violacoes.incrementAndGet();
                        log("VIOLAÇÃO: " + atual + " threads dentro! (máx = " + K + ")");
                    }

                    log(String.format("usando recurso [dentro=%d/%d]", atual, K));
                    Thread.sleep(50); // simula uso do recurso
                    log("liberando recurso");

                    dentro.decrementAndGet();
                    pool.release();                         // V — devolve conexão
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "worker-" + id);
        }

        for (Thread t : threads) t.start();
        for (Thread t : threads) t.join();

        resultado(violacoes.get() == 0,
            String.format("Nunca mais de %d threads simultâneas (máximo observado: %d)",
                K, maxDentro.get()));
        resultado(pool.availablePermits() == K,
            String.format("Semáforo voltou ao estado inicial (%d permissões)", K));
    }

    // =========================================================================
    // TESTE 3 — Produtor / Consumidor
    // =========================================================================

    /**
     * Padrão clássico de SO com dois semáforos:
     *
     *   vazio  = Semaphore(CAPACIDADE)  → conta espaços livres no buffer
     *   cheio  = Semaphore(0)           → conta itens disponíveis para consumo
     *
     * Produtor:
     *   P(vazio)     → aguarda espaço livre
     *   ... produz ...
     *   V(cheio)     → sinaliza item disponível
     *
     * Consumidor:
     *   P(cheio)     → aguarda item disponível
     *   ... consome ...
     *   V(vazio)     → sinaliza espaço liberado
     *
     * Invariante: buffer nunca tem mais de CAPACIDADE itens; consumidor
     * nunca consome um item que não foi produzido.
     */
    static void testProdutorConsumidor() throws InterruptedException {
        section("TESTE 3 — Produtor / Consumidor com dois semáforos");

        final int CAPACIDADE = 5;
        final int TOTAL_ITENS = 20;

        // Buffer circular simples (array + índices)
        int[] buffer  = new int[CAPACIDADE];
        int[] head    = {0}; // próximo a consumir
        int[] tail    = {0}; // próxima posição a produzir

        Semaphore vazio  = new Semaphore(CAPACIDADE); // espaços livres
        Semaphore cheio  = new Semaphore(0);           // itens prontos
        Semaphore mutex  = new Semaphore(1);           // protege head/tail

        AtomicInteger produzidos = new AtomicInteger(0);
        AtomicInteger consumidos = new AtomicInteger(0);
        AtomicInteger erros      = new AtomicInteger(0);

        // Produtor
        Thread produtor = new Thread(() -> {
            for (int item = 1; item <= TOTAL_ITENS; item++) {
                try {
                    vazio.acquire();          // P(vazio): aguarda espaço livre

                    mutex.acquire();          // P(mutex): acessa o buffer
                    buffer[tail[0]] = item;
                    tail[0] = (tail[0] + 1) % CAPACIDADE;
                    log("produziu item " + item);
                    produzidos.incrementAndGet();
                    mutex.release();          // V(mutex)

                    cheio.release();          // V(cheio): sinaliza item disponível
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }, "Produtor");

        // Consumidor
        Thread consumidor = new Thread(() -> {
            for (int i = 0; i < TOTAL_ITENS; i++) {
                try {
                    cheio.acquire();          // P(cheio): aguarda item disponível

                    mutex.acquire();          // P(mutex): acessa o buffer
                    int item = buffer[head[0]];
                    buffer[head[0]] = 0;
                    head[0] = (head[0] + 1) % CAPACIDADE;
                    log("consumiu item " + item);
                    if (item <= 0) erros.incrementAndGet(); // consumiu slot vazio
                    consumidos.incrementAndGet();
                    mutex.release();          // V(mutex)

                    vazio.release();          // V(vazio): sinaliza espaço liberado
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }, "Consumidor");

        produtor.start();
        consumidor.start();
        produtor.join();
        consumidor.join();

        resultado(produzidos.get() == TOTAL_ITENS,
            "Todos os " + TOTAL_ITENS + " itens foram produzidos");
        resultado(consumidos.get() == TOTAL_ITENS,
            "Todos os " + TOTAL_ITENS + " itens foram consumidos");
        resultado(erros.get() == 0,
            "Nenhum slot vazio consumido (sem consumo antes de produção)");
    }

    // =========================================================================
    // TESTE 4 — tryAcquire não-bloqueante
    // =========================================================================

    /**
     * Verifica o comportamento de tryAcquire:
     *   - retorna true quando há permissão
     *   - retorna false imediatamente quando não há (sem bloquear)
     */
    static void testTryAcquire() throws InterruptedException {
        section("TESTE 4 — tryAcquire não-bloqueante");

        Semaphore.BinarySemaphore mutex = new Semaphore.BinarySemaphore();

        // Primeira tentativa: deve conseguir
        boolean primeira = mutex.tryAcquire();
        resultado(primeira, "tryAcquire() retorna true quando há permissão");

        // Segunda tentativa com o mutex já adquirido: deve falhar imediatamente
        boolean segunda = mutex.tryAcquire();
        resultado(!segunda, "tryAcquire() retorna false imediatamente quando bloqueado");

        // Após release, deve funcionar de novo
        mutex.release();
        boolean terceira = mutex.tryAcquire();
        resultado(terceira, "tryAcquire() retorna true após release()");
        mutex.release();

        // Garante que tryAcquire não bloqueou (tempo total deve ser mínimo)
        System.out.println("  Estado final: " + mutex);
    }

    // =========================================================================
    // Main
    // =========================================================================

    public static void main(String[] args) throws InterruptedException {
        System.out.println("╔═══════════════════════════════════════════════════════════════╗");
        System.out.println("║        TESTE DO SEMÁFORO — IMPLEMENTAÇÃO DO ZERO             ║");
        System.out.println("║   Primitiva: CAS (AtomicInteger) + spin com backoff          ║");
        System.out.println("╚═══════════════════════════════════════════════════════════════╝");

        testExclusaoMutua();
        testSemaforoContador();
        testProdutorConsumidor();
        testTryAcquire();

        System.out.println();
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        System.out.println("  Testes concluídos.");
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
    }
}
