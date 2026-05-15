import { Cpu, Database, Activity } from "lucide-react";

export function Header() {
	return (
		<header className="border-b border-zinc-800 bg-zinc-950/95 px-6 py-4">
			<div className="flex flex-col gap-4 lg:flex-row lg:items-center lg:justify-between">
				<div>
					<div className="flex items-center gap-3">
						<div className="rounded-xl border border-cyan-500/30 bg-cyan-500/10 p-2">
							<Cpu className="h-6 w-6 text-cyan-400" />
						</div>

						<div>
							<h1 className="text-xl font-semibold tracking-tight text-zinc-100">
								Heap Simulator
							</h1>
							<p className="text-sm text-zinc-400">
								Simulador visual de gerenciamento dinâmico de
								memória
							</p>
						</div>
					</div>
				</div>

				<div className="flex flex-wrap gap-3 text-xs text-zinc-400">
					<div className="flex items-center gap-2 rounded-lg border border-zinc-800 bg-zinc-900 px-3 py-2">
						<Database className="h-4 w-4 text-emerald-400" />
						Heap como vetor de inteiros
					</div>

					<div className="flex items-center gap-2 rounded-lg border border-zinc-800 bg-zinc-900 px-3 py-2">
						<Activity className="h-4 w-4 text-fuchsia-400" />
						Worst Fit + Random 30%
					</div>
				</div>
			</div>
		</header>
	);
}