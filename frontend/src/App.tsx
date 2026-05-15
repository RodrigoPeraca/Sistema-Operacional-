import { useEffect, useRef, useState } from "react";
import {
	Activity,
	ArchiveX,
	Clock,
	Database,
	Gauge,
	Server,
	Trash2,
} from "lucide-react";

import { Header } from "./components/Header";
import { SidebarConfig } from "./components/SidebarConfig";
import { MetricsCard } from "./components/MetricsCard";
import { MemoryGrid } from "./components/MemoryGrid";
import { SimulationDashboard } from "./components/SimulationDashboard";
import { EventLog } from "./components/EventLog";

import type { BenchmarkItem, SimulationConfig } from "./types/simulation";
import { createSimulation } from "./utils/simulator";
import type { SimulationState } from "./utils/simulator";
import { runBackendSimulationSteps } from "./services/api";

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

type BackendStatus =
	| "idle"
	| "loading"
	| "animating"
	| "success"
	| "error";

export default function App() {
	const [config, setConfig] = useState<SimulationConfig>(defaultConfig);

	const [simulation, setSimulation] = useState<SimulationState>(() =>
		createSimulation(defaultConfig)
	);

	const [benchmark, setBenchmark] =
		useState<BenchmarkItem[]>(defaultBenchmark);

	const [backendStatus, setBackendStatus] =
		useState<BackendStatus>("idle");

	const [backendError, setBackendError] = useState<string | null>(null);

	const intervalRef = useRef<number | null>(null);

	useEffect(() => {
		return () => {
			clearAnimation();
		};
	}, []);

	function clearAnimation(): void {
		if (intervalRef.current !== null) {
			window.clearInterval(intervalRef.current);
			intervalRef.current = null;
		}
	}

	function handleConfigChange(nextConfig: SimulationConfig): void {
		clearAnimation();

		const normalizedConfig = normalizeConfig(nextConfig);

		setConfig(normalizedConfig);
		setSimulation(createSimulation(normalizedConfig));
		setBenchmark(defaultBenchmark);
		setBackendStatus("idle");
		setBackendError(null);
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

			setBenchmark(result.benchmark);
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

	function handleReset(): void {
		clearAnimation();

		setSimulation(createSimulation(config));
		setBenchmark(defaultBenchmark);
		setBackendStatus("idle");
		setBackendError(null);
	}
	const occupiedCells = simulation.heap.filter((cell) => cell.id !== null).length;
	const freeCells = simulation.heap.length - occupiedCells;
	
	const occupiedBytes = occupiedCells * 4;
	const freeBytes = freeCells * 4;

	return (
		<div className="min-h-screen bg-zinc-950 text-zinc-100">
			<Header />

			<div className="flex flex-col lg:flex-row">
				<SidebarConfig
					config={config}
					isRunning={
						backendStatus === "loading" ||
						backendStatus === "animating"
					}
					isFinished={false}
					onConfigChange={handleConfigChange}
					onStartPause={handleStartPause}
					onReset={handleReset}
				/>

				<main className="min-w-0 flex-1 space-y-5 p-5">
					<div className="flex flex-col gap-3 rounded-xl border border-zinc-800 bg-zinc-900/80 p-4 md:flex-row md:items-center md:justify-between">
						<div className="flex items-center gap-3">
							<div className="rounded-lg border border-cyan-500/20 bg-cyan-500/10 p-2">
								<Server className="h-5 w-5 text-cyan-400" />
							</div>

							<div>
								<p className="font-semibold text-zinc-100">
									Integração Backend Java
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
							value={`${simulation.metrics.averageVariableSize.toFixed(
								1
							)} B`}
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
							value={`${simulation.metrics.executionTimeMs.toFixed(
								0
							)} ms`}
							description={
								simulation.config.mode === "parallel"
									? `${simulation.config.threads} threads`
									: "Execução sequencial"
							}
							icon={Clock}
							accent="fuchsia"
						/>
					</section>

					{simulation.metrics.rejectedRequests > 0 && (
						<div className="flex items-center gap-2 rounded-xl border border-orange-500/30 bg-orange-500/10 px-4 py-3 text-sm text-orange-300">
							<ArchiveX className="h-5 w-5" />
							{simulation.metrics.rejectedRequests} requisição(ões)
							não puderam ser alocadas mesmo após liberação.
						</div>
					)}

					<MemoryGrid heap={simulation.heap} />

					<SimulationDashboard metrics={simulation.metrics} />

					<EventLog logs={simulation.logs} />
				</main>
			</div>
		</div>
	);
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