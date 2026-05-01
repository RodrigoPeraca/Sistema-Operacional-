package model;
 
/**
 * Teste manual da classe MemoryRequest.
 * Execute este arquivo para verificar o funcionamento básico.
 *
 * Compile com: javac model/MemoryRequest.java model/MemoryRequestTest.java
 * Execute com: java model.MemoryRequestTest
 */
public class Test_Requisitor_Memoria {
 
    public static void main(String[] args) {
 
        System.out.println("=== Teste: MemoryRequest ===\n");
 
        // 1. IDs únicos e incrementais
        Requisitor_Memoria r1 = new Requisitor_Memoria(64);
        Requisitor_Memoria r2 = new Requisitor_Memoria(128);
        Requisitor_Memoria r3 = new Requisitor_Memoria(256);
 
        System.out.println("Requisições criadas:");
        System.out.println("  " + r1);
        System.out.println("  " + r2);
        System.out.println("  " + r3);
 
        assert r1.getId() != r2.getId() : "IDs devem ser únicos";
        assert r2.getId() != r3.getId() : "IDs devem ser únicos";
        assert r1.getId() < r2.getId()  : "IDs devem ser incrementais";
        System.out.println("\nIDs únicos e incrementais: OK");
 
        // 2. Getters retornam valores corretos
        assert r1.getSize() == 64  : "Tamanho incorreto para r1";
        assert r2.getSize() == 128 : "Tamanho incorreto para r2";
        assert r3.getSize() == 256 : "Tamanho incorreto para r3";
        System.out.println("Getters retornam valores corretos: OK");
 
        // 3. Tamanho inválido lança exceção
        boolean exceptionThrown = false;
        try {
            new Requisitor_Memoria(0);
        } catch (IllegalArgumentException e) {
            exceptionThrown = true;
            System.out.println("Tamanho 0 rejeitado: " + e.getMessage());
        }
        assert exceptionThrown : "Deveria lançar IllegalArgumentException para size=0";
 
        exceptionThrown = false;
        try {
            new Requisitor_Memoria(-10);
        } catch (IllegalArgumentException e) {
            exceptionThrown = true;
            System.out.println("Tamanho negativo rejeitado: " + e.getMessage());
        }
        assert exceptionThrown : "Deveria lançar IllegalArgumentException para size<0";
 
        System.out.println("\n=== Todos os testes passaram ===");
    }
}
