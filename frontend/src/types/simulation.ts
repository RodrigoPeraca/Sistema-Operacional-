export type SimulationMode = "sequential" | "parallel";

export type EventType =
	| "allocation"
	| "release"
	| "compaction"
	| "warning"
	| "system";

export type SimulationConfig = {
	heapKb: number;
	minBytes: number;
	maxBytes: number;
	totalRequests: number;
	mode: SimulationMode;
	threads: number;
};

export type MemoryCell = {
	index: number;
	id: number | null;
};

export type Metrics = {
	generatedRequests: number;
	attendedRequests: number;
	rejectedRequests: number;
	removedVariables: number;
	averageVariableSize: number;
	liberationCalls: number;
	compactionCalls: number;
	executionTimeMs: number;
	usedPercentage: number;
	freePercentage: number;
};

export type EventLogItem = {
	id: number;
	timestamp: string;
	type: EventType;
	message: string;
};

export type BenchmarkItem = {
	name: string;
	tempoMs: number;
};