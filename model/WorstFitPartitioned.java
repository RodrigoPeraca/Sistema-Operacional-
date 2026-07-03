package model;

import sync.Semaphore;

import java.util.Map;
import java.util.NoSuchElementException;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Implementação do algoritmo Worst Fit com particionamento por segmentos.
 *
 * ── Estratégia ─────────────────────────────────────────────────────────────
 * Em vez de 1 mutex para toda a heap (heapMutex), dividimos a heap em N
 * segmentos (ex: 4 ou 8), cada um com seu próprio semáforo.
 *
 * Vantagem: Múltiplos threads podem alocar em segmentos DIFERENTES
 * simultaneamente, gerando verdadeiro paralelismo.
 *
 * ── Implementação ──────────────────────────────────────────────────────────
 * - heapSize dividido em segmentos de tamanho heapSize / NUM_SEGMENTS
 * - Cada segmento tem:
 *   * sizeToIndices[] / indexToSize[] locais (livre list do segmento)
 *   * semáforo mutex próprio
 *
 * - allocate(): Procura o MAIOR bloco entre TODOS os segmentos (sem lock)
 *   Depois faz lock APENAS do segmento escolhido.
 *
 * - deallocate(): Faz lock apenas do segmento contendo o índice a liberar.
 *
 * Coalescência: dentro do segmento, só pode mesclar blocos do mesmo segmento.
 * Se blocos vizinhos estão em segmentos diferentes, coalescência é impossível.
 */
public class WorstFitPartitioned {

    private static final int NUM_SEGMENTS = 4;  // configurável

    private final Heap heap;
    private final int totalSize;
    private final int heapSize;
    private final int segmentSize;
    private int nextRequestId = 1;

    // ── Segmentos: cada um com free list + mutex próprio ─────────────────────
    private static class Segment {
        TreeMap<Integer, TreeSet<Integer>> sizeToIndices = new TreeMap<>();
        TreeMap<Integer, Integer>          indexToSize   = new TreeMap<>();
        Semaphore.BinarySemaphore          mutex         = new Semaphore.BinarySemaphore();
    }

    private final Segment[] segments = new Segment[NUM_SEGMENTS];

    // ── Construtor ─────────────────────────────────────────────────────────────

    public WorstFitPartitioned(Heap heap) {
        if (heap == null) throw new IllegalArgumentException("Heap não pode ser nula");
        this.heap      = heap;
        this.heapSize  = heap.getCapacity();
        this.totalSize = heapSize * 4;
        this.segmentSize = (heapSize + NUM_SEGMENTS - 1) / NUM_SEGMENTS;

        for (int i = 0; i < NUM_SEGMENTS; i++) {
            segments[i] = new Segment();
        }
        buildFreeList();
    }

    public WorstFitPartitioned(int sizeInKB) {
        this(new Heap(calculateCapacity(sizeInKB)));
    }

    private static int calculateCapacity(int sizeInKB) {
        if (sizeInKB <= 0)
            throw new IllegalArgumentException("Tamanho deve ser maior que 0 KB");
        return (sizeInKB * 1024) / 4;
    }

    // ── Mapeamento: índice → segmento ──────────────────────────────────────────

    private int getSegmentId(int index) {
        return Math.min(index / segmentSize, NUM_SEGMENTS - 1);
    }

    private int getSegmentStart(int segmentId) {
        return segmentId * segmentSize;
    }

    private int getSegmentEnd(int segmentId) {
        return Math.min((segmentId + 1) * segmentSize, heapSize);
    }

    // ── Inicialização da free list ─────────────────────────────────────────────

    private void buildFreeList() {
        int i = 0;
        while (i < heapSize) {
            if (heap.isFree(i)) {
                int start = i, size = 0;
                while (i < heapSize && heap.isFree(i)) { size++; i++; }
                // Bloco pode cruzar segmentos — dividir se necessário
                insertFreeBlockCrossingSegments(start, size);
            } else {
                i++;
            }
        }
    }

    private void insertFreeBlockCrossingSegments(int start, int size) {
        int end = start + size;
        for (int segId = 0; segId < NUM_SEGMENTS; segId++) {
            int segStart = getSegmentStart(segId);
            int segEnd   = getSegmentEnd(segId);

            int overlapStart = Math.max(start, segStart);
            int overlapEnd   = Math.min(end, segEnd);

            if (overlapStart < overlapEnd) {
                Segment seg = segments[segId];
                int blockSize = overlapEnd - overlapStart;
                seg.sizeToIndices.computeIfAbsent(blockSize, k -> new TreeSet<>())
                    .add(overlapStart);
                seg.indexToSize.put(overlapStart, blockSize);
            }
        }
    }

    // ── Alocação com busca paralela ────────────────────────────────────────────

    public int allocate(int sizeInBytes) {
        return allocateWithId(sizeInBytes, nextRequestId++);
    }

    public int allocate(int sizeInBytes, int requestId) {
        if (sizeInBytes <= 0) throw new IllegalArgumentException("Tamanho deve ser > 0");
        if (requestId   <= 0) throw new IllegalArgumentException("requestId deve ser > 0");
        return allocateWithId(sizeInBytes, requestId);
    }

