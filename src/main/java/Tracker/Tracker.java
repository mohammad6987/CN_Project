package Tracker;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

public class Tracker {
    private static final int TRACKER_PORT = 6881;
    private static final int TCP_PORT = 6882;
    private static final int BUFFER_SIZE = 1024;
    private static final int TIMEOUT_MS = 300_000;
    private final Map<String, PeerInfo> peers;
    private final List<String> otherTrackers;

    public class PeerInfo {
        private final InetAddress ip;
        private final int port;
        private final long loginTime;
        private final Set<String> sharedFiles;

        public PeerInfo(InetAddress ip, int port, Set<String> sharedFiles) {
            this.ip = ip;
            this.port = port;
            this.loginTime = System.currentTimeMillis();
            this.sharedFiles = sharedFiles;
        }

        public InetAddress getIp() {
            return ip;
        }

        public int getPort() {
            return port;
        }

        public long getLoginTime() {
            return loginTime;
        }

        public Set<String> getSharedFiles() {
            return sharedFiles;
        }

        @Override
        public String toString() {
            return ip.getHostAddress() + ":" + port + " (Files: " + sharedFiles + ")";
        }
    }

    public Tracker() {
        this.peers = new ConcurrentHashMap<>();
        this.otherTrackers = new CopyOnWriteArrayList<>();
    }

    public static void main(String[] args) {
        Tracker tracker = new Tracker();
        tracker.start();
    }

    private void start() {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        executor.execute(this::listenForTrackers);
        executor.execute(this::listenForPeers);
    }

    private void listenForTrackers() {
        try (ServerSocket serverSocket = new ServerSocket(TCP_PORT)) {
            while (true) {
                Socket socket = serverSocket.accept();
                new Thread(() -> handleTrackerConnection(socket)).start();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void handleTrackerConnection(Socket socket) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                PrintWriter writer = new PrintWriter(socket.getOutputStream(), true)) {
            String trackerAddress = reader.readLine();
            if (trackerAddress != null && !otherTrackers.contains(trackerAddress)) {
                otherTrackers.add(trackerAddress);
                writer.println("Tracker added: " + trackerAddress);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void listenForPeers() {
        try (DatagramSocket socket = new DatagramSocket(TRACKER_PORT)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            while (true) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);
                new Thread(() -> handlePeerMessage(packet)).start();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void handlePeerMessage(DatagramPacket packet) {
        try {
            String message = new String(packet.getData(), 0, packet.getLength()).trim();
            InetAddress address = packet.getAddress();
            int port = packet.getPort();
            String response;

            if (message.startsWith("share")) {
                String fileName = message.substring(6).trim();
                peers.computeIfAbsent(address.getHostAddress(),
                        k -> new PeerInfo(address, port, ConcurrentHashMap.newKeySet())).getSharedFiles().add(fileName);
                response = "File shared: " + fileName;
            } else if (message.startsWith("get")) {
                String fileName = message.substring(4).trim();
                response = getOldestPeerWithFile(fileName);
            } else {
                response = "Invalid command";
            }

            byte[] responseData = response.getBytes();
            DatagramPacket responsePacket = new DatagramPacket(responseData, responseData.length, address, port);
            try (DatagramSocket socket = new DatagramSocket()) {
                socket.send(responsePacket);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private String getOldestPeerWithFile(String fileName) {
        return peers.values().stream()
                .filter(peer -> peer.getSharedFiles().contains(fileName))
                .min(Comparator.comparingLong(PeerInfo::getLoginTime))
                .map(peer -> peer.getIp().getHostAddress() + ":" + peer.getPort())
                .orElse("File not found");
    }
}
