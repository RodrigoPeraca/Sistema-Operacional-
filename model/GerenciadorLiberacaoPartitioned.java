package model;

import java.util.Random;

/**
 * Adaptador de GerenciadorLiberacao para WorstFitPartitioned.
 */
public class GerenciadorLiberacaoPartitioned {

    private static final double META_LIVRE = 0.30;

    private final WorstFitPartitioned worstFit;
    private final Random random;

    public GerenciadorLiberacaoPartitioned(WorstFitPartitioned worstFit) {
        if (worstFit == null)
            throw new IllegalArgumentException("WorstFitPartitioned não pode ser nulo");
        this.worstFit = worstFit;
        this.random   = new Random();
    }

    public GerenciadorLiberacao.RelatorioLiberacao executarLiberacaoRandomica() {
        return executarLiberacaoRandomica(false);
    }

    public GerenciadorLiberacao.RelatorioLiberacao executarLiberacaoRandomica(boolean verbose) {
        GerenciadorLiberacao.RelatorioLiberacao relatorio = new GerenciadorLiberacao.RelatorioLiberacao();
        int totalInts = worstFit.getCapacity();
        int alvoLivre = (int) Math.ceil(totalInts * META_LIVRE);

        int[] snap = worstFit.snapshot();
        java.util.List<Integer[]> blocos = new java.util.ArrayList<>();
        
        for (int i = 0; i < snap.length; ) {
            if (snap[i] != Heap.FREE) {
                int size = 1, val = snap[i], j = i + 1;
                while (j < snap.length && snap[j] == val) { size++; j++; }
                blocos.add(new Integer[]{i, size});
                i = j;
            } else {
                i++;
            }
        }

        while (worstFit.getTotalFreeMemory() < alvoLivre && !blocos.isEmpty()) {
            int escolhido = random.nextInt(blocos.size());
            Integer[] bloco = blocos.get(escolhido);
            int idx = bloco[0];
            int sz = bloco[1];
            int bytes = sz * 4;

            worstFit.deallocate(idx, bytes);
            relatorio.adicionarLiberado(idx, bytes);  // Rastreia liberação
            blocos.remove(escolhido);
        }

        relatorio.setMetaAtingida(worstFit.getTotalFreeMemory() >= alvoLivre);
        return relatorio;
    }
}
