package Peer;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

public class Peer {
    private static final int SERVER_PORT = 5000;
    private static final int TRACKER_PORT = 6771;
    private static final String[] TRACKER_IPS = { "127.0.0.1" };
    private static final Map<String, File> sharedFiles = new ConcurrentHashMap<>();
    private static final ExecutorService uploadPool = Executors.newFixedThreadPool(5);
    private static final Object downloadLock = new Object();
    private static final Map<String, ProgressBar> uploadProgressBars = new ConcurrentHashMap<>();
    private static final Map<String, ProgressBar> downloadProgressBars = new ConcurrentHashMap<>();

    public static void main(String[] args) {
        new Thread(Peer::startServer).start(); // Start listening for incoming file requests
        startCLI();
    }

    private static void startServer() {
        try (ServerSocket serverSocket = new ServerSocket(SERVER_PORT)) {
            System.out.println("Peer listening on port " + SERVER_PORT);
            while (true) {
                Socket clientSocket = serverSocket.accept();
                uploadPool.execute(() -> handleUploadRequest(clientSocket));
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void handleUploadRequest(Socket socket) {
        try (DataInputStream dis = new DataInputStream(socket.getInputStream());
                DataOutputStream dos = new DataOutputStream(socket.getOutputStream())) {

            String fileName = dis.readUTF();
            if (!sharedFiles.containsKey(fileName)) {
                dos.writeUTF("File not found");
                return;
            }

            File file = sharedFiles.get(fileName);
            dos.writeLong(file.length());

            ProgressBar progressBar = new ProgressBar(file.length());
            uploadProgressBars.put(fileName, progressBar);

            try (FileInputStream fis = new FileInputStream(file)) {
                byte[] buffer = new byte[4096];
                int bytesRead;
                long totalBytesSent = 0;
                while ((bytesRead = fis.read(buffer)) != -1) {
                    dos.write(buffer, 0, bytesRead);
                    totalBytesSent += bytesRead;
                    progressBar.updateProgress(totalBytesSent);
                }
            }
            uploadProgressBars.remove(fileName);
            System.out.println("File " + fileName + " sent to " + socket.getInetAddress());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void startCLI() {
        Scanner scanner = new Scanner(System.in);
        try {
            while (true) {
                System.out.print("Enter command: ");
                String command = scanner.nextLine();
                String[] parts = command.split(" ");

                if (parts.length < 3) {
                    System.out.println("Invalid command");
                    continue;
                }

                switch (parts[0]) {
                    case "share":
                        if (parts.length < 4) {
                            System.out.println("Usage: share <file_path> <tracker_address> <listen_address>");
                            continue;
                        }
                        shareFile(parts[1], parts[2], parts[3]);
                        break;
                    case "get":
                        if (parts.length < 2) {
                            System.out.println("Usage: get <file_name>");
                            continue;
                        }
                        getFile(parts[1]);
                        break;
                    default:
                        System.out.println("Unknown command");
                }
            }
        } catch (Exception e) {
            scanner.close();
            System.out.println("end of program");
        }
    }

    private static void shareFile(String filePath, String trackerAddress, String listenAddress) {
        File file = new File(filePath);
        if (!file.exists()) {
            System.out.println("File does not exist");
            return;
        }

        sharedFiles.put(file.getName(), file);
        try (DatagramSocket socket = new DatagramSocket()) {
            String message = "share " + file.getName() + " " + listenAddress + " " + SERVER_PORT;
            byte[] buffer = message.getBytes();
            for (String trackerIp : TRACKER_IPS) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length, InetAddress.getByName(trackerIp),
                        TRACKER_PORT);
                socket.send(packet);
            }
            System.out.println("Shared file: " + file.getName());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void getFile(String fileName) {
        synchronized (downloadLock) {
            try (DatagramSocket socket = new DatagramSocket()) {
                String message = "get " + fileName;
                byte[] buffer = message.getBytes();
                for (String trackerIp : TRACKER_IPS) {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length, InetAddress.getByName(trackerIp),
                            TRACKER_PORT);
                    socket.send(packet);
                }

                byte[] responseBuffer = new byte[1024];
                DatagramPacket responsePacket = new DatagramPacket(responseBuffer, responseBuffer.length);
                socket.receive(responsePacket);
                String response = new String(responsePacket.getData(), 0, responsePacket.getLength());

                if (response.equals("File not found")) {
                    System.out.println("File not found on network");
                    return;
                }

                String[] peerInfo = response.split(":");
                String peerIP = peerInfo[0];
                int peerPort = Integer.parseInt(peerInfo[1]);

                downloadFile(fileName, peerIP, peerPort);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private static void downloadFile(String fileName, String peerIP, int peerPort) {
        try (Socket socket = new Socket(peerIP, peerPort);
                DataInputStream dis = new DataInputStream(socket.getInputStream());
                DataOutputStream dos = new DataOutputStream(socket.getOutputStream());
                FileOutputStream fos = new FileOutputStream(fileName)) {

            dos.writeUTF(fileName);
            long fileSize = dis.readLong();

            ProgressBar progressBar = new ProgressBar(fileSize);
            downloadProgressBars.put(fileName, progressBar);

            byte[] buffer = new byte[4096];
            int bytesRead;
            long totalBytesReceived = 0;

            while (fileSize > 0 && (bytesRead = dis.read(buffer, 0, (int) Math.min(buffer.length, fileSize))) != -1) {
                fos.write(buffer, 0, bytesRead);
                totalBytesReceived += bytesRead;
                progressBar.updateProgress(totalBytesReceived);
                fileSize -= bytesRead;
            }

            downloadProgressBars.remove(fileName);
            System.out.println("Downloaded: " + fileName);
            sharedFiles.put(fileName, new File(fileName));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static class ProgressBar {
        private final long totalSize;
        private long currentProgress;

        public ProgressBar(long totalSize) {
            this.totalSize = totalSize;
            this.currentProgress = 0;
        }

        public void updateProgress(long progress) {
            this.currentProgress = progress;
            printProgressBar();
        }

        private void printProgressBar() {
            int progressPercentage = (int) ((currentProgress * 100) / totalSize);
            int barLength = 50;
            int progressLength = (int) ((progressPercentage * barLength) / 100);
            StringBuilder progressBar = new StringBuilder();
            progressBar.append("[");
            for (int i = 0; i < barLength; i++) {
                if (i < progressLength) {
                    progressBar.append("=");
                } else {
                    progressBar.append(" ");
                }
            }
            progressBar.append("] ").append(progressPercentage).append("%");
            System.out.print("\r" + progressBar.toString());
        }
    }
}
