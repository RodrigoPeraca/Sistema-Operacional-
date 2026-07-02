package model;

import sync.Semaphore;

import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Implementação do algoritmo Worst Fit com free list e sincronização por semáforo.
 *
 * ── Algoritmo ────────────────────────────────────────────────────────────────
 * Worst Fit: escolhe sempre o MAIOR bloco livre contíguo disponível.
 * Objetivo: deixar os buracos menores ainda úteis para requisições futuras.
 *
 * ── Free list ────────────────────────────────────────────────────────────────
 * Em vez de varrer o vetor da heap a cada alocação (O(n)), mantemos duas
 * estruturas complementares que rastreiam apenas os blocos livres:
 *
 *   sizeToIndices: TreeMap<tamanho, TreeSet<índices>>
 *     - lastKey() → maior bloco em O(log n)
 *     - inserção/remoção em O(log n)
 *
 *   indexToSize: TreeMap<índice, tamanho>
 *     - lookup por posição em O(log n), necessário para coalescência
 *
 * Complexidade: alocação O(log n), liberação O(log n), inicialização O(n).
 *
 * ── Região crítica e semáforo ────────────────────────────────────────────────
 * heap[] e a free list (sizeToIndices + indexToSize) formam uma única
 * região crítica: sempre devem estar consistentes entre si.
 *
 * heapMutex (BinarySemaphore) protege AMBAS as estruturas juntas.
 * Toda chamada a allocate() e deallocate() adquire o mutex antes de tocar
 * qualquer estrutura e o libera no finally — garantindo exclusão mútua.
 *
 * nextRequestId também é protegido pelo mesmo heapMutex porque é lido e
 * incrementado dentro de allocate(), que já está na região crítica.
 *
 * Métodos de consulta (snapshot, getCapacity, getTotalFreeMemory, etc.)
 * NÃO adquirem o mutex — são usados para monitoramento e podem ler um
 * estado ligeiramente defasado, o que é aceitável para logs e visualização.
 *
 * ── Coalescência ─────────────────────────────────────────────────────────────
 * Na liberação, blocos adjacentes são fundidos automaticamente:
 *   vizinho à direita → indexToSize.get(index + size)
 *   vizinho à esquerda → indexToSize.floorKey(index), verificando adjacência
 */
public class WorstFit {

    private final Heap heap;
    private final int totalSize;
    private final int heapSize;
    private int nextRequestId = 1;

    // ── Free list ─────────────────────────────────────────────────────────────
    private final TreeMap<Integer, TreeSet<Integer>> sizeToIndices = new TreeMap<>();
    private final TreeMap<Integer, Integer>          indexToSize   = new TreeMap<>();

    // ── Região crítica: protege heap[] + free list + nextRequestId ────────────
    private final Semaphore.BinarySemaphore heapMutex = new Semaphore.BinarySemaphore();
    // ─────────────────────────────────────────────────────────────────────────

    // ── Construtores ──────────────────────────────────────────────────────────

    public WorstFit(Heap heap) {
        if (heap == null) throw new IllegalArgumentException("Heap não pode ser nula");
        this.heap     = heap;
        this.heapSize = heap.getCapacity();
        this.totalSize = heapSize * 4;
        buildFreeList();
    }

    public WorstFit(int sizeInKB) {
        this(new Heap(calculateCapacity(sizeInKB)));
    }

    private static int calculateCapacity(int sizeInKB) {
        if (sizeInKB <= 0)
            throw new IllegalArgumentException("Tamanho deve ser maior que 0 KB");
        return (sizeInKB * 1024) / 4;
    }

    // ── Inicialização da free list ────────────────────────────────────────────

    /** Varre a heap uma única vez no construtor. Chamado antes de qualquer thread existir. */
    private void buildFreeList() {
        int i = 0;
        while (i < heapSize) {
            if (heap.isFree(i)) {
                int start = i, size = 0;
                while (i < heapSize && heap.isFree(i)) { size++; i++; }
                insertFreeBlock(start, size);
            } else {
                i++;
            }
        }
    }

    // ── Operações internas da free list (chamadas sempre dentro do heapMutex) ─

    private void insertFreeBlock(int index, int size) {
        sizeToIndices.computeIfAbsent(size, k -> new TreeSet<>()).add(index);
        indexToSize.put(index, size);
    }

    private void removeFreeBlock(int index, int size) {
        TreeSet<Integer> indices = sizeToIndices.get(size);
        if (indices != null) {
            indices.remove(index);
            if (indices.isEmpty()) sizeToIndices.remove(size);
        }
        indexToSize.remove(index);
    }

    // ── Alocação ──────────────────────────────────────────────────────────────

