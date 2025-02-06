package Peer;

import java.io.*;
import java.net.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

public class Peer {
    private static int serverPort = 6885; // File server port (for uploads)
    private static final int TRACKER_PORT = 6881; // Tracker port
    private static int pingPort = 6883; // Ping listener port
    private final Map<String, File> sharedFiles = new ConcurrentHashMap<>();
    private final ExecutorService uploadPool = Executors.newFixedThreadPool(5);
    private final Object downloadLock = new Object();
    private final Map<String, ProgressBar> uploadProgressBars = new ConcurrentHashMap<>();
    private final Map<String, ProgressBar> downloadProgressBars = new ConcurrentHashMap<>();
    private SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    private List<String> logs = new CopyOnWriteArrayList<>();

    private void log(String message) {
        String timestamp = dateFormat.format(new Date());
        logs.add("[" + timestamp + "] " + message);
    }

    public static void main(String[] args) {
        if (args.length >= 1) {
            try {
                pingPort = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                System.out.println("Invalid ping port number provided, using default: " + pingPort);
            }
        }
        try {
            System.out.println("Starting peer on IP: " + InetAddress.getLocalHost().getHostAddress() +
                    ", Ping Port: " + pingPort);
        } catch (UnknownHostException e) {
            e.printStackTrace();
        }
        new Peer().runner();

    }

    private void runner() {
        startPingListener();
        startCLI();

    }

