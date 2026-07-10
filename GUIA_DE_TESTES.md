# Guia de Testes — Simulador de Heap com Semáforos

## Estrutura de pacotes

```
src/
├── model/
│   ├── Heap.java                  sem semáforo — estrutura de dados pura
│   ├── Requisitor_Memoria.java    idMutex      — protege geração de IDs únicos
│   ├── WorstFit.java              heapMutex    — protege heap + free list
│   ├── GerenciadorLiberacao.java  sem semáforo — chamado dentro do heapMutex do WorstFit
│   └── HeapApiServer.java         servidor HTTP — chama HeapBenchmark
├── sync/
│   └── Semaphore.java             BinarySemaphore — base de tudo
└── benchmark/
    └── HeapBenchmark.java         queueMutex + countersMutex (paralelo)
```

---

## Passo 1 — Compilar tudo

Na raiz do projeto:

````bash
# Criar pasta de saída
mkdir -p out

# Compilar todos os arquivos de uma vez (ordem correta de dependências)
```javac -d out .\sync\*.java .\model\*.java .\benchmark\*.java

Se compilar sem erros, o terminal não exibe nada. A pasta `out/` terá os `.class` organizados por pacote.

---

## Passo 2 — Testar o Semáforo isolado

Valida que o BinarySemaphore e o semáforo contador funcionam corretamente antes de testar a heap.

```bash
# Compilar o teste do semáforo
javac -d out sync/Semaphore.java sync/SemaphoreTest.java

# Executar
java -cp out sync.SemaphoreTest
````

**O que verificar na saída:**

```
✓ Com mutex: esperado 10000, obtido 10000        ← sem race condition
✓ Nunca mais de 3 threads simultâneas            ← invariante do semáforo contador
✓ Todos os 20 itens foram produzidos             ← produtor/consumidor correto
✓ Todos os 20 itens foram consumidos
✓ Nenhum slot vazio consumido
✓ tryAcquire() retorna true quando há permissão
✓ tryAcquire() retorna false imediatamente quando bloqueado
✓ tryAcquire() retorna true após release()
```

Se algum item mostrar `✗ FALHOU`, há problema no semáforo antes de continuar.

---

## Passo 3 — Testar o WorstFit isolado (sem threads)

```bash
javac -d out .\sync\*.java .\model\*.java .\benchmark\*.java
java -cp out model.WorstFitTest
```

**O que verificar:**

- Alocações retornam índices >= 0
- Worst Fit escolhe o maior bloco (índice 0, que tem 256 bytes livres, não o de 128)
- Fragmentação calculada sem varrer a heap
- Após liberar todos os blocos, a heap volta a 1 bloco contíguo (coalescência funcionando)

---

## Passo 4 — Testar o Benchmark standalone (sem API)

Este é o teste principal: roda sequencial e paralelo com as mesmas entradas e mostra o comparativo real.

```bash
java -cp out benchmark.HeapBenchmark
```

**Tempo esperado:** 30–90 segundos (5 warmup + 10 medições × 2 modos)

**O que verificar na saída:**

```
╔══════════════════════════════════════════════════════╗
║      BENCHMARK: HEAP SEQUENCIAL vs PARALELO          ║
...
│  Latência  mín/méd/máx │  XX.XX /  XX.XX /  XX.XX ms │
│  Throughput mín/méd/máx │  XXXXX /  XXXXX /  XXXXX req/s│
│  Atendidas / Rejeitadas  │ XXXX / X
...
║  Speedup latência   :  X.XXx  (sequencial mais rápido) ║
║  Speedup throughput :  X.XXx  (sequencial mais rápido) ║
```

**Resultado esperado:** speedup < 1 (sequencial mais rápido).
Isso é o resultado CORRETO para este cenário — a região crítica (heapMutex)
domina o tempo e o overhead dos semáforos supera o ganho de paralelismo.
É exatamente essa conclusão que o trabalho precisa demonstrar.

---

## Passo 5 — Testar a API com o benchmark integrado

### 5a. Iniciar o servidor

```bash
java -cp out model.HeapApiServer
```

Saída esperada:

```
API rodando em http://localhost:8080
Endpoints:
  GET /api/health
  GET /api/simular-passos?heapKb=8&minBytes=16&maxBytes=1024&totalRequests=250
  GET /api/benchmark?heapKb=64&minBytes=16&maxBytes=256&totalRequests=5000
```

### 5b. Testar health (novo terminal)

```bash
curl http://localhost:8080/api/health
```

Esperado:

```json
{ "status": "ok", "message": "Backend Java rodando" }
```

### 5c. Testar simulação sequencial com passos

```bash
curl "http://localhost:8080/api/simular-passos?heapKb=1&minBytes=16&maxBytes=16&totalRequests=80"
```

Esperado: JSON com campo `"steps"` contendo array de snapshots da heap.
Verifique que `attendedRequests` chega a 80 e `removedVariables` fica em torno de 20.

### 5d. Testar benchmark real (demora ~30–90s)

```bash
curl "http://localhost:8080/api/benchmark?heapKb=64&minBytes=16&maxBytes=256&totalRequests=5000"
```

Esperado — JSON com estrutura:

```json
{
  "params": { "heapKb": 64, "totalRequests": 5000, ... },
  "sequencial": {
    "latenciaMinMs": ...,
    "latenciaMediaMs": ...,
    "throughputMedio": ...,
    "atendidas": ...,
    "randomAcionado": ...
  },
  "paralelo": {
    "latenciaMinMs": ...,
    "latenciaMediaMs": ...,
    "throughputMedio": ...,
    "atendidas": ...,
    "randomAcionado": ...
  },
  "speedup": {
    "latencia": 0.XX,
    "throughput": 0.XX
  }
}
```

**Os valores de speedup < 1 confirmam que o benchmark está real** — dados fabricados
teriam valores > 1 para o paralelo.

---

## Passo 6 — Teste controlado para a apresentação

Parâmetros exatos para reproduzir o cálculo teórico da apresentação:

```bash
curl "http://localhost:8080/api/simular-passos?heapKb=1&minBytes=16&maxBytes=16&totalRequests=80"
```

**Cálculo teórico:**

- 1 KB = 256 células (posições do vetor)
- Cada req = 16 bytes = 4 células
- Capacidade: 256 / 4 = 64 variáveis simultâneas
- Requisições 65–80 acionam RANDOM: libera ~30% = ~20 variáveis
- Resultado esperado: 80 atendidas, ~20 removidas, ~93,75% de uso

---

## Resumo dos semáforos por arquivo

| Arquivo                     | Semáforo                          | Região crítica protegida                                          |
| --------------------------- | --------------------------------- | ----------------------------------------------------------------- |
| `Semaphore.java`            | —                                 | implementação base (CAS + spin backoff)                           |
| `Requisitor_Memoria.java`   | `idMutex` (BinarySemaphore)       | leitura + incremento de `idCounter`                               |
| `WorstFit.java`             | `heapMutex` (BinarySemaphore)     | `heap[]` + `sizeToIndices` + `indexToSize` + `nextRequestId`      |
| `GerenciadorLiberacao.java` | nenhum próprio                    | chamado fora do heapMutex; usa deallocate() que adquire heapMutex |
| `HeapBenchmark.java`        | `queueMutex` (BinarySemaphore)    | índice `queueIndex` da fila compartilhada                         |
|                             | `countersMutex` (BinarySemaphore) | contadores `served`, `rejected`, `randoms`                        |
| `HeapApiServer.java`        | nenhum próprio                    | delega ao HeapBenchmark e WorstFit                                |
