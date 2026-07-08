import { useEffect, useRef, useState } from "react";
import type { ReactNode } from "react";
import {
  Activity,
  ArchiveX,
  ArrowLeft,
  Clock,
  Cpu,
  Database,
  Gauge,
  Layers3,
  PlayCircle,
  Server,
  Trash2,
  Zap,
} from "lucide-react";

import { Header } from "./components/Header";
import { SidebarConfig } from "./components/SidebarConfig";
import { MetricsCard } from "./components/MetricsCard";
import { MemoryGrid } from "./components/MemoryGrid";
import { SimulationDashboard } from "./components/SimulationDashboard";
import { EventLog } from "./components/EventLog";

import type {
  BenchmarkItem,
  BenchmarkResult,
  ParallelVisualResponse,
  ParallelVisualStep,
  SimulationConfig,
} from "./types/simulation";

import { createSimulation } from "./utils/simulator";
import type { SimulationState } from "./utils/simulator";

import {
  runBackendBenchmark,
  runBackendParallelSteps,
  runBackendSimulationSteps,
} from "./services/api";

const defaultConfig: SimulationConfig = {
  heapKb: 8,
  minBytes: 16,
  maxBytes: 1024,
  totalRequests: 250,
  mode: "sequential",
  threads: 4,
};

const defaultBenchmark: BenchmarkItem[] = [
  {
    name: "Sequencial",
    tempoMs: 0,
  },
  {
    name: "Paralelo",
    tempoMs: 0,
  },
];

type BackendStatus = "idle" | "loading" | "animating" | "success" | "error";

type BenchmarkStatus = "idle" | "loading" | "success" | "error";

type ParallelVisualStatus =
  | "idle"
  | "loading"
  | "animating"
  | "paused"
  | "success"
  | "error";

type AppPage = "home" | "sequential" | "parallel";

