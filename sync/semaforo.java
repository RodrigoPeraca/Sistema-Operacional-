package sync;

public class semaforo {
    private int count;

    public semaforo(int permits) {
        if (permits < 0) throw new IllegalArgumentException("permits < 0");
        this.count = permits;
    }

    public synchronized void acquire() throws InterruptedException {
        while (count == 0) wait();   // P: bloqueia se sem permissão
        count--;
    }

    public synchronized void release() {
        count++;
        notify();                     // V: acorda um processo bloqueado
    }

    public static final class BinarySemaphore extends Semaphore {
        public BinarySemaphore() { super(1); }
    }
}
