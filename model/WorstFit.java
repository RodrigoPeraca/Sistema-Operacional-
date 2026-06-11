package model;

import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Implementação do algoritmo Worst Fit para alocação de memória em heap.
 *
 * Esta classe usa a implementação de heap fornecida em {@code Heap.java}.
 * A heap é representada como um vetor de inteiros no objeto {@code Heap}, onde:
 *   - 0  = bloco livre
 *   - ID = bloco ocupado pela requisição de memória com esse ID
 *
 * O Worst Fit percorre toda a heap e escolhe o maior bloco livre capaz de
 * atender a requisição.
 *
 * <h2>Free list</h2>
 * Em vez de varrer o vetor da heap a cada alocação, esta implementação mantém
 * internamente uma <em>free list</em> — uma estrutura que rastreia apenas os
 * blocos livres existentes, sempre ordenada por tamanho decrescente.
 *
 * <p>A free list é composta por duas estruturas complementares:
 * <ul>
 *   <li>{@code sizeToIndices}: {@code TreeMap<Integer, TreeSet<Integer>>} onde
 *       a chave é o tamanho do bloco (em inteiros) e o valor é um conjunto
 *       ordenado de índices iniciais de blocos com aquele tamanho. Permite
 *       encontrar o maior bloco disponível em O(log n) via {@code lastKey()},
 *       e inserir/remover blocos em O(log n).</li>
 *   <li>{@code indexToSize}: {@code TreeMap<Integer, Integer>} onde a chave é
 *       o índice inicial do bloco livre e o valor é seu tamanho. Permite
 *       localizar um bloco pelo índice em O(log n), essencial para a
 *       coalescência durante a liberação.</li>
 * </ul>
 *
 * <h2>Coalescência</h2>
 * Na liberação ({@code deallocate}), o bloco devolvido é verificado contra
 * seus vizinhos imediatos na free list:
 * <ul>
 *   <li>Vizinho à direita: existe algum bloco livre que começa em
 *       {@code index + size}? Consultado em O(log n) via {@code indexToSize}.</li>
 *   <li>Vizinho à esquerda: existe algum bloco livre que termina em
 *       {@code index - 1}? Consultado via {@code indexToSize.floorKey(index)},
 *       verificando se {@code floorKey + tamanho == index}.</li>
 * </ul>
 * Blocos adjacentes são fundidos antes da reinserção, mantendo a free list
 * sem fragmentação interna.
 *
 * <h2>Complexidade</h2>
 * <ul>
 *   <li>Inicialização: O(n) — uma única varredura da heap.</li>
 *   <li>Alocação: O(log n) — lookup do maior bloco + atualização da free list.</li>
 *   <li>Liberação: O(log n) — coalescência + reinserção na free list.</li>
 *   <li>Métricas ({@code getLargestFreeBlock}, {@code getTotalFreeMemory},
 *       {@code calculateExternalFragmentation}): O(log n) ou O(k), onde k é
 *       o número de blocos livres — sem varredura da heap.</li>
 * </ul>
 */
public class WorstFit {

    private final Heap heap;
    private final int totalSize;
    private final int heapSize;
    private int nextRequestId = 1;

    /**
     * Free list indexada por tamanho → conjunto de índices iniciais.
     * {@code lastKey()} retorna o tamanho do maior bloco disponível em O(log n).
     */
    private final TreeMap<Integer, TreeSet<Integer>> sizeToIndices = new TreeMap<>();

    /**
     * Índice auxiliar da free list: índice inicial → tamanho do bloco.
     * Permite localizar e remover um bloco pelo índice em O(log n),
     * necessário para a coalescência.
     */
    private final TreeMap<Integer, Integer> indexToSize = new TreeMap<>();

    // -------------------------------------------------------------------------
    // Construtores
    // -------------------------------------------------------------------------

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
        buildFreeList();
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

    // -------------------------------------------------------------------------
    // Inicialização da free list
    // -------------------------------------------------------------------------

