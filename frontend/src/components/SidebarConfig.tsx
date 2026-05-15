import type { ReactNode } from "react";
import {
	Play,
	Pause,
	RotateCcw,
	Settings,
	Zap,
	Workflow,
} from "lucide-react";
import type { SimulationConfig, SimulationMode } from "../types/simulation";

interface SidebarConfigProps {
	config: SimulationConfig;
	isRunning: boolean;
	isFinished: boolean;
	onConfigChange: (config: SimulationConfig) => void;
	onStartPause: () => void;
	onReset: () => void;
}

export function SidebarConfig({
	config,
	isRunning,
	isFinished,
	onConfigChange,
	onStartPause,
	onReset,
}: SidebarConfigProps) {
	function updateNumberField(
		key: keyof SimulationConfig,
		value: string
	): void {
		const parsed = Number(value);

		if (Number.isNaN(parsed)) {
			return;
		}

		onConfigChange({
			...config,
			[key]: parsed,
		});
	}

	function updateMode(mode: SimulationMode): void {
		onConfigChange({
			...config,
			mode,
		});
	}

	return (
		<aside className="w-full border-b border-zinc-800 bg-zinc-950 p-5 lg:h-[calc(100vh-73px)] lg:w-80 lg:border-b-0 lg:border-r">
			<div className="mb-5 flex items-center gap-2">
				<Settings className="h-5 w-5 text-cyan-400" />
				<h2 className="font-semibold text-zinc-100">
					Configuração
				</h2>
			</div>

			<div className="space-y-4">
				<Field label="Tamanho da Heap (KB)">
					<input
						type="number"
						min={1}
						max={256}
						value={config.heapKb}
						disabled={isRunning}
						onChange={(e) =>
							updateNumberField("heapKb", e.target.value)
						}
						className="w-full rounded-lg border border-zinc-700 bg-zinc-900 px-3 py-2 text-sm text-zinc-100 outline-none focus:border-cyan-500"
					/>
				</Field>

				<div className="grid grid-cols-2 gap-3">
					<Field label="Mín. bytes">
						<input
							type="number"
							min={4}
							value={config.minBytes}
							disabled={isRunning}
							onChange={(e) =>
								updateNumberField(
									"minBytes",
									e.target.value
								)
							}
							className="w-full rounded-lg border border-zinc-700 bg-zinc-900 px-3 py-2 text-sm text-zinc-100 outline-none focus:border-cyan-500"
						/>
					</Field>

					<Field label="Máx. bytes">
						<input
							type="number"
							min={4}
							value={config.maxBytes}
							disabled={isRunning}
							onChange={(e) =>
								updateNumberField(
									"maxBytes",
									e.target.value
								)
							}
							className="w-full rounded-lg border border-zinc-700 bg-zinc-900 px-3 py-2 text-sm text-zinc-100 outline-none focus:border-cyan-500"
						/>
					</Field>
				</div>

				<Field label="Total de requisições">
					<input
						type="number"
						min={1}
						max={100000}
						value={config.totalRequests}
						disabled={isRunning}
						onChange={(e) =>
							updateNumberField(
								"totalRequests",
								e.target.value
							)
						}
						className="w-full rounded-lg border border-zinc-700 bg-zinc-900 px-3 py-2 text-sm text-zinc-100 outline-none focus:border-cyan-500"
					/>
				</Field>

				<Field label="Modo de execução">
					<div className="grid grid-cols-2 gap-2">
						<button
							type="button"
							disabled={isRunning}
							onClick={() => updateMode("sequential")}
							className={`flex items-center justify-center gap-2 rounded-lg border px-3 py-2 text-sm transition ${
								config.mode === "sequential"
									? "border-cyan-500 bg-cyan-500/10 text-cyan-300"
									: "border-zinc-700 bg-zinc-900 text-zinc-400 hover:border-zinc-500"
							}`}
						>
							<Workflow className="h-4 w-4" />
							Seq.
						</button>

						<button
							type="button"
							disabled={isRunning}
							onClick={() => updateMode("parallel")}
							className={`flex items-center justify-center gap-2 rounded-lg border px-3 py-2 text-sm transition ${
								config.mode === "parallel"
									? "border-fuchsia-500 bg-fuchsia-500/10 text-fuchsia-300"
									: "border-zinc-700 bg-zinc-900 text-zinc-400 hover:border-zinc-500"
							}`}
						>
							<Zap className="h-4 w-4" />
							Par.
						</button>
					</div>
				</Field>

				{config.mode === "parallel" && (
					<Field label="Threads simuladas">
						<input
							type="number"
							min={2}
							max={16}
							value={config.threads}
							disabled={isRunning}
							onChange={(e) =>
								updateNumberField(
									"threads",
									e.target.value
								)
							}
							className="w-full rounded-lg border border-zinc-700 bg-zinc-900 px-3 py-2 text-sm text-zinc-100 outline-none focus:border-fuchsia-500"
						/>
					</Field>
				)}

				<div className="grid grid-cols-2 gap-3 pt-3">
					<button
						type="button"
						onClick={onStartPause}
						disabled={isFinished}
						className="flex items-center justify-center gap-2 rounded-lg bg-cyan-500 px-4 py-2.5 text-sm font-semibold text-zinc-950 transition hover:bg-cyan-400 disabled:cursor-not-allowed disabled:bg-zinc-700 disabled:text-zinc-400"
					>
						{isRunning ? (
							<>
								<Pause className="h-4 w-4" />
								Pausar
							</>
						) : (
							<>
								<Play className="h-4 w-4" />
								Iniciar
							</>
						)}
					</button>

					<button
						type="button"
						onClick={onReset}
						className="flex items-center justify-center gap-2 rounded-lg border border-zinc-700 bg-zinc-900 px-4 py-2.5 text-sm font-semibold text-zinc-300 transition hover:border-zinc-500 hover:bg-zinc-800"
					>
						<RotateCcw className="h-4 w-4" />
						Reset
					</button>
				</div>

				<div className="rounded-xl border border-zinc-800 bg-zinc-900/70 p-4 text-xs leading-relaxed text-zinc-400">
					<p className="mb-2 font-semibold text-zinc-300">
						Nota técnica
					</p>
					<p>
						Cada célula visual representa uma posição do vetor de
						inteiros da heap. Como cada inteiro equivale a 4 bytes,
						uma variável de 16 bytes ocupa 4 células.
					</p>
				</div>
			</div>
		</aside>
	);
}

function Field({
	label,
	children,
}: {
	label: string;
	children: ReactNode;
}) {
	return (
		<label className="block">
			<span className="mb-1.5 block text-xs font-medium uppercase tracking-wide text-zinc-500">
				{label}
			</span>
			{children}
		</label>
	);
}