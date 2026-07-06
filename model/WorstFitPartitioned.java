package model;

import sync.Semaphore;

import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Worst Fit com particionamento por segmentos — v4.
 *
 * ── Estratégia de afinidade por thread ───────────────────────────────────────
 * Cada thread tem uma repartição primária atribuída pelo benchmark (índice 0–3).
 * A thread tenta alocar PRIMEIRO na sua repartição. Só tenta outras se falhar.
 * Ordem de fallback: primária → demais em ordem crescente → cross-segment.
 *
 * Isso reduz contenção: threads diferentes raramente disputam o mesmo mutex
 * porque cada uma opera preferencialmente em sua própria repartição.
 *
 * O índice da thread é passado via nome: "part-worker-0", "part-worker-1", etc.
 * allocate() extrai o índice do nome com threadSegmentId().
 *
 * ── Estrutura ─────────────────────────────────────────────────────────────────
 * NUM_SEGMENTS = 4  (= número de threads)
 * Cada segmento: free list (sizeToIndices + indexToSize) + freeCount + mutex
 *
 * ── Invariantes garantidos ────────────────────────────────────────────────────
 * 1. heap.free() sempre dentro do mutex do segmento (sem janela de inconsistência)
 * 2. deallocateSlice() valida isFree() antes de liberar (sem dupla-liberação)
 * 3. Locks cross-segment sempre em ordem crescente de segId (sem deadlock)
 * 4. freeCount atualizado atomicamente com a free list (sempre consistente)
 */
public class WorstFitPartitioned {

    public static final int NUM_SEGMENTS = 4; // público — HeapBenchmark usa para THREAD_COUNT

    private final Heap heap;
    private final int  totalSize;
    private final int  heapSize;
    private final int  segmentSize;
    private int nextRequestId = 1;

    // ── Segmento ──────────────────────────────────────────────────────────────

    private static class Segment {
        final TreeMap<Integer, TreeSet<Integer>> sizeToIndices = new TreeMap<>();
        final TreeMap<Integer, Integer>          indexToSize   = new TreeMap<>();
        volatile int freeCount = 0;
        final Semaphore.BinarySemaphore mutex = new Semaphore.BinarySemaphore();

        void insert(int index, int size) {
            sizeToIndices.computeIfAbsent(size, k -> new TreeSet<>()).add(index);
            indexToSize.put(index, size);
            freeCount += size;
        }

        void remove(int index, int size) {
            TreeSet<Integer> set = sizeToIndices.get(size);
            if (set != null) {
                set.remove(index);
                if (set.isEmpty()) sizeToIndices.remove(size);
            }
            indexToSize.remove(index);
            freeCount -= size;
        }

        /** Lido sem lock — verifica null antes do unboxing para evitar NPE concorrente. */
        int peekLargest() {
            if (sizeToIndices.isEmpty()) return 0;
            Integer key = sizeToIndices.lastKey();
            return key == null ? 0 : key;
        }
    }

    private final Segment[] segments = new Segment[NUM_SEGMENTS];

    // ── Construtores ──────────────────────────────────────────────────────────

    public WorstFitPartitioned(Heap heap) {
        if (heap == null) throw new IllegalArgumentException("Heap não pode ser nula");
        this.heap        = heap;
        this.heapSize    = heap.getCapacity();
        this.totalSize   = heapSize * 4;
        this.segmentSize = (heapSize + NUM_SEGMENTS - 1) / NUM_SEGMENTS;
        for (int i = 0; i < NUM_SEGMENTS; i++) segments[i] = new Segment();
        buildFreeList();
    }

    public WorstFitPartitioned(int sizeInKB) {
        this(new Heap(calculateCapacity(sizeInKB)));
    }

    private static int calculateCapacity(int sizeInKB) {
        if (sizeInKB <= 0) throw new IllegalArgumentException("Tamanho deve ser > 0 KB");
        return (sizeInKB * 1024) / 4;
    }

    // ── Mapeamento índice → segmento ──────────────────────────────────────────

    private int getSegmentId(int index) {
        return Math.min(index / segmentSize, NUM_SEGMENTS - 1);
    }

