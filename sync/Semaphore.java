package sync;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Semáforo contador implementado do zero.
 *
 * Não usa java.util.concurrent.Semaphore, ReentrantLock, synchronized
 * nem qualquer primitiva de bloqueio pronta do Java.
 *
 * Primitiva de base: AtomicInteger com CAS (Compare-And-Swap), que mapeia
 * para instrução de hardware CMPXCHG (x86). Toda a lógica do semáforo
 * (quando bloquear, invariantes, coalescência) é implementada aqui.
 *
 * Estratégia de espera: spin com backoff progressivo.
 *   1ª falha  → Thread.yield()      — cede o processador, sem dormir
 *   2ª+ falha → Thread.sleep(1ms)   — libera o núcleo de verdade
 *
 * Invariante: count >= 0 em todo instante observável externamente.
 *
 * BinarySemaphore (mutex) é um caso especial com permits = 1.
 */
public class Semaphore {

    private final AtomicInteger count;
    private final int initialPermits;
    private static final long SLEEP_MS = 1L;

    public Semaphore(int permits) {
        if (permits < 0) {
            throw new IllegalArgumentException(
                "Número de permissões não pode ser negativo. Recebido: " + permits);
        }
        this.count          = new AtomicInteger(permits);
        this.initialPermits = permits;
    }

    /**
     * P (wait/acquire): decrementa o contador atomicamente via CAS.
     * Bloqueia em spin se count == 0.
     *
     * Por que CAS e não decrementAndGet()?
     * decrementAndGet() decrementaria mesmo quando count já é 0,
     * violando o invariante count >= 0. O CAS faz ler+verificar+decrementar
     * como uma instrução atômica: só grava se o valor não mudou desde a leitura.
     */
    public void acquire() throws InterruptedException {
        boolean firstAttempt = true;
        while (true) {
            if (Thread.interrupted()) {
                throw new InterruptedException(
                    "Thread interrompida aguardando semáforo");
            }
            int current = count.get();
            if (current > 0) {
                if (count.compareAndSet(current, current - 1)) {
                    return; // permissão adquirida
                }
                // CAS falhou: outra thread ganhou a corrida → retenta
            } else {
                // Sem permissão: spin com backoff progressivo
                if (firstAttempt) {
                    Thread.yield();
                    firstAttempt = false;
                } else {
                    Thread.sleep(SLEEP_MS);
                }
            }
        }
    }

    /**
     * Tenta adquirir sem bloquear.
     * Retorna true se conseguiu, false se count == 0 no momento da chamada.
     */
    public boolean tryAcquire() {
        while (true) {
            int current = count.get();
            if (current <= 0) return false;
            if (count.compareAndSet(current, current - 1)) return true;
        }
    }

    /**
     * V (signal/release): incrementa o contador atomicamente.
     * Nunca bloqueia. Deve ser chamado exatamente uma vez por acquire() bem-sucedido.
     */
    public void release() {
        count.incrementAndGet();
    }

    public int availablePermits() {
        return count.get();
    }

    public boolean isBlocking() {
        return count.get() <= 0;
    }

    @Override
    public String toString() {
        return String.format("Semaphore{%d/%d}", count.get(), initialPermits);
    }

    // =========================================================================
    // Semáforo binário — mutex (exclusão mútua)
    // =========================================================================

    /**
     * Mutex: restringe acesso a no máximo 1 thread por vez.
     * Caso especial do semáforo contador com permits = 1.
     *
     * Uso correto (SEMPRE com finally para evitar deadlock permanente):
     *
     *   mutex.acquire();
     *   try {
     *       // região crítica
     *   } finally {
     *       mutex.release();
     *   }
     */
    public static final class BinarySemaphore extends Semaphore {

        /** Cria o mutex inicialmente liberado (1 permissão disponível). */
        public BinarySemaphore() {
            super(1);
        }

        /** Cria o mutex com estado inicial explícito. */
        public BinarySemaphore(boolean initiallyLocked) {
            super(initiallyLocked ? 0 : 1);
        }

        @Override
        public String toString() {
            return "BinarySemaphore{" + (availablePermits() == 1 ? "LIVRE" : "BLOQUEADO") + "}";
        }
    }
}
