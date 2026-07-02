package model;

import sync.Semaphore;

/**
 * Representa um pedido de alocação de memória.
 *
 * Cada requisição tem um ID único gerado automaticamente e um tamanho em bytes.
 *
 * Região crítica: o contador estático idCounter é compartilhado entre todas as
 * instâncias e pode ser acessado por múltiplas threads simultaneamente.
 * Protegido por BinarySemaphore (mutex) em vez de AtomicInteger, mantendo
 * coerência com a estratégia de sincronização do projeto.
 *
 * O mutex só é segurado durante a leitura e incremento do contador —
 * operação de microssegundos, contenção mínima.
 */
public class Requisitor_Memoria {

    public static final int MIN_SIZE = 16;
    public static final int MAX_SIZE = 1024;

    // ── Região crítica: geração de ID único ──────────────────────────────────
    private static int idCounter = 1;
    private static final Semaphore.BinarySemaphore idMutex = new Semaphore.BinarySemaphore();
    // ─────────────────────────────────────────────────────────────────────────

    private static final java.util.Random random = new java.util.Random();

    private final int id;
    private final int size;

    /** Cria uma requisição com tamanho aleatório entre MIN_SIZE e MAX_SIZE. */
    public Requisitor_Memoria() {
        this(MIN_SIZE + random.nextInt(MAX_SIZE - MIN_SIZE + 1));
    }

    /**
     * Cria uma requisição com tamanho específico em bytes.
     *
     * @param size tamanho entre MIN_SIZE e MAX_SIZE bytes
     */
    public Requisitor_Memoria(int size) {
        if (size < MIN_SIZE || size > MAX_SIZE) {
            throw new IllegalArgumentException(
                "Tamanho deve estar entre " + MIN_SIZE + " e " + MAX_SIZE +
                " bytes. Recebido: " + size);
        }
        this.id   = nextId();
        this.size = size;
    }

    /**
     * Gera o próximo ID único de forma thread-safe.
     *
     * Região crítica: leitura + incremento de idCounter.
     * Protegida por idMutex (BinarySemaphore) para garantir que duas threads
     * não leiam o mesmo valor antes de incrementar.
     */
    private static int nextId() {
        try {
            idMutex.acquire();              // P — entra na região crítica
            try {
                return idCounter++;         // leitura + incremento atômico via mutex
            } finally {
                idMutex.release();          // V — sai da região crítica
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrompido ao gerar ID de requisição", e);
        }
    }

    /** Reseta o contador (apenas para testes isolados — não usar em produção). */
    static void resetIdCounter() {
        try {
            idMutex.acquire();
            try { idCounter = 1; }
            finally { idMutex.release(); }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public int getId()   { return id;   }
    public int getSize() { return size; }

    @Override
    public String toString() {
        return "Requisitor_Memoria{id=" + id + ", size=" + size + "B}";
    }
}
