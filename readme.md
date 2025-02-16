# Peer-to-Peer File Sharing System

A decentralized peer-to-peer (P2P) file-sharing system where peers can share and download files via a central tracker. Built with Java, this project demonstrates basic P2P networking concepts, including file distribution, peer discovery, and health monitoring.

## Features

- **File Sharing:** Peers can share files with the network via a tracker.
- **File Downloading:** Peers can request and download files from other peers.
- **Tracker Coordination:** A central tracker manages peer connections and file availability.
- **Progress Tracking:** Real-time progress bars for uploads and downloads.
- **Health Checks:** Periodic pings to ensure peers are active.
- **Logging:** Detailed logs for peer activity and tracker operations.
- **CLI Interface:** Command-line interfaces for both peers and the tracker.

## Requirements

- Java JDK 8 or higher.
- Basic familiarity with command-line tools.

## Installation

### Clone the Repository

```bash
git clone https://github.com/your-username/p2p-file-sharing.git
cd p2p-file-sharing
```

## Compilation

```bash
javac Peer/Peer.java Tracker/Tracker.java
```

## Usage

### Starting the Tracker

Run the tracker to coordinate peer connections:

```bash
java Tracker.Tracker
```

## Starting a Peer

Start a peer with optional ping and server ports (defaults: 6883 and 6885):

```bash
java Peer.Peer <ping_port> <server_port>
```

## Peer Commands

Use these commands in the peer's CLI:

### Share a File

```bash
share <file_path> <tracker_IP> <listen_port>
```

### Example(absolute paths and relative paths are supported):

```bash
share ./file.txt 127.0.0.1 6881
```

### Download a File

```bash
get <file_name> <tracker_IP> <tracker_port>
```

### Example:

```bash
get file.txt 127.0.0.1 6881
```

### View Logs

```bash
logs
```

## Tracker Commands

Use these commands in the tracker's CLI:

### View All Logs

```bash
all-logs
```

### Filter Logs by IP

```bash
log requests <IP_address>
```

### Filter Logs by Filename

```bash
file-logs <filename>
```
