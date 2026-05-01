🧠 Visão Geral

Este projeto consiste na implementação de um simulador de gerenciamento de memória, inspirado no funcionamento de sistemas operacionais.

O sistema será capaz de simular alocação dinâmica de memória, com geração de requisições, gerenciamento de heap e execução tanto sequencial quanto concorrente, permitindo análise de desempenho.

🛠️ Tecnologias Utilizadas

Java (principal)

Estrutura do sistema

Implementação dos algoritmos

JavaFX

Visualização gráfica da heap em tempo real

Interface interativa para análise

Programação concorrente (Java)

Threads

synchronized / controle de concorrência

Bibliotecas padrão Java

java.util

java.util.concurrent

🏗️ Estrutura do Projeto

O sistema seguirá uma arquitetura organizada em camadas:

model

Representação dos dados (Heap, Requisição)

service

Regras de negócio (alocação, liberação, geração)

service.interfaces / service.impl

Separação entre contratos e implementações (ex: First Fit, Random)

concurrent

Controle de execução paralela e threads

ui (JavaFX)

Interface gráfica

Visualização da heap

Controllers e componentes

stats

Coleta e exibição de métricas

main

Inicialização do sistema

⚙️ Como o Sistema Funciona

O sistema gera requisições de memória aleatórias

Tenta alocar essas requisições na heap

Caso não haja espaço suficiente:

Um algoritmo de liberação é acionado (RANDOM)

O sistema continua processando requisições

A execução pode ocorrer:

de forma sequencial

ou concorrente (com múltiplas threads)

A interface exibe:

estado da heap em tempo real

blocos ocupados e livres

▶️ Como Executar o Projeto (visão inicial)

Compilar o projeto Java

Executar a aplicação principal (App.java)

Interface JavaFX será aberta

Usuário poderá iniciar a simulação

(Detalhes serão refinados conforme implementação)

🎯 Objetivo Final

Ao final do projeto, espera-se ter:

✔ Simulador funcional de gerenciamento de memória

✔ Implementação de algoritmo de alocação (ex: First Fit)

✔ Sistema de liberação de memória (RANDOM)

✔ Versão sequencial e concorrente funcionando

✔ Comparação de desempenho entre as versões

✔ Interface gráfica exibindo a heap em tempo real

✔ Coleta e apresentação de métricas

🚀 Diferenciais do Projeto

Visualização gráfica da memória em tempo real

Arquitetura desacoplada (uso de interfaces)

Suporte a múltiplos algoritmos de alocação

Simulação próxima de cenários reais de sistemas operacionais

💡 Ideias Futuras (Opcional)

Implementar outros algoritmos (Best Fit, Worst Fit)

Adicionar compactação de memória

Exibir gráficos de desempenho

Controle de velocidade da simulação

Exportação de resultados