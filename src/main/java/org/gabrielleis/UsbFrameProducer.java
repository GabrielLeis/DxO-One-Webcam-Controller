package org.gabrielleis;

import org.usb4java.*;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.concurrent.BlockingQueue;

public class UsbFrameProducer implements Runnable {
    private final BlockingQueue<byte[]> queue;

    // JPEG standard markers
    private static final byte[] JPEG_START = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] JPEG_END = {(byte) 0xFF, (byte) 0xD9};

    // Initial signature that the camera is waiting to "open" the channel
    private static final byte[] METADATA_INIT_RESPONSE_SIGNATURE = new byte[] {
            (byte) 0xA3, (byte) 0xBA, (byte) 0xD1, 0x10, (byte) 0xDC, (byte) 0xBA, (byte) 0xDC, (byte) 0xBA,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
    };

    // Standard header for RPC calls
    private static final byte[] RPC_HEADER = new byte[] {
            (byte) 0xA3, (byte) 0xBA, (byte) 0xD1, 0x10, 0x17, 0x08, 0x00, 0x0C
    };

    private static final byte[] RPC_HEADER_TRAILER = new byte[] {
            0x00, 0x00, 0x03, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
    };

    // Camera vendor Id
    private static final short VENDOR_ID = 0x2b8f;

    private static final byte ENDPOINT_IN = (byte) 0x82;
    private static final byte ENDPOINT_OUT = (byte) 0x01;

    private static final int TIMEOUT_MS = 1000;

    public UsbFrameProducer(BlockingQueue<byte[]> queue) {
        this.queue = queue;
    }

    @Override
    public void run() {

        while (!Thread.currentThread().isInterrupted()) {
            Context context = new Context();

            int result = LibUsb.init(context);
            if (result != LibUsb.SUCCESS) {
                throw new LibUsbException("Failed to initialize libusb.", result);
            }

            DeviceHandle handle = null;

            try {
                handle = findAndOpenDevice(context, VENDOR_ID);
                if (handle == null) {
                    System.out.println("Waiting for DxO ONE camera...");
                    System.out.println("-> Make sure the cable is connected AND the lens cover is OPEN.\n");
                    try {
                        Thread.sleep(2000);
                        continue;
                    } catch (InterruptedException e) {
                        Thread.currentThread().isInterrupted();
                        break;
                    }
                }

                // Claim exclusive control of the device interfaces
                try {
                    setupDeviceInterfaces(handle);
                    startLiveViewMode(handle);
                } catch (RuntimeException e) {
                    System.err.println("Camera detected, but connection was rejected: " + e.getMessage());
                    System.out.println("-> Firmware disconnects USB if the cover is closed. Please open it.\n");

                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    continue;
                }
                System.out.println("Camera initialized. Starting frame capture...");

                // 16KB or 32KB transfers to avoid saturating the bus with tiny requests
                ByteBuffer inBuffer = BufferUtils.allocateByteBuffer(32768);
                IntBuffer transferred = BufferUtils.allocateIntBuffer();

                java.io.ByteArrayOutputStream accumulator = new java.io.ByteArrayOutputStream();

                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        inBuffer.clear();

                        int transferResult = LibUsb.bulkTransfer(handle, ENDPOINT_IN, inBuffer, transferred, TIMEOUT_MS);

                        if (transferResult == LibUsb.SUCCESS && transferred.get(0) > 0) {
                            byte[] payload = new byte[transferred.get(0)];
                            inBuffer.get(payload);

                            accumulator.write(payload);
                            byte[] currentData = accumulator.toByteArray();

                            int startIndex = indexOf(currentData, JPEG_START, 0);

                            if (startIndex != -1) {
                                int endIndex = indexOf(currentData, JPEG_END, startIndex + 3);

                                if (endIndex != -1) {
                                    int frameLength = (endIndex + 2) - startIndex;
                                    byte[] fullFrame = new byte[frameLength];
                                    System.arraycopy(currentData, startIndex, fullFrame, 0, frameLength);

                                    if (queue.remainingCapacity() == 0) {
                                        queue.poll();
                                    }

                                    queue.put(fullFrame);

                                    accumulator.reset();
                                    int remainingLength = currentData.length - (endIndex + 2);
                                    if (remainingLength > 0) {
                                        accumulator.write(currentData, endIndex + 2, remainingLength);
                                    }
                                }
                            }
                            if (accumulator.size() > 5000000) {
                                System.err.println("Corrupted USB stream. Clearing buffer...");
                                accumulator.reset();
                            }

                        } else if (transferResult != LibUsb.ERROR_TIMEOUT) {
                            System.err.println("Critical error in USB read: " + LibUsb.errorName(transferResult));
                            System.out.println("Connection lost. System will try to reconnect...\n");
                            break;
                        }

                    } catch (java.io.IOException e) {
                        System.err.println("Error processing bytes in memory: " + e.getMessage());
                        accumulator.reset();
                    }
                }
            } catch (InterruptedException e) {
                System.out.println("Producer thread interrupted. Shutting down...");
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                System.err.println("Unexpected error: " + e.getMessage());
            } finally {
                if (handle != null) {
                    LibUsb.releaseInterface(handle, 0);
                    LibUsb.releaseInterface(handle, 1);
                    LibUsb.close(handle);
                }
                LibUsb.exit(context);
            }

            if(!Thread.currentThread().isInterrupted()){
                try{
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    Thread.currentThread().isInterrupted();
                }
            }
        }
    }

    private DeviceHandle findAndOpenDevice(Context context, short vendorId) {
        DeviceList list = new DeviceList();
        int result = LibUsb.getDeviceList(context, list);
        if (result < 0) throw new LibUsbException("Failed to retrieve the device list.", result);

        try {
            for (Device device : list) {
                DeviceDescriptor descriptor = new DeviceDescriptor();
                LibUsb.getDeviceDescriptor(device, descriptor);

                if (descriptor.idVendor() == vendorId) {
                    DeviceHandle handle = new DeviceHandle();
                    result = LibUsb.open(device, handle);
                    if (result == LibUsb.SUCCESS) {
                        return handle;
                    }
                }
            }
        } finally {
            LibUsb.freeDeviceList(list, true);
        }
        return null;
    }

    private void setupDeviceInterfaces(DeviceHandle handle) {
        LibUsb.setConfiguration(handle, 1);

        if (LibUsb.kernelDriverActive(handle, 0) == 1) LibUsb.detachKernelDriver(handle, 0);
        if (LibUsb.kernelDriverActive(handle, 1) == 1) LibUsb.detachKernelDriver(handle, 1);

        int res1 = LibUsb.claimInterface(handle, 0);
        int res2 = LibUsb.claimInterface(handle, 1);
        if (res1 != LibUsb.SUCCESS || res2 != LibUsb.SUCCESS) {
            throw new RuntimeException("Failed to claim the USB interfaces.");
        }

        int altRes = LibUsb.setInterfaceAltSetting(handle, 1, 1);
        if (altRes != LibUsb.SUCCESS) {
            throw new RuntimeException("Failed to set the alternate interface.");
        }
    }

    private void startLiveViewMode(DeviceHandle handle) {
        System.out.println("Sending start signal and Live View command...");

        sendBytesToUsb(handle, METADATA_INIT_RESPONSE_SIGNATURE);

        /*
           The '\0' character at the end is mandatory. In C/C++, strings are null-terminated.
           If Java does not send it, the camera's RTOS will continue reading garbage memory until it crashes.
        */
        String jsonCommand = "{\"jsonrpc\":\"2.0\",\"id\":0,\"method\":\"dxo_camera_mode_switch\",\"params\":{\"param\":\"view\"}}\0";
        byte[] payloadBytes = jsonCommand.getBytes(java.nio.charset.StandardCharsets.UTF_8);

        int payloadLength = payloadBytes.length;
        // Least significant byte
        byte lengthLsb = (byte) (payloadLength & 0xFF);
        // Most significant byte
        byte lengthMsb = (byte) ((payloadLength >> 8) & 0xFF);

        int totalSize = RPC_HEADER.length + 2 + RPC_HEADER_TRAILER.length + payloadBytes.length;
        ByteBuffer directBuffer = BufferUtils.allocateByteBuffer(totalSize);

        directBuffer.put(RPC_HEADER);
        directBuffer.put(lengthLsb);
        directBuffer.put(lengthMsb);
        directBuffer.put(RPC_HEADER_TRAILER);
        directBuffer.put(payloadBytes);

        sendBufferToUsb(handle, directBuffer);
    }

    // Auxiliary method for sending byte arrays
    private void sendBytesToUsb(DeviceHandle handle, byte[] data) {
        ByteBuffer directBuffer = BufferUtils.allocateByteBuffer(data.length);
        directBuffer.put(data);
        sendBufferToUsb(handle, directBuffer);
    }

    // Auxiliary method that interacts with the native layer
    private void sendBufferToUsb(DeviceHandle handle, ByteBuffer buffer) {
        IntBuffer transferred = BufferUtils.allocateIntBuffer();

        buffer.flip();

        int result = LibUsb.bulkTransfer(handle, ENDPOINT_OUT, buffer, transferred, TIMEOUT_MS);

        if (result != LibUsb.SUCCESS) {
            System.err.println("Error sending data to the OUT endpoint: " + LibUsb.errorName(result));
        } else {
            System.out.println("Sent " + transferred.get(0) + " bytes to the camera.");
        }
    }

    // Auxiliary classic C sub-array search method, adapted to Java
    private int indexOf(byte[] data, byte[] pattern, int startOffset) {
        if (data == null || pattern == null || pattern.length == 0 || startOffset < 0) return -1;

        for (int i = startOffset; i <= data.length - pattern.length; i++) {
            boolean found = true;
            for (int j = 0; j < pattern.length; j++) {
                if (data[i + j] != pattern[j]) {
                    found = false;
                    break;
                }
            }
            if (found) return i;
        }
        return -1;
    }

    private void printUsbArchitecture(Device device) {
        ConfigDescriptor descriptor = new ConfigDescriptor();
        int result = LibUsb.getActiveConfigDescriptor(device, descriptor);
        if (result != LibUsb.SUCCESS) {
            System.err.println("Failed to read the configuration: " + LibUsb.errorName(result));
            return;
        }

        System.out.println("\n=== USB TOPOLOGY OF THE DxO ONE ===");

        for (Interface iface : descriptor.iface()) {
            for (InterfaceDescriptor alt : iface.altsetting()) {
                System.out.println("Interface " + alt.bInterfaceNumber() + " (Alt-Setting " + alt.bAlternateSetting() + ")");

                for (EndpointDescriptor ep : alt.endpoint()) {
                    String address = (ep.bEndpointAddress() & LibUsb.ENDPOINT_IN) != 0 ? "IN (read)" : "OUT (write)";

                    System.out.printf("  -> Endpoint %s : Hex Address [0x%02X]\n",
                            address,
                            ep.bEndpointAddress());
                }
            }
        }
        System.out.println("===================================\n");

        LibUsb.freeConfigDescriptor(descriptor);
    }
}