package model;

import java.util.Arrays;

/**
 * Teste manual da classe Heap.
 *
 * Compile com: javac model/Heap.java model/HeapTest.java
 * Execute com: java -ea model.HeapTest
 */
public class HeapTest {

    public static void main(String[] args) {

        System.out.println("=== Teste: Heap ===\n");

        // 1. Tamanho configurável
        Heap heap8  = new Heap(8);
        Heap heap32 = new Heap(32);

        assert heap8.getCapacity()  == 8  : "Capacidade incorreta";
        assert heap32.getCapacity() == 32 : "Capacidade incorreta";
        System.out.println("Tamanho configurável: OK (8 e 32 blocos)");

        // 2. Inicialização com blocos livres (FREE = 0)
        int[] snap = heap8.snapshot();
        boolean allFree = true;
        for (int v : snap) {
            if (v != Heap.FREE) { allFree = false; break; }
        }
        assert allFree              : "Heap deveria iniciar totalmente livre";
        assert heap8.freeBlocks()  == 8 : "Deveria ter 8 blocos livres";
        assert heap8.usedBlocks()  == 0 : "Deveria ter 0 blocos usados";
        System.out.println("Inicialização com FREE (0): OK");
        System.out.println("  Estado inicial: " + Arrays.toString(snap));

        // 3. Escrita e leitura de blocos
        heap8.set(0, 1);   // requisição id=1 ocupa bloco 0
        heap8.set(1, 1);   // requisição id=1 ocupa bloco 1
        heap8.set(2, 2);   // requisição id=2 ocupa bloco 2

        assert heap8.get(0) == 1        : "Bloco 0 deveria ser da req 1";
        assert heap8.get(2) == 2        : "Bloco 2 deveria ser da req 2";
        assert heap8.isFree(3)          : "Bloco 3 deveria estar livre";
        assert !heap8.isFree(0)         : "Bloco 0 não deveria estar livre";
        assert heap8.usedBlocks() == 3  : "Deveriam ter 3 blocos usados";
        assert heap8.freeBlocks()  == 5 : "Deveriam ter 5 blocos livres";
        System.out.println("Escrita e leitura de blocos: OK");
        System.out.println("  Após alocações: " + Arrays.toString(heap8.snapshot()));

        // 4. Liberação de um bloco
        heap8.free(2);
        assert heap8.isFree(2)         : "Bloco 2 deveria estar livre após free()";
        assert heap8.freeBlocks() == 6 : "Deveriam ter 6 blocos livres";
        System.out.println("Liberação de bloco individual (free): OK");

        // 5. Liberação de todos os blocos de uma requisição
        int released = heap8.freeAll(1);
        assert released == 2           : "freeAll deveria ter liberado 2 blocos";
        assert heap8.usedBlocks() == 0 : "Heap deveria estar totalmente livre";
        System.out.println("Liberação por requestId (freeAll): OK — " + released + " blocos liberados");
        System.out.println("  Estado final:  " + Arrays.toString(heap8.snapshot()));

        // 6. Capacidade inválida
        boolean threw = false;
        try { new Heap(0); } catch (IllegalArgumentException e) {
            threw = true;
            System.out.println("Capacidade 0 rejeitada: " + e.getMessage());
        }
        assert threw : "Deveria lançar IllegalArgumentException para capacity=0";

        // 7. Índice fora dos limites
        threw = false;
        try { heap8.get(99); } catch (IndexOutOfBoundsException e) {
            threw = true;
            System.out.println("Índice inválido rejeitado: " + e.getMessage());
        }
        assert threw : "Deveria lançar IndexOutOfBoundsException";

        System.out.println("\n=== Todos os testes passaram ===");
    }
}