export default function App() {
  const [page, setPage] = useState<AppPage>("home");

  const [config, setConfig] = useState<SimulationConfig>(defaultConfig);

  const [simulation, setSimulation] = useState<SimulationState>(() =>
    createSimulation(defaultConfig),
  );

  const [, setBenchmark] = useState<BenchmarkItem[]>(defaultBenchmark);

  const [backendStatus, setBackendStatus] = useState<BackendStatus>("idle");

  const [backendError, setBackendError] = useState<string | null>(null);

  const [benchmarkResult, setBenchmarkResult] =
    useState<BenchmarkResult | null>(null);

  const [benchmarkStatus, setBenchmarkStatus] =
    useState<BenchmarkStatus>("idle");

  const [benchmarkError, setBenchmarkError] = useState<string | null>(null);

  const intervalRef = useRef<number | null>(null);
  const [parallelVisual, setParallelVisual] =
    useState<ParallelVisualResponse | null>(null);

  const [parallelStep, setParallelStep] = useState<ParallelVisualStep | null>(
    null,
  );

  const [parallelVisualStatus, setParallelVisualStatus] =
    useState<ParallelVisualStatus>("idle");

  const [parallelVisualError, setParallelVisualError] = useState<string | null>(
    null,
  );

  const parallelIntervalRef = useRef<number | null>(null);

  const parallelStepIndexRef = useRef(0);

  useEffect(() => {
    return () => {
      clearAnimation();
      clearParallelAnimation();
    };
  }, []);

  function clearAnimation(): void {
    if (intervalRef.current !== null) {
      window.clearInterval(intervalRef.current);
      intervalRef.current = null;
    }
  }
  function clearParallelAnimation(): void {
    if (parallelIntervalRef.current !== null) {
      window.clearInterval(parallelIntervalRef.current);
      parallelIntervalRef.current = null;
    }
  }
  function startParallelAnimation(steps: ParallelVisualStep[]): void {
    clearParallelAnimation();

    setParallelVisualStatus("animating");

    parallelIntervalRef.current = window.setInterval(() => {
      const step = steps[parallelStepIndexRef.current];

      if (!step) {
        clearParallelAnimation();
        setParallelVisualStatus("success");
        return;
      }

      setParallelStep(step);
      parallelStepIndexRef.current++;

      if (parallelStepIndexRef.current >= steps.length) {
        clearParallelAnimation();
        setParallelVisualStatus("success");
      }
    }, 600);
  }
  function handleConfigChange(nextConfig: SimulationConfig): void {
    clearAnimation();

    const normalizedConfig = normalizeConfig(nextConfig);

    setConfig(normalizedConfig);
    setSimulation(createSimulation(normalizedConfig));
    setBenchmark(defaultBenchmark);
    setBackendStatus("idle");
    setBackendError(null);
    setBenchmarkStatus("idle");
    setBenchmarkError(null);
    setParallelVisual(null);
    setParallelStep(null);
    setParallelVisualStatus("idle");
    setParallelVisualError(null);
    parallelStepIndexRef.current = 0;
    clearParallelAnimation();
  }

  async function handleStartPause(): Promise<void> {
    if (backendStatus === "loading") {
      return;
    }

    if (backendStatus === "animating") {
      clearAnimation();
      setBackendStatus("success");

      setSimulation((current) => ({
        ...current,
        isRunning: false,
      }));

      return;
    }

    try {
      clearAnimation();

      setBackendStatus("loading");
      setBackendError(null);

      const result = await runBackendSimulationSteps(config);

      if (result.steps.length === 0) {
        setBackendStatus("error");
        setBackendError("O backend não retornou passos para animação.");
        return;
      }

      setBenchmark(result.benchmark ?? defaultBenchmark);
      setBackendStatus("animating");

      let index = 0;

      intervalRef.current = window.setInterval(() => {
        const step = result.steps[index];

        if (!step) {
          clearAnimation();
          setBackendStatus("success");

          setSimulation((current) => ({
            ...current,
            isRunning: false,
            isFinished: true,
          }));

          return;
        }

        setSimulation((current) => ({
          ...current,
          heap: step.heap,
          metrics: step.metrics,
          logs: step.logs,
          isRunning: true,
          isFinished: false,
          nextRequestId: step.metrics.generatedRequests + 1,
          startedAt: null,
        }));

        index++;

        if (index >= result.steps.length) {
          clearAnimation();
          setBackendStatus("success");

          setSimulation((current) => ({
            ...current,
            isRunning: false,
            isFinished: true,
          }));
        }
      }, 90);
    } catch (error) {
      const message =
        error instanceof Error
          ? error.message
          : "Erro desconhecido ao chamar backend";

      clearAnimation();
      setBackendStatus("error");
      setBackendError(message);
    }
  }

  async function handleRunBenchmark(): Promise<void> {
    try {
      setBenchmarkStatus("loading");
      setBenchmarkError(null);
      setBenchmarkResult(null);

      const benchmarkConfig = normalizeConfig({
        ...config,
        mode: "parallel",
        threads: 4,
      });

      setConfig(benchmarkConfig);

      const result = await runBackendBenchmark(benchmarkConfig);

      setBenchmarkResult(result);
      setBenchmarkStatus("success");
    } catch (error) {
      const message =
        error instanceof Error
          ? error.message
          : "Erro desconhecido ao chamar benchmark";

      setBenchmarkStatus("error");
      setBenchmarkError(message);
    }
  }

  async function handleRunParallelVisual(): Promise<void> {
    if (parallelVisualStatus === "loading") {
      return;
    }

    if (parallelVisualStatus === "animating") {
      clearParallelAnimation();
      setParallelVisualStatus("paused");
      return;
    }

    if (parallelVisualStatus === "paused" && parallelVisual) {
      startParallelAnimation(parallelVisual.steps);
      return;
    }

    try {
      clearParallelAnimation();

      setParallelVisualStatus("loading");
      setParallelVisualError(null);
      setParallelVisual(null);
      setParallelStep(null);
      parallelStepIndexRef.current = 0;

      const visualConfig = normalizeConfig({
        ...config,
        mode: "parallel",
        threads: 4,
      });

      setConfig(visualConfig);

      const result = await runBackendParallelSteps(visualConfig);

      if (result.steps.length === 0) {
        setParallelVisualStatus("error");
        setParallelVisualError("O backend não retornou passos paralelos.");
        return;
      }

      setParallelVisual(result);
      setParallelStep(result.steps[0]);
      parallelStepIndexRef.current = 1;

      startParallelAnimation(result.steps);
    } catch (error) {
      const message =
        error instanceof Error
          ? error.message
          : "Erro desconhecido ao chamar simulação paralela";

      clearParallelAnimation();
      setParallelVisualStatus("error");
      setParallelVisualError(message);
    }
  }
  function handleReset(): void {
    clearAnimation();

    setSimulation(createSimulation(config));
    setBenchmark(defaultBenchmark);
    setBackendStatus("idle");
    setBackendError(null);
  }

  function handleGoHome(): void {
    clearAnimation();
    clearParallelAnimation();
    setPage("home");
  }

  if (page === "home") {
    return (
      <AppShell>
        <HomePage
          onOpenSequential={() => setPage("sequential")}
          onOpenParallel={() => setPage("parallel")}
        />
      </AppShell>
    );
  }

  if (page === "parallel") {
    return (
      <AppShell>
        <ParallelPlaceholderPage
          onBack={handleGoHome}
          config={config}
          benchmarkResult={benchmarkResult}
          benchmarkStatus={benchmarkStatus}
          benchmarkError={benchmarkError}
          parallelVisual={parallelVisual}
          parallelStep={parallelStep}
          parallelVisualStatus={parallelVisualStatus}
          parallelVisualError={parallelVisualError}
          onConfigChange={handleConfigChange}
          onRunBenchmark={handleRunBenchmark}
          onRunParallelVisual={handleRunParallelVisual}
        />
      </AppShell>
    );
  }

  const occupiedCells = simulation.heap.filter(
    (cell) => cell.id !== null,
  ).length;
  const freeCells = simulation.heap.length - occupiedCells;

  const occupiedBytes = occupiedCells * 4;
  const freeBytes = freeCells * 4;

  return (
    <AppShell>
      <div className="flex flex-col lg:flex-row">
        <SidebarConfig
          config={config}
          isRunning={
            backendStatus === "loading" || backendStatus === "animating"
          }
          isFinished={false}
          showModeSelector={false}
          onConfigChange={handleConfigChange}
          onStartPause={handleStartPause}
          onReset={handleReset}
        />

        <main className="min-w-0 flex-1 space-y-5 p-5">
          <div className="flex flex-col gap-3 rounded-xl border border-zinc-800 bg-zinc-900/80 p-4 md:flex-row md:items-center md:justify-between">
            <div className="flex items-center gap-3">
              <button
                type="button"
                onClick={handleGoHome}
                className="rounded-lg border border-zinc-700 bg-zinc-900 p-2 text-zinc-300 transition hover:border-cyan-500/50 hover:text-cyan-300"
                title="Voltar para tela inicial"
              >
                <ArrowLeft className="h-5 w-5" />
              </button>

              <div className="rounded-lg border border-cyan-500/20 bg-cyan-500/10 p-2">
                <Server className="h-5 w-5 text-cyan-400" />
              </div>

              <div>
                <p className="font-semibold text-zinc-100">
                  Simulação Sequencial — Validação Visual
                </p>
                <p className="text-sm text-zinc-400">
                  Fonte dos dados: http://localhost:8080/api/simular-passos
                </p>
              </div>
            </div>

            <BackendBadge status={backendStatus} />
          </div>

          {backendError && (
            <div className="rounded-xl border border-rose-500/30 bg-rose-500/10 px-4 py-3 text-sm text-rose-300">
              Erro ao conectar com o backend: {backendError}
            </div>
          )}

          <section className="grid grid-cols-1 gap-4 md:grid-cols-2 xl:grid-cols-5">
            <MetricsCard
              title="Requisições atendidas"
              value={simulation.metrics.attendedRequests}
              description={`${simulation.metrics.generatedRequests}/${simulation.config.totalRequests} processadas`}
              icon={Activity}
              accent="cyan"
            />

            <MetricsCard
              title="Tamanho médio"
              value={`${simulation.metrics.averageVariableSize.toFixed(1)} B`}
              description="Média das variáveis alocadas"
              icon={Gauge}
              accent="emerald"
            />

            <MetricsCard
              title="Variáveis removidas"
              value={simulation.metrics.removedVariables}
              description="Liberação RANDOM 30%"
              icon={Trash2}
              accent="rose"
            />

            <MetricsCard
              title="Ocupadas / Livres"
              value={`${occupiedCells} / ${freeCells}`}
              description={`${occupiedBytes} B ocupados · ${freeBytes} B livres`}
              icon={Database}
              accent="amber"
            />

            <MetricsCard
              title="Tempo"
              value={`${simulation.metrics.executionTimeMs.toFixed(0)} ms`}
              description="Execução sequencial"
              icon={Clock}
              accent="fuchsia"
            />
          </section>

          {simulation.metrics.rejectedRequests > 0 && (
            <div className="flex items-center gap-2 rounded-xl border border-orange-500/30 bg-orange-500/10 px-4 py-3 text-sm text-orange-300">
              <ArchiveX className="h-5 w-5" />
              {simulation.metrics.rejectedRequests} requisição(ões) não puderam
              ser alocadas mesmo após liberação.
            </div>
          )}

          <MemoryGrid heap={simulation.heap} />

          <SimulationDashboard metrics={simulation.metrics} />

          <EventLog logs={simulation.logs} />
        </main>
      </div>
    </AppShell>
  );
}