    /**
     * Varre a heap uma única vez no construtor e popula a free list com todos
     * os blocos livres encontrados. Blocos contíguos são agrupados em uma única
     * entrada. Complexidade: O(n).
     */
    private void buildFreeList() {
        int i = 0;
        while (i < heapSize) {
            if (heap.isFree(i)) {
                int start = i;
                int size = 0;
                while (i < heapSize && heap.isFree(i)) {
                    size++;
                    i++;
                }
                insertFreeBlock(start, size);
            } else {
                i++;
            }
        }
    }

    // -------------------------------------------------------------------------
    // Operações internas da free list
    // -------------------------------------------------------------------------

    /**
     * Insere um bloco livre nas duas estruturas da free list.
     *
     * @param index índice inicial do bloco na heap
     * @param size  tamanho do bloco em inteiros
     */
    private void insertFreeBlock(int index, int size) {
        sizeToIndices
            .computeIfAbsent(size, k -> new TreeSet<>())
            .add(index);
        indexToSize.put(index, size);
    }

    /**
     * Remove um bloco livre das duas estruturas da free list.
     * Se o tamanho associado ao índice não corresponder a {@code size},
     * o comportamento é indefinido — sempre remova com os valores corretos.
     *
     * @param index índice inicial do bloco na heap
     * @param size  tamanho do bloco em inteiros
     */
    private void removeFreeBlock(int index, int size) {
        TreeSet<Integer> indices = sizeToIndices.get(size);
        if (indices != null) {
            indices.remove(index);
            if (indices.isEmpty()) {
                sizeToIndices.remove(size);
            }
        }
        indexToSize.remove(index);
    }

    // -------------------------------------------------------------------------
    // Alocação
    // -------------------------------------------------------------------------

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
     * <p>Worst Fit: seleciona o maior bloco livre disponível que comporte a
     * requisição. Com {@code sizeToIndices} ordenado por tamanho crescente,
     * {@code lastKey()} retorna o maior tamanho em O(log n). Se esse maior
     * bloco não comportar a requisição, nenhum outro comportará — retorna -1
     * sem varrer a heap.
     *
     * <p>Após alocar, a free list é atualizada:
     * <ul>
     *   <li>Se o bloco foi totalmente consumido: remove-o da free list.</li>
     *   <li>Se sobrou espaço: o bloco residual (índice avançado, tamanho reduzido)
     *       é reinserido mantendo a ordenação.</li>
     * </ul>
     *
     * @param sizeInBytes tamanho desejado em bytes
     * @param requestId   ID da requisição a ser gravado nos blocos
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

        // Sem nenhum bloco livre ou o maior bloco não comporta a requisição
        if (sizeToIndices.isEmpty()) {
            return -1;
        }
        int largestSize = sizeToIndices.lastKey();
        if (largestSize < sizeInInts) {
            return -1;
        }

        // Worst Fit: usar o maior bloco disponível
        TreeSet<Integer> candidates = sizeToIndices.get(largestSize);
        int chosenIndex = candidates.first(); // índice menor entre os de mesmo tamanho

        // Remover o bloco escolhido da free list
        removeFreeBlock(chosenIndex, largestSize);

        // Gravar o ID na heap
        for (int i = 0; i < sizeInInts; i++) {
            heap.set(chosenIndex + i, requestId);
        }

        // Se sobrou espaço no bloco, reinsere o restante na free list
        int remainder = largestSize - sizeInInts;
        if (remainder > 0) {
            int residualIndex = chosenIndex + sizeInInts;
            insertFreeBlock(residualIndex, remainder);
        }

        return chosenIndex;
    }

    // -------------------------------------------------------------------------
    // Liberação
    // -------------------------------------------------------------------------

    /**
     * Libera um bloco de memória previamente alocado.
     *
     * <p>Após liberar os slots na heap, o bloco devolvido é reinserido na
     * free list com coalescência: blocos adjacentes (à esquerda e à direita)
     * são fundidos antes da inserção, evitando a fragmentação da própria
     * free list.
     *
     * <p>Coalescência com vizinho à direita: verifica se {@code indexToSize}
     * contém a chave {@code index + size} — O(log n).
     * <br>
     * Coalescência com vizinho à esquerda: usa {@code indexToSize.floorKey(index)}
     * para encontrar o bloco imediatamente anterior e verifica se ele termina
     * exatamente em {@code index} — O(log n).
     *
     * @param index       índice inicial do bloco a liberar
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

        // Liberar os slots na heap
        for (int i = 0; i < sizeInInts; i++) {
            heap.free(index + i);
        }

        // Coalescência: inicia com o bloco devolvido e expande se houver vizinhos livres
        int mergedIndex = index;
        int mergedSize  = sizeInInts;

        // Vizinho à direita: começa em (mergedIndex + mergedSize)?
        Integer rightSize = indexToSize.get(mergedIndex + mergedSize);
        if (rightSize != null) {
            removeFreeBlock(mergedIndex + mergedSize, rightSize);
            mergedSize += rightSize;
        }

        // Vizinho à esquerda: existe bloco que termina exatamente em mergedIndex?
        Integer leftStart = indexToSize.floorKey(mergedIndex);
        if (leftStart != null) {
            int leftSize = indexToSize.get(leftStart);
            if (leftStart + leftSize == mergedIndex) {
                removeFreeBlock(leftStart, leftSize);
                mergedIndex = leftStart;
                mergedSize += leftSize;
            }
        }

        // Inserir o bloco (possivelmente fundido) na free list
        insertFreeBlock(mergedIndex, mergedSize);
    }

    // -------------------------------------------------------------------------
    // Métricas — derivadas da free list, sem varredura da heap
    // -------------------------------------------------------------------------

    /**
     * Retorna o tamanho do maior bloco livre atual (em inteiros).
     * Derivado diretamente da free list: O(log n).
     */
    public int getLargestFreeBlock() {
        if (sizeToIndices.isEmpty()) {
            return 0;
        }
        return sizeToIndices.lastKey();
    }

    /**
     * Retorna o total de memória livre (em inteiros).
     * Derivado da free list iterando sobre os blocos: O(k), onde k é o
     * número de blocos livres distintos — em geral muito menor que n.
     */
    public int getTotalFreeMemory() {
        int total = 0;
        for (Map.Entry<Integer, TreeSet<Integer>> entry : sizeToIndices.entrySet()) {
            total += entry.getKey() * entry.getValue().size();
        }
        return total;
    }

    /**
     * Retorna o total de memória ocupada (em inteiros).
     */
    public int getTotalOccupiedMemory() {
        return heapSize - getTotalFreeMemory();
    }

    /**
     * Calcula a fragmentação externa atual da heap.
     *
     * <p>Definição: {@code (totalFree - largestFreeBlock) / totalFree × 100}.
     * Derivado da free list, sem varredura da heap: O(log n + k).
     *
     * @return percentual de fragmentação externa (0 a 100)
     */
    public double calculateExternalFragmentation() {
        int totalFreeSize    = getTotalFreeMemory();
        int largestFreeBlock = getLargestFreeBlock();

        if (totalFreeSize == 0) {
            return 0.0;
        }

        return (double) (totalFreeSize - largestFreeBlock) / totalFreeSize * 100.0;
    }

    // -------------------------------------------------------------------------
    // Exibição do estado da heap
    // -------------------------------------------------------------------------

    /**
     * Exibe o estado atual da heap, indicando quais blocos estão livres e ocupados.
     */
    public void printHeapStatus() {
        System.out.println("\n========== ESTADO DA HEAP ==========");
        System.out.printf("Tamanho total: %d KB (%d bytes, %d inteiros)%n",
                totalSize / 1024, totalSize, heapSize);
        System.out.println("-----------------------------------");

        int currentIndex = 0;
        int blockNumber  = 1;
        int totalOccupied = 0;
        int totalFree     = 0;

        while (currentIndex < heapSize) {
            int value = heap.get(currentIndex);
            int size  = 1;
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

    // -------------------------------------------------------------------------
    // Delegação para Heap
    // -------------------------------------------------------------------------

    /** Retorna um snapshot imutável do estado atual da heap. */
    public int[] snapshot() {
        return heap.snapshot();
    }

    /** Retorna a capacidade total da heap em inteiros. */
    public int getCapacity() {
        return heapSize;
    }

    /** Retorna a capacidade total da heap em bytes. */
    public int getCapacityInBytes() {
        return totalSize;
    }

    /** Acesso de pacote para {@code GerenciadorLiberacao}. */
    Heap getHeap() {
        return heap;
    }
}