    private void startServer(int port, String fileName) {
        new Thread(() -> {
            try (ServerSocket serverSocket = new ServerSocket(port)) {
                System.out.println("Peer listening for file requests on port " + port + " for " + fileName);
                while (true) {
                    Socket clientSocket = serverSocket.accept();

                    uploadPool.execute(() -> handleUploadRequest(clientSocket, fileName));
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }).start();
    }

    private void startPingListener() {
        new Thread(() -> {
            try (DatagramSocket socket = new DatagramSocket(pingPort)) {
                byte[] buffer = new byte[1024];
                // System.out.println("Ping listener started on port " + pingPort);
                while (true) {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    socket.receive(packet);

                    String message = new String(packet.getData(), 0, packet.getLength()).trim();
                    log(message);
                    if ("Are you still alive?".equals(message)) {

                        InetAddress senderIP = packet.getAddress();
                        int senderPort = packet.getPort();
                        byte[] pongResponse = "yep , I am still alive.".getBytes();
                        DatagramPacket responsePacket = new DatagramPacket(pongResponse, pongResponse.length, senderIP,
                                senderPort);
                        socket.send(responsePacket);
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }).start();
    }

    private void handleUploadRequest(Socket socket, String originalfileName) {
        try (DataInputStream dis = new DataInputStream(socket.getInputStream());
                DataOutputStream dos = new DataOutputStream(socket.getOutputStream())) {

            String fileName = dis.readUTF();
            if (!sharedFiles.containsKey(fileName)) {
                dos.writeUTF("File not found");
                return;
            }
            if (!fileName.equals(originalfileName)) {

                dos.writeUTF("This port is not for this file !");
                return;
            }
            File file = sharedFiles.get(fileName);
            dos.writeLong(file.length());
            dos.flush();
            ProgressBar progressBar = new ProgressBar(file.length());
            uploadProgressBars.put(fileName, progressBar);

            try (FileInputStream fis = new FileInputStream(file)) {
                byte[] buffer = new byte[4096];
                int bytesRead;
                long totalBytesSent = 0;
                while ((bytesRead = fis.read(buffer)) != -1) {
                    dos.write(buffer, 0, bytesRead);
                    dos.flush();
                    totalBytesSent += bytesRead;
                    progressBar.updateProgress(totalBytesSent);
                }
            } catch (Exception e) {
                System.out.println("Error in download Process!");
                System.out.println(e.getMessage());
            }
            uploadProgressBars.remove(fileName);
            System.out.println("File " + fileName + " sent to " + socket.getInetAddress());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // CLI for user commands.
    private void startCLI() {
        Scanner scanner = new Scanner(System.in);
        ExecutorService executor = Executors.newCachedThreadPool();
        try {
            System.out.println("Peer CLI started. Use commands such as:");
            System.out.println("  share <file_path> <tracker_address> <listen_port>");
            System.out.println("  get <file_name>");
            while (true) {
                System.out.print("> ");
                String command = scanner.nextLine();
                String[] parts = command.split(" ");

                switch (parts[0]) {
                    case "share":
                        if (parts.length < 4) {
                            System.out.println("Usage: share <file_path> <tracker_address> <listen_port>");
                            continue;
                        }
                        executor.execute(() -> shareFile(parts[1], parts[2], parts[3]));
                        break;
                    case "get":
                        if (parts.length < 2) {
                            System.out.println("Usage: get <file_name> <tracker_address> <port>");
                            continue;
                        }
                        executor.execute(() -> getFile(parts[1], parts[2], parts[3]));
                        break;
                    case "logs":
                        for(String x: logs)
                            System.out.println(x);    
                    default:
                        System.out.println("Unknown command");
                }
            }
        } catch (Exception e) {
            scanner.close();
            System.out.println("CLI encountered an exception, ending program.");
        }
    }

    private void shareFile(String filePath, String trackerAddress, String listenPortStr) {
        File file = new File(filePath);
        if (!file.exists()) {
            System.out.println("File does not exist: " + filePath);
            return;
        }

        int listenPort;
        try {
            listenPort = Integer.parseInt(listenPortStr);
        } catch (NumberFormatException e) {
            System.out.println("Invalid port number: " + listenPortStr);
            return;
        }

        try (DatagramSocket socket = new DatagramSocket(listenPort)) {
            String message = "share " + file.getName() + " " + trackerAddress + " " + listenPort + " " + serverPort
                    + " " + pingPort;
            byte[] buffer = message.getBytes();

            DatagramPacket packet = new DatagramPacket(buffer, buffer.length, InetAddress.getByName(trackerAddress),
                    TRACKER_PORT);
            socket.send(packet);
            System.out.println("Sent share request for file: " + file.getName());

            byte[] responseBuffer = new byte[1024];
            DatagramPacket responsePacket = new DatagramPacket(responseBuffer, responseBuffer.length);
            socket.setSoTimeout(5000);

            try {
                socket.receive(responsePacket);
                String response = new String(responsePacket.getData(), 0, responsePacket.getLength()).trim();
                System.out.println("Tracker response: " + response);

                if (response.startsWith("File shared successfully")) {
                    startServer(listenPort, file.getName());
                    sharedFiles.put(file.getName(), file);
                    System.out.println("File " + file.getName() + " successfully registered with tracker.");
                } else {
                    System.out.println("Error from tracker: " + response);
                }
            } catch (SocketTimeoutException e) {
                System.out.println("Tracker did not respond in time.");
            }

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void getFile(String fileName, String trackerAddress, String port) {
        synchronized (downloadLock) {
            Random rand = new Random();
            try (DatagramSocket socket = new DatagramSocket()) {
                String message = "get " + fileName;
                byte[] buffer = message.getBytes();

                DatagramPacket packet = new DatagramPacket(buffer, buffer.length, InetAddress.getByName(trackerAddress),
                        TRACKER_PORT);
                socket.send(packet);

                byte[] responseBuffer = new byte[1024];
                DatagramPacket responsePacket = new DatagramPacket(responseBuffer, responseBuffer.length);
                socket.receive(responsePacket);
                String response = new String(responsePacket.getData(), 0, responsePacket.getLength());

                if (response.equals("File not found")) {
                    System.out.println("File not found on network");
                    return;
                }
                String[] rawInfo = response.split(",");
                int randomIndex = rand.nextInt(rawInfo.length);
                String[] peerInfo = rawInfo[randomIndex].split(":");
                String peerIP = peerInfo[0];
                int peerPort = Integer.parseInt(peerInfo[1]);

                downloadFile(fileName, peerIP, peerPort, trackerAddress);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private void downloadFile(String fileName, String peerIP, int peerPort, String trackerAddress) {
        boolean success = false;
        int newpPort = -1;
        try (Socket socket = new Socket(peerIP, peerPort);
                DataInputStream dis = new DataInputStream(socket.getInputStream());
                DataOutputStream dos = new DataOutputStream(socket.getOutputStream());
                FileOutputStream fos = new FileOutputStream(fileName)) {

            dos.writeUTF(fileName);
            dos.flush();
            long fileSize = dis.readLong();
            if (fileSize <= 0) {
                System.out.println("Invalid file size received.");
                return;
            }

            ProgressBar progressBar = new ProgressBar(fileSize);
            downloadProgressBars.put(fileName, progressBar);

            byte[] buffer = new byte[4096];
            int bytesRead;
            long totalBytesReceived = 0;

            while (totalBytesReceived < fileSize
                    && (bytesRead = dis.read(buffer, 0, (int) Math.min(buffer.length, fileSize))) != -1) {
                fos.write(buffer, 0, bytesRead);
                fos.flush();
                totalBytesReceived += bytesRead;
                progressBar.updateProgress(totalBytesReceived);
                fileSize -= bytesRead;
            }

            downloadProgressBars.remove(fileName);
            System.out.println("\nDownloaded: " + fileName);
            sharedFiles.put(fileName, new File(fileName));
            success = (totalBytesReceived == fileSize);
            if (success) {
                System.out.println(
                        "you have downloaded a file , now you have to share it with others...\n select a port number:\n");
                Scanner scanner = new Scanner(System.in);
                boolean inloop = true;
                while (inloop) {
                    try {
                        newpPort = Integer.parseInt(scanner.nextLine());
                        startServer(newpPort, fileName);
                    } catch (Exception e) {
                        System.out.println("port number is not valid , try again...");
                    }
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        sendDownloadAckToTracker(fileName, success, trackerAddress, newpPort);
    }

    private void sendDownloadAckToTracker(String fileName, boolean success, String trackerAddress, int newPort) {
        try (DatagramSocket socket = new DatagramSocket()) {
            String message = "ack " + fileName + " " + newPort + " " + (success ? "success" : "failure") + " "
                    + pingPort;
            byte[] buffer = message.getBytes();

            DatagramPacket packet = new DatagramPacket(buffer, buffer.length, InetAddress.getByName(trackerAddress),
                    TRACKER_PORT);
            socket.send(packet);

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private class ProgressBar {
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
