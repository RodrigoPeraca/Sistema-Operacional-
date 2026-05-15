package model;

/**
 * Implementação do algoritmo Worst Fit para alocação de memória em heap.
 *
 * Esta classe usa a implementação de heap fornecida em `Heap.java`.
 * A heap é representada como um vetor de inteiros no objeto `Heap`, onde:
 *   - 0  = bloco livre
 *   - ID = bloco ocupado pela requisição de memória com esse ID
 *
 * O Worst Fit percorre toda a heap e escolhe o maior bloco livre capaz de
 * atender a requisição.
 */
public class WorstFit {

    private final Heap heap;       // Heap usada como armazenamento real
    private final int totalSize;   // Tamanho total em bytes
    private final int heapSize;    // Tamanho total em número de inteiros (4 bytes cada)
    private int nextRequestId = 1; // ID interno para alocações sem requisição explícita

    /**
     * Cria um WorstFit usando a heap existente.
     *
     * @param heap heap já inicializada pelo usuário
     */
    public WorstFit(Heap heap) {
        if (heap == null) {
            throw new IllegalArgumentException("Heap não pode ser nula");
        }
        this.heap = heap;
        this.heapSize = heap.getCapacity();
        this.totalSize = heapSize * 4;
    }

    /**
     * Cria uma nova heap com tamanho definido pelo usuário em KB.
     *
     * @param sizeInKB tamanho total da heap em kilobytes
     */
    public WorstFit(int sizeInKB) {
        this(new Heap(calculateCapacity(sizeInKB)));
    }

    private static int calculateCapacity(int sizeInKB) {
        if (sizeInKB <= 0) {
            throw new IllegalArgumentException("Tamanho da heap deve ser maior que 0 KB");
        }
        return (sizeInKB * 1024) / 4;
    }

    /**
     * Aloca um bloco de memória para uma requisição sem ID explícito.
     *
     * @param sizeInBytes tamanho desejado em bytes
     * @return índice inicial do bloco alocado, ou -1 se não houver espaço suficiente
     */
    public int allocate(int sizeInBytes) {
        return allocate(sizeInBytes, nextRequestId++);
    }

    /**
     * Aloca um bloco de memória usando o ID da requisição.
     *
     * @param sizeInBytes tamanho desejado em bytes
     * @param requestId ID da requisição a ser gravado nos blocos
     * @return índice inicial do bloco alocado, ou -1 se não houver espaço suficiente
     */
    public int allocate(int sizeInBytes, int requestId) {
        if (sizeInBytes <= 0) {
            throw new IllegalArgumentException("Tamanho de alocação deve ser maior que zero");
        }
        if (requestId <= 0) {
            throw new IllegalArgumentException("requestId deve ser maior que zero");
        }

        int sizeInInts = (sizeInBytes + 3) / 4;
        int worstFitIndex = -1;
        int worstFitSize = -1;

        int currentIndex = 0;
        while (currentIndex < heapSize) {
            if (heap.isFree(currentIndex)) {
                int freeSize = 0;
                int scan = currentIndex;

                while (scan < heapSize && heap.isFree(scan)) {
                    freeSize++;
                    scan++;
                }

                if (freeSize >= sizeInInts && freeSize > worstFitSize) {
                    worstFitIndex = currentIndex;
                    worstFitSize = freeSize;
                }
                currentIndex = scan;
            } else {
                currentIndex++;
            }
        }

        if (worstFitIndex == -1) {
            return -1;
        }

        for (int i = 0; i < sizeInInts; i++) {
            heap.set(worstFitIndex + i, requestId);
        }

        return worstFitIndex;
    }

    /**
     * Libera um bloco de memória previamente alocado.
     *
     * @param index índice inicial do bloco a liberar
     * @param sizeInBytes tamanho do bloco em bytes
     */
    public void deallocate(int index, int sizeInBytes) {
        if (index < 0 || index >= heapSize) {
            System.err.println("Erro: índice inválido para liberação: " + index);
            return;
        }

        int sizeInInts = (sizeInBytes + 3) / 4;
        if (index + sizeInInts > heapSize) {
            System.err.println("Erro: tamanho inválido para liberação no índice: " + index);
            return;
        }

        for (int i = 0; i < sizeInInts; i++) {
            heap.free(index + i);
        }
    }

