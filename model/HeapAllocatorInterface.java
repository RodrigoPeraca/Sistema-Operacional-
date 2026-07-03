package model;

/**
 * Interface comum para todas as implementações de alocador de heap.
 * Permite que GerenciadorLiberacao funcione com WorstFitUnsafe, 
 * WorstFitSynchronized e WorstFitPartitioned.
 */
public interface HeapAllocatorInterface {
    int allocate(int sizeInBytes, int requestId);
    void deallocate(int index, int sizeInBytes);
    int getCapacity();
    int getTotalFreeMemory();
    int getTotalOccupiedMemory();
    Heap getHeap();
    int[] snapshot();
}
