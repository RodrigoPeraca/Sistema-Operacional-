package model;

import java.util.Random;
import java.util.Scanner;

/**
 * Menu interativo para testar o funcionamento do Worst Fit e do algoritmo de
 * liberação RANDOM em um simulador de heap.
 *
 * Permite executar alocações, liberações e ver o estado passo a passo.
 */
public class WorstFitMenu {

    private static final Scanner scanner = new Scanner(System.in);
    private static final Random random = new Random();

    private static WorstFit heap;
    private static GerenciadorLiberacao liberador;

    public static void main(String[] args) {
        mostrarCabecalho();

        while (true) {
            mostrarMenuPrincipal();
            int opcao = lerInteiro("Escolha uma opção: ", 0, 7);

            switch (opcao) {
                case 1:
                    criarHeap();
                    break;
                case 2:
                    alocarRequisicaoAleatoria();
                    break;
                case 3:
                    alocarRequisicaoPersonalizada();
                    break;
                case 4:
                    liberarBlocoManual();
                    break;
                case 5:
                    executarLiberacaoRandomica();
                    break;
                case 6:
                    mostrarEstadoAtual();
                    break;
                case 7:
                    executarCenarioDemonstrativo();
                    break;
                case 0:
                    finalizar();
                    return;
                default:
                    System.out.println("Opção inválida. Tente novamente.");
            }
        }
    }

    private static void mostrarCabecalho() {
        System.out.println("==============================================");
        System.out.println("  SIMULADOR WORST FIT + LIBERAÇÃO RANDOM     ");
        System.out.println("==============================================");
        System.out.println("Este menu permite testar o comportamento do algoritmo");
        System.out.println("Worst Fit e visualizar a liberação RANDOM passo a passo.");
        System.out.println();
    }

    private static void mostrarMenuPrincipal() {
        System.out.println("\n------ MENU PRINCIPAL ------");
        System.out.println("1) Criar nova heap");
        System.out.println("2) Alocar requisição aleatória (16-1024 bytes)");
        System.out.println("3) Alocar requisição personalizada");
        System.out.println("4) Liberar bloco manualmente");
        System.out.println("5) Executar liberação RANDOM até 30% livre");
        System.out.println("6) Mostrar estado atual da heap");
        System.out.println("7) Executar cenário demonstrativo passo a passo");
        System.out.println("0) Sair");
    }

    private static void criarHeap() {
        int tamanhoKB = lerInteiro("Informe o tamanho da heap em KB: ", 1, Integer.MAX_VALUE);
        heap = new WorstFit(tamanhoKB);
        liberador = new GerenciadorLiberacao(heap);
        System.out.println("Heap criada com sucesso: " + tamanhoKB + " KB.");
        heap.printHeapStatus();
    }

    private static void alocarRequisicaoAleatoria() {
        if (!verificarHeapCriada()) {
            return;
        }
        Requisitor_Memoria requisicao = new Requisitor_Memoria();
        System.out.println("\n=== ALCAO ALEATORIA ===");
        System.out.println("Requisição gerada: " + requisicao);
        mostrarEstadoBreve("Estado antes da alocação:");

        int endereco = heap.allocate(requisicao.getSize(), requisicao.getId());
        if (endereco == -1) {
            System.out.println("Não foi possível alocar. Espaço insuficiente.");
            executarLiberacaoAutomaticamente(requisicao);
        } else {
            System.out.println("Alocação bem-sucedida no índice: " + endereco);
            mostrarEstadoBreve("Estado após alocação:");
        }
    }

    private static void alocarRequisicaoPersonalizada() {
        if (!verificarHeapCriada()) {
            return;
        }
        int tamanho = lerInteiro("Informe o tamanho em bytes (16-1024): ", 16, 1024);
        Requisitor_Memoria requisicao = new Requisitor_Memoria(tamanho);
        System.out.println("\n=== ALCAO PERSONALIZADA ===");
        System.out.println("Requisição criada: " + requisicao);
        mostrarEstadoBreve("Estado antes da alocação:");

        int endereco = heap.allocate(requisicao.getSize(), requisicao.getId());
        if (endereco == -1) {
            System.out.println("Não foi possível alocar. Espaço insuficiente.");
            executarLiberacaoAutomaticamente(requisicao);
        } else {
            System.out.println("Alocação bem-sucedida no índice: " + endereco);
            mostrarEstadoBreve("Estado após alocação:");
        }
    }

    private static void liberarBlocoManual() {
        if (!verificarHeapCriada()) {
            return;
        }
        System.out.println("\n=== LIBERAR BLOCO MANUAL ===");
        mostrarEstadoBreve("Estado atual antes da liberação:");
        int index = lerInteiro("Informe o índice inicial do bloco a liberar: ", 0, heap.getCapacity() - 1);
        int tamanho = lerInteiro("Informe o tamanho do bloco em bytes: ", 16, heap.getCapacityInBytes());
        heap.deallocate(index, tamanho);
        System.out.println("Bloco liberado no índice " + index + " com " + tamanho + " bytes.");
        mostrarEstadoBreve("Estado após a liberação:");
    }

    private static void executarLiberacaoRandomica() {
        if (!verificarHeapCriada()) {
            return;
        }
        System.out.println("\n=== LIBERAÇÃO RANDOM ===");
        mostrarEstadoBreve("Estado atual antes da liberação random:");
        GerenciadorLiberacao.RelatorioLiberacao relatorio = liberador.executarLiberacaoRandomica(true);
        relatorio.imprimirRelatorio();
        mostrarEstadoBreve("Estado final após liberação random:");
    }

    private static void mostrarEstadoAtual() {
        if (!verificarHeapCriada()) {
            return;
        }
        System.out.println("\n=== ESTADO DA HEAP ===");
        heap.printHeapStatus();
        mostrarMapaInterno();
    }

    private static void executarCenarioDemonstrativo() {
        System.out.println("\n=== CENÁRIO DEMONSTRATIVO ===");
        heap = new WorstFit(32);
        liberador = new GerenciadorLiberacao(heap);
        System.out.println("Heap de 32 KB criada para demonstração.");
        heap.printHeapStatus();

        int[] requisicoes = {256, 512, 320, 160, 400};
        int[] enderecos = new int[requisicoes.length];

        for (int i = 0; i < requisicoes.length; i++) {
            System.out.println("\nAlocando " + requisicoes[i] + " bytes...");
            enderecos[i] = heap.allocate(requisicoes[i]);
            System.out.println("Resultado: índice " + enderecos[i]);
            heap.printHeapStatus();
        }

        System.out.println("\nLiberando blocos alternados para criar fragmentação...");
        heap.deallocate(enderecos[1], requisicoes[1]);
        heap.deallocate(enderecos[3], requisicoes[3]);
        heap.printHeapStatus();

        System.out.println("\nTentando alocar 1024 bytes após fragmentação...");
        int resultado = heap.allocate(1024);
        if (resultado == -1) {
            System.out.println("Não coube. Executando liberação RANDOM para recuperar espaço...");
            GerenciadorLiberacao.RelatorioLiberacao relatorio = liberador.executarLiberacaoRandomica(true);
            relatorio.imprimirRelatorio();
            System.out.println("Tentando alocar novamente 1024 bytes...");
            resultado = heap.allocate(1024);
        }

        System.out.println("Resultado final da alocação de 1024 bytes: " + resultado);
        heap.printHeapStatus();
    }

    private static boolean verificarHeapCriada() {
        if (heap == null) {
            System.out.println("Nenhuma heap criada. Escolha a opção 1 para criar uma heap primeiro.");
            return false;
        }
        return true;
    }

    private static int lerInteiro(String mensagem, int min, int max) {
        while (true) {
            try {
                System.out.print(mensagem);
                int valor = Integer.parseInt(scanner.nextLine().trim());
                if (valor < min || valor > max) {
                    System.out.println("Valor inválido. Informe um número entre " + min + " e " + max + ".");
                } else {
                    return valor;
                }
            } catch (NumberFormatException e) {
                System.out.println("Entrada inválida. Digite um número inteiro.");
            }
        }
    }

    private static int gerarTamanhoAleatorio() {
        return 16 + random.nextInt(1024 - 16 + 1);
    }

    private static void mostrarEstadoBreve(String titulo) {
        System.out.println("\n" + titulo);
        heap.printHeapStatus();
    }

    private static void mostrarMapaInterno() {
        int capacidade = heap.getCapacity();
        if (capacidade > 80) {
            System.out.println("Mapa interno oculto para heaps maiores que 80 posições.");
            return;
        }

        System.out.println("Mapa interno da heap (índice: valor):");
        int[] interno = heap.getHeap().snapshot();
        for (int i = 0; i < capacidade; i++) {
            System.out.printf("%d:%d ", i, interno[i]);
            if ((i + 1) % 10 == 0) {
                System.out.println();
            }
        }
        System.out.println();
    }

    private static void executarLiberacaoAutomaticamente(Requisitor_Memoria requisicao) {
        System.out.println("Deseja executar a liberação RANDOM para tentar alocar novamente? (1=Sim, 2=Não)");
        int opcao = lerInteiro("Opção: ", 1, 2);
        if (opcao == 1) {
            GerenciadorLiberacao.RelatorioLiberacao relatorio = liberador.executarLiberacaoRandomica(true);
            relatorio.imprimirRelatorio();
            System.out.println("Tentando alocar novamente " + requisicao.getSize() + " bytes (ID=" + requisicao.getId() + ")...");
            int enderecoFinal = heap.allocate(requisicao.getSize(), requisicao.getId());
            if (enderecoFinal == -1) {
                System.out.println("Ainda não foi possível alocar o bloco.");
            } else {
                System.out.println("Alocação bem-sucedida no índice: " + enderecoFinal);
                mostrarEstadoBreve("Estado após alocação:");
            }
        }
    }

    private static void finalizar() {
        System.out.println("Encerrando o simulador. Obrigado por testar o Worst Fit.");
    }
}