function AppShell({ children }: { children: React.ReactNode }) {
  return (
    <div className="min-h-screen bg-zinc-950 text-zinc-100">
      <Header />
      {children}
    </div>
  );
}

function HomePage({
  onOpenSequential,
  onOpenParallel,
}: {
  onOpenSequential: () => void;
  onOpenParallel: () => void;
}) {
  return (
    <main className="mx-auto flex min-h-[calc(100vh-73px)] w-full max-w-7xl flex-col justify-center px-5 py-8">
      <section className="grid gap-8 lg:grid-cols-[1.1fr_0.9fr] lg:items-center">
        <div className="space-y-6">
          <div className="inline-flex items-center gap-2 rounded-full border border-cyan-500/30 bg-cyan-500/10 px-3 py-1 text-xs font-semibold uppercase tracking-[0.2em] text-cyan-300">
            Sistemas Operacionais
          </div>

          <div className="space-y-4">
            <h1 className="max-w-4xl text-4xl font-bold tracking-tight text-zinc-50 md:text-6xl">
              Simulador de Gerenciamento Dinâmico de Memória
            </h1>

            <p className="max-w-3xl text-base leading-relaxed text-zinc-400 md:text-lg">
              O sistema simula uma heap representada por um vetor de inteiros,
              aplicando o algoritmo Worst Fit para alocação dinâmica de memória.
              A tela sequencial valida visualmente a heap, enquanto a tela
              paralela será usada para análise de desempenho, threads e regiões
              críticas.
            </p>
          </div>

          <div className="grid gap-3 sm:grid-cols-2">
            <button
              type="button"
              onClick={onOpenSequential}
              className="group rounded-2xl border border-cyan-500/30 bg-cyan-500 px-5 py-4 text-left font-semibold text-zinc-950 transition hover:bg-cyan-400"
            >
              <div className="mb-3 flex items-center gap-2">
                <PlayCircle className="h-5 w-5" />
                <span>Simulação Sequencial</span>
              </div>
              <p className="text-sm font-medium text-zinc-900">
                Visualizar heap, logs, alocações e liberação RANDOM passo a
                passo.
              </p>
            </button>

            <button
              type="button"
              onClick={onOpenParallel}
              className="group rounded-2xl border border-fuchsia-500/30 bg-zinc-900 px-5 py-4 text-left font-semibold text-zinc-100 transition hover:border-fuchsia-400 hover:bg-fuchsia-500/10"
            >
              <div className="mb-3 flex items-center gap-2 text-fuchsia-300">
                <Zap className="h-5 w-5" />
                <span>Benchmark Paralelo</span>
              </div>
              <p className="text-sm font-medium text-zinc-400">
                Comparar execução sequencial e paralela com os mesmos
                parâmetros.
              </p>
            </button>
          </div>
        </div>

        <div className="grid gap-4">
          <FeatureCard
            icon={<Database className="h-5 w-5" />}
            title="Heap simulada"
            description="A memória é representada por células de inteiros: 0 indica livre e IDs positivos indicam posições ocupadas."
            accent="cyan"
          />

          <FeatureCard
            icon={<Layers3 className="h-5 w-5" />}
            title="Worst Fit + Free List"
            description="O algoritmo escolhe o maior bloco livre disponível e atualiza a lista de blocos após cada alocação ou liberação."
            accent="emerald"
          />

          <FeatureCard
            icon={<Cpu className="h-5 w-5" />}
            title="Paralelismo e regiões críticas"
            description="A versão paralela trabalha com segmentos da heap e mecanismos de sincronização para reduzir contenção entre threads."
            accent="fuchsia"
          />
        </div>
      </section>
    </main>
  );
}

