package model;

/**
 * Classe de teste para demonstrar o funcionamento do algoritmo Worst Fit.
 *
 * Simula alocações e liberações de memória para ilustrar:
 * - O comportamento do algoritmo Worst Fit
 * - Coalescência de blocos livres
 * - Cálculo de fragmentação externa
 * - Distribuição da memória
 */
public class WorstFitTest {

    public static void main(String[] args) {
        System.out.println("╔═══════════════════════════════════════════════════════════════╗");
        System.out.println("║  TESTE DO ALGORITMO WORST FIT - GERENCIAMENTO DE MEMÓRIA     ║");
        System.out.println("╚═══════════════════════════════════════════════════════════════╝");

        // ===== TESTE 1: Inicialização e primeira alocação =====
        System.out.println("\n\n▶ TESTE 1: Inicialização com 64 KB e alocações básicas");
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");

        WorstFit heap = new WorstFit(64);
        heap.printHeapStatus();

        // Alocar 256 bytes (64 inteiros)
        System.out.println("• Alocando 256 bytes (64 inteiros)...");
        int addr1 = heap.allocate(256);
        System.out.printf("  ✓ Alocado no índice: %d%n", addr1);
        heap.printHeapStatus();

        // Alocar 128 bytes (32 inteiros)
        System.out.println("• Alocando 128 bytes (32 inteiros)...");
        int addr2 = heap.allocate(128);
        System.out.printf("  ✓ Alocado no índice: %d%n", addr2);
        heap.printHeapStatus();

        // ===== TESTE 2: Demonstração do Worst Fit =====
        System.out.println("\n▶ TESTE 2: Demonstração do Worst Fit");
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");

        // Liberar o primeiro bloco
        System.out.println("• Liberando bloco no índice " + addr1 + " (256 bytes)...");
        heap.deallocate(addr1, 256);
        heap.printHeapStatus();

        // Alocar um bloco pequeno (100 bytes = 25 inteiros)
        // Worst Fit deve escolher o maior bloco livre (que agora é o bloco de 256 bytes)
        System.out.println("• Alocando 100 bytes (25 inteiros)...");
        System.out.println("  Esperado: será escolhido o MAIOR bloco livre (Worst Fit)");
        int addr3 = heap.allocate(100);
        System.out.printf("  ✓ Alocado no índice: %d%n", addr3);
        heap.printHeapStatus();

        // ===== TESTE 3: Fragmentação e coalescência =====
        System.out.println("\n▶ TESTE 3: Fragmentação e coalescência");
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");

        System.out.println("• Estado atual - blocos livres espalhados (fragmentação):");
        System.out.printf("  Maior bloco livre: %d inteiros = %d bytes%n",
                heap.getLargestFreeBlock(), heap.getLargestFreeBlock() * 4);
        System.out.printf("  Total livre: %d inteiros = %d bytes%n",
                heap.getTotalFreeMemory(), heap.getTotalFreeMemory() * 4);
        System.out.printf("  Fragmentação: %.2f%%%n\n", heap.calculateExternalFragmentation());

        // Liberar o segundo bloco (128 bytes)
        System.out.println("• Liberando bloco no índice " + addr2 + " (128 bytes)...");
        heap.deallocate(addr2, 128);
        System.out.println("  (Coalescência é realizada automaticamente)");
        heap.printHeapStatus();

        // Liberar o terceiro bloco
        System.out.println("• Liberando bloco no índice " + addr3 + " (100 bytes)...");
        heap.deallocate(addr3, 100);
        System.out.println("  (A heap volta ao estado de bloco livre único)");
        heap.printHeapStatus();

        // ===== TESTE 4: Alocação com múltiplos blocos =====
        System.out.println("\n▶ TESTE 4: Múltiplas alocações simultâneas");
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");

        WorstFit heap2 = new WorstFit(32);  // 32 KB = 8192 inteiros

        int[] allocations = new int[5];
        int[] sizes = {256, 512, 320, 160, 400};  // em bytes

        for (int i = 0; i < sizes.length; i++) {
            allocations[i] = heap2.allocate(sizes[i]);
            System.out.printf("Alocação %d: %d bytes → índice %d%n", 
                    i + 1, sizes[i], allocations[i]);
        }
        heap2.printHeapStatus();

        // ===== TESTE 5: Padrão de fragmentação =====
        System.out.println("\n▶ TESTE 5: Análise de fragmentação em padrão alternado");
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");

        System.out.println("Padrão: liberar blocos alternados para criar fragmentação\n");

        // Liberar posições alternadas
        System.out.println("• Liberando blocos nos índices: " + allocations[1] + ", " + allocations[3]);
        heap2.deallocate(allocations[1], sizes[1]);
        heap2.deallocate(allocations[3], sizes[3]);
        heap2.printHeapStatus();

        System.out.println("• Tentando alocar 1024 bytes (maior que qualquer bloco livre)...");
        int result = heap2.allocate(1024);
        if (result == -1) {
            System.out.println("  ✗ Falha: sem espaço suficiente! Retornou: -1");
        } else {
            System.out.println("  ✓ Sucesso: alocado no índice " + result);
        }

        // ===== TESTE 6: Comportamento com blocos pequenos =====
        System.out.println("\n▶ TESTE 6: Alocações com tamanhos pequenos (16-32 bytes)");
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");

        WorstFit heap3 = new WorstFit(16);  // 16 KB pequeno para demonstração

        System.out.println("Alocando múltiplos blocos pequenos:\n");
        int[] smallAllocs = new int[6];
        for (int i = 0; i < 6; i++) {
            smallAllocs[i] = heap3.allocate(20 + (i * 10));  // 20, 30, 40, 50, 60, 70 bytes
            System.out.printf("Bloco %d: %d bytes → índice %d%n", 
                    i + 1, 20 + (i * 10), smallAllocs[i]);
        }
        heap3.printHeapStatus();

        System.out.println("Liberando blocos alternados para criar fragmentação...\n");
        heap3.deallocate(smallAllocs[0], 20);
        heap3.deallocate(smallAllocs[2], 40);
        heap3.deallocate(smallAllocs[4], 60);
        heap3.printHeapStatus();

        // ===== RESUMO FINAL =====
        System.out.println("\n╔═══════════════════════════════════════════════════════════════╗");
        System.out.println("║                    RESUMO DO TESTE                            ║");
        System.out.println("╠═══════════════════════════════════════════════════════════════╣");
        System.out.println("║ ALGORITMO: Worst Fit                                          ║");
        System.out.println("║ ESTRATÉGIA: Encontra o MAIOR bloco livre disponível           ║");
        System.out.println("║ OBJETIVO: Minimizar efeitos de fragmentação                   ║");
        System.out.println("║                                                               ║");
        System.out.println("║ VANTAGENS:                                                    ║");
        System.out.println("║ V Reduz fragmentação deixando blocos maiores intactos         ║");
        System.out.println("║ V Melhor para alocações futuras de tamanho grande             ║");
        System.out.println("║                                                               ║");
        System.out.println("║ DESVANTAGENS:                                                 ║");
        System.out.println("║ X Usa mais memória por alocação (maior espaço ocupado)        ║");
        System.out.println("║ X Mais lento que First Fit (as vezes percorre todo o array)   ║");
        System.out.println("║ X Pode aumentar fragmentação a longo prazo                    ║");
        System.out.println("╚═══════════════════════════════════════════════════════════════╝\n");
    }

}
