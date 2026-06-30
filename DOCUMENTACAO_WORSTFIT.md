# Documentação - Classe WorstFit

## 📋 Visão Geral

A classe `WorstFit` implementa o **algoritmo Worst Fit** para alocação dinâmica de memória em um simulador educacional de gerenciamento de heap. O projeto atualmente segue uma arquitetura híbrida:

- **Frontend web em React + TypeScript + Vite** para interface interativa e visualização da simulação
- **Modelo em Java** para implementação do algoritmo, controle da heap e comunicação com a camada de servidor
- A antiga abordagem baseada em **JavaFX** foi substituída por uma interface web, o que facilita a execução em navegador e a integração com APIs

---

## 🏗️ Arquitetura Atual do Projeto

### Componentes principais

- **Frontend**: localizado na pasta `frontend/`, com componentes para painel de simulação, grid de memória, métricas, log de eventos e configuração
- **Modelo/ lógica**: localizado na pasta `model/`, contendo as classes `WorstFit`, `Heap`, `Requisitor_Memoria`, `HeapApiServer` e testes associados
- **Sincronização/ concorrência**: arquivos em `sync/` para explorar conceitos de semáforos e sincronização
- **Comunicação**: o frontend consome a API do servidor Java para exibir os resultados da simulação em tempo real

### Representação da Heap

A heap é representada como um vetor de inteiros primitivos (`int[]`):

```
heap = int[heapSize]
```

Onde:

- **Cada posição** = inteiro de 4 bytes
- **Tamanho total em bytes** = `heapSize * 4`
- **Inicializado pelo usuário** em kilobytes (KB)

### Convenção de Marcação de Blocos

| Valor em heap[i] | Significado   | Tamanho           |
| ---------------- | ------------- | ----------------- |
| **Valor > 0**    | Bloco OCUPADO | `valor` inteiros  |
| **Valor < 0**    | Bloco LIVRE   | `-valor` inteiros |

**Importante:** Apenas a primeira posição de cada bloco contém a marcação. As demais posições do bloco não são marcadas individualmente.

#### Exemplo Visual

```
Heap (índices e valores):
┌─────┬─────┬─────┬─────┬─────┬─────┬─────┬─────┐
│  0  │ 1   │ 2   │ 3   │ 4   │ 5   │ 6   │ 7   │  (índices)
├─────┼─────┼─────┼─────┼─────┼─────┼─────┼─────┤
│ 100 │ ?   │ ?   │ ?   │-50  │ ?   │ ?   │ ?   │  (valores)
└─────┴─────┴─────┴─────┴─────┴─────┴─────┴─────┘
  ↓                        ↓
OCUPADO               LIVRE
(100 inteiros)        (50 inteiros)

Bloco 1: [0-99]    OCUPADO (100 inteiros = 400 bytes)
Bloco 2: [100-149] LIVRE   (50 inteiros = 200 bytes)
```

---

## 🔍 Algoritmo Worst Fit - Detalhamento

### Pseudocódigo

```
funcao allocate(tamanhoEmBytes):
    1. Converter tamanho em bytes para inteiros (arredondar para cima)
    2. Inicializar: worstFitIndex = -1, worstFitSize = -1
    3. Para cada bloco da heap (O(n)):
        a. Se bloco é livre E tamanho >= necessário:
            - Se tamanho > worstFitSize:
                + Atualizar worstFitIndex e worstFitSize
    4. Se nenhum bloco adequado encontrado:
        - Retornar -1
    5. Alocar o bloco (marcar como positivo)
    6. Se há espaço restante:
        - Criar novo bloco livre com o restante
    7. Retornar worstFitIndex
```

### Complexidade de Tempo

- **Busca do melhor bloco**: **O(n)** — percorre TODA a heap
- **Alocação**: **O(1)** — apenas uma marcação
- **Coalescência**: **O(m)** — m = número de blocos
- **Total por alocação**: **O(n + m)**

### Complexidade de Espaço

- **O(1)** — apenas armazena pontos de referência temporários

---

## 💾 Métodos Principais

### `public WorstFit(int sizeInKB)`

Inicializa uma nova heap com o tamanho especificado.

