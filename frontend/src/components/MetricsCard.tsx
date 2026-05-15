import type { LucideIcon } from "lucide-react";
interface MetricsCardProps {
	title: string;
	value: string | number;
	description: string;
	icon: LucideIcon;
	accent: "cyan" | "emerald" | "fuchsia" | "amber" | "rose";
}

const accentMap = {
	cyan: "text-cyan-400 bg-cyan-500/10 border-cyan-500/20",
	emerald: "text-emerald-400 bg-emerald-500/10 border-emerald-500/20",
	fuchsia: "text-fuchsia-400 bg-fuchsia-500/10 border-fuchsia-500/20",
	amber: "text-amber-400 bg-amber-500/10 border-amber-500/20",
	rose: "text-rose-400 bg-rose-500/10 border-rose-500/20",
};

export function MetricsCard({
	title,
	value,
	description,
	icon: Icon,
	accent,
}: MetricsCardProps) {
	return (
		<div className="rounded-xl border border-zinc-800 bg-zinc-900/80 p-4 shadow-lg shadow-black/20">
			<div className="mb-3 flex items-center justify-between">
				<p className="text-sm font-medium text-zinc-400">
					{title}
				</p>

				<div
					className={`rounded-lg border p-2 ${accentMap[accent]}`}
				>
					<Icon className="h-4 w-4" />
				</div>
			</div>

			<p className="text-2xl font-semibold text-zinc-100">
				{value}
			</p>

			<p className="mt-1 text-xs text-zinc-500">
				{description}
			</p>
		</div>
	);
}