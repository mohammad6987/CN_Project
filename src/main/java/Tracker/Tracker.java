package Tracker;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.*;

public class Tracker {
    private static final int UDP_PEER_TO_TRACKER = 6881;
    private static final int TCP_PORT_FOR_OTHER_TRACKERS = 6882;
    private static final int TCP_PORT_FILE_REQUESTS_FROM_OTHER_TRAKCERS = 6883;
    private static final int BUFFER_SIZE = 1024;
    private static final int TIMEOUT_MS = 300_000;
    private static final int PEER_CHECK_INTERVAL_MS = 60000;
    private Map<String, PeerInfo> peers = new ConcurrentHashMap<>();
    private List<String> otherTrackers;
    private ReadWriteLock peerLock = new ReentrantReadWriteLock();
    private Lock trackerLock = new ReentrantLock();

    public class PeerInfo {
        private final InetAddress ip;
        private final int port;
        private volatile long lastSeen;
        private final Set<String> sharedFiles;

        public PeerInfo(InetAddress ip, int port) {
            this.ip = ip;
            this.port = port;
            this.lastSeen = System.currentTimeMillis();
            this.sharedFiles = ConcurrentHashMap.newKeySet();
        }

        public void updateLastSeen() {
            this.lastSeen = System.currentTimeMillis();
        }

        public InetAddress getIp() {
            return ip;
        }

        public int getPort() {
            return port;
        }

        public long getLastSeen() {
            return lastSeen;
        }

        public Set<String> getSharedFiles() {
            return sharedFiles;
        }
    }

    public Tracker() {
        this.otherTrackers = new CopyOnWriteArrayList<>();
    }

    public static void main(String[] args) {
        Tracker tracker = new Tracker();
        tracker.start();
    }

    private void start() {
        System.out.println("tracker stated!");
        ExecutorService executor = Executors.newFixedThreadPool(3);
        executor.execute(this::listenForTrackers);
        executor.execute(this::listenForPeers);
        executor.execute(this::checkPeerHealth);
        System.out.println("all services are functional!");
    }

    private void listenForTrackers() {
        try (ServerSocket serverSocket = new ServerSocket(TCP_PORT_FOR_OTHER_TRACKERS)) {
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
            if (trackerAddress != null) {
                trackerLock.lock();
                try {
                    if (!otherTrackers.contains(trackerAddress)) {
                        otherTrackers.add(trackerAddress);
                        writer.println("Tracker added: " + trackerAddress);
                    }
                } finally {
                    trackerLock.unlock();
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void listenForPeers() {
        try (DatagramSocket socket = new DatagramSocket(UDP_PEER_TO_TRACKER)) {
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

            peerLock.writeLock().lock();
            try {
                if (message.startsWith("share") && message.length() > 6) {
                    String fileName = message.substring(6).trim();
                    peers.computeIfAbsent(address.toString(),
                            k -> new PeerInfo(address, port)).getSharedFiles()
                            .add(fileName);
                    response = "File shared: " + fileName;
                } else if (message.startsWith("get") && message.length() > 4) {
                    String fileName = message.substring(4).trim();
                    response = getPeersWithFile(fileName);
                } else {
                    response = "Invalid command";
                }
            } finally {
                peerLock.writeLock().unlock();
            }
            // Nisixixixi
            byte[] responseData = response.getBytes();
            DatagramPacket responsePacket = new DatagramPacket(responseData, responseData.length, address, port);
            try (DatagramSocket socket = new DatagramSocket()) {
                socket.send(responsePacket);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private String getPeersWithFile(String fileName) {
        peerLock.readLock().lock();
        try {
            List<String> peerList = new ArrayList<>();
            for (PeerInfo peer : peers.values()) {
                if (peer.getSharedFiles().contains(fileName)) {
                    peerList.add(peer.getIp().getHostAddress() + ":" + peer.getPort());
                }
            }
            if (!peerList.isEmpty()) {
                return String.join(", ", peerList);
            }
    
            String trackerResponse = requestFileFromOtherTrackers(fileName);
            if (!trackerResponse.equals("File not found")) {
                String[] trackerIps = trackerResponse.split(", ");
                trackerLock.lock();
                try {
                    for (String trackerIp : trackerIps) {
                        if (!otherTrackers.contains(trackerIp)) {
                            otherTrackers.add(trackerIp);
                        }
                    }
                } finally {
                    trackerLock.unlock();
                }
                return trackerResponse;
            }
    
            return "File not found";
        } finally {
            peerLock.readLock().unlock();
        }
    }
    

    private String requestFileFromOtherTrackers(String fileName) {
        for (String tracker : otherTrackers) {
            try (Socket socket = new Socket(tracker, TCP_PORT_FILE_REQUESTS_FROM_OTHER_TRAKCERS);
                    PrintWriter writer = new PrintWriter(socket.getOutputStream(), true);
                    BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {
                writer.println(fileName);
                String response = reader.readLine();
                if (response != null && !response.equals("File not found")) {
                    return response;
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return "File not found";
    }

    private void checkPeerHealth() {
        while (true) {
            try {
                Thread.sleep(PEER_CHECK_INTERVAL_MS);
                List<String> toRemove = new ArrayList<>();

                peerLock.readLock().lock();
                try {
                    for (Map.Entry<String, PeerInfo> entry : peers.entrySet()) {
                        if (!isPeerAlive(entry.getValue())) {
                            toRemove.add(entry.getKey());
                        }
                    }
                } finally {
                    peerLock.readLock().unlock();
                }

                peerLock.writeLock().lock();
                try {
                    for (String key : toRemove) {
                        peers.remove(key);
                    }
                } finally {
                    peerLock.writeLock().unlock();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private boolean isPeerAlive(PeerInfo peer) {
        for (int i = 0; i < 3; ++i) {
            try (DatagramSocket socket = new DatagramSocket()) {
                byte[] pingMessage = "ping".getBytes();
                DatagramPacket packet = new DatagramPacket(pingMessage, pingMessage.length, peer.getIp(),
                        peer.getPort());
                socket.send(packet);

                socket.setSoTimeout(5000);
                byte[] buffer = new byte[BUFFER_SIZE];
                DatagramPacket responsePacket = new DatagramPacket(buffer, buffer.length);
                socket.receive(responsePacket);
                String response = new String(responsePacket.getData(), 0, responsePacket.getLength()).trim();
                return "pong".equals(response);
            } catch (IOException e) {
                return false;
            }
        }
        return false;
    }
}
