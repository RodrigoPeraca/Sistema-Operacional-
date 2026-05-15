package model;

import java.util.Arrays;

/**
 * Representa a memória simulada (heap) do sistema.
 *
 * Internamente é um vetor de inteiros onde:
 *   - 0  → bloco livre
 *   - ID → bloco ocupado pela requisição de ID correspondente
 *
 * O tamanho é configurável na construção e não muda durante a execução.
 */
public class Heap {

    /** Valor que representa um bloco de memória livre. */
    public static final int FREE = 0;

    private final int   capacity;
    private final int[] memory;

    /**
     * Cria uma heap com o tamanho informado, totalmente livre.
     *
     * @param capacity número de blocos de memória (deve ser maior que zero)
     * @throws IllegalArgumentException se capacity for menor ou igual a zero
     */
    public Heap(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException(
                "A capacidade da heap deve ser maior que zero. Recebido: " + capacity
            );
        }
        this.capacity = capacity;
        this.memory   = new int[capacity]; // Java já inicializa com 0 (FREE)
    }

    // -------------------------------------------------------------------------
    // Leitura
    // -------------------------------------------------------------------------

    /**
     * Retorna a capacidade total da heap (número de blocos).
     *
     * @return capacidade configurada na construção
     */
    public int getCapacity() {
        return capacity;
    }

    /**
     * Retorna o valor armazenado em uma posição específica.
     * 0 = livre, qualquer outro valor = ID da requisição que ocupa o bloco.
     *
     * @param index posição a consultar
     * @return valor no índice informado
     * @throws IndexOutOfBoundsException se o índice for inválido
     */
    public int get(int index) {
        validateIndex(index);
        return memory[index];
    }

    /**
     * Verifica se um bloco específico está livre.
     *
     * @param index posição a verificar
     * @return {@code true} se o bloco estiver livre
     */
    public boolean isFree(int index) {
        validateIndex(index);
        return memory[index] == FREE;
    }

    /**
     * Retorna uma cópia do estado atual da memória (snapshot seguro).
     *
     * @return cópia do vetor interno
     */
    public int[] snapshot() {
        return Arrays.copyOf(memory, capacity);
    }

    // -------------------------------------------------------------------------
    // Escrita (acesso restrito ao pacote; services usam via métodos de negócio)
    // -------------------------------------------------------------------------

    /**
     * Marca um bloco como ocupado por uma requisição.
     *
     * @param index posição a ocupar
     * @param requestId ID da requisição dona do bloco
     */
    void set(int index, int requestId) {
        validateIndex(index);
        memory[index] = requestId;
    }

    /**
     * Libera um bloco, marcando-o como livre (FREE = 0).
     *
     * @param index posição a liberar
     */
    void free(int index) {
        validateIndex(index);
        memory[index] = FREE;
    }

    /**
     * Libera todos os blocos ocupados por uma requisição específica.
     *
     * @param requestId ID da requisição a liberar
     * @return número de blocos liberados
     */
    int freeAll(int requestId) {
        int released = 0;
        for (int i = 0; i < capacity; i++) {
            if (memory[i] == requestId) {
                memory[i] = FREE;
                released++;
            }
        }
        return released;
    }

    // -------------------------------------------------------------------------
    // Métricas
    // -------------------------------------------------------------------------

    /**
     * Conta quantos blocos estão livres.
     *
     * @return número de blocos livres
     */
    public int freeBlocks() {
        int count = 0;
        for (int v : memory) {
            if (v == FREE) count++;
        }
        return count;
    }

    /**
     * Conta quantos blocos estão ocupados.
     *
     * @return número de blocos ocupados
     */
    public int usedBlocks() {
        return capacity - freeBlocks();
    }

    // -------------------------------------------------------------------------
    // Utilitários
    // -------------------------------------------------------------------------

    private void validateIndex(int index) {
        if (index < 0 || index >= capacity) {
            throw new IndexOutOfBoundsException(
                "Índice " + index + " fora dos limites da heap (0–" + (capacity - 1) + ")"
            );
        }
    }

    @Override
    public String toString() {
        return "Heap{capacity=" + capacity
             + ", used=" + usedBlocks()
             + ", free=" + freeBlocks()
             + ", memory=" + Arrays.toString(memory) + "}";
    }
}