    private int getSegmentStart(int segId) { return segId * segmentSize; }

    private int getSegmentEnd(int segId) {
        return Math.min((segId + 1) * segmentSize, heapSize);
    }

    // ── Inicialização ─────────────────────────────────────────────────────────

    private void buildFreeList() {
        int i = 0;
        while (i < heapSize) {
            if (heap.isFree(i)) {
                int start = i, size = 0;
                while (i < heapSize && heap.isFree(i)) { size++; i++; }
                splitAcrossSegments(start, size);
            } else { i++; }
        }
    }

    private void splitAcrossSegments(int start, int size) {
        int end = start + size;
        for (int segId = 0; segId < NUM_SEGMENTS; segId++) {
            int oStart = Math.max(start, getSegmentStart(segId));
            int oEnd   = Math.min(end,   getSegmentEnd(segId));
            if (oStart < oEnd) segments[segId].insert(oStart, oEnd - oStart);
        }
    }

    // ── Identificação da repartição da thread ─────────────────────────────────

    /**
     * Extrai o índice da repartição do nome da thread.
     *
     * Convenção: o benchmark nomeia as threads "part-worker-0", "part-worker-1", etc.
     * Se o nome não seguir essa convenção (thread externa, GerenciadorLiberacao, etc.),
     * retorna -1 — allocateWithId usará ordem 0,1,2,3 como fallback.
     *
     * Usar nome em vez de Thread.getId() porque getId() retorna valores altos
     * e imprevisíveis; % NUM_SEGMENTS distribui mal (depende do ID base da JVM).
     */
    private int threadSegmentId() {
        String name = Thread.currentThread().getName();
        // Espera formato "part-worker-N"
        int dash = name.lastIndexOf('-');
        if (dash < 0) return -1;
        try {
            int id = Integer.parseInt(name.substring(dash + 1));
            return (id >= 0 && id < NUM_SEGMENTS) ? id : id % NUM_SEGMENTS;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // ── Alocação com afinidade por thread ─────────────────────────────────────

    public int allocate(int sizeInBytes) {
        return allocateWithId(sizeInBytes, nextRequestId++);
    }

    public int allocate(int sizeInBytes, int requestId) {
        if (sizeInBytes <= 0) throw new IllegalArgumentException("Tamanho deve ser > 0");
        if (requestId   <= 0) throw new IllegalArgumentException("requestId deve ser > 0");
        return allocateWithId(sizeInBytes, requestId);
    }

    /**
     * Alocação em 3 fases com afinidade por thread:
     *
     * FASE 1 — Repartição primária da thread:
     *   Tenta alocar diretamente na repartição que pertence a esta thread.
     *   Se conseguir, retorna sem disputar nenhum outro segmento.
     *   Contenção mínima: cada thread opera preferencialmente em seu próprio segmento.
     *
     * FASE 2 — Fallback: demais repartições em ordem crescente:
     *   Se a repartição primária não comportar a requisição (cheia ou sem bloco
     *   suficiente), tenta as outras repartições na ordem 0, 1, 2, 3,
     *   pulando a primária já tentada.
     *
     * FASE 3 — Cross-segment:
     *   Último recurso: verifica fronteiras entre segmentos adjacentes.
     */
    private int allocateWithId(int sizeInBytes, int requestId) {
        int sizeInInts = (sizeInBytes + 3) / 4;
        int primary    = threadSegmentId(); // repartição desta thread (-1 se desconhecida)

        // FASE 1 — tenta a repartição primária primeiro
        if (primary >= 0) {
            int result = tryAllocateInSegment(primary, sizeInInts, requestId);
            if (result >= 0) return result;
        }

        // FASE 2 — tenta as demais em ordem crescente (pula a primária)
        for (int segId = 0; segId < NUM_SEGMENTS; segId++) {
            if (segId == primary) continue; // já tentou
            int result = tryAllocateInSegment(segId, sizeInInts, requestId);
            if (result >= 0) return result;
        }

        // FASE 3 — cross-segment: fronteiras entre segmentos adjacentes
        return allocateCrossSegment(sizeInInts, requestId);
    }

    /**
     * Tenta alocar em um segmento específico com lock.
     * Usa Worst Fit dentro do segmento: pega o maior bloco disponível.
     *
     * @return índice alocado, ou -1 se não houver bloco suficiente
     */
    private int tryAllocateInSegment(int segId, int sizeInInts, int requestId) {
        Segment seg = segments[segId];

        // Verificação rápida sem lock antes de adquiri-lo
        if (seg.peekLargest() < sizeInInts) return -1;

        try {
            seg.mutex.acquire();                        // P
            try {
                // Double-check após adquirir lock
                if (seg.sizeToIndices.isEmpty()) return -1;
                Integer largestKey = seg.sizeToIndices.lastKey();
                if (largestKey == null || largestKey < sizeInInts) return -1;

                TreeSet<Integer> indices = seg.sizeToIndices.get(largestKey);
                if (indices == null || indices.isEmpty()) return -1;

                int chosenIndex = indices.first();
                seg.remove(chosenIndex, largestKey);

                for (int j = 0; j < sizeInInts; j++) {
                    heap.set(chosenIndex + j, requestId);
                }

                int remainder = largestKey - sizeInInts;
                if (remainder > 0) seg.insert(chosenIndex + sizeInInts, remainder);

                return chosenIndex;

            } finally {
                seg.mutex.release();                    // V
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }

    /**
     * Fallback cross-segment: tenta fronteiras entre segmentos adjacentes.
     * Locks sempre em ordem crescente (segA antes segB) — sem deadlock.
     */
    private int allocateCrossSegment(int sizeInInts, int requestId) {
        for (int s = 0; s < NUM_SEGMENTS - 1; s++) {
            Segment segA = segments[s];
            Segment segB = segments[s + 1];
            int boundary = getSegmentEnd(s);

            try {
                segA.mutex.acquire();
                try {
                    segB.mutex.acquire();
                    try {
                        // Bloco em segA que termina exatamente na fronteira
                        Integer endBlockStart = segA.indexToSize.floorKey(boundary);
                        if (endBlockStart == null) continue;
                        int endBlockSize = segA.indexToSize.get(endBlockStart);
                        if (endBlockStart + endBlockSize != boundary) continue;

                        // Bloco em segB que começa exatamente na fronteira
                        Integer startBlockSize = segB.indexToSize.get(boundary);
                        if (startBlockSize == null) continue;

                        if (endBlockSize + startBlockSize < sizeInInts) continue;

                        segA.remove(endBlockStart, endBlockSize);
                        segB.remove(boundary, startBlockSize);

                        for (int j = 0; j < sizeInInts; j++) {
                            heap.set(endBlockStart + j, requestId);
                        }

                        int usedInA = Math.min(sizeInInts, endBlockSize);
                        int remA    = endBlockSize - usedInA;
                        if (remA > 0) segA.insert(endBlockStart, remA);

                        int usedInB = sizeInInts - usedInA;
                        int remB    = startBlockSize - usedInB;
                        if (remB > 0) segB.insert(boundary + usedInB, remB);

                        return endBlockStart;

                    } finally { segB.mutex.release(); }
                } finally { segA.mutex.release(); }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return -1;
            }
        }
        return -1;
    }

    // ── Liberação ─────────────────────────────────────────────────────────────

    /**
     * Divide o bloco liberado entre os segmentos que ele abrange.
     * heap.free() chamado DENTRO do mutex — sem janela de inconsistência.
     */
    public void deallocate(int index, int sizeInBytes) {
        if (index < 0 || index >= heapSize) {
            System.err.println("Erro: índice inválido: " + index); return;
        }
        int sizeInInts = (sizeInBytes + 3) / 4;
        if (index + sizeInInts > heapSize) {
            System.err.println("Erro: tamanho inválido no índice: " + index); return;
        }

        int end = index + sizeInInts;
        for (int segId = 0; segId < NUM_SEGMENTS; segId++) {
            int oStart = Math.max(index, getSegmentStart(segId));
            int oEnd   = Math.min(end,   getSegmentEnd(segId));
            if (oStart >= oEnd) continue;

            try {
                segments[segId].mutex.acquire();
                try {
                    deallocateSlice(segId, oStart, oEnd - oStart);
                } finally {
                    segments[segId].mutex.release();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * Libera fatia na heap e na free list, com coalescência.
     * Valida isFree() antes de liberar cada posição — proteção contra dupla-liberação.
     * Chamado SOMENTE dentro do mutex do segmento.
     */
    private void deallocateSlice(int segId, int sliceStart, int sliceSize) {
        Segment seg = segments[segId];

        int actuallyFreed = 0;
        for (int i = 0; i < sliceSize; i++) {
            if (!heap.isFree(sliceStart + i)) {
                heap.free(sliceStart + i);
                actuallyFreed++;
            }
        }
        if (actuallyFreed == 0) return; // dupla-liberação detectada

        int mergedIndex = sliceStart;
        int mergedSize  = actuallyFreed == sliceSize ? sliceSize : actuallyFreed;
        int segStart    = getSegmentStart(segId);
        int segEnd      = getSegmentEnd(segId);

        // Coalescência: vizinho à direita
        if (mergedIndex + mergedSize < segEnd) {
            Integer rightSize = seg.indexToSize.get(mergedIndex + mergedSize);
            if (rightSize != null) {
                seg.remove(mergedIndex + mergedSize, rightSize);
                mergedSize += rightSize;
            }
        }

        // Coalescência: vizinho à esquerda
        if (mergedIndex > segStart) {
            Integer leftStart = seg.indexToSize.floorKey(mergedIndex);
            if (leftStart != null && leftStart >= segStart) {
                int leftSize = seg.indexToSize.get(leftStart);
                if (leftStart + leftSize == mergedIndex) {
                    seg.remove(leftStart, leftSize);
                    mergedIndex = leftStart;
                    mergedSize += leftSize;
                }
            }
        }

        seg.insert(mergedIndex, mergedSize);
    }

    // ── Métricas ──────────────────────────────────────────────────────────────

    /** Soma freeCount sem lock — int volatile é atômico na JVM (JLS §17.7). */
    public int getTotalFreeMemory() {
        int total = 0;
        for (Segment seg : segments) total += seg.freeCount;
        return total;
    }

    public int getLargestFreeBlock() {
        int largest = 0;
        for (Segment seg : segments) largest = Math.max(largest, seg.peekLargest());
        return largest;
    }

    public int getTotalOccupiedMemory() { return heapSize - getTotalFreeMemory(); }

    public double calculateExternalFragmentation() {
        int totalFree = getTotalFreeMemory();
        int largest   = getLargestFreeBlock();
        if (totalFree == 0) return 0.0;
        return (double)(totalFree - largest) / totalFree * 100.0;
    }

    // ── Diagnóstico (single-thread only) ─────────────────────────────────────

    public void verificarIntegridade() {
        int[] snap = heap.snapshot();
        int[] realFree = new int[NUM_SEGMENTS];
        for (int i = 0; i < heapSize; i++) {
            if (snap[i] == Heap.FREE) realFree[getSegmentId(i)]++;
        }
        System.out.println("=== DIAGNÓSTICO DE INTEGRIDADE ===");
        int totFL = 0, totReal = 0;
        for (int s = 0; s < NUM_SEGMENTS; s++) {
            totFL   += segments[s].freeCount;
            totReal += realFree[s];
            System.out.printf("  Seg[%d]: freeCount=%d  realFree=%d  %s%n",
                s, segments[s].freeCount, realFree[s],
                segments[s].freeCount == realFree[s] ? "OK" : "DIVERGÊNCIA");
        }
        System.out.printf("  TOTAL: freeList=%d  realHeap=%d  %s%n",
            totFL, totReal, totFL == totReal ? "OK" : "DIVERGÊNCIA");
        System.out.println("==================================");
    }

    // ── Delegações ────────────────────────────────────────────────────────────

    public int[] snapshot()           { return heap.snapshot(); }
    public int   getCapacity()        { return heapSize; }
    public int   getCapacityInBytes() { return totalSize; }
    Heap         getHeap()            { return heap; }
}