function FeatureCard({
  icon,
  title,
  description,
  accent,
}: {
  icon: React.ReactNode;
  title: string;
  description: string;
  accent: "cyan" | "emerald" | "fuchsia";
}) {
  const accents = {
    cyan: "border-cyan-500/20 bg-cyan-500/10 text-cyan-300",
    emerald: "border-emerald-500/20 bg-emerald-500/10 text-emerald-300",
    fuchsia: "border-fuchsia-500/20 bg-fuchsia-500/10 text-fuchsia-300",
  };

  return (
    <div className="rounded-2xl border border-zinc-800 bg-zinc-900/80 p-5">
      <div
        className={`mb-4 inline-flex rounded-xl border p-2 ${accents[accent]}`}
      >
        {icon}
      </div>
      <h3 className="mb-2 font-semibold text-zinc-100">{title}</h3>
      <p className="text-sm leading-relaxed text-zinc-400">{description}</p>
    </div>
  );
}

function ParallelPlaceholderPage({
  onBack,
  config,
  benchmarkResult,
  benchmarkStatus,
  benchmarkError,
  parallelVisual,
  parallelStep,
  parallelVisualStatus,
  parallelVisualError,
  onConfigChange,
  onRunBenchmark,
  onRunParallelVisual,
}: {
  onBack: () => void;
  config: SimulationConfig;
  benchmarkResult: BenchmarkResult | null;
  benchmarkStatus: BenchmarkStatus;
  benchmarkError: string | null;
  parallelVisual: ParallelVisualResponse | null;
  parallelStep: ParallelVisualStep | null;
  parallelVisualStatus: ParallelVisualStatus;
  parallelVisualError: string | null;
  onConfigChange: (config: SimulationConfig) => void;
  onRunBenchmark: () => void;
  onRunParallelVisual: () => void;
}) {
  function updateNumberField(key: keyof SimulationConfig, value: string): void {
    const parsed = Number(value);

    if (Number.isNaN(parsed)) {
      return;
    }

    onConfigChange({
      ...config,
      [key]: parsed,
      mode: "parallel",
      threads: 4,
    });
  }

  const sequencial = benchmarkResult?.sequencial;
  const paralelo = benchmarkResult?.paralelo;
  const speedup = benchmarkResult?.speedup;
  const params = benchmarkResult?.params;
  const currentParallelStep = parallelStep ?? parallelVisual?.steps[0] ?? null;
  const currentFrame = currentParallelStep?.frame ?? 0;
  const totalFrames = parallelVisual?.steps.length ?? 0;
  const visualSegments =
    currentParallelStep?.segments ?? createEmptyParallelSegments();

  const visualThreads = currentParallelStep?.threads ?? [];

  const visualLogs = currentParallelStep?.logs
    ? [...currentParallelStep.logs].reverse()
    : [];
  const parallelMode = parallelVisual?.mode ?? "aguardando execução";
  const visualThreadsCount = parallelVisual?.threads ?? 4;
  const visualSegmentsCount = parallelVisual?.segments ?? 4;
  const visualRequestsCount = parallelVisual?.visualRequests ?? 0;
  return (
    <main className="mx-auto min-h-[calc(100vh-73px)] w-full max-w-7xl px-5 py-6">
      <div className="mb-5 flex flex-col gap-3 rounded-xl border border-zinc-800 bg-zinc-900/80 p-4 md:flex-row md:items-center md:justify-between">
        <div className="flex items-center gap-3">
          <button
            type="button"
            onClick={onBack}
            className="rounded-lg border border-zinc-700 bg-zinc-900 p-2 text-zinc-300 transition hover:border-fuchsia-500/50 hover:text-fuchsia-300"
            title="Voltar para tela inicial"
          >
            <ArrowLeft className="h-5 w-5" />
          </button>

          <div className="rounded-lg border border-fuchsia-500/20 bg-fuchsia-500/10 p-2">
            <Zap className="h-5 w-5 text-fuchsia-300" />
          </div>

          <div>
            <p className="font-semibold text-zinc-100">
              Benchmark Paralelo — Análise de Desempenho
            </p>
            <p className="text-sm text-zinc-400">
              Comparação entre execução sequencial e paralela usando os mesmos
              parâmetros.
            </p>
          </div>
        </div>

        <div className="flex items-center gap-2">
          <div className="rounded-full border border-fuchsia-500/30 bg-fuchsia-500/10 px-3 py-1 text-xs font-semibold text-fuchsia-300">
            {params?.threadCount ?? 4} regiões críticas no backend
          </div>
          {totalFrames > 0 && (
            <div className="rounded-full border border-zinc-700 bg-zinc-950 px-3 py-1 text-xs font-semibold text-zinc-300">
              Frame {currentFrame} / {totalFrames}
            </div>
          )}
          {benchmarkStatus === "success" && (
            <div className="rounded-full border border-emerald-500/30 bg-emerald-500/10 px-3 py-1 text-xs font-semibold text-emerald-300">
              Benchmark finalizado
            </div>
          )}

          {benchmarkStatus === "loading" && (
            <div className="rounded-full border border-amber-500/30 bg-amber-500/10 px-3 py-1 text-xs font-semibold text-amber-300">
              Executando...
            </div>
          )}
        </div>
      </div>

      {benchmarkError && (
        <div className="mb-5 rounded-xl border border-rose-500/30 bg-rose-500/10 px-4 py-3 text-sm text-rose-300">
          Erro ao executar benchmark: {benchmarkError}
        </div>
      )}
      {parallelVisualError && (
        <div className="mb-5 rounded-xl border border-rose-500/30 bg-rose-500/10 px-4 py-3 text-sm text-rose-300">
          Erro ao executar animação paralela: {parallelVisualError}
        </div>
      )}

      <section className="grid gap-5 lg:grid-cols-[360px_1fr]">
        <aside className="space-y-5">
          <div className="rounded-2xl border border-zinc-800 bg-zinc-900/80 p-5">
            <div className="mb-4 flex items-center gap-2">
              <Server className="h-5 w-5 text-fuchsia-300" />
              <h2 className="font-semibold text-zinc-100">
                Configuração do benchmark
              </h2>
            </div>

            <div className="space-y-4">
              <label className="block">
                <span className="mb-1.5 block text-xs font-medium uppercase tracking-wide text-zinc-500">
                  Tamanho da Heap (KB)
                </span>
                <input
                  type="number"
                  min={1}
                  max={256}
                  value={config.heapKb}
                  disabled={benchmarkStatus === "loading"}
                  onChange={(e) => updateNumberField("heapKb", e.target.value)}
                  className="w-full rounded-lg border border-zinc-700 bg-zinc-950 px-3 py-2 text-sm text-zinc-100 outline-none focus:border-fuchsia-500 disabled:cursor-not-allowed disabled:opacity-60"
                />
              </label>

              <div className="grid grid-cols-2 gap-3">
                <label className="block">
                  <span className="mb-1.5 block text-xs font-medium uppercase tracking-wide text-zinc-500">
                    Mín. bytes
                  </span>
                  <input
                    type="number"
                    min={4}
                    value={config.minBytes}
                    disabled={benchmarkStatus === "loading"}
                    onChange={(e) =>
                      updateNumberField("minBytes", e.target.value)
                    }
                    className="w-full rounded-lg border border-zinc-700 bg-zinc-950 px-3 py-2 text-sm text-zinc-100 outline-none focus:border-fuchsia-500 disabled:cursor-not-allowed disabled:opacity-60"
                  />
                </label>

                <label className="block">
                  <span className="mb-1.5 block text-xs font-medium uppercase tracking-wide text-zinc-500">
                    Máx. bytes
                  </span>
                  <input
                    type="number"
                    min={4}
                    value={config.maxBytes}
                    disabled={benchmarkStatus === "loading"}
                    onChange={(e) =>
                      updateNumberField("maxBytes", e.target.value)
                    }
                    className="w-full rounded-lg border border-zinc-700 bg-zinc-950 px-3 py-2 text-sm text-zinc-100 outline-none focus:border-fuchsia-500 disabled:cursor-not-allowed disabled:opacity-60"
                  />
                </label>
              </div>

              <label className="block">
                <span className="mb-1.5 block text-xs font-medium uppercase tracking-wide text-zinc-500">
                  Total de requisições
                </span>
                <input
                  type="number"
                  min={1}
                  max={100000}
                  value={config.totalRequests}
                  disabled={benchmarkStatus === "loading"}
                  onChange={(e) =>
                    updateNumberField("totalRequests", e.target.value)
                  }
                  className="w-full rounded-lg border border-zinc-700 bg-zinc-950 px-3 py-2 text-sm text-zinc-100 outline-none focus:border-fuchsia-500 disabled:cursor-not-allowed disabled:opacity-60"
                />
              </label>

              <button
                type="button"
                onClick={onRunBenchmark}
                disabled={benchmarkStatus === "loading"}
                className="flex w-full items-center justify-center gap-2 rounded-lg bg-fuchsia-500 px-4 py-2.5 text-sm font-semibold text-zinc-950 transition hover:bg-fuchsia-400 disabled:cursor-not-allowed disabled:bg-zinc-700 disabled:text-zinc-400"
              >
                <button
                  type="button"
                  onClick={onRunParallelVisual}
                  disabled={parallelVisualStatus === "loading"}
                  className="flex w-full items-center justify-center gap-2 rounded-lg border border-fuchsia-500/30 bg-fuchsia-500/10 px-4 py-2.5 text-sm font-semibold text-fuchsia-300 transition hover:bg-fuchsia-500/20 disabled:cursor-not-allowed disabled:border-zinc-700 disabled:bg-zinc-900 disabled:text-zinc-500"
                >
                  <PlayCircle className="h-4 w-4" />
                  {parallelVisualStatus === "loading"
                    ? "Buscando passos..."
                    : parallelVisualStatus === "animating"
                      ? "Pausar animação"
                      : parallelVisualStatus === "paused"
                        ? "Continuar animação"
                        : parallelVisualStatus === "success"
                          ? "Reiniciar animação"
                          : "Animar regiões críticas"}
                </button>
                <PlayCircle className="h-4 w-4" />
                {benchmarkStatus === "loading"
                  ? "Executando benchmark..."
                  : "Rodar benchmark"}
              </button>

              <div className="rounded-xl border border-zinc-800 bg-zinc-950 p-4 text-xs leading-relaxed text-zinc-400">
                <p className="mb-2 font-semibold text-zinc-300">
                  Como será medido
                </p>
                <p>
                  O backend executa o modo sequencial e o modo paralelo com os
                  mesmos parâmetros, permitindo comparar latência, throughput e
                  speedup.
                </p>
              </div>
            </div>
          </div>

          <div className="rounded-2xl border border-zinc-800 bg-zinc-900/80 p-5">
            <div className="mb-4 flex items-center gap-2">
              <Cpu className="h-5 w-5 text-fuchsia-300" />
              <h2 className="font-semibold text-zinc-100">
                Arquitetura paralela
              </h2>
            </div>

            <div className="space-y-3 text-sm text-zinc-400">
              <div className="flex items-center justify-between rounded-lg border border-zinc-800 bg-zinc-950 px-3 py-2">
                <span>Threads</span>
                <span className="font-mono text-fuchsia-300">
                  {params?.threadCount ?? 4}
                </span>
              </div>

              <div className="flex items-center justify-between rounded-lg border border-zinc-800 bg-zinc-950 px-3 py-2">
                <span>Regiões críticas</span>
                <span className="font-mono text-fuchsia-300">
                  {params?.threadCount ?? 4}
                </span>
              </div>

              <div className="flex items-center justify-between rounded-lg border border-zinc-800 bg-zinc-950 px-3 py-2">
                <span>Estratégia</span>
                <span className="font-mono text-fuchsia-300">Partitioned</span>
              </div>

              <div className="flex items-center justify-between rounded-lg border border-zinc-800 bg-zinc-950 px-3 py-2">
                <span>Warmup / medições</span>
                <span className="font-mono text-fuchsia-300">
                  {params
                    ? `${params.warmupRounds} / ${params.measureRounds}`
                    : "--"}
                </span>
              </div>
            </div>
          </div>
        </aside>

        <div className="space-y-5">
          <section className="grid gap-4 md:grid-cols-2 xl:grid-cols-4">
            <MetricsCard
              title="Latência média"
              value={formatMs(paralelo?.latenciaMediaMs)}
              description="Tempo médio da versão paralela"
              icon={Clock}
              accent="fuchsia"
            />

            <MetricsCard
              title="Throughput"
              value={`${formatInteger(paralelo?.throughputMedio)} req/s`}
              description="Requisições por segundo"
              icon={Gauge}
              accent="cyan"
            />

            <MetricsCard
              title="Speedup"
              value={formatSpeedup(speedup?.throughput)}
              description={speedupDescription(speedup?.throughput)}
              icon={Zap}
              accent="emerald"
            />

            <MetricsCard
              title="RANDOM acionado"
              value={formatInteger(paralelo?.randomAcionado)}
              description="Chamadas de liberação no paralelo"
              icon={Trash2}
              accent="rose"
            />
          </section>
          {benchmarkResult && (
            <BenchmarkAnalysisCard result={benchmarkResult} />
          )}

          <section className="rounded-2xl border border-zinc-800 bg-zinc-900/80 p-5">
            <div className="mb-5 flex items-center justify-between gap-3">
              <div>
                <h2 className="font-semibold text-zinc-100">
                  Regiões críticas da heap
                </h2>
                <p className="text-sm text-zinc-400">
                  Representação visual da heap particionada em quatro segmentos.
                </p>
              </div>

              <div className="rounded-full border border-zinc-700 bg-zinc-950 px-3 py-1 text-xs text-zinc-400">
                Visual didático
              </div>
            </div>

            <div className="grid gap-3 md:grid-cols-4">
              {visualSegments.map((segment) => {
                const activeThreads = visualThreads.filter(
                  (thread) => thread.segmentId === segment.segmentId,
                );

                return (
                  <div
                    key={segment.segmentId}
                    className={`rounded-xl border p-4 transition ${
                      activeThreads.length > 0
                        ? "border-fuchsia-400/60 bg-fuchsia-500/10 shadow-lg shadow-fuchsia-950/40"
                        : "border-fuchsia-500/20 bg-fuchsia-500/5"
                    }`}
                  >
                    <div className="mb-3 flex items-center justify-between">
                      <span className="text-sm font-semibold text-zinc-100">
                        Segmento {segment.segmentId}
                      </span>
                      <span className="rounded-full border border-fuchsia-500/30 bg-fuchsia-500/10 px-2 py-0.5 text-xs text-fuchsia-300">
                        mutex {segment.mutexId}
                      </span>
                    </div>

                    <div className="mb-3">
                      <div className="mb-1 flex justify-between text-xs text-zinc-500">
                        <span>Uso</span>
                        <span>{segment.usedPercentage.toFixed(1)}%</span>
                      </div>

                      <div className="h-2 overflow-hidden rounded-full bg-zinc-800">
                        <div
                          className="h-full rounded-full bg-fuchsia-500"
                          style={{
                            width: `${Math.min(
                              100,
                              Math.max(0, segment.usedPercentage),
                            )}%`,
                          }}
                        />
                      </div>
                    </div>

                    <div className="space-y-2">
                      {activeThreads.length > 0 ? (
                        activeThreads.map((thread) => (
                          <div
                            key={`${thread.threadId}-${thread.requestId}`}
                            className="rounded-lg border border-zinc-700 bg-zinc-950 px-2 py-2"
                          >
                            <div className="mb-1 flex items-center justify-between">
                              <span className="text-xs font-semibold text-zinc-200">
                                Thread {thread.threadId}
                              </span>

                              <span
                                className={`rounded-full border px-2 py-0.5 text-[10px] font-semibold ${parallelStatusStyle(
                                  thread.status,
                                )}`}
                              >
                                {parallelStatusLabel(thread.status)}
                              </span>
                            </div>

                            <p className="text-xs leading-relaxed text-zinc-500">
                              ID {thread.requestId} · {thread.sizeBytes} bytes
                            </p>
                          </div>
                        ))
                      ) : (
                        <p className="text-xs leading-relaxed text-zinc-500">
                          Nenhuma thread ativa neste frame.
                        </p>
                      )}
                    </div>
                  </div>
                );
              })}
            </div>
          </section>

          <section className="rounded-2xl border border-zinc-800 bg-zinc-900/80 p-5">
            <div className="mb-4">
              <h2 className="font-semibold text-zinc-100">
                Comparação Sequencial x Paralelo
              </h2>
              <p className="text-sm text-zinc-400">
                Os dois modos são executados pelo backend com os mesmos
                parâmetros.
              </p>
            </div>

            <div className="overflow-hidden rounded-xl border border-zinc-800">
              <table className="w-full text-left text-sm">
                <thead className="bg-zinc-950 text-xs uppercase tracking-wide text-zinc-500">
                  <tr>
                    <th className="px-4 py-3">Métrica</th>
                    <th className="px-4 py-3">Sequencial</th>
                    <th className="px-4 py-3">Paralelo</th>
                  </tr>
                </thead>

                <tbody className="divide-y divide-zinc-800 text-zinc-300">
                  <tr>
                    <td className="px-4 py-3">Latência média</td>
                    <td className="px-4 py-3 font-mono">
                      {formatMs(sequencial?.latenciaMediaMs)}
                    </td>
                    <td className="px-4 py-3 font-mono">
                      {formatMs(paralelo?.latenciaMediaMs)}
                    </td>
                  </tr>

                  <tr>
                    <td className="px-4 py-3">Throughput médio</td>
                    <td className="px-4 py-3 font-mono">
                      {formatInteger(sequencial?.throughputMedio)} req/s
                    </td>
                    <td className="px-4 py-3 font-mono">
                      {formatInteger(paralelo?.throughputMedio)} req/s
                    </td>
                  </tr>

                  <tr>
                    <td className="px-4 py-3">Atendidas</td>
                    <td className="px-4 py-3 font-mono">
                      {formatInteger(sequencial?.atendidas)}
                    </td>
                    <td className="px-4 py-3 font-mono">
                      {formatInteger(paralelo?.atendidas)}
                    </td>
                  </tr>

                  <tr>
                    <td className="px-4 py-3">Rejeitadas</td>
                    <td className="px-4 py-3 font-mono">
                      {formatInteger(sequencial?.rejeitadas)}
                    </td>
                    <td className="px-4 py-3 font-mono">
                      {formatInteger(paralelo?.rejeitadas)}
                    </td>
                  </tr>

                  <tr>
                    <td className="px-4 py-3">RANDOM acionado</td>
                    <td className="px-4 py-3 font-mono">
                      {formatInteger(sequencial?.randomAcionado)}
                    </td>
                    <td className="px-4 py-3 font-mono">
                      {formatInteger(paralelo?.randomAcionado)}
                    </td>
                  </tr>

                  <tr>
                    <td className="px-4 py-3">Speedup de throughput</td>
                    <td className="px-4 py-3 font-mono">Referência</td>
                    <td className="px-4 py-3 font-mono">
                      {formatSpeedup(speedup?.throughput)}
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
          </section>
        </div>
      </section>
    </main>
  );
}

function createEmptyParallelSegments() {
  return [0, 1, 2, 3].map((segmentId) => ({
    segmentId,
    mutexId: segmentId,
    usedCells: 0,
    freeCells: 0,
    usedPercentage: 0,
    freePercentage: 100,
  }));
}

function parallelStatusLabel(status: string): string {
  const labels: Record<string, string> = {
    waiting: "aguardando",
    locked: "mutex",
    allocated: "alocou",
    fallback: "fallback",
    released: "finalizou",
    failed: "falhou",
  };

  return labels[status] ?? status;
}

function parallelStatusStyle(status: string): string {
  const styles: Record<string, string> = {
    waiting: "border-amber-500/30 bg-amber-500/10 text-amber-300",
    locked: "border-cyan-500/30 bg-cyan-500/10 text-cyan-300",
    allocated: "border-emerald-500/30 bg-emerald-500/10 text-emerald-300",
    fallback: "border-fuchsia-500/30 bg-fuchsia-500/10 text-fuchsia-300",
    released: "border-zinc-600 bg-zinc-800 text-zinc-300",
    failed: "border-rose-500/30 bg-rose-500/10 text-rose-300",
  };

  return styles[status] ?? styles.waiting;
}

function logTypeColor(type: string): string {
  const styles: Record<string, string> = {
    allocation: "text-emerald-300",
    release: "text-rose-300",
    warning: "text-amber-300",
    system: "text-cyan-300",
    compaction: "text-fuchsia-300",
  };

  return styles[type] ?? "text-zinc-300";
}
function formatMs(value?: number): string {
  if (value === undefined || value === null) {
    return "-- ms";
  }

  return `${value.toFixed(2)} ms`;
}

function formatInteger(value?: number): string {
  if (value === undefined || value === null) {
    return "--";
  }

  return value.toLocaleString("pt-BR");
}

function formatSpeedup(value?: number): string {
  if (value === undefined || value === null) {
    return "-- x";
  }

  return `${value.toFixed(2)}x`;
}

function speedupDescription(value?: number): string {
  if (value === undefined || value === null) {
    return "Aguardando benchmark";
  }

  if (value > 1) {
    return "Paralelo mais rápido";
  }

  if (value === 1) {
    return "Desempenho equivalente";
  }

  return "Sequencial mais rápido neste teste";
}
function BenchmarkAnalysisCard({ result }: { result: BenchmarkResult }) {
  const analysis = buildBenchmarkAnalysis(result);

  const toneStyles = {
    success: "border-emerald-500/30 bg-emerald-500/10 text-emerald-300",
    warning: "border-amber-500/30 bg-amber-500/10 text-amber-300",
    danger: "border-rose-500/30 bg-rose-500/10 text-rose-300",
    neutral: "border-zinc-700 bg-zinc-950 text-zinc-300",
  };

  return (
    <section className="rounded-2xl border border-zinc-800 bg-zinc-900/80 p-5">
      <div className="mb-4 flex flex-col gap-3 md:flex-row md:items-start md:justify-between">
        <div>
          <h2 className="font-semibold text-zinc-100">
            Resultado do benchmark
          </h2>
          <p className="text-sm text-zinc-400">
            Interpretação automática com base nos dados retornados pelo backend.
          </p>
        </div>

        <span
          className={`rounded-full border px-3 py-1 text-xs font-semibold ${toneStyles[analysis.tone]}`}
        >
          {analysis.badge}
        </span>
      </div>

      <div className="rounded-xl border border-zinc-800 bg-zinc-950 p-4">
        <p className="mb-3 text-sm leading-relaxed text-zinc-300">
          {analysis.summary}
        </p>

        <div className="grid gap-3 md:grid-cols-3">
          {analysis.points.map((point) => (
            <div
              key={point.label}
              className="rounded-lg border border-zinc-800 bg-zinc-900 px-3 py-3"
            >
              <p className="text-xs uppercase tracking-wide text-zinc-500">
                {point.label}
              </p>
              <p className="mt-1 text-sm font-semibold text-zinc-100">
                {point.value}
              </p>
              <p className="mt-1 text-xs leading-relaxed text-zinc-500">
                {point.description}
              </p>
            </div>
          ))}
        </div>
      </div>
    </section>
  );
}
function buildBenchmarkAnalysis(result: BenchmarkResult) {
  const seq = result.sequencial;
  const par = result.paralelo;

  const throughputSpeedup = result.speedup.throughput;
  const latencySpeedup = result.speedup.latencia;

  const randomDiff = par.randomAcionado - seq.randomAcionado;
  const rejectedDiff = par.rejeitadas - seq.rejeitadas;

  const throughputGainPercent = (throughputSpeedup - 1) * 100;
  const latencyGainPercent = (latencySpeedup - 1) * 100;

  let tone: "success" | "warning" | "danger" | "neutral" = "neutral";
  let badge = "Desempenho equivalente";
  let summary = "";

  if (throughputSpeedup > 1.05) {
    tone = "success";
    badge = "Paralelo mais rápido";

    summary =
      `Neste cenário, a versão paralela apresentou melhor desempenho. ` +
      `O throughput ficou ${throughputGainPercent.toFixed(1)}% acima do sequencial, ` +
      `indicando que a divisão da heap em regiões críticas conseguiu reduzir parte da contenção entre threads.`;
  } else if (throughputSpeedup < 0.95) {
    tone = "warning";
    badge = "Sequencial mais rápido";

    summary =
      `Neste cenário, a versão sequencial apresentou melhor desempenho. ` +
      `Isso pode ocorrer porque o custo de criar/controlar threads, sincronizar regiões críticas ` +
      `e lidar com contenção superou o ganho obtido pelo paralelismo.`;
  } else {
    tone = "neutral";
    badge = "Resultado próximo";

    summary =
      `Neste cenário, as versões sequencial e paralela tiveram desempenho próximo. ` +
      `Isso indica que o particionamento reduziu parte da contenção, mas o ganho ainda ficou limitado pelos custos de sincronização e controle das threads.`;
  }

  const randomDescription =
    randomDiff > 0
      ? `O paralelo acionou ${randomDiff} liberações RANDOM a mais.`
      : randomDiff < 0
        ? `O paralelo acionou ${Math.abs(randomDiff)} liberações RANDOM a menos.`
        : "Os dois modos acionaram RANDOM na mesma quantidade.";

  const rejectedDescription =
    rejectedDiff > 0
      ? `O paralelo rejeitou ${rejectedDiff} requisições a mais.`
      : rejectedDiff < 0
        ? `O paralelo rejeitou ${Math.abs(rejectedDiff)} requisições a menos.`
        : "Os dois modos rejeitaram a mesma quantidade de requisições.";

  return {
    tone,
    badge,
    summary,
    points: [
      {
        label: "Throughput",
        value: `${formatSpeedup(throughputSpeedup)} (${throughputGainPercent.toFixed(1)}%)`,
        description:
          throughputSpeedup >= 1
            ? "Quanto maior, melhor para o paralelo."
            : "Valor abaixo de 1 indica vantagem do sequencial.",
      },
      {
        label: "Latência",
        value: `${formatSpeedup(latencySpeedup)} (${latencyGainPercent.toFixed(1)}%)`,
        description:
          latencySpeedup >= 1
            ? "O paralelo teve menor tempo médio."
            : "O paralelo teve maior tempo médio neste teste.",
      },
      {
        label: "RANDOM/Rejeições",
        value: `${par.randomAcionado} RANDOM · ${par.rejeitadas} rejeitadas`,
        description: `${randomDescription} ${rejectedDescription}`,
      },
    ],
  };
}
function BackendBadge({ status }: { status: BackendStatus }) {
  const styles = {
    idle: "border-zinc-700 bg-zinc-800 text-zinc-300",
    loading: "border-cyan-500/30 bg-cyan-500/10 text-cyan-300",
    animating: "border-amber-500/30 bg-amber-500/10 text-amber-300",
    success: "border-emerald-500/30 bg-emerald-500/10 text-emerald-300",
    error: "border-rose-500/30 bg-rose-500/10 text-rose-300",
  };

  const labels = {
    idle: "Aguardando execução",
    loading: "Buscando passos no Java...",
    animating: "Animando execução real",
    success: "Execução finalizada",
    error: "Erro no backend",
  };

  return (
    <span
      className={`rounded-full border px-3 py-1 text-xs font-semibold ${styles[status]}`}
    >
      {labels[status]}
    </span>
  );
}

function normalizeConfig(config: SimulationConfig): SimulationConfig {
  const heapKb = clamp(config.heapKb, 1, 256);
  const minBytes = clamp(config.minBytes, 4, 8192);
  const maxBytes = Math.max(minBytes, clamp(config.maxBytes, 4, 8192));
  const totalRequests = clamp(config.totalRequests, 1, 100000);
  const threads = clamp(config.threads, 1, 16);

  return {
    ...config,
    heapKb,
    minBytes,
    maxBytes,
    totalRequests,
    threads,
  };
}

function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value));
}
