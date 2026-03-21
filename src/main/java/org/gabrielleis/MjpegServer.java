package org.gabrielleis;

import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.BlockingQueue;

class MjpegServer implements Runnable {

    private final BlockingQueue<byte[]> queue;
    private final int PORT = 8080;

    public MjpegServer(BlockingQueue<byte[]> queue) {
        this.queue = queue;
    }

    @Override
    public void run() {
        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            System.out.println("MJPEG server listening on http://localhost:" + PORT + "/video");

            while (!Thread.currentThread().isInterrupted()) {

                Socket clientSocket = serverSocket.accept();
                System.out.println("Client connected: " + clientSocket.getInetAddress());

                try (OutputStream out = clientSocket.getOutputStream()) {

                    String header = "HTTP/1.1 200 OK\r\n" +
                            "Connection: close\r\n" +
                            "Cache-Control: no-cache\r\n" +
                            "Cache-Control: private\r\n" +
                            "Pragma: no-cache\r\n" +
                            "Content-type: multipart/x-mixed-replace; boundary=--BoundaryString\r\n\r\n";
                    out.write(header.getBytes());

                    while (!clientSocket.isClosed()) {
                        byte[] frame = queue.take();

                        String frameHeader = "--BoundaryString\r\n" +
                                "Content-Type: image/jpeg\r\n" +
                                "Content-Length: " + frame.length + "\r\n\r\n";

                        out.write(frameHeader.getBytes());
                        out.write(frame);
                        out.write("\r\n".getBytes());
                        out.flush();
                    }
                } catch (Exception e) {
                    System.out.println("Client disconnected.");
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
