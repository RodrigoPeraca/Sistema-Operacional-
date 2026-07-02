package model;

/**
 * Representa a heap simulada como um vetor de inteiros.
 *
 * Cada posição equivale a 4 bytes.
 *   - 0  (FREE) = posição livre
 *   - ID > 0    = posição ocupada pela requisição com esse ID
 *
 * Esta classe não tem conhecimento de threads nem de semáforos.
 * A sincronização é responsabilidade de quem a usa (WorstFit).
 */
public class Heap {

    public static final int FREE = 0;

    private final int[] data;

    public Heap(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacidade deve ser maior que 0");
        }
        this.data = new int[capacity];
    }

    public int getCapacity() {
        return data.length;
    }

    public int get(int index) {
        return data[index];
    }

    /** Marca a posição com o ID da requisição. Acesso de pacote — só WorstFit chama. */
    void set(int index, int requestId) {
        data[index] = requestId;
    }

    /** Libera a posição (volta para FREE). Acesso de pacote — só WorstFit chama. */
    void free(int index) {
        data[index] = FREE;
    }

    public boolean isFree(int index) {
        return data[index] == FREE;
    }

    public int countFree() {
        int count = 0;
        for (int v : data) if (v == FREE) count++;
        return count;
    }

    public int countOccupied() {
        return data.length - countFree();
    }

    /** Retorna uma cópia do estado atual — snapshot imutável para log e visualização. */
    public int[] snapshot() {
        return data.clone();
    }
}
