package sync;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Semáforo contator implementado do zero, sem uso de java.util.concurrent.Semaphore,
 * ReentrantLock, synchronized ou qualquer primitiva de bloqueio do Java.
 *
 * <h2>Fundamentos teóricos</h2>
 * Baseado no semáforo de Dijkstra (1965), com duas operações atômicas:
 * <ul>
 *   <li><b>P (wait/acquire)</b> — do holandês "Proberen" (testar):
 *       decrementa o contador; se o resultado for negativo, bloqueia até
 *       que outro processo execute V.</li>
 *   <li><b>V (signal/release)</b> — do holandês "Verhogen" (incrementar):
 *       incrementa o contador, liberando um processo bloqueado se houver.</li>
 * </ul>
 *
 * <h2>Invariante</h2>
 * O contador {@code count} satisfaz sempre:
 * <pre>
 *   count = permissões_iniciais - (número de P bem-sucedidos) + (número de V executados)
 *   count ≥ 0  em todo instante observável externamente
 * </pre>
 *
 * <h2>Primitiva de base: CAS (Compare-And-Swap)</h2>
 * A atomicidade é garantida por {@link AtomicInteger#compareAndSet}, que mapeia
 * diretamente para a instrução de hardware {@code CMPXCHG} (x86) ou {@code LDXR/STXR}
 * (ARM). Isso garante que a sequência "ler → verificar → decrementar" seja atômica
 * sem usar {@code synchronized}.
 *
 * <h2>Estratégia de espera: spin com backoff progressivo</h2>
 * Em vez de busy-waiting puro (que consome 100% de CPU) ou bloqueio real
 * (que exigiria {@code wait/notify}), adotamos um spin com backoff:
 * <ol>
 *   <li>Primeira tentativa falha → {@code Thread.yield()} cede o processador
 *       ao scheduler sem dormir.</li>
 *   <li>Tentativas seguintes → {@code Thread.sleep(SLEEP_MS)} libera o núcleo
 *       de verdade, reduzindo consumo de CPU.</li>
 * </ol>
 *
 * <h2>Semáforo binário</h2>
 * {@link BinarySemaphore} estende esta classe fixando {@code permits = 1},
 * tornando-a equivalente a um mutex (exclusão mútua).
 */
public class Semaphore {

    /**
     * Contador de permissões disponíveis.
     * Nunca negativo em estado estável.
     * Usamos AtomicInteger para garantir que a operação CAS
     * (ler + comparar + escrever) seja indivisível no hardware.
     */
    private final AtomicInteger count;

    /**
     * Número máximo de permissões (usado apenas para documentação e toString).
     * Não é um limite técnico; é o valor inicial passado no construtor.
     */
    private final int initialPermits;

    /** Milissegundos de sono após a primeira tentativa falha de acquire. */
    private static final long SLEEP_MS = 1L;

    // -------------------------------------------------------------------------
    // Construtor
    // -------------------------------------------------------------------------

    /**
     * Cria um semáforo contador com {@code permits} permissões disponíveis.
     *
     * @param permits número inicial de permissões (deve ser ≥ 0)
     * @throws IllegalArgumentException se {@code permits} for negativo
     */
    public Semaphore(int permits) {
        if (permits < 0) {
            throw new IllegalArgumentException(
                "Número de permissões não pode ser negativo. Recebido: " + permits
            );
        }
        this.count          = new AtomicInteger(permits);
        this.initialPermits = permits;
    }

    // -------------------------------------------------------------------------
    // P — wait / acquire
    // -------------------------------------------------------------------------

    /**
     * Operação P (wait/acquire) do semáforo.
     *
     * <p>Tenta decrementar o contador atomicamente. Se o contador estiver em 0,
     * a thread entra em spin com backoff progressivo até que uma permissão
     * seja liberada por outra thread via {@link #release()}.
     *
     * <p><b>Algoritmo CAS:</b>
     * <pre>
     *   loop:
     *     current = count.get()          // lê o valor atual
     *     if current == 0: spin/espera   // sem permissão disponível
     *     if CAS(count, current, current-1): retorna   // sucesso atômico
     *     // senão: outra thread ganhou a corrida → tenta de novo
     * </pre>
     *
     * <p><b>Por que o CAS é necessário?</b> Entre o {@code get()} e o
     * {@code decrementAndGet()}, outra thread poderia ler o mesmo valor e
     * ambas decrementariam — violando a exclusão mútua. O CAS faz as duas
     * operações atomicamente: só grava se o valor ainda for o mesmo que leu.
     *
     * @throws InterruptedException se a thread for interrompida durante o spin
     */
    public void acquire() throws InterruptedException {
        boolean firstAttempt = true;

        while (true) {
            // Verifica interrupção a cada volta do loop
            if (Thread.interrupted()) {
                throw new InterruptedException(
                    "Thread interrompida enquanto aguardava permissão no semáforo"
                );
            }

            int current = count.get();

            if (current > 0) {
                // Tentativa atômica de decrementar: só grava se count ainda for 'current'
                // Se outra thread modificou count entre o get() e aqui, CAS falha
                // e voltamos ao início do loop — sem race condition
                if (count.compareAndSet(current, current - 1)) {
                    return; // permissão adquirida com sucesso
                }
                // CAS falhou: outra thread ganhou a corrida → retenta imediatamente
            } else {
                // Sem permissões disponíveis: spin com backoff progressivo
                if (firstAttempt) {
                    // Primeira espera: apenas cede o processador ao scheduler
                    // Thread continua "pronta" mas permite que outras rodem
                    Thread.yield();
                    firstAttempt = false;
                } else {
                    // Esperas seguintes: dorme de verdade, libera o núcleo de CPU
                    Thread.sleep(SLEEP_MS);
                }
            }
        }
    }

    /**
     * Operação P não-bloqueante: tenta adquirir sem esperar.
     *
     * @return {@code true} se a permissão foi adquirida; {@code false} se não
     *         havia permissão disponível no momento da chamada
     */
    public boolean tryAcquire() {
        while (true) {
            int current = count.get();
            if (current <= 0) {
                return false; // não há permissão — retorna imediatamente sem bloquear
            }
            if (count.compareAndSet(current, current - 1)) {
                return true; // permissão adquirida
            }
            // CAS falhou por contenção → retenta (o valor pode ainda ser > 0)
        }
    }

    // -------------------------------------------------------------------------
    // V — signal / release
    // -------------------------------------------------------------------------

    /**
     * Operação V (signal/release) do semáforo.
     *
     * <p>Incrementa o contador atomicamente, sinalizando que uma permissão
     * foi devolvida. Se houver threads em spin aguardando em {@link #acquire()},
     * elas poderão competir pela permissão liberada.
     *
     * <p>Esta operação nunca bloqueia e nunca lança exceção.
     *
     * <p><b>Nota sobre uso correto:</b> {@code release()} deve ser chamado
     * exatamente uma vez para cada {@code acquire()} bem-sucedido. Chamar
     * {@code release()} sem um {@code acquire()} correspondente incrementa o
     * contador além do valor inicial — o que é válido para semáforos contadores
     * (sinalização), mas viola a semântica de mutex para semáforos binários.
     */
    public void release() {
        count.incrementAndGet();
    }

    // -------------------------------------------------------------------------
    // Consultas de estado
    // -------------------------------------------------------------------------

    /**
     * Retorna o número atual de permissões disponíveis.
     *
     * <p><b>Atenção:</b> este valor pode mudar imediatamente após a leitura
     * em contexto concorrente. Use apenas para monitoramento e logs.
     *
     * @return permissões disponíveis no momento da leitura
     */
    public int availablePermits() {
        return count.get();
    }

    /**
     * Retorna {@code true} se não há permissões disponíveis (alguma thread
     * pode estar em espera).
     */
    public boolean isBlocking() {
        return count.get() <= 0;
    }

    @Override
    public String toString() {
        return String.format("Semaphore{permits=%d/%d}", count.get(), initialPermits);
    }

    // =========================================================================
    // Semáforo binário — mutex
    // =========================================================================

    /**
     * Semáforo binário (mutex): restringe o acesso a no máximo 1 thread por vez.
     *
     * <p>É um caso especial do semáforo contador com {@code permits = 1}.
     * Garante exclusão mútua: se uma thread adquiriu, todas as outras
     * bloqueiam em {@link #acquire()} até que {@link #release()} seja chamado.
     *
     * <p>Uso típico:
     * <pre>{@code
     *   BinarySemaphore mutex = new BinarySemaphore();
     *
     *   mutex.acquire();   // entra na região crítica
     *   try {
     *       // ... acesso exclusivo ao recurso compartilhado ...
     *   } finally {
     *       mutex.release();   // sai da região crítica — SEMPRE no finally
     *   }
     * }</pre>
     *
     * <p><b>Por que o finally é obrigatório?</b> Se a região crítica lançar
     * uma exceção e {@code release()} não for chamado, o semáforo fica em 0
     * para sempre — deadlock permanente para todas as threads que tentarem
     * adquiri-lo depois.
     */
    public static class BinarySemaphore extends Semaphore {

        /**
         * Cria um mutex inicialmente liberado (1 permissão disponível).
         */
        public BinarySemaphore() {
            super(1);
        }

        /**
         * Cria um mutex com estado inicial explícito.
         *
         * @param initiallyLocked {@code true} para criar já adquirido (0 permissões);
         *                        {@code false} para criar liberado (1 permissão)
         */
        public BinarySemaphore(boolean initiallyLocked) {
            super(initiallyLocked ? 0 : 1);
        }

        @Override
        public String toString() {
            return String.format("BinarySemaphore{%s}",
                availablePermits() == 1 ? "LIVRE" : "BLOQUEADO");
        }
    }
}