**Parâmetros:**

- `sizeInKB` — Tamanho total em kilobytes

**Exemplo:**

```java
WorstFit heap = new WorstFit(64);  // 64 KB = 65.536 bytes = 16.384 inteiros
```

---

### `public int allocate(int sizeInBytes)`

Aloca um bloco de memória usando Worst Fit.

**Parâmetros:**

- `sizeInBytes` — Tamanho desejado em bytes

**Retorno:**

- Índice inicial do bloco alocado (>= 0)
- `-1` se sem espaço suficiente

**Exemplo:**

```java
int addr = heap.allocate(256);  // Aloca 256 bytes
if (addr != -1) {
    System.out.println("Alocado no índice: " + addr);
} else {
    System.out.println("Erro: memória insuficiente");
}
```

**Comportamento:**

1. Percorre toda a heap identificando blocos livres
2. Seleciona o MAIOR bloco que caba a requisição
3. Marca-o como ocupado
4. Cria novo bloco livre com o espaço restante (se houver)

---

### `public void deallocate(int index, int sizeInBytes)`

Libera um bloco de memória previamente alocado.

**Parâmetros:**

- `index` — Índice inicial do bloco
- `sizeInBytes` — Tamanho do bloco em bytes

**Exemplo:**

```java
heap.deallocate(addr, 256);  // Libera o bloco
```

**Comportamento:**

1. Marca o bloco como livre (negativo)
2. Executa coalescência com blocos adjacentes livres
3. Reduz fragmentação

---

### `public void printHeapStatus()`

Exibe o estado completo da heap no console.

**Saída Exemplo:**

```
========== ESTADO DA HEAP ==========
Tamanho total: 64 KB (65536 bytes, 16384 inteiros)
-----------------------------------
Bloco  1: [     0-    99] OCUPADO (   100 inteiros =     400 bytes)
Bloco  2: [   100-   149] LIVRE   (    50 inteiros =     200 bytes)
Bloco  3: [   150- 16383] LIVRE   ( 16234 inteiros = 64936 bytes)
-----------------------------------
Memória ocupada: 100 inteiros (400 bytes = 0.61%)
Memória livre:   16284 inteiros (65136 bytes = 99.39%)
Fragmentação externa: 99.69%
====================================
```

---

### `public double calculateExternalFragmentation()`

Calcula a fragmentação externa da heap.

**Fórmula:**

```
Fragmentação = (Espaço livre total - Maior bloco livre) / Espaço livre total
```

**Retorno:**

- Percentual de fragmentação (0 a 100)
- 0% = sem fragmentação (um único bloco livre)
- 100% = máxima fragmentação (sem bloco contíguo grande)

**Exemplo:**

```java
double frag = heap.calculateExternalFragmentation();
System.out.printf("Fragmentação: %.2f%%%n", frag);
```

---

### `public int getLargestFreeBlock()`

Retorna o tamanho do maior bloco livre.

**Retorno:**

- Tamanho em inteiros

**Exemplo:**

```java
int maxFree = heap.getLargestFreeBlock();
System.out.println("Maior bloco livre: " + (maxFree * 4) + " bytes");
```

---

### `public int getTotalFreeMemory()` / `getTotalOccupiedMemory()`

Retornam o total de memória livre/ocupada.

**Retorno:**

- Tamanho em inteiros

---

### `public int[] snapshot()`

Retorna uma cópia do estado atual da heap.

**Uso:** Para análise ou backup do estado.

---

## 🧪 Exemplos de Uso

### Exemplo 1: Alocação simples

```java
WorstFit heap = new WorstFit(32);  // 32 KB

// Alocar 256 bytes
int addr1 = heap.allocate(256);
System.out.println("Bloco 1 alocado em: " + addr1);

// Alocar 128 bytes
int addr2 = heap.allocate(128);
System.out.println("Bloco 2 alocado em: " + addr2);

heap.printHeapStatus();
```

### Exemplo 2: Demonstração de Worst Fit

