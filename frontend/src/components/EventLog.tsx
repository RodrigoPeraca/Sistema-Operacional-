import { Terminal } from "lucide-react";
import type { EventLogItem } from "../types/simulation";
interface EventLogProps {
	logs: EventLogItem[];
}

const typeColor = {
	allocation: "text-cyan-400",
	release: "text-rose-400",
	compaction: "text-amber-400",
	warning: "text-orange-400",
	system: "text-emerald-400",
};

export function EventLog({ logs }: EventLogProps) {
	return (
		<section className="rounded-xl border border-zinc-800 bg-black shadow-lg shadow-black/30">
			<div className="flex items-center gap-2 border-b border-zinc-800 px-4 py-3">
				<Terminal className="h-5 w-5 text-emerald-400" />
				<h2 className="font-semibold text-zinc-100">
					Log de Eventos
				</h2>
			</div>

			<div className="h-64 overflow-auto p-4 font-mono text-xs">
				{logs.length === 0 ? (
					<p className="text-zinc-600">
						Aguardando eventos da simulação...
					</p>
				) : (
					<div className="space-y-2">
						{logs.map((log) => (
							<div key={log.id} className="flex gap-3">
								<span className="text-zinc-600">
									[{log.timestamp}]
								</span>

								<span
									className={`min-w-24 uppercase ${
										typeColor[log.type]
									}`}
								>
									{log.type}
								</span>

								<span className="text-zinc-300">
									{log.message}
								</span>
							</div>
						))}
					</div>
				)}
			</div>
		</section>
	);
}