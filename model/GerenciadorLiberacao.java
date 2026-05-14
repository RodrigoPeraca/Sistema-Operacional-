package model;

import java.util.Random;

import model.GerenciadorLiberacao.RelatorioLiberacao;

/**
 * Gerenciador de liberação RANDOM para um simulador de heap.
 *
 * Sempre que a heap não possui espaço suficiente para alocar uma nova requisição,
 * este gerenciador libera blocos ocupados aleatoriamente até que pelo menos 30%
 * da heap esteja livre.
 *
 * A classe trabalha diretamente sobre a mesma estrutura de heap usada pelo
 * WorstFit, acessando o array interno e usando o método de liberação existente.
 */
public class GerenciadorLiberacao {

    private static final double META_LIVRE = 0.30;

    private final WorstFit worstFit;
    private final Random random;

    /**
     * Cria o gerenciador de liberação RANDOM para uma instância de WorstFit.
     *
     * @param worstFit instância de WorstFit que contém a heap a ser gerenciada
     */
    public GerenciadorLiberacao(WorstFit worstFit) {
        if (worstFit == null) {
            throw new IllegalArgumentException("WorstFit não pode ser nulo");
        }
        this.worstFit = worstFit;
        this.random = new Random();
    }

    /**
     * Executa a liberação RANDOM até que pelo menos 30% da heap esteja livre.
     *
     * A cada rodada, identifica novamente todos os blocos ocupados (varredura O(n)),
     * escolhe um deles aleatoriamente e libera usando o método de liberação do WorstFit.
     *
     * @return relatório contendo os resultados da liberação
     */
    public RelatorioLiberacao executarLiberacaoRandomica() {
        return executarLiberacaoRandomica(false);
    }

    /**
     * Executa a liberação RANDOM até que pelo menos 30% da heap esteja livre.
     *
     * A cada rodada, identifica novamente todos os blocos ocupados (varredura O(n)),
     * escolhe um deles aleatoriamente e libera usando o método de liberação do WorstFit.
     *
     * @param verbose se true, exibe passo a passo de cada liberação
     * @return relatório contendo os resultados da liberação
     */
    public RelatorioLiberacao executarLiberacaoRandomica(boolean verbose) {
        RelatorioLiberacao relatorio = new RelatorioLiberacao();
        int totalInts = worstFit.getCapacity();
        int alvoLivre = (int) Math.ceil(totalInts * META_LIVRE);

        // Enquanto não atingir a meta de memória livre, continue liberando
        while (getFreeInts() < alvoLivre) {
            // Identificar blocos ocupados atuais; varredura O(n)
            BlocoOcupado[] blocos = identificarBlocosOcupados();
            if (blocos.length == 0) {
                break;
            }

            int escolhido = random.nextInt(blocos.length);
            BlocoOcupado bloco = blocos[escolhido];

            if (verbose) {
                System.out.println("Liberação RANDOM: escolhendo bloco ocupado em índice " + bloco.index +
                        " com tamanho " + (bloco.size * 4) + " bytes.");
            }

            // Liberar o bloco escolhido via WorstFit
            worstFit.deallocate(bloco.index, bloco.size * 4);
            relatorio.adicionarLiberado(bloco.index, bloco.size * 4);

            if (verbose) {
                System.out.println("Estado após liberação:");
                worstFit.printHeapStatus();
            }

            // Verificar novamente após cada liberação
            if (getFreeInts() >= alvoLivre) {
                break;
            }
        }

        relatorio.setMetaAtingida(getFreeInts() >= alvoLivre);
        return relatorio;
    }

    /**
     * Identifica todos os blocos ocupados atuais da heap.
     *
     * Este método varre a heap usando a API existente do WorstFit/Heap.
     * A complexidade é O(n).
     *
     * @return vetor de blocos ocupados
     */
    private BlocoOcupado[] identificarBlocosOcupados() {
        Heap heap = worstFit.getHeap();
        int capacidade = heap.getCapacity();

        // Contar blocos ocupados para alocar o array de tamanho exato
        int contador = 0;
        int pos = 0;
        while (pos < capacidade) {
            if (heap.get(pos) != Heap.FREE) {
                contador++;
                int id = heap.get(pos); // captura o ID da requisição atual
                while (pos < capacidade && heap.get(pos) == id) { // para ao mudar de ID ou encontrar FREE
                    pos++;
                }
            } else {
                pos++;
            }
        }

        BlocoOcupado[] blocos = new BlocoOcupado[contador];
        pos = 0;
        int indice = 0;
        while (pos < capacidade) {
            if (heap.get(pos) != Heap.FREE) {
                int start = pos;
                int id = heap.get(pos); // captura o ID da requisição atual
                int tamanho = 0;
                while (pos < capacidade && heap.get(pos) == id) { // para ao mudar de ID ou encontrar FREE
                    tamanho++;
                    pos++;
                }
                blocos[indice++] = new BlocoOcupado(start, tamanho);
            } else {
                pos++;
            }
        }

        return blocos;
    }

    /**
     * Retorna o número de posições livres na heap (em inteiros).
     *
     * @return inteiros livres na heap
     */
    private int getFreeInts() {
        Heap heap = worstFit.getHeap();
        int capacidade = heap.getCapacity();
        int livres = 0;

        int pos = 0;
        while (pos < capacidade) {
            if (heap.get(pos) == Heap.FREE) {
                livres++;
            }
            pos++;
        }

        return livres;
    }

    /**
     * Retorna a quantidade total de memória livre em bytes.
     *
     * @return bytes livres na heap
     */
    public int getFreeBytes() {
        return getFreeInts() * 4;
    }

    /**
     * Struct simples para representar um bloco ocupado.
     */
    private static class BlocoOcupado {
        final int index;
        final int size;

        BlocoOcupado(int index, int size) {
            this.index = index;
            this.size = size;
        }
    }

    /**
     * Relatório de resultados da liberação RANDOM.
     */
    public static class RelatorioLiberacao {
        private int blocosLiberados;
        private int bytesRecuperados;
        private boolean metaAtingida;

        void adicionarLiberado(int index, int bytes) {
            blocosLiberados++;
            bytesRecuperados += bytes;
        }

        void setMetaAtingida(boolean atingida) {
            this.metaAtingida = atingida;
        }

        public int getBlocosLiberados() {
            return blocosLiberados;
        }

        public int getBytesRecuperados() {
            return bytesRecuperados;
        }

        public boolean isMetaAtingida() {
            return metaAtingida;
        }

        public void imprimirRelatorio() {
            System.out.println("\n========== RELATÓRIO DE LIBERAÇÃO RANDOM ==========");
            System.out.println("Blocos liberados: " + blocosLiberados);
            System.out.println("Memória recuperada: " + bytesRecuperados + " bytes");
            System.out.println("Meta de 30% livre atingida: " + (metaAtingida ? "SIM" : "NÃO"));
            System.out.println("=================================================\n");
        }
    }
}
