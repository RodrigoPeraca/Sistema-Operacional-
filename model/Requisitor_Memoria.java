package model;
 
import java.util.concurrent.atomic.AtomicInteger;
 
/**
 * Representa um pedido de alocação de memória no simulador.
 *
 * Cada requisição possui:
 *   - um ID único gerado automaticamente
 *   - um tamanho (em unidades de memória) solicitado
 */
public class Requisitor_Memoria {
 
    // Contador estático thread-safe para geração de IDs únicos
    private static final AtomicInteger idCounter = new AtomicInteger(1);
 
    private final int id;
    private final int size;
 
    /**
     * Cria uma nova requisição de memória com ID gerado automaticamente.
     *
     * @param size tamanho de memória solicitado (deve ser maior que zero)
     * @throws IllegalArgumentException se o tamanho for menor ou igual a zero
     */
    public Requisitor_Memoria(int size) {
        if (size <= 0) {
            throw new IllegalArgumentException(
                "O tamanho da requisição deve ser maior que zero. Recebido: " + size
            );
        }
        this.id   = idCounter.getAndIncrement();
        this.size = size;
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
