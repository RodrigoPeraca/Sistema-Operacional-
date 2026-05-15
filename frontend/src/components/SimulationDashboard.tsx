import type { Metrics } from "../types/simulation";

interface SimulationDashboardProps {
	metrics: Metrics;
}

export function SimulationDashboard({ metrics }: SimulationDashboardProps) {
	return (
		<section className="grid grid-cols-1 gap-4">
			<div className="rounded-xl border border-zinc-800 bg-zinc-900/80 p-4">
				<h2 className="mb-1 font-semibold text-zinc-100">
					Uso da Heap
				</h2>

				<p className="mb-4 text-xs text-zinc-500">
					Distribuição percentual entre memória ocupada e livre.
				</p>

				<div className="space-y-4">
					<ProgressBar
						label="Memória ocupada"
						value={metrics.usedPercentage}
						color="bg-cyan-500"
					/>

					<ProgressBar
						label="Memória livre"
						value={metrics.freePercentage}
						color="bg-emerald-500"
					/>
				</div>

				<div className="mt-4 rounded-lg border border-zinc-800 bg-zinc-950/70 p-3 text-xs leading-relaxed text-zinc-400">
					<p>
						O comparativo entre execução sequencial e paralela será exibido
						quando a versão concorrente real estiver implementada no backend.
					</p>
				</div>
			</div>
		</section>
	);
}

function ProgressBar({
	label,
	value,
	color,
}: {
	label: string;
	value: number;
	color: string;
}) {
	return (
		<div>
			<div className="mb-1 flex items-center justify-between text-xs">
				<span className="text-zinc-400">{label}</span>

				<span className="font-mono text-zinc-300">
					{value.toFixed(2)}%
				</span>
			</div>

			<div className="h-2 overflow-hidden rounded-full bg-zinc-800">
				<div
					className={`h-full rounded-full ${color}`}
					style={{ width: `${Math.min(100, Math.max(0, value))}%` }}
				/>
			</div>
		</div>
	);
}