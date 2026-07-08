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
  compactionCalls?: number;
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

export type BenchmarkParams = {
  heapKb: number;
  totalRequests: number;
  minBytes: number;
  maxBytes: number;
  threadCount: number;
  warmupRounds: number;
  measureRounds: number;
};

export type BenchmarkModeResult = {
  latenciaMinMs: number;
  latenciaMediaMs: number;
  latenciaMaxMs: number;
  throughputMin: number;
  throughputMedio: number;
  throughputMax: number;
  atendidas: number;
  rejeitadas: number;
  randomAcionado: number;
};

export type BenchmarkSpeedup = {
  latencia: number;
  throughput: number;
};

export type BenchmarkResult = {
  params: BenchmarkParams;
  sequencial: BenchmarkModeResult;
  paralelo: BenchmarkModeResult;
  speedup: BenchmarkSpeedup;
};
export type ParallelThreadStatus =
  | "waiting"
  | "locked"
  | "allocated"
  | "fallback"
  | "released"
  | "failed";

export type ParallelSegmentState = {
  segmentId: number;
  mutexId: number;
  usedCells: number;
  freeCells: number;
  usedPercentage: number;
  freePercentage: number;
};

export type ParallelThreadEvent = {
  threadId: number;
  segmentId: number;
  mutexId: number;
  requestId: number;
  sizeBytes: number;
  status: ParallelThreadStatus;
  message: string;
};

export type ParallelVisualStep = {
  frame: number;
  segments: ParallelSegmentState[];
  threads: ParallelThreadEvent[];
  logs: EventLogItem[];
};

export type ParallelVisualResponse = {
  steps: ParallelVisualStep[];
  mode: string;
  threads: number;
  segments: number;
  heapKb: number;
  visualRequests: number;
};
