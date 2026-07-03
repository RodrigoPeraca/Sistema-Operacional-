package model;

import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Implementação do algoritmo Worst Fit SEM sincronização — apenas para sequencial.
 *
 * ── Diferença do WorstFitSynchronized ──────────────────────────────────────
 * Este é um Worst Fit "puro" sem heapMutex.
 * Usado para benchmark sequencial — linha de base sem overhead de sincronização.
 * NÃO é thread-safe; usar apenas com 1 thread.
 *
 * ── Algoritmo ────────────────────────────────────────────────────────────────
 * Worst Fit: escolhe sempre o MAIOR bloco livre contíguo disponível.
 * Free list: TreeMap<tamanho, TreeSet<índices>> para O(log n).
 * Coalescência: blocos adjacentes são fundidos na liberação.
 */
public class WorstFitUnsafe {

    private final Heap heap;
    private final int totalSize;
    private final int heapSize;
    private int nextRequestId = 1;

    // ── Free list ─────────────────────────────────────────────────────────────
    private final TreeMap<Integer, TreeSet<Integer>> sizeToIndices = new TreeMap<>();
    private final TreeMap<Integer, Integer>          indexToSize   = new TreeMap<>();

    // ── Construtores ──────────────────────────────────────────────────────────

    public WorstFitUnsafe(Heap heap) {
        if (heap == null) throw new IllegalArgumentException("Heap não pode ser nula");
        this.heap     = heap;
        this.heapSize = heap.getCapacity();
        this.totalSize = heapSize * 4;
        buildFreeList();
    }

    public WorstFitUnsafe(int sizeInKB) {
        this(new Heap(calculateCapacity(sizeInKB)));
    }

    private static int calculateCapacity(int sizeInKB) {
        if (sizeInKB <= 0)
            throw new IllegalArgumentException("Tamanho deve ser maior que 0 KB");
        return (sizeInKB * 1024) / 4;
    }

    // ── Inicialização da free list ────────────────────────────────────────────

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

    // ── Operações internas da free list ───────────────────────────────────────

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

    // ── Alocação (SEM LOCK) ───────────────────────────────────────────────────

    public int allocate(int sizeInBytes) {
        return allocateInternal(sizeInBytes, nextRequestId++);
    }

    public int allocate(int sizeInBytes, int requestId) {
        if (sizeInBytes <= 0) throw new IllegalArgumentException("Tamanho deve ser > 0");
        if (requestId   <= 0) throw new IllegalArgumentException("requestId deve ser > 0");
        return allocateInternal(sizeInBytes, requestId);
    }

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

    // ── Liberação (SEM LOCK) ──────────────────────────────────────────────────

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
        deallocateInternal(index, sizeInInts);
    }

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

    // ── Métricas ──────────────────────────────────────────────────────────────

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

    // ── Delegações ────────────────────────────────────────────────────────────

    public int[] snapshot()        { return heap.snapshot(); }
    public int   getCapacity()     { return heapSize; }
    public int   getCapacityInBytes() { return totalSize; }

    Heap getHeap() { return heap; }
}
