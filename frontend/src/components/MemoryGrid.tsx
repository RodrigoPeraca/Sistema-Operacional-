import { Database } from "lucide-react";
import type { MemoryCell } from "../types/simulation";import {
	getAllocationBackground,
	getAllocationTextColor,
} from "../utils/colors";

interface MemoryGridProps {
	heap: MemoryCell[];
}

export function MemoryGrid({ heap }: MemoryGridProps) {
	const occupied = heap.filter((cell) => cell.id !== null).length;
	const free = heap.length - occupied;

	return (
		<section className="rounded-xl border border-zinc-800 bg-zinc-950 shadow-lg shadow-black/20">
			<div className="flex flex-col gap-3 border-b border-zinc-800 px-4 py-3 md:flex-row md:items-center md:justify-between">
				<div className="flex items-center gap-2">
					<Database className="h-5 w-5 text-cyan-400" />
					<div>
						<h2 className="font-semibold text-zinc-100">
							Visualizador da Heap
						</h2>
						<p className="text-xs text-zinc-500">
							{heap.length} células de inteiros · {heap.length * 4} bytes
						</p>
					</div>
				</div>

				<div className="flex gap-3 text-xs text-zinc-400">
					<span>
						Ocupadas:{" "}
						<strong className="text-zinc-200">{occupied}</strong>
					</span>
					<span>
						Livres:{" "}
						<strong className="text-zinc-200">{free}</strong>
					</span>
				</div>
			</div>

			<div className="max-h-[430px] overflow-auto p-4">
				<div
					className="grid gap-[3px]"
					style={{
						gridTemplateColumns:
							"repeat(auto-fill, minmax(26px, 1fr))",
					}}
				>
					{heap.map((cell) => (
						<div
							key={cell.index}
							title={`Índice ${cell.index} | ${
								cell.id === null
									? "Livre"
									: `ID ${cell.id}`
							}`}
							className="flex h-7 min-w-7 items-center justify-center rounded border border-zinc-800 font-mono text-[10px] transition hover:scale-110 hover:border-zinc-200"
							style={{
								background: getAllocationBackground(cell.id),
								color: getAllocationTextColor(cell.id),
							}}
						>
							{cell.id ?? ""}
						</div>
					))}
				</div>
			</div>

			<div className="border-t border-zinc-800 px-4 py-3 text-xs text-zinc-500">
				Cores iguais indicam células pertencentes à mesma variável
				alocada. Espaços vazios evidenciam fragmentação externa.
			</div>
		</section>
	);
}