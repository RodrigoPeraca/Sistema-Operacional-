export function getAllocationColor(id: number): string {
	const hue = (id * 137.508) % 360;

	return `hsl(${hue}, 82%, 58%)`;
}

export function getAllocationBackground(id: number | null): string {
	if (id === null) {
		return "rgba(31, 41, 55, 0.72)";
	}

	return getAllocationColor(id);
}

export function getAllocationTextColor(id: number | null): string {
	if (id === null) {
		return "#6b7280";
	}

	return "#020617";
}