package model;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Scanner;

/**
 * Menu interativo para testar o funcionamento do Worst Fit e do algoritmo de
 * liberação RANDOM em um simulador de heap.
 *
 * Permite executar alocações, liberações, ver o estado passo a passo
 * e executar testes em lote com coleta de dados para geração de relatórios.
 */
public class WorstFitMenu {

    private static final Scanner scanner = new Scanner(System.in);
    private static final Random random = new Random();

    private static WorstFit heap;
    private static GerenciadorLiberacao liberador;

    // -------------------------------------------------------------------------
    // Estrutura de coleta de dados
    // -------------------------------------------------------------------------

    /**
     * Registro de uma amostra coletada após cada alocação.
     */
    private static class Amostra {
        int numeroRequisicao;
        int tamanhoRequisicao;
        boolean alocacaoBemSucedida;
        double fragmentacaoExterna;
        double percentualOcupado;
        double percentualLivre;
        int maiorBlocoLivre;      // em bytes
        int totalLivre;           // em bytes
        boolean randomAcionado;
        int bytesRecuperadosRandom;
        int blocosLiberadosRandom;

        @Override
        public String toString() {
            return String.format(
                "Req#%d | %4d bytes | %s | Frag: %5.2f%% | Ocupado: %5.2f%% | MaiorBloco: %d bytes | RANDOM: %s",
                numeroRequisicao,
                tamanhoRequisicao,
                alocacaoBemSucedida ? "OK  " : "FAIL",
                fragmentacaoExterna,
                percentualOcupado,
                maiorBlocoLivre,
                randomAcionado ? "SIM (" + bytesRecuperadosRandom + " bytes)" : "NÃO"
            );
        }
    }

    // -------------------------------------------------------------------------
    // Main e menu
    // -------------------------------------------------------------------------

    public static void main(String[] args) {
        mostrarCabecalho();

        while (true) {
            mostrarMenuPrincipal();
            int opcao = lerInteiro("Escolha uma opção: ", 0, 8);

            switch (opcao) {
                case 1: criarHeap();                    break;
                case 2: alocarRequisicaoAleatoria();    break;
                case 3: alocarRequisicaoPersonalizada(); break;
                case 4: liberarBlocoManual();           break;
                case 5: executarLiberacaoRandomica();   break;
                case 6: mostrarEstadoAtual();           break;
                case 7: executarCenarioDemonstrativo(); break;
                case 8: executarTesteEmLote();          break;
                case 0: finalizar(); return;
                default: System.out.println("Opção inválida. Tente novamente.");
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
        System.out.println("8) Executar teste em lote com coleta de dados");
        System.out.println("0) Sair");
    }

    // -------------------------------------------------------------------------
    // Opção 8 — Teste em lote com coleta de dados
    // -------------------------------------------------------------------------

    /**
     * Executa N requisições aleatórias em uma ou múltiplas configurações de heap,
     * coletando métricas após cada alocação e salvando CSV no disco para gráficos.
     *
     * Modo 1 — configuração única: o usuário informa 1 tamanho de heap e N requisições.
     * Modo 2 — comparativo: roda automaticamente nas heaps de 8, 16, 32, 64 KB
     *           com o mesmo N, gerando um CSV por tamanho.
     */
    private static void executarTesteEmLote() {
        System.out.println("\n=== TESTE EM LOTE COM COLETA DE DADOS ===");
        System.out.println("1) Configuração única (você define o tamanho da heap)");
        System.out.println("2) Comparativo automático (8, 16, 32, 64 KB — mesmo N)");
        int modo = lerInteiro("Escolha o modo: ", 1, 2);

        int nRequisicoes = lerInteiro("Número de requisições (ex: 500): ", 1, 10000);

        if (modo == 1) {
            int tamanhoKB = lerInteiro("Tamanho da heap em KB: ", 1, 1024);
            rodarUmaConfiguracao(tamanhoKB, nRequisicoes);
        } else {
            int[] tamanhos = {8, 16, 32, 64};
            System.out.println("\nRodando teste comparativo para: 8, 16, 32 e 64 KB...");
            for (int kb : tamanhos) {
                rodarUmaConfiguracao(kb, nRequisicoes);
            }
            System.out.println("\nTodos os CSVs foram salvos. Use-os para gerar os gráficos comparativos.");
        }
    }

    /**
     * Executa N requisições aleatórias em uma heap de tamanhoKB,
     * coleta as métricas e salva o resultado em CSV.
     */
    private static void rodarUmaConfiguracao(int tamanhoKB, int nRequisicoes) {
        System.out.println("\n--- Iniciando: heap=" + tamanhoKB + " KB | requisições=" + nRequisicoes + " ---");

        heap      = new WorstFit(tamanhoKB);
        liberador = new GerenciadorLiberacao(heap);

        List<Amostra> amostras = new ArrayList<>();

        int totalRandom      = 0;
        int totalBytesRandom = 0;
        int totalSucesso     = 0;
        int totalFalha       = 0;

        for (int i = 1; i <= nRequisicoes; i++) {
            Amostra amostra          = new Amostra();
            amostra.numeroRequisicao = i;

            Requisitor_Memoria req   = new Requisitor_Memoria();
            amostra.tamanhoRequisicao = req.getSize();

            // Tenta alocar
            int endereco = heap.allocate(req.getSize(), req.getId());

            if (endereco == -1) {
                // Sem espaço: aciona RANDOM e tenta de novo
                amostra.randomAcionado = true;
                GerenciadorLiberacao.RelatorioLiberacao rel =
                        liberador.executarLiberacaoRandomica(false);
                amostra.bytesRecuperadosRandom = rel.getBytesRecuperados();
                amostra.blocosLiberadosRandom  = rel.getBlocosLiberados();
                totalRandom++;
                totalBytesRandom += rel.getBytesRecuperados();

                // Segunda tentativa após liberação
                endereco = heap.allocate(req.getSize(), req.getId());
                amostra.alocacaoBemSucedida = (endereco != -1);
            } else {
                amostra.alocacaoBemSucedida = true;
                amostra.randomAcionado      = false;
            }

            if (amostra.alocacaoBemSucedida) totalSucesso++; else totalFalha++;

            // Coleta métricas
            int capacidadeInts = heap.getCapacity();
            int ocupadoInts    = heap.getTotalOccupiedMemory();
            int livreInts      = heap.getTotalFreeMemory();
            int maiorBlocoInts = heap.getLargestFreeBlock();

            amostra.fragmentacaoExterna = heap.calculateExternalFragmentation();
            amostra.percentualOcupado   = (ocupadoInts * 100.0) / capacidadeInts;
            amostra.percentualLivre     = (livreInts   * 100.0) / capacidadeInts;
            amostra.maiorBlocoLivre     = maiorBlocoInts * 4;
            amostra.totalLivre          = livreInts * 4;

            amostras.add(amostra);
        }

        // Relatório no console
        imprimirRelatorioLote(amostras, tamanhoKB, nRequisicoes,
                totalSucesso, totalFalha, totalRandom, totalBytesRandom);

        // Salva CSV em arquivo
        String nomeArquivo = "resultado_" + tamanhoKB + "kb_" + nRequisicoes + "req.csv";
        salvarCSV(amostras, nomeArquivo, tamanhoKB);

        // Estado final da heap no console
        System.out.println("Estado final da heap (" + tamanhoKB + " KB):");
        heap.printHeapStatus();
    }

    /**
     * Imprime um relatório resumido do teste em lote.
     */
    private static void imprimirRelatorioLote(List<Amostra> amostras,
            int tamanhoKB, int nRequisicoes,
            int totalSucesso, int totalFalha,
            int totalRandom, int totalBytesRandom) {

        // Calcula médias
        double mediaFragmentacao = amostras.stream()
                .mapToDouble(a -> a.fragmentacaoExterna).average().orElse(0);
        double maxFragmentacao   = amostras.stream()
                .mapToDouble(a -> a.fragmentacaoExterna).max().orElse(0);
        double mediaOcupado      = amostras.stream()
                .mapToDouble(a -> a.percentualOcupado).average().orElse(0);

        System.out.println("\n╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║              RELATÓRIO DO TESTE EM LOTE                     ║");
        System.out.println("╠══════════════════════════════════════════════════════════════╣");
        System.out.printf( "║  Heap:                  %d KB%n", tamanhoKB);
        System.out.printf( "║  Requisições executadas: %d%n", nRequisicoes);
        System.out.println("╠══════════════════════════════════════════════════════════════╣");
        System.out.println("║  ALOCAÇÕES");
        System.out.printf( "║    Bem-sucedidas:       %d (%.1f%%)%n",
                totalSucesso, (totalSucesso * 100.0) / nRequisicoes);
        System.out.printf( "║    Falhas definitivas:  %d (%.1f%%)%n",
                totalFalha, (totalFalha * 100.0) / nRequisicoes);
        System.out.println("╠══════════════════════════════════════════════════════════════╣");
        System.out.println("║  LIBERAÇÃO RANDOM");
        System.out.printf( "║    Acionamentos:        %d%n", totalRandom);
        System.out.printf( "║    Total recuperado:    %d bytes%n", totalBytesRandom);
        System.out.printf( "║    Média por acion.:    %.0f bytes%n",
                totalRandom > 0 ? (double) totalBytesRandom / totalRandom : 0);
        System.out.println("╠══════════════════════════════════════════════════════════════╣");
        System.out.println("║  FRAGMENTAÇÃO EXTERNA");
        System.out.printf( "║    Média:               %.2f%%%n", mediaFragmentacao);
        System.out.printf( "║    Máxima:              %.2f%%%n", maxFragmentacao);
        System.out.printf( "║    Final:               %.2f%%%n",
                amostras.get(amostras.size() - 1).fragmentacaoExterna);
        System.out.println("╠══════════════════════════════════════════════════════════════╣");
        System.out.println("║  OCUPAÇÃO MÉDIA DA HEAP");
        System.out.printf( "║    Média:               %.2f%%%n", mediaOcupado);
        System.out.println("╚══════════════════════════════════════════════════════════════╝");

        // Tabela amostral — exibe 1 linha a cada 10 requisições para não poluir
        System.out.println("\n--- AMOSTRA DOS DADOS (a cada 10 requisições) ---");
        System.out.printf("%-6s | %-8s | %-6s | %-8s | %-9s | %-12s | %-6s%n",
                "Req#", "Tam(B)", "OK?", "Frag%", "Ocupado%", "MaiorBloco", "RANDOM");
        System.out.println("-".repeat(70));
        for (int i = 0; i < amostras.size(); i++) {
            if (i == 0 || (i + 1) % 10 == 0 || i == amostras.size() - 1) {
                Amostra a = amostras.get(i);
                System.out.printf("%-6d | %-8d | %-6s | %-8.2f | %-9.2f | %-12d | %-6s%n",
                        a.numeroRequisicao,
                        a.tamanhoRequisicao,
                        a.alocacaoBemSucedida ? "OK" : "FAIL",
                        a.fragmentacaoExterna,
                        a.percentualOcupado,
                        a.maiorBlocoLivre,
                        a.randomAcionado ? "SIM" : "NÃO");
            }
        }
    }

    /**
     * Salva todas as amostras em um arquivo CSV no diretório corrente.
     * Cada linha corresponde a uma requisição, com todas as métricas coletadas.
     */
    private static void salvarCSV(List<Amostra> amostras, String nomeArquivo, int tamanhoKB) {
    // Cria a pasta "resultados" se não existir
    java.io.File pasta = new java.io.File("resultados");
    if (!pasta.exists()) {
        pasta.mkdirs();
    }

    // Caminho completo: resultados/resultado_32kb_500req.csv
    String caminho = "resultados" + java.io.File.separator + nomeArquivo;

    try (PrintWriter pw = new PrintWriter(new FileWriter(caminho))) {
            // Cabeçalho
            pw.println("heap_kb,requisicao,tamanho_bytes,alocacao_ok,fragmentacao_pct,"
                    + "ocupado_pct,livre_pct,maior_bloco_bytes,total_livre_bytes,"
                    + "random_acionado,bytes_recuperados_random,blocos_liberados_random");

            for (Amostra a : amostras) {
                pw.printf("%d,%d,%d,%b,%.4f,%.4f,%.4f,%d,%d,%b,%d,%d%n",
                        tamanhoKB,
                        a.numeroRequisicao,
                        a.tamanhoRequisicao,
                        a.alocacaoBemSucedida,
                        a.fragmentacaoExterna,
                        a.percentualOcupado,
                        a.percentualLivre,
                        a.maiorBlocoLivre,
                        a.totalLivre,
                        a.randomAcionado,
                        a.bytesRecuperadosRandom,
                        a.blocosLiberadosRandom);
            }

            System.out.println("CSV salvo em: " + nomeArquivo);
        } catch (IOException e) {
            System.err.println("Erro ao salvar CSV: " + e.getMessage());
        }
    }


    private static void criarHeap() {
        int tamanhoKB = lerInteiro("Informe o tamanho da heap em KB: ", 1, Integer.MAX_VALUE);
        heap = new WorstFit(tamanhoKB);
        liberador = new GerenciadorLiberacao(heap);
        System.out.println("Heap criada com sucesso: " + tamanhoKB + " KB.");
        heap.printHeapStatus();
    }

    private static void alocarRequisicaoAleatoria() {
        if (!verificarHeapCriada()) return;
        Requisitor_Memoria requisicao = new Requisitor_Memoria();
        System.out.println("\n=== ALOCAÇÃO ALEATÓRIA ===");
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
        if (!verificarHeapCriada()) return;
        int tamanho = lerInteiro("Informe o tamanho em bytes (16-1024): ", 16, 1024);
        Requisitor_Memoria requisicao = new Requisitor_Memoria(tamanho);
        System.out.println("\n=== ALOCAÇÃO PERSONALIZADA ===");
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
        if (!verificarHeapCriada()) return;
        System.out.println("\n=== LIBERAR BLOCO MANUAL ===");
        mostrarEstadoBreve("Estado atual antes da liberação:");
        int index   = lerInteiro("Informe o índice inicial do bloco a liberar: ", 0, heap.getCapacity() - 1);
        int tamanho = lerInteiro("Informe o tamanho do bloco em bytes: ", 16, heap.getCapacityInBytes());
        heap.deallocate(index, tamanho);
        System.out.println("Bloco liberado no índice " + index + " com " + tamanho + " bytes.");
        mostrarEstadoBreve("Estado após a liberação:");
    }

    private static void executarLiberacaoRandomica() {
        if (!verificarHeapCriada()) return;
        System.out.println("\n=== LIBERAÇÃO RANDOM ===");
        mostrarEstadoBreve("Estado atual antes da liberação random:");
        GerenciadorLiberacao.RelatorioLiberacao relatorio = liberador.executarLiberacaoRandomica(true);
        relatorio.imprimirRelatorio();
        mostrarEstadoBreve("Estado final após liberação random:");
    }

    private static void mostrarEstadoAtual() {
        if (!verificarHeapCriada()) return;
        System.out.println("\n=== ESTADO DA HEAP ===");
        heap.printHeapStatus();
        mostrarMapaInterno();
    }

    private static void executarCenarioDemonstrativo() {
        System.out.println("\n=== CENÁRIO DEMONSTRATIVO ===");
        heap     = new WorstFit(32);
        liberador = new GerenciadorLiberacao(heap);
        System.out.println("Heap de 32 KB criada para demonstração.");
        heap.printHeapStatus();

        int[] requisicoes = {256, 512, 320, 160, 400};
        int[] enderecos   = new int[requisicoes.length];

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

    // -------------------------------------------------------------------------
    // Utilitários
    // -------------------------------------------------------------------------

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
            if ((i + 1) % 10 == 0) System.out.println();
        }
        System.out.println();
    }

    private static void executarLiberacaoAutomaticamente(Requisitor_Memoria requisicao) {
        System.out.println("Deseja executar a liberação RANDOM para tentar alocar novamente? (1=Sim, 2=Não)");
        int opcao = lerInteiro("Opção: ", 1, 2);
        if (opcao == 1) {
            GerenciadorLiberacao.RelatorioLiberacao relatorio = liberador.executarLiberacaoRandomica(true);
            relatorio.imprimirRelatorio();
            System.out.println("Tentando alocar novamente " + requisicao.getSize()
                    + " bytes (ID=" + requisicao.getId() + ")...");
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