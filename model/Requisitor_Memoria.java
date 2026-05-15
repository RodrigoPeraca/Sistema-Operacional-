package model;
 
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Random;
 
/**
 * Representa um pedido de alocação de memória no simulador.
 *
 * Cada requisição possui:
 *   - um ID único gerado automaticamente
 *   - um tamanho (em bytes) solicitado, entre 16 e 1024 bytes
 */
public class Requisitor_Memoria {
 
    // Constantes de tamanho
    public static final int MIN_SIZE = 16;      // Tamanho mínimo: 16 bytes
    public static final int MAX_SIZE = 1024;    // Tamanho máximo: 1 KB
 
    // Contador estático thread-safe para geração de IDs únicos
    private static final AtomicInteger idCounter = new AtomicInteger(1);
    private static final Random random = new Random();
 
    private final int id;
    private final int size;
 
    /**
     * Cria uma nova requisição de memória com tamanho ALEATÓRIO entre 16 e 1024 bytes.
     * ID é gerado automaticamente.
     */
    public Requisitor_Memoria() {
        this(generateRandomSize());
    }
 
    /**
     * Cria uma nova requisição de memória com tamanho específico.
     *
     * @param size tamanho de memória solicitado em bytes
     * @throws IllegalArgumentException se o tamanho for menor que 16 ou maior que 1024
     */
    public Requisitor_Memoria(int size) {
        if (size < MIN_SIZE || size > MAX_SIZE) {
            throw new IllegalArgumentException(
                "O tamanho da requisição deve estar entre " + MIN_SIZE + 
                " e " + MAX_SIZE + " bytes. Recebido: " + size
            );
        }
        this.id   = idCounter.getAndIncrement();
        this.size = size;
    }
 
    /**
     * Gera um tamanho aleatório entre 16 e 1024 bytes.
     *
     * @return tamanho aleatório em bytes
     */
    private static int generateRandomSize() {
        return MIN_SIZE + random.nextInt(MAX_SIZE - MIN_SIZE + 1);
    }
 
    /**
     * Retorna o ID único desta requisição.
     *
     * @return ID da requisição
     */
    public int getId() {
        return id;
    }
 
    /**
     * Retorna o tamanho de memória solicitado por esta requisição.
     *
     * @return tamanho em unidades de memória
     */
    public int getSize() {
        return size;
    }
 
    /**
     * Reseta o contador de IDs (útil apenas para testes isolados).
     * NÃO deve ser chamado em produção.
     */
    static void resetIdCounter() {
        idCounter.set(1);
    }
 
    @Override
    public String toString() {
        return "MemoryRequest{id=" + id + ", size=" + size + "}";
    }
}