    /**
     * Exibe o estado atual da heap, indicando quais blocos estão livres e ocupados.
     */
    public void printHeapStatus() {
        System.out.println("\n========== ESTADO DA HEAP ==========");
        System.out.printf("Tamanho total: %d KB (%d bytes, %d inteiros)%n",
                totalSize / 1024, totalSize, heapSize);
        System.out.println("-----------------------------------");

        int currentIndex = 0;
        int blockNumber = 1;
        int totalOccupied = 0;
        int totalFree = 0;

        while (currentIndex < heapSize) {
            int value = heap.get(currentIndex);
            int size = 1;
            int nextIndex = currentIndex + 1;

            while (nextIndex < heapSize && heap.get(nextIndex) == value) {
                size++;
                nextIndex++;
            }

            String status = value == Heap.FREE ? "LIVRE" : "OCUPADO";
            if (value == Heap.FREE) {
                totalFree += size;
            } else {
                totalOccupied += size;
            }

            System.out.printf("Bloco %2d: [%6d-%6d] %-7s (%6d inteiros = %7d bytes)%n",
                    blockNumber, currentIndex, currentIndex + size - 1,
                    status, size, size * 4);

            currentIndex = nextIndex;
            blockNumber++;
        }

        System.out.println("-----------------------------------");
        System.out.printf("Memória ocupada: %d inteiros (%d bytes = %.2f%%)%n",
                totalOccupied, totalOccupied * 4,
                (totalOccupied * 100.0) / heapSize);
        System.out.printf("Memória livre:   %d inteiros (%d bytes = %.2f%%)%n",
                totalFree, totalFree * 4,
                (totalFree * 100.0) / heapSize);
        System.out.printf("Fragmentação externa: %.2f%%%n", calculateExternalFragmentation());
        System.out.println("====================================\n");
    }

    /**
     * Calcula a fragmentação externa atual da heap.
     *
     * @return percentual de fragmentação externa (0 a 100)
     */
    public double calculateExternalFragmentation() {
        int totalFreeSize = 0;
        int largestFreeBlock = 0;

        int currentIndex = 0;
        while (currentIndex < heapSize) {
            if (heap.isFree(currentIndex)) {
                int freeSize = 0;
                int scan = currentIndex;
                while (scan < heapSize && heap.isFree(scan)) {
                    freeSize++;
                    scan++;
                }
                totalFreeSize += freeSize;
                if (freeSize > largestFreeBlock) {
                    largestFreeBlock = freeSize;
                }
                currentIndex = scan;
            } else {
                currentIndex++;
            }
        }

        if (totalFreeSize == 0) {
            return 0.0;
        }

        return (double) (totalFreeSize - largestFreeBlock) / totalFreeSize * 100.0;
    }

    public int getLargestFreeBlock() {
        int largestFreeBlock = 0;
        int currentIndex = 0;

        while (currentIndex < heapSize) {
            if (heap.isFree(currentIndex)) {
                int freeSize = 0;
                int scan = currentIndex;
                while (scan < heapSize && heap.isFree(scan)) {
                    freeSize++;
                    scan++;
                }
                if (freeSize > largestFreeBlock) {
                    largestFreeBlock = freeSize;
                }
                currentIndex = scan;
            } else {
                currentIndex++;
            }
        }

        return largestFreeBlock;
    }

    public int getTotalFreeMemory() {
        int totalFree = 0;
        for (int i = 0; i < heapSize; i++) {
            if (heap.isFree(i)) {
                totalFree++;
            }
        }
        return totalFree;
    }

    public int getTotalOccupiedMemory() {
        return heapSize - getTotalFreeMemory();
    }

    public int[] snapshot() {
        return heap.snapshot();
    }

    public int getCapacity() {
        return heapSize;
    }

    public int getCapacityInBytes() {
        return totalSize;
    }

    Heap getHeap() {
        return heap;
    }
}