    private int allocateWithId(int sizeInBytes, int requestId) {
        int sizeInInts = (sizeInBytes + 3) / 4;

        // Estratégia: tenta cada segmento em ordem (0, 1, 2, ...)
        for (int i = 0; i < NUM_SEGMENTS; i++) {
            Segment seg = segments[i];

            // Verifica apenas se isEmpty() sem lock (otimização)
            if (seg.sizeToIndices.isEmpty()) continue;

            // Tenta alocar neste segmento COM lock
            try {
                seg.mutex.acquire();
                try {
                    // Verifica novamente após lock
                    if (seg.sizeToIndices.isEmpty()) continue;  // Outro thread consumiu
                    
                    int currentLargestSize = 0;
                    try {
                        currentLargestSize = seg.sizeToIndices.lastKey();
                    } catch (NoSuchElementException e) {
                        continue;  // Esvaziou entre isEmpty() e lastKey()
                    }
                    
                    if (currentLargestSize < sizeInInts) continue;  // Insuficiente

                    // Aloca!
                    TreeSet<Integer> indices = seg.sizeToIndices.get(currentLargestSize);
                    if (indices == null || indices.isEmpty()) continue;  // Race condition
                    int chosenIndex = indices.first();
                    removeFreeBlockInSegment(i, chosenIndex, currentLargestSize);

                    for (int j = 0; j < sizeInInts; j++) {
                        heap.set(chosenIndex + j, requestId);
                    }

                    int remainder = currentLargestSize - sizeInInts;
                    if (remainder > 0) {
                        insertFreeBlockInSegment(i, chosenIndex + sizeInInts, remainder);
                    }

                    return chosenIndex;  // Sucesso!
                } finally {
                    seg.mutex.release();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return -1;
            }
        }

        // Nenhum segmento tinha espaço suficiente
        return -1;
    }

    // ── Liberação ──────────────────────────────────────────────────────────────

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

        int segId = getSegmentId(index);
        Segment seg = segments[segId];
        try {
            seg.mutex.acquire();
            try {
                deallocateInternal(segId, index, sizeInInts);
            } finally {
                seg.mutex.release();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void deallocateInternal(int segId, int index, int sizeInInts) {
        for (int i = 0; i < sizeInInts; i++) heap.free(index + i);

        Segment seg = segments[segId];
        int mergedIndex = index;
        int mergedSize  = sizeInInts;

        // Coalescência dentro do segmento (vizinhos devem estar no mesmo segmento)
        int segStart = getSegmentStart(segId);
        int segEnd   = getSegmentEnd(segId);

        // Vizinho à direita (se está no mesmo segmento)
        if (mergedIndex + mergedSize < segEnd) {
            Integer rightSize = seg.indexToSize.get(mergedIndex + mergedSize);
            if (rightSize != null) {
                removeFreeBlockInSegment(segId, mergedIndex + mergedSize, rightSize);
                mergedSize += rightSize;
            }
        }

        // Vizinho à esquerda (se está no mesmo segmento)
        if (mergedIndex > segStart) {
            Integer leftStart = seg.indexToSize.floorKey(mergedIndex);
            if (leftStart != null && leftStart >= segStart) {
                int leftSize = seg.indexToSize.get(leftStart);
                if (leftStart + leftSize == mergedIndex) {
                    removeFreeBlockInSegment(segId, leftStart, leftSize);
                    mergedIndex = leftStart;
                    mergedSize += leftSize;
                }
            }
        }

        insertFreeBlockInSegment(segId, mergedIndex, mergedSize);
    }

    // ── Operações internas de free list por segmento ────────────────────────────

    private void insertFreeBlockInSegment(int segId, int index, int size) {
        Segment seg = segments[segId];
        seg.sizeToIndices.computeIfAbsent(size, k -> new TreeSet<>()).add(index);
        seg.indexToSize.put(index, size);
    }

    private void removeFreeBlockInSegment(int segId, int index, int size) {
        Segment seg = segments[segId];
        TreeSet<Integer> indices = seg.sizeToIndices.get(size);
        if (indices != null) {
            indices.remove(index);
            if (indices.isEmpty()) seg.sizeToIndices.remove(size);
        }
        seg.indexToSize.remove(index);
    }

    // ── Métricas ───────────────────────────────────────────────────────────────

    public int getLargestFreeBlock() {
        int largest = 0;
        for (Segment seg : segments) {
            if (!seg.sizeToIndices.isEmpty()) {
                largest = Math.max(largest, seg.sizeToIndices.lastKey());
            }
        }
        return largest;
    }

    public int getTotalFreeMemory() {
        int total = 0;
        // Adquire locks de todos os segmentos para leitura segura
        for (Segment seg : segments) {
            try {
                seg.mutex.acquire();
                try {
                    for (Map.Entry<Integer, TreeSet<Integer>> e : seg.sizeToIndices.entrySet()) {
                        if (e.getValue() != null) {
                            total += e.getKey() * e.getValue().size();
                        }
                    }
                } finally {
                    seg.mutex.release();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return -1;  // Erro: não conseguiu calcular
            }
        }
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

    // ── Delegações ─────────────────────────────────────────────────────────────

    public int[] snapshot()        { return heap.snapshot(); }
    public int   getCapacity()     { return heapSize; }
    public int   getCapacityInBytes() { return totalSize; }

    Heap getHeap() { return heap; }
}
