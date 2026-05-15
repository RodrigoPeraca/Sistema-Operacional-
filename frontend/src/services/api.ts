import type {
	BenchmarkItem,
	EventLogItem,
	MemoryCell,
	Metrics,
	SimulationConfig,
} from "../types/simulation";

export type BackendSimulationResponse = {
	heap: number[];
	metrics: Metrics;
	benchmark: BenchmarkItem[];
	logs: EventLogItem[];
	mode: string;
	threads: number;
};

export type BackendSimulationStep = {
	heap: number[];
	metrics: Metrics;
	logs: EventLogItem[];
};

export type BackendSimulationStepsResponse = {
	steps: BackendSimulationStep[];
	benchmark: BenchmarkItem[];
	mode: string;
	threads: number;
};

export type FrontendSimulationStep = {
	heap: MemoryCell[];
	metrics: Metrics;
	logs: EventLogItem[];
};

const API_BASE_URL = "http://localhost:8080";

export async function runBackendSimulationSteps(
	config: SimulationConfig
): Promise<{
	steps: FrontendSimulationStep[];
	benchmark: BenchmarkItem[];
}> {
	const params = new URLSearchParams({
		heapKb: String(config.heapKb),
		minBytes: String(config.minBytes),
		maxBytes: String(config.maxBytes),
		totalRequests: String(config.totalRequests),
		mode: config.mode,
		threads: String(config.threads),
	});

	const response = await fetch(
		`${API_BASE_URL}/api/simular-passos?${params.toString()}`
	);

	if (!response.ok) {
		throw new Error(`Erro ao chamar backend: HTTP ${response.status}`);
	}

	const data = (await response.json()) as BackendSimulationStepsResponse;

	return {
		steps: data.steps.map((step) => ({
			heap: convertHeapFromBackend(step.heap),
			metrics: step.metrics,
			logs: [...step.logs].reverse(),
		})),
		benchmark: data.benchmark,
	};
}

export async function runBackendSimulation(
	config: SimulationConfig
): Promise<{
	heap: MemoryCell[];
	metrics: Metrics;
	benchmark: BenchmarkItem[];
	logs: EventLogItem[];
}> {
	const params = new URLSearchParams({
		heapKb: String(config.heapKb),
		minBytes: String(config.minBytes),
		maxBytes: String(config.maxBytes),
		totalRequests: String(config.totalRequests),
		mode: config.mode,
		threads: String(config.threads),
	});

	const response = await fetch(`${API_BASE_URL}/api/simular?${params.toString()}`);

	if (!response.ok) {
		throw new Error(`Erro ao chamar backend: HTTP ${response.status}`);
	}

	const data = (await response.json()) as BackendSimulationResponse;

	return {
		heap: convertHeapFromBackend(data.heap),
		metrics: data.metrics,
		benchmark: data.benchmark,
		logs: [...data.logs].reverse(),
	};
}

function convertHeapFromBackend(heap: number[]): MemoryCell[] {
	return heap.map((value, index) => ({
		index,
		id: value === 0 ? null : value,
	}));
}