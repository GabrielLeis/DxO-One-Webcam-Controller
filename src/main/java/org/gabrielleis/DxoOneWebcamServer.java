package org.gabrielleis;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

public class DxoOneWebcamServer {
    private static final BlockingQueue<byte[]> frameQueue = new ArrayBlockingQueue<>(5);
    public static void main(String[] args) {
        System.out.println("Starting USB-to-TCP bridge for DxO ONE...");

        Thread usbReaderThread = new Thread(new UsbFrameProducer(frameQueue));
        usbReaderThread.start();

        Thread tcpServerThread = new Thread(new MjpegServer(frameQueue));
        tcpServerThread.start();

    }
}