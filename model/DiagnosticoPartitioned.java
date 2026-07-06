import model.Heap;
import model.WorstFitPartitioned;

/**
 * Teste de diagnóstico: verifica se freeCount da free list
 * sempre bate com a contagem real de posições livres na heap.
 *
 * Executa alocações e liberações cruzando fronteiras de segmentos
 * e chama verificarIntegridade() após cada operação.
 */
public class DiagnosticoPartitioned {

    public static void main(String[] args) {
        // Heap pequena para facilitar análise: 4 segmentos de 8 posições cada
        // segmentSize = 8, heapSize = 32
        // Fronteiras em: 8, 16, 24
        int heapSize = 32; // 32 posições × 4 bytes = 128 bytes
        WorstFitPartitioned wf = new WorstFitPartitioned(new Heap(heapSize));

        System.out.println("=== ESTADO INICIAL ===");
        wf.verificarIntegridade();

        // Aloca um bloco que começa no seg[0] mas cruza para seg[1]
        // Começa em posição 6 (seg[0] vai até 7), tamanho 6 posições = 24 bytes
        // Mas allocate escolhe o bloco — não controlamos o índice de início.
        // Então enchemos o seg[0] até sobrar só o final, forçando cruzamento.

        System.out.println("\n=== ALOCANDO ATÉ FORÇAR CRUZAMENTO DE FRONTEIRA ===");

        // Aloca 7 posições (28 bytes) — deve ir para índice 0, seg[0]
        int a1 = wf.allocate(28, 1);
        System.out.println("a1 = " + a1 + " (esperado: 0 ou próximo ao maior bloco)");
        wf.verificarIntegridade();

        // Agora o seg[0] tem 1 posição livre (índice 7) e seg[1..3] cheio de livres
        // Aloca 4 posições (16 bytes) — deve ir para o maior bloco (seg[1], seg[2] ou seg[3])
        int a2 = wf.allocate(16, 2);
        System.out.println("a2 = " + a2);
        wf.verificarIntegridade();

        int a3 = wf.allocate(16, 3);
        System.out.println("a3 = " + a3);
        wf.verificarIntegridade();

        int a4 = wf.allocate(16, 4);
        System.out.println("a4 = " + a4);
        wf.verificarIntegridade();

        System.out.println("\n=== LIBERANDO BLOCO a1 (que pode ter cruzado fronteira) ===");
        wf.deallocate(a1, 28);
        wf.verificarIntegridade();

        System.out.println("\n=== LIBERANDO DEMAIS BLOCOS ===");
        if (a2 >= 0) { wf.deallocate(a2, 16); wf.verificarIntegridade(); }
        if (a3 >= 0) { wf.deallocate(a3, 16); wf.verificarIntegridade(); }
        if (a4 >= 0) { wf.deallocate(a4, 16); wf.verificarIntegridade(); }

        System.out.println("\n=== TESTE COM MUITAS ALOCAÇÕES (simula benchmark) ===");
        WorstFitPartitioned wf2 = new WorstFitPartitioned(new Heap(256)); // 256 posições
        int[] indices = new int[50];
        int[] sizes   = new int[50];

        // Aloca 50 blocos de tamanhos variados
        for (int i = 0; i < 50; i++) {
            sizes[i] = 4 + (i % 12); // tamanhos 4 a 15 posições
            indices[i] = wf2.allocate(sizes[i] * 4, i + 1);
        }
        System.out.println("Após 50 alocações:");
        wf2.verificarIntegridade();

        // Libera metade
        for (int i = 0; i < 50; i += 2) {
            if (indices[i] >= 0) wf2.deallocate(indices[i], sizes[i] * 4);
        }
        System.out.println("Após liberar 25 blocos alternados:");
        wf2.verificarIntegridade();

        // Aloca mais 20
        for (int i = 0; i < 20; i++) {
            wf2.allocate((4 + i % 8) * 4, 100 + i);
        }
        System.out.println("Após mais 20 alocações:");
        wf2.verificarIntegridade();
    }
}
