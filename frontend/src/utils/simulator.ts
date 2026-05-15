import type {
	EventLogItem,
	MemoryCell,
	Metrics,
	SimulationConfig,
} from "../types/simulation";

export interface SimulationState {
	config: SimulationConfig;
	heap: MemoryCell[];
	metrics: Metrics;
	logs: EventLogItem[];
	nextRequestId: number;
	isRunning: boolean;
	isFinished: boolean;
	startedAt: number | null;
}

interface AllocationResult {
	success: boolean;
	startIndex: number;
	sizeInCells: number;
}

interface LiberationResult {
	removedVariables: number;
	releasedCells: number;
	removedIds: number[];
}

export function createInitialHeap(heapKb: number): MemoryCell[] {
	const totalBytes = heapKb * 1024;
	const totalCells = Math.floor(totalBytes / 4);

	return Array.from({ length: totalCells }, (_, index) => ({
		index,
		id: null,
	}));
}

export function createInitialMetrics(): Metrics {
	return {
		generatedRequests: 0,
		attendedRequests: 0,
		rejectedRequests: 0,
		removedVariables: 0,
		averageVariableSize: 0,
		liberationCalls: 0,
		compactionCalls: 0,
		executionTimeMs: 0,
		usedPercentage: 0,
		freePercentage: 100,
	};
}

export function createSimulation(config: SimulationConfig): SimulationState {
	return {
		config,
		heap: createInitialHeap(config.heapKb),
		metrics: createInitialMetrics(),
		logs: [
			createLog(
				"system",
				`Simulação criada: heap=${config.heapKb}KB, modo=${config.mode}, faixa=${config.minBytes}-${config.maxBytes} bytes.`
			),
		],
		nextRequestId: 1,
		isRunning: false,
		isFinished: false,
		startedAt: null,
	};
}

export function processBatch(state: SimulationState): SimulationState {
	if (state.isFinished) {
		return state;
	}

	let nextState = { ...state };

	const batchSize =
		state.config.mode === "parallel"
			? Math.max(1, state.config.threads)
			: 1;

	for (let i = 0; i < batchSize; i++) {
		if (
			nextState.metrics.generatedRequests >=
			nextState.config.totalRequests
		) {
			nextState = {
				...nextState,
				isRunning: false,
				isFinished: true,
				logs: [
					createLog(
						"system",
						"Simulação finalizada. Todas as requisições configuradas foram processadas."
					),
					...nextState.logs,
				],
			};
			break;
		}

		nextState = processOneRequest(nextState);
	}

	return refreshPercentages(nextState);
}

function processOneRequest(state: SimulationState): SimulationState {
	const requestId = state.nextRequestId;
	const sizeBytes = randomInt(state.config.minBytes, state.config.maxBytes);

	let heap = [...state.heap];
	let metrics = { ...state.metrics };
	let logs = [...state.logs];

	metrics.generatedRequests++;

	let allocation = allocateWorstFit(heap, requestId, sizeBytes);

	if (!allocation.success) {
		const liberation = randomLiberation30(heap);

		metrics.liberationCalls++;
		metrics.removedVariables += liberation.removedVariables;

		logs.unshift(
			createLog(
				"release",
				`Sem espaço contíguo para ID=${requestId}. Liberação RANDOM acionada: ${liberation.removedVariables} variável(is) removida(s), ${liberation.releasedCells * 4} bytes liberados.`
			)
		);

		allocation = allocateWorstFit(heap, requestId, sizeBytes);
	}

	if (!allocation.success && hasEnoughTotalFreeCells(heap, sizeBytes)) {
		heap = compactHeap(heap);
		metrics.compactionCalls++;

		logs.unshift(
			createLog(
				"compaction",
				`Compactação executada para reduzir fragmentação externa antes de realocar ID=${requestId}.`
			)
		);

		allocation = allocateWorstFit(heap, requestId, sizeBytes);
	}

	if (allocation.success) {
		metrics.attendedRequests++;

		metrics.averageVariableSize =
			(metrics.averageVariableSize *
				(metrics.attendedRequests - 1) +
				sizeBytes) /
			metrics.attendedRequests;

		logs.unshift(
			createLog(
				"allocation",
				`ID=${requestId} alocado no índice ${allocation.startIndex}, tamanho=${sizeBytes} bytes (${allocation.sizeInCells} célula(s)).`
			)
		);
	} else {
		metrics.rejectedRequests++;

		logs.unshift(
			createLog(
				"warning",
				`ID=${requestId} rejeitado. Mesmo após liberação e compactação, não houve espaço suficiente.`
			)
		);
	}

	const executionTimeMs =
		state.startedAt === null ? 0 : performance.now() - state.startedAt;

	metrics.executionTimeMs = executionTimeMs;

	return {
		...state,
		heap,
		metrics,
		logs: logs.slice(0, 160),
		nextRequestId: state.nextRequestId + 1,
	};
}

function allocateWorstFit(
	heap: MemoryCell[],
	requestId: number,
	sizeBytes: number
): AllocationResult {
	const sizeInCells = Math.ceil(sizeBytes / 4);

	let worstStart = -1;
	let worstSize = -1;

	let i = 0;

	while (i < heap.length) {
		if (heap[i].id !== null) {
			i++;
			continue;
		}

		const start = i;
		let freeSize = 0;

		while (i < heap.length && heap[i].id === null) {
			freeSize++;
			i++;
		}

		if (freeSize >= sizeInCells && freeSize > worstSize) {
			worstStart = start;
			worstSize = freeSize;
		}
	}

	if (worstStart === -1) {
		return {
			success: false,
			startIndex: -1,
			sizeInCells,
		};
	}

	for (let j = 0; j < sizeInCells; j++) {
		heap[worstStart + j] = {
			...heap[worstStart + j],
			id: requestId,
		};
	}

	return {
		success: true,
		startIndex: worstStart,
		sizeInCells,
	};
}

function randomLiberation30(heap: MemoryCell[]): LiberationResult {
	const targetCells = Math.ceil(heap.length * 0.3);
	const ids = Array.from(
		new Set(heap.filter((cell) => cell.id !== null).map((cell) => cell.id))
	) as number[];

	let releasedCells = 0;
	let removedVariables = 0;
	const removedIds: number[] = [];

	while (releasedCells < targetCells && ids.length > 0) {
		const randomIndex = randomInt(0, ids.length - 1);
		const selectedId = ids.splice(randomIndex, 1)[0];

		let cellsRemovedForId = 0;

		for (let i = 0; i < heap.length; i++) {
			if (heap[i].id === selectedId) {
				heap[i] = {
					...heap[i],
					id: null,
				};

				cellsRemovedForId++;
			}
		}

		if (cellsRemovedForId > 0) {
			releasedCells += cellsRemovedForId;
			removedVariables++;
			removedIds.push(selectedId);
		}
	}

	return {
		removedVariables,
		releasedCells,
		removedIds,
	};
}

function compactHeap(heap: MemoryCell[]): MemoryCell[] {
	const occupied = heap.filter((cell) => cell.id !== null);
	const freeCount = heap.length - occupied.length;

	const compacted: MemoryCell[] = [];

	for (const cell of occupied) {
		compacted.push({
			index: compacted.length,
			id: cell.id,
		});
	}

	for (let i = 0; i < freeCount; i++) {
		compacted.push({
			index: compacted.length,
			id: null,
		});
	}

	return compacted;
}

function hasEnoughTotalFreeCells(heap: MemoryCell[], sizeBytes: number): boolean {
	const needed = Math.ceil(sizeBytes / 4);
	const freeCells = heap.filter((cell) => cell.id === null).length;

	return freeCells >= needed;
}

function refreshPercentages(state: SimulationState): SimulationState {
	const usedCells = state.heap.filter((cell) => cell.id !== null).length;
	const usedPercentage = (usedCells / state.heap.length) * 100;
	const freePercentage = 100 - usedPercentage;

	return {
		...state,
		metrics: {
			...state.metrics,
			usedPercentage,
			freePercentage,
		},
	};
}

function createLog(
	type: EventLogItem["type"],
	message: string
): EventLogItem {
	return {
		id: Date.now() + Math.random(),
		timestamp: new Date().toLocaleTimeString("pt-BR"),
		type,
		message,
	};
}

function randomInt(min: number, max: number): number {
	return Math.floor(Math.random() * (max - min + 1)) + min;
}