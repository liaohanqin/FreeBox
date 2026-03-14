package io.knifer.freebox;

public class KeepAliveThread extends Thread {
    public KeepAliveThread() {
        super("Headless-KeepAlive");
        setDaemon(false);
    }

    @Override
    public void run() {
        try {
            while (true) {
                Thread.sleep(1000000);
            }
        } catch (InterruptedException e) {
            // interrupted
        }
    }
}