```java
WorstFit heap = new WorstFit(64);

int addr1 = heap.allocate(256);   // Aloca 256 bytes
int addr2 = heap.allocate(128);   // Aloca 128 bytes
int addr3 = heap.allocate(100);   // Aloca 100 bytes

heap.deallocate(addr1, 256);      // Libera 256 bytes

// Agora temos:
// - Bloco livre de 256 bytes
// - Bloco ocupado de 128 bytes
// - Bloco ocupado de 100 bytes
// - Bloco livre grande

// Worst Fit escolherá o MAIOR bloco livre (não o primeiro!)
int addr4 = heap.allocate(200);
System.out.println("Novo bloco alocado em: " + addr4);

heap.printHeapStatus();
```

### Exemplo 3: Análise de fragmentação

```java
WorstFit heap = new WorstFit(16);

int[] addrs = new int[5];
for (int i = 0; i < 5; i++) {
    addrs[i] = heap.allocate(100);
}

// Liberar alternadamente para criar fragmentação
heap.deallocate(addrs[0], 100);
heap.deallocate(addrs[2], 100);
heap.deallocate(addrs[4], 100);

System.out.println("Fragmentação: " + heap.calculateExternalFragmentation() + "%");
System.out.println("Total livre: " + heap.getTotalFreeMemory() * 4 + " bytes");
System.out.println("Maior bloco: " + heap.getLargestFreeBlock() * 4 + " bytes");

heap.printHeapStatus();
```

---

## ⚙️ Coalescência de Blocos

A coalescência é executada automaticamente após cada liberação (`deallocate`).

**O que faz:**

- Une blocos livres adjacentes em um único bloco maior
- Reduz fragmentação externa
- Melhora futuras alocações

**Exemplo:**

```
Antes da coalescência:
[OCUPADO: 100][LIVRE: 50][LIVRE: 75][OCUPADO: 200]

Depois da coalescência:
[OCUPADO: 100][LIVRE: 125][OCUPADO: 200]
```

---

## 📊 Análise Comparativa: Worst Fit vs. First Fit

| Aspecto            | **Worst Fit**       | **First Fit**           |
| ------------------ | ------------------- | ----------------------- |
| **Seleção**        | Maior bloco         | Primeiro bloco adequado |
| **Complexidade**   | O(n)                | O(n)                    |
| **Fragmentação**   | Menor a curto prazo | Maior a curto prazo     |
| **Velocidade**     | Mais lento          | Mais rápido             |
| **Espaço ocupado** | Maior               | Menor                   |
| **Blocos grandes** | Preserva            | Fragmenta               |

---

## 🐛 Validações e Erros

A classe valida:

- ✓ Tamanho de heap > 0 KB
- ✓ Índices válidos em deallocate
- ✓ Tamanhos coerentes
- ✓ Evita loops infinitos

**Comportamento em erro:**

- Retorna `-1` em alocação sem espaço
- Imprime erro em stderr para deallocate inválido
- Continua operável

---

## 📝 Notas de Implementação

1. **Lógica central simples**: a implementação do algoritmo continua baseada em arrays primitivos `int[]`
2. **Sem List/ArrayList**: Controle manual de índices para manter a transparência didática
3. **O(n) obrigatório**: a busca por blocos livres percorre toda a heap em alocação
4. **Interface web moderna**: a visualização foi migrada para React/Vite em vez de JavaFX
5. **Código didático**: comentários em português, estrutura clara e foco no ensino de gerenciamento de memória

---

## 🔗 Integração com o Projeto

A classe `WorstFit` pode ser integrada com:

- `frontend/` — Interface web em React para visualização e interação com a simulação
- `Heap.java` — Classe base de representação da heap
- `HeapApiServer.java` — Servidor/API que expõe a lógica do simulador para o frontend
- `Requisitor_Memoria.java` — Gerador de requisições e cenários de teste
- Algoritmos alternativos (FirstFit, BestFit, etc.)

---

## 📚 Referências

- **Algorithm**: Worst-Fit Memory Allocation
- **Contexto**: Operating Systems (Memory Management)
- **Complexidade**: Introduction to Algorithms (CLRS)
- **Fragmentação Externa**: Stallings - Operating Systems Internals

---

**Versão**: 1.0  
**Autor**: Sistema de Gerenciamento de Memória  
**Data**: 2026  
**Linguagem**: Java
