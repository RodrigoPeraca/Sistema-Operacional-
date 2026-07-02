package model;

import java.util.Random;

/**
 * Política de liberação RANDOM para recuperar memória quando a heap está cheia.
 *
 * ── Responsabilidade de sincronização ────────────────────────────────────────
 * Esta classe NÃO possui semáforo próprio.
 *
 * Motivo: GerenciadorLiberacao opera diretamente sobre a mesma heap e free list
 * que WorstFit. Se tivesse seu próprio mutex, haveria risco de deadlock por
 * ordem de aquisição (WorstFit tenta adquirir gerenciadorMutex enquanto segura
 * heapMutex, e vice-versa).
 *
 * A solução correta: executarLiberacaoRandomica() é sempre chamado de dentro
 * do heapMutex do WorstFit — ou seja, já está na região crítica quando chega
 * aqui. O gerenciador assume isso e não tenta adquirir nenhum lock adicional.
 *
 * ── Algoritmo ────────────────────────────────────────────────────────────────
 * 1. Identifica todos os blocos ocupados (varredura O(n) do snapshot).
 * 2. Embaralha os blocos com Fisher-Yates (sem coleções, sem GC).
 * 3. Libera blocos via WorstFit.deallocate() até que >= 30% da heap esteja livre.
 *    Como deallocate() também adquire heapMutex e já estamos dentro dele,
 *    usamos deallocateInternal via o método interno — ver nota abaixo.
 *
 * NOTA IMPORTANTE: GerenciadorLiberacao chama worstFit.deallocate() que tenta
 * adquirir heapMutex. Para evitar deadlock com o mutex já segurado pelo
 * chamador, o benchmark e a API chamam executarLiberacaoRandomica() FORA do
 * heapMutex, como uma operação separada. O fluxo correto é:
 *
 *   1. allocate() falha (retorna -1)         → fora do mutex
 *   2. gerenciador.executarLiberacaoRandomica() → adquire/libera mutex internamente
 *   3. allocate() tenta de novo               → adquire mutex novamente
 *
 * Isso garante que nunca tentamos adquirir heapMutex recursivamente.
 */
public class GerenciadorLiberacao {

    private static final double META_LIVRE = 0.30;

    private final WorstFit worstFit;
    private final Random   random;

    public GerenciadorLiberacao(WorstFit worstFit) {
        if (worstFit == null)
            throw new IllegalArgumentException("WorstFit não pode ser nulo");
        this.worstFit = worstFit;
        this.random   = new Random();
    }

    /**
     * Executa a liberação RANDOM até que >= 30% da heap esteja livre.
     *
     * Chama worstFit.deallocate() que adquire e libera heapMutex por conta própria.
     * Por isso, este método deve ser chamado FORA de qualquer lock existente.
     *
     * @return relatório com blocos liberados, bytes recuperados e se a meta foi atingida
     */
    public RelatorioLiberacao executarLiberacaoRandomica() {
        return executarLiberacaoRandomica(false);
    }

    public RelatorioLiberacao executarLiberacaoRandomica(boolean verbose) {
        RelatorioLiberacao relatorio = new RelatorioLiberacao();
        int totalInts = worstFit.getCapacity();
        int alvoLivre = (int) Math.ceil(totalInts * META_LIVRE);

        while (worstFit.getTotalFreeMemory() < alvoLivre) {
            BlocoOcupado[] blocos = identificarBlocosOcupados();
            if (blocos.length == 0) break;

            // Escolhe aleatoriamente
            int escolhido = random.nextInt(blocos.length);
            BlocoOcupado bloco = blocos[escolhido];

            if (verbose) {
                System.out.println("RANDOM: liberando índice=" + bloco.index +
                        ", " + (bloco.size * 4) + " bytes");
            }

            // deallocate() adquire heapMutex internamente — OK aqui pois estamos fora de qualquer lock
            worstFit.deallocate(bloco.index, bloco.size * 4);
            relatorio.adicionarLiberado(bloco.index, bloco.size * 4);
        }

        relatorio.setMetaAtingida(worstFit.getTotalFreeMemory() >= alvoLivre);
        return relatorio;
    }

    /**
     * Varre o snapshot da heap para identificar todos os blocos ocupados.
     * Usa array pré-alocado em vez de coleções para minimizar pressão no GC.
     */
    private BlocoOcupado[] identificarBlocosOcupados() {
        Heap heap       = worstFit.getHeap();
        int  capacidade = heap.getCapacity();

        // Primeira passagem: conta blocos distintos
        int contador = 0, pos = 0;
        while (pos < capacidade) {
            if (heap.get(pos) != Heap.FREE) {
                contador++;
                int id = heap.get(pos);
                while (pos < capacidade && heap.get(pos) == id) pos++;
            } else {
                pos++;
            }
        }

        // Segunda passagem: preenche o array
        BlocoOcupado[] blocos = new BlocoOcupado[contador];
        pos = 0; int idx = 0;
        while (pos < capacidade) {
            if (heap.get(pos) != Heap.FREE) {
                int start = pos, id = heap.get(pos), tamanho = 0;
                while (pos < capacidade && heap.get(pos) == id) { tamanho++; pos++; }
                blocos[idx++] = new BlocoOcupado(start, tamanho);
            } else {
                pos++;
            }
        }

        return blocos;
    }

    // ── Tipos internos ────────────────────────────────────────────────────────

    private static class BlocoOcupado {
        final int index, size;
        BlocoOcupado(int index, int size) { this.index = index; this.size = size; }
    }

    public static class RelatorioLiberacao {
        private int    blocosLiberados  = 0;
        private int    bytesRecuperados = 0;
        private boolean metaAtingida   = false;

        void adicionarLiberado(int index, int bytes) {
            blocosLiberados++;
            bytesRecuperados += bytes;
        }

        void setMetaAtingida(boolean atingida) { this.metaAtingida = atingida; }

        public int     getBlocosLiberados()   { return blocosLiberados;  }
        public int     getBytesRecuperados()  { return bytesRecuperados; }
        public boolean isMetaAtingida()       { return metaAtingida;     }

        public void imprimirRelatorio() {
            System.out.println("\n========== RELATÓRIO RANDOM ==========");
            System.out.println("Blocos liberados : " + blocosLiberados);
            System.out.println("Bytes recuperados: " + bytesRecuperados);
            System.out.println("Meta 30% atingida: " + (metaAtingida ? "SIM" : "NÃO"));
            System.out.println("======================================\n");
        }
    }
}
