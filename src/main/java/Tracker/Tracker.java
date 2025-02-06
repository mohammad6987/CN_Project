package Tracker;

import java.io.*;
import java.net.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.*;

public class Tracker {
    private static final int UDP_PEER_TO_TRACKER = 6881;
    private static final int TCP_PORT_FOR_OTHER_TRACKERS = 6882;
    private static final int TCP_PORT_FILE_REQUESTS_FROM_OTHER_TRAKCERS = 6883;
    private static final int BUFFER_SIZE = 1024;
    private static final int TIMEOUT_MS = 300_000;
    private static final int PEER_CHECK_INTERVAL_MS = 20000;
    private Map<String, PeerInfo> peers = new ConcurrentHashMap<>();
    private List<String> otherTrackers;
    private ReadWriteLock peerLock = new ReentrantReadWriteLock();
    private Lock trackerLock = new ReentrantLock();
    private SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    private List<String> logs = new CopyOnWriteArrayList<>();

    public class PeerInfo {
        private  InetAddress ip;
        private int listenPort;
        private int serverPort;
        private int pingPort;
        private volatile long lastSeen;
        private Set<String> sharedFiles;

        public PeerInfo(InetAddress ip, int listenport,int serverPort, int pingPort) {
            this.ip = ip;
            this.listenPort = listenport;
            this.serverPort = serverPort;
            this.pingPort = pingPort;
            this.lastSeen = System.currentTimeMillis();
            this.sharedFiles = ConcurrentHashMap.newKeySet();
        }

        public void updateLastSeen() {
            this.lastSeen = System.currentTimeMillis();
        }

        public InetAddress getIp() {
            return ip;
        }

        public int getListenPort() {
            return listenPort;
        }
        public int getServerPort(){
            return serverPort;
        }

        public int getPingPort(){
            return pingPort;
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
        ExecutorService executor = Executors.newFixedThreadPool(4);
        executor.execute(this::listenForTrackers);
        executor.execute(this::listenForPeers);
        executor.execute(this::checkPeerHealth);
        System.out.println("all services are functional!");
        executor.execute(this::CLI);

    }
    private void log(String message) {
        String timestamp = dateFormat.format(new Date());
        logs.add("[" + timestamp + "] " + message);
    }

    private void CLI(){
        Scanner scanner = new Scanner(System.in);
        try{
            int counter =0;
            while(true){
                counter = 0;
                System.out.print("> ");
                String command = scanner.nextLine();
                if(command.equals("all-logs")){
                    for(String x : logs)
                        System.out.println(x);

                }else if(command.startsWith("log requests ")){
                    String[] info = command.split(" ");
                    if(info.length > 2){
                        System.out.println("Error in command format!");
                        continue;
                    }
                    String keyword = info[1];
                    for(String log : logs){
                        if(log.contains(keyword)){
                            System.out.println(log);
                            counter++;
                        }
                        if(counter == 0){
                            System.out.println("No log with this IP");
                        }
                    }
                }else if (command.startsWith("file_logs ")){
                    String[] info = command.split(" ");
                    if(info.length > 2){
                        System.out.println("Error in command format!");
                        continue;
                    }
                    String keyword = info[1];
                    for(String log : logs){
                        if(log.contains(keyword)){
                            System.out.println(log);
                            counter++;
                        }
                        if(counter == 0){
                            System.out.println("No log with this FileName!");
                        }
                    }

                }else{

                    System.out.println("Invalid command!");
                }
            
            }


        }catch(Exception e){

        }
        
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
                        log("Tracker added: " + trackerAddress);
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
            
            int tempPort;
            String response;
            log("got a peer packet : ip :" + address + " message: " + message);
            peerLock.writeLock().lock();
            try {
                if (message.startsWith("share") && message.length() > 6) {
                    String fileName = message.split(" ")[1];
                    int peerServerPort = Integer.parseInt(message.split(" ")[4]);
                    int peerPingPort = Integer.parseInt(message.split(" ")[5]);
                    peers.computeIfAbsent(address.toString()+":"+peerServerPort,
                            k -> new PeerInfo(address, port , peerServerPort , peerPingPort)).getSharedFiles()
                            .add(fileName);
                    response = "File shared successfully: " + fileName;
                    tempPort = Integer.parseInt(message.split(" ")[3]);

                } else if (message.startsWith("get") && message.length() > 4) {
                    String fileName = message.substring(4).trim();
                    response = getPeersWithFile(fileName);
                    tempPort = port;
                }else if(message.startsWith("ack") && message.length() > 4){
                    String[] info = message.split(" ");
                    String senderKey = address.toString() +":"+ info[2];
                    tempPort = 8080;
                    if(info[3].equals("success")){

                        peers.computeIfAbsent(senderKey, x ->new PeerInfo(address, port, Integer.parseInt(info[2]) ,Integer.parseInt(info[4])));
                        peers.get(senderKey).sharedFiles.add(info[1]);
                        response = senderKey + " successfully donwloaded " +info[1];
                        log(senderKey + " successfully donwloaded " + info[1]);
                    }else{
                        response = senderKey + " couldn't download " +info[1];
                        log(senderKey + " couldn't download " + info[1]);
                    }




                } else {
                    response = "Invalid command";
                    tempPort = port;
                }
            } finally {
                peerLock.writeLock().unlock();
            }
            // Nisixixixi
            byte[] responseData = response.getBytes();
            DatagramPacket responsePacket = new DatagramPacket(responseData, responseData.length, address, tempPort);
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
                    peerList.add(peer.getIp().getHostAddress() + ":" + peer.getServerPort());
                }
            }
            if (!peerList.isEmpty()) {
                System.out.println(String.join(", ", peerList));
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
        StringBuilder builder = new StringBuilder();
        while (true) {
            try {
                Thread.sleep(PEER_CHECK_INTERVAL_MS);
                //System.out.println("stareted checking health with " + peers.size() + " peers !");
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
                builder = new StringBuilder();
                try {
                    for (String key : toRemove) {
                        builder.append(key);
                        peers.remove(key);
                    }
                    log("dead peers : "+builder.toString());
                    builder = new StringBuilder();
                    for(String key:peers.keySet()){
                        builder.append(key + " ");
                    }
                    log("alive peers : " + builder.toString());
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
                byte[] pingMessage = "Are you still alive?".getBytes();
                DatagramPacket packet = new DatagramPacket(pingMessage, pingMessage.length, peer.getIp(),
                        peer.pingPort);
                socket.send(packet);

                socket.setSoTimeout(5000);
                byte[] buffer = new byte[BUFFER_SIZE];
                DatagramPacket responsePacket = new DatagramPacket(buffer, buffer.length);
                socket.receive(responsePacket);
                String response = new String(responsePacket.getData(), 0, responsePacket.getLength()).trim();
                return "yep , I am still alive.".equals(response);
            } catch (IOException e) {
                return false;
            }
        }
        return false;
    }
}