    /**
     * Aloca com ID gerado automaticamente.
     * REGIÃO CRÍTICA: protegida por heapMutex.
     */
    public int allocate(int sizeInBytes) {
        try {
            heapMutex.acquire();                    // P — entra na região crítica
            try {
                return allocateInternal(sizeInBytes, nextRequestId++);
            } finally {
                heapMutex.release();                // V — sai da região crítica
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }

    /**
     * Aloca com ID explícito (usado pelo benchmark e pela API).
     * REGIÃO CRÍTICA: protegida por heapMutex.
     */
    public int allocate(int sizeInBytes, int requestId) {
        if (sizeInBytes <= 0) throw new IllegalArgumentException("Tamanho deve ser > 0");
        if (requestId   <= 0) throw new IllegalArgumentException("requestId deve ser > 0");
        try {
            heapMutex.acquire();                    // P — entra na região crítica
            try {
                return allocateInternal(sizeInBytes, requestId);
            } finally {
                heapMutex.release();                // V — sai da região crítica
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }

    /**
     * Núcleo do Worst Fit — chamado SOMENTE dentro do heapMutex.
     * Não adquire lock; assume que o chamador já o fez.
     */
    private int allocateInternal(int sizeInBytes, int requestId) {
        int sizeInInts = (sizeInBytes + 3) / 4;
        if (sizeToIndices.isEmpty()) return -1;

        int largestSize = sizeToIndices.lastKey();      // O(log n) — Worst Fit
        if (largestSize < sizeInInts) return -1;

        int chosenIndex = sizeToIndices.get(largestSize).first();
        removeFreeBlock(chosenIndex, largestSize);

        for (int i = 0; i < sizeInInts; i++) {
            heap.set(chosenIndex + i, requestId);
        }

        int remainder = largestSize - sizeInInts;
        if (remainder > 0) {
            insertFreeBlock(chosenIndex + sizeInInts, remainder);
        }

        return chosenIndex;
    }

    // ── Liberação ─────────────────────────────────────────────────────────────

    /**
     * Libera o bloco e reinsere na free list com coalescência.
     * REGIÃO CRÍTICA: protegida por heapMutex.
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
        try {
            heapMutex.acquire();                    // P — entra na região crítica
            try {
                deallocateInternal(index, sizeInInts);
            } finally {
                heapMutex.release();                // V — sai da região crítica
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Núcleo da liberação com coalescência — chamado SOMENTE dentro do heapMutex.
     */
    private void deallocateInternal(int index, int sizeInInts) {
        for (int i = 0; i < sizeInInts; i++) heap.free(index + i);

        int mergedIndex = index;
        int mergedSize  = sizeInInts;

        // Coalescência: vizinho à direita
        Integer rightSize = indexToSize.get(mergedIndex + mergedSize);
        if (rightSize != null) {
            removeFreeBlock(mergedIndex + mergedSize, rightSize);
            mergedSize += rightSize;
        }

        // Coalescência: vizinho à esquerda
        Integer leftStart = indexToSize.floorKey(mergedIndex);
        if (leftStart != null) {
            int leftSize = indexToSize.get(leftStart);
            if (leftStart + leftSize == mergedIndex) {
                removeFreeBlock(leftStart, leftSize);
                mergedIndex = leftStart;
                mergedSize += leftSize;
            }
        }

        insertFreeBlock(mergedIndex, mergedSize);
    }

    // ── Métricas derivadas da free list (sem varredura da heap, sem lock) ─────

    public int getLargestFreeBlock() {
        return sizeToIndices.isEmpty() ? 0 : sizeToIndices.lastKey();
    }

    public int getTotalFreeMemory() {
        int total = 0;
        for (Map.Entry<Integer, TreeSet<Integer>> e : sizeToIndices.entrySet())
            total += e.getKey() * e.getValue().size();
        return total;
    }

    public int getTotalOccupiedMemory() {
        return heapSize - getTotalFreeMemory();
    }

    public double calculateExternalFragmentation() {
        int totalFree    = getTotalFreeMemory();
        int largestBlock = getLargestFreeBlock();
        if (totalFree == 0) return 0.0;
        return (double)(totalFree - largestBlock) / totalFree * 100.0;
    }

    // ── Estado e exibição ─────────────────────────────────────────────────────

    public void printHeapStatus() {
        System.out.println("\n========== ESTADO DA HEAP ==========");
        System.out.printf("Tamanho total: %d KB (%d bytes, %d inteiros)%n",
                totalSize / 1024, totalSize, heapSize);
        System.out.println("-----------------------------------");

        int i = 0, blockNum = 1, totalOcc = 0, totalFree = 0;
        while (i < heapSize) {
            int value = heap.get(i), size = 1, next = i + 1;
            while (next < heapSize && heap.get(next) == value) { size++; next++; }
            if (value == Heap.FREE) totalFree += size; else totalOcc += size;
            System.out.printf("Bloco %2d: [%6d-%6d] %-7s (%6d inteiros = %7d bytes)%n",
                    blockNum, i, i + size - 1,
                    value == Heap.FREE ? "LIVRE" : "OCUPADO", size, size * 4);
            i = next; blockNum++;
        }

        System.out.println("-----------------------------------");
        System.out.printf("Ocupada: %d int (%d bytes = %.2f%%)%n",
                totalOcc, totalOcc * 4, totalOcc * 100.0 / heapSize);
        System.out.printf("Livre:   %d int (%d bytes = %.2f%%)%n",
                totalFree, totalFree * 4, totalFree * 100.0 / heapSize);
        System.out.printf("Fragmentação externa: %.2f%%%n", calculateExternalFragmentation());
        System.out.println("====================================\n");
    }

    // ── Delegações ────────────────────────────────────────────────────────────

    public int[] snapshot()        { return heap.snapshot(); }
    public int   getCapacity()     { return heapSize; }
    public int   getCapacityInBytes() { return totalSize; }

    /** Acesso de pacote para GerenciadorLiberacao. */
    Heap getHeap() { return heap; }
}
