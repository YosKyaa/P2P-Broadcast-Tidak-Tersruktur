// Untuk menjalankan kode ini, Anda memerlukan EMPAT library eksternal.
// 1. Java-WebSocket: https://github.com/TooTallNate/Java-WebSocket/releases (misal: Java-WebSocket-1.6.0.jar)
// 2. JSON-Java: https://repo1.maven.org/maven2/org/json/json/20240303/json-20240303.jar
// 3. SLF4J API: https://repo1.maven.org/maven2/org/slf4j/slf4j-api/2.0.13/slf4j-api-2.0.13.jar
// 4. SLF4J Simple: https://repo1.maven.org/maven2/org/slf4j/slf4j-simple/2.0.13/slf4j-simple-2.0.13.jar
//
// Letakkan keempat file .jar di direktori yang sama dengan P2PNode.java
//
// Kompilasi (Windows): javac -cp "Java-WebSocket-1.6.0.jar;json-20240303.jar;slf4j-api-2.0.13.jar;slf4j-simple-2.0.13.jar;." P2PNode.java
// Kompilasi (macOS/Linux): javac -cp "Java-WebSocket-1.6.0.jar:json-20240303.jar:slf4j-api-2.0.13.jar:slf4j-simple-2.0.13.jar:." P2PNode.java
//
// Jalankan (Windows): java -cp "Java-WebSocket-1.6.0.jar;json-20240303.jar;slf4j-api-2.0.13.jar;slf4j-simple-2.0.13.jar;." P2PNode <port>
// Jalankan (macOS/Linux): java -cp "Java-WebSocket-1.6.0.jar:json-20240303.jar:slf4j-api-2.0.13.jar:slf4j-simple-2.0.13.jar:." P2PNode <port>

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;
import org.json.JSONObject;
import org.json.JSONArray;

public class P2PNode {
    private String peerId;
    private int httpPort;
    private int p2pPort;
    private int wsPort;
    private String hostIp;

    private final Set<String> localFiles = new ConcurrentHashMap<>().newKeySet();
    private final Map<String, String> discoveredPeers = new ConcurrentHashMap<>(); // peerId -> host:p2pPort
    private final MyWebSocketServer wsServer;
    private P2PListener p2pListener;

    public P2PNode(int port) {
        this.httpPort = port;
        this.p2pPort = port + 1;
        this.wsPort = port + 2;
        try {
            this.hostIp = InetAddress.getLocalHost().getHostAddress();
            this.peerId = "Peer@" + hostIp + ":" + p2pPort;
        } catch (UnknownHostException e) {
            this.hostIp = "127.0.0.1";
            this.peerId = "Peer@" + hostIp + ":" + p2pPort;
            System.err.println("Tidak dapat menemukan IP lokal, menggunakan 127.0.0.1");
        }
        this.wsServer = new MyWebSocketServer(new InetSocketAddress(wsPort), this);
    }

    public void start() {
        startHttpServer();
        wsServer.start();
        new DiscoveryThread(this).start();
        p2pListener = new P2PListener(this, p2pPort);
        p2pListener.start();
        System.out.println("=====================================================");
        System.out.println(peerId + " Berjalan!");
        System.out.println("Buka http://" + hostIp + ":" + httpPort + " di browser Anda.");
        System.out.println("=====================================================");
    }

    private void startHttpServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(httpPort), 0);
            server.createContext("/", new RootHandler());
            server.createContext("/upload", new UploadHandler(this));
            server.setExecutor(null);
            server.start();
        } catch (IOException e) {
            System.err.println("Gagal memulai server HTTP: " + e.getMessage());
            System.exit(1);
        }
    }
    
    static class RootHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            String response = getHtmlContent();
            t.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
            t.sendResponseHeaders(200, response.getBytes(StandardCharsets.UTF_8).length);
            OutputStream os = t.getResponseBody();
            os.write(response.getBytes(StandardCharsets.UTF_8));
            os.close();
        }
    }

    // === UPLOAD HANDLER YANG TELAH DIPERBAIKI ===
    static class UploadHandler implements HttpHandler {
        private P2PNode node;
        public UploadHandler(P2PNode node) { this.node = node; }
        @Override
        public void handle(HttpExchange t) throws IOException {
            if (!"POST".equals(t.getRequestMethod())) {
                t.sendResponseHeaders(405, -1);
                return;
            }

            String filename = null;
            try {
                // Membaca seluruh body request. Ini tidak efisien untuk file besar
                // tapi lebih sederhana dan andal untuk skala proyek ini.
                InputStream is = t.getRequestBody();
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buffer = new byte[2048];
                int length;
                while ((length = is.read(buffer)) != -1) {
                    baos.write(buffer, 0, length);
                }
                byte[] requestBodyBytes = baos.toByteArray();

                // Konversi ke string untuk mencari nama file. Gunakan charset yang aman.
                String requestBody = new String(requestBodyBytes, StandardCharsets.ISO_8859_1);
                
                String searchString = "filename=\"";
                int startIndex = requestBody.indexOf(searchString);
                if (startIndex != -1) {
                    startIndex += searchString.length();
                    int endIndex = requestBody.indexOf("\"", startIndex);
                    if (endIndex != -1) {
                        filename = new String(requestBody.substring(startIndex, endIndex).getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }

            String response;
            if (filename != null && !filename.isEmpty()) {
                // Membersihkan nama file untuk keamanan
                filename = new File(filename).getName();
                node.addLocalFile(filename);
                node.wsServer.broadcastState();
                response = "File '" + filename + "' berhasil diunggah.";
                t.sendResponseHeaders(200, response.length());
            } else {
                response = "Gagal mengunggah file. Tidak ada file yang dipilih atau format salah.";
                t.sendResponseHeaders(400, response.length());
            }
            OutputStream os = t.getResponseBody();
            os.write(response.getBytes(StandardCharsets.UTF_8));
            os.close();
        }
    }
    
    public void addLocalFile(String filename) {
        this.localFiles.add(filename);
        System.out.println("File ditambahkan: " + filename);
    }
    
    public void searchFile(String filename) {
        String message = String.format("SEARCH_REQUEST::%s::%s", this.peerId, filename);
        broadcastToPeers(message);
        wsServer.logToUI("Mencari file '" + filename + "'...");
    }

    public void requestPermission(String ownerId, String filename) {
        String message = String.format("PERMISSION_REQUEST::%s::%s", this.peerId, filename);
        sendMessageToPeer(ownerId, message);
        wsServer.logToUI("Meminta izin unduh dari " + ownerId + " untuk file '" + filename + "'");
    }
    
    public void respondToPermission(String requesterId, String filename, boolean grant) {
        String type = grant ? "PERMISSION_GRANTED" : "PERMISSION_DENIED";
        String message = String.format("%s::%s::%s", type, this.peerId, filename);
        sendMessageToPeer(requesterId, message);
        wsServer.logToUI("Mengirim respons izin (" + (grant ? "DISETUJUI" : "DITOLAK") + ") ke " + requesterId);
    }

    private void broadcastToPeers(String message) {
        wsServer.logToUI("Melakukan broadcast: " + message.split("::")[0]);
        discoveredPeers.forEach((id, address) -> {
            if (!id.equals(this.peerId)) {
                sendMessageToPeer(id, message);
            }
        });
    }

    private void sendMessageToPeer(String peerId, String message) {
        String address = discoveredPeers.get(peerId);
        if (address != null) {
            String[] parts = address.split(":");
            String host = parts[0];
            int port = Integer.parseInt(parts[1]);
            new Thread(() -> {
                try (Socket socket = new Socket(host, port);
                     PrintWriter out = new PrintWriter(socket.getOutputStream(), true)) {
                    out.println(message);
                } catch (IOException e) {
                    wsServer.logToUI("ERROR: Gagal mengirim pesan ke " + peerId);
                }
            }).start();
        }
    }

    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("Penggunaan: java P2PNode <http-port>");
            return;
        }
        int port = Integer.parseInt(args[0]);
        new P2PNode(port).start();
    }
    
    // Metode getter
    public String getPeerId() { return peerId; }
    public String getHostIp() { return hostIp; }
    public int getP2pPort() { return p2pPort; }
    public Map<String, String> getDiscoveredPeers() { return discoveredPeers; }
    public Set<String> getLocalFiles() { return localFiles; }
    public MyWebSocketServer getWsServer() { return wsServer; }
    public boolean hasFile(String filename) { return localFiles.contains(filename); }

    // Metode untuk menyajikan konten HTML dari string
    private static String getHtmlContent() {
        return "<!DOCTYPE html>"
             + "<html lang='id'>"
             + "<head>"
             + "<meta charset='UTF-8'>"
             + "<meta name='viewport' content='width=device-width, initial-scale=1.0'>"
             + "<title>P2P Node Interface</title>"
             + "<script src='https://cdn.tailwindcss.com'></script>"
             + "<link href='https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700&display=swap' rel='stylesheet'>"
             + "<style>"
             + "body { font-family: 'Inter', sans-serif; background-color: #f3f4f6; }"
             + ".card { background-color: white; border-radius: 0.75rem; box-shadow: 0 4px 6px -1px rgb(0 0 0 / 0.1), 0 2px 4px -2px rgb(0 0 0 / 0.1); padding: 1.5rem; margin-bottom: 1.5rem; }"
             + ".log-entry { animation: fadeIn 0.5s ease; }"
             + "@keyframes fadeIn { from { opacity: 0; transform: translateY(5px); } to { opacity: 1; transform: translateY(0); } }"
             + "</style>"
             + "</head>"
             + "<body class='p-4 sm:p-8'>"
             + "<div class='max-w-6xl mx-auto'>"
             + "<header class='text-center mb-8'>"
             + "<h1 id='peerIdHeader' class='text-3xl font-bold text-gray-900'>P2P Node</h1>"
             + "<p class='text-gray-600'>Antarmuka untuk interaksi P2P</p>"
             + "</header>"
             + "<div class='grid grid-cols-1 lg:grid-cols-3 gap-6'>"
             + "<div class='lg:col-span-1 space-y-6'>"
             + "<div class='card'>"
             + "<h2 class='text-lg font-semibold mb-3'>File Lokal</h2>"
             + "<ul id='localFilesList' class='list-disc list-inside text-gray-700 space-y-1'></ul>"
             + "</div>"
             + "<div class='card'>"
             + "<h2 class='text-lg font-semibold mb-3'>Unggah File</h2>"
             + "<form id='uploadForm' class='space-y-2'><input type='file' id='fileInput' class='block w-full text-sm text-gray-500 file:mr-4 file:py-2 file:px-4 file:rounded-full file:border-0 file:text-sm file:font-semibold file:bg-violet-50 file:text-violet-700 hover:file:bg-violet-100'/><button type='submit' class='w-full bg-blue-600 text-white py-2 rounded-md hover:bg-blue-700'>Unggah</button></form>"
             + "</div>"
             + "<div class='card'>"
             + "<h2 class='text-lg font-semibold mb-3'>Peer yang Ditemukan</h2>"
             + "<ul id='peerList' class='list-disc list-inside text-gray-700 space-y-1'></ul>"
             + "</div>"
             + "</div>"
             + "<div class='lg:col-span-2 space-y-6'>"
             + "<div class='card'>"
             + "<h2 class='text-lg font-semibold mb-3'>Kontrol & Pencarian</h2>"
             + "<div class='flex space-x-2'><input type='text' id='searchInput' placeholder='Nama file...' class='flex-grow border rounded-md px-3 py-2'/><button id='searchBtn' class='bg-green-600 text-white px-4 py-2 rounded-md hover:bg-green-700'>Cari</button></div>"
             + "<div id='searchResults' class='mt-4 space-y-2'></div>"
             + "</div>"
             + "<div class='card'>"
             + "<h2 class='text-lg font-semibold mb-3'>Permintaan Izin Masuk</h2>"
             + "<div id='permissionRequests' class='space-y-2'></div>"
             + "</div>"
             + "<div class='card'>"
             + "<h2 class='text-lg font-semibold mb-3'>Log Aktivitas</h2>"
             + "<div id='log' class='h-64 bg-gray-900 text-white p-3 rounded-md overflow-y-auto text-sm font-mono'></div>"
             + "</div>"
             + "</div>"
             + "</div>"
             + "</div>"
             + "<script>"
             + "const ws = new WebSocket('ws://' + window.location.hostname + ':' + (parseInt(window.location.port) + 2));"
             + "const log = document.getElementById('log');"
             + "const localFilesList = document.getElementById('localFilesList');"
             + "const peerList = document.getElementById('peerList');"
             + "const searchBtn = document.getElementById('searchBtn');"
             + "const searchInput = document.getElementById('searchInput');"
             + "const searchResults = document.getElementById('searchResults');"
             + "const permissionRequests = document.getElementById('permissionRequests');"
             + "const uploadForm = document.getElementById('uploadForm');"
             + "const fileInput = document.getElementById('fileInput');"
             + "ws.onopen = () => logMessage('Koneksi WebSocket terbuka.');"
             + "ws.onclose = () => logMessage('Koneksi WebSocket ditutup.');"
             + "ws.onmessage = (event) => {"
             + "  const data = JSON.parse(event.data);"
             + "  switch(data.type) {"
             + "    case 'STATE_UPDATE': updateState(data); break;"
             + "    case 'LOG': logMessage(data.message); break;"
             + "    case 'FILE_FOUND': showSearchResult(data); break;"
             + "    case 'PERMISSION_REQUEST': showPermissionRequest(data); break;"
             + "  }"
             + "};"
             + "function logMessage(msg) { log.innerHTML += `<div>[${new Date().toLocaleTimeString()}] ${msg}</div>`; log.scrollTop = log.scrollHeight; }"
             + "function updateState(data) {"
             + "  document.getElementById('peerIdHeader').textContent = data.peerId;"
             + "  localFilesList.innerHTML = ''; data.localFiles.forEach(f => { localFilesList.innerHTML += `<li>${f}</li>`; });"
             + "  peerList.innerHTML = ''; Object.keys(data.discoveredPeers).forEach(p => { if(p !== data.peerId) peerList.innerHTML += `<li>${p}</li>`; });"
             + "}"
             + "searchBtn.onclick = () => {"
             + "  if (searchInput.value) ws.send(JSON.stringify({type: 'SEARCH', filename: searchInput.value}));"
             + "};"
             + "function showSearchResult(data) {"
             + "  searchResults.innerHTML += `<div class='p-2 border rounded'>File <strong>${data.filename}</strong> ditemukan di <strong>${data.ownerId}</strong>. <button onclick='requestPermission(\"${data.ownerId}\", \"${data.filename}\")' class='bg-yellow-500 text-white px-2 py-1 rounded text-xs'>Minta Izin</button></div>`;"
             + "}"
             + "function requestPermission(ownerId, filename) { ws.send(JSON.stringify({type: 'REQUEST_PERMISSION', ownerId, filename})); }"
             + "function showPermissionRequest(data) {"
             + "  permissionRequests.innerHTML += `<div class='p-2 border-yellow-400 bg-yellow-50 rounded'>Permintaan dari <strong>${data.requesterId}</strong> untuk file <strong>${data.filename}</strong>. <button onclick='respond(\"${data.requesterId}\", \"${data.filename}\", true)' class='bg-green-500 text-white px-2 py-1 rounded text-xs'>Izinkan</button> <button onclick='respond(\"${data.requesterId}\", \"${data.filename}\", false)' class='bg-red-500 text-white px-2 py-1 rounded text-xs'>Tolak</button></div>`;"
             + "}"
             + "function respond(requesterId, filename, grant) { ws.send(JSON.stringify({type: 'PERMISSION_RESPONSE', requesterId, filename, grant})); permissionRequests.innerHTML = ''; }"
             + "uploadForm.onsubmit = (e) => {"
             + "  e.preventDefault();"
             + "  if (fileInput.files.length > 0) {"
             + "    const formData = new FormData();"
             + "    formData.append('file', fileInput.files[0]);"
             + "    fetch('/upload', { method: 'POST', body: formData })"
             + "      .then(response => response.text())"
             + "      .then(result => { logMessage(result); fileInput.value = ''; })"
             + "      .catch(error => logMessage('Error unggah: ' + error));"
             + "  }"
             + "};"
             + "</script>"
             + "</body>"
             + "</html>";
    }
}

// Thread untuk mendengarkan pesan P2P (TCP)
class P2PListener extends Thread {
    private P2PNode node;
    private int port;
    private ServerSocket serverSocket;

    public P2PListener(P2PNode node, int port) {
        this.node = node;
        this.port = port;
        setDaemon(true);
    }

    @Override
    public void run() {
        try {
            serverSocket = new ServerSocket(port);
            while (!serverSocket.isClosed()) {
                Socket clientSocket = serverSocket.accept();
                new Thread(() -> handleClient(clientSocket)).start();
            }
        } catch (IOException e) {
            if (!serverSocket.isClosed()) {
                System.err.println("P2P Listener error: " + e.getMessage());
            }
        }
    }

    private void handleClient(Socket clientSocket) {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()))) {
            String message = in.readLine();
            if (message == null) return;
            
            String[] parts = message.split("::", 3);
            String type = parts[0];
            String senderId = parts[1];
            String payload = parts[2];
            
            node.getWsServer().logToUI("Menerima " + type + " dari " + senderId);

            switch (type) {
                case "SEARCH_REQUEST":
                    if (node.hasFile(payload)) {
                        node.getWsServer().logToUI("File '" + payload + "' ditemukan. Mengirim respons ke " + senderId);
                        String response = String.format("FILE_FOUND::%s::%s", node.getPeerId(), payload);
                        sendMessageToPeer(senderId, response);
                    }
                    break;
                case "FILE_FOUND":
                    node.getWsServer().logToUI("Respons diterima: File '" + payload + "' ditemukan di " + senderId);
                    node.getWsServer().foundFile(senderId, payload);
                    break;
                case "PERMISSION_REQUEST":
                    node.getWsServer().permissionRequested(senderId, payload);
                    break;
                case "PERMISSION_GRANTED":
                    node.getWsServer().logToUI("Izin DIBERIKAN oleh " + senderId + " untuk file '" + payload + "'.");
                    new Thread(() -> {
                        try {
                            Thread.sleep(2000);
                            node.addLocalFile(payload);
                            node.getWsServer().logToUI("Unduhan '" + payload + "' selesai.");
                            node.getWsServer().broadcastState();
                        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    }).start();
                    break;
                case "PERMISSION_DENIED":
                     node.getWsServer().logToUI("Izin DITOLAK oleh " + senderId + " untuk file '" + payload + "'.");
                    break;
            }
        } catch (IOException e) {
            // e.printStackTrace();
        } finally {
            try { clientSocket.close(); } catch (IOException e) {}
        }
    }
     private void sendMessageToPeer(String peerId, String message) {
        String address = node.getDiscoveredPeers().get(peerId);
        if (address != null) {
            String[] parts = address.split(":");
            String host = parts[0];
            int port = Integer.parseInt(parts[1]);
            try (Socket socket = new Socket(host, port);
                 PrintWriter out = new PrintWriter(socket.getOutputStream(), true)) {
                out.println(message);
            } catch (IOException e) {
                node.getWsServer().logToUI("ERROR: Gagal mengirim pesan ke " + peerId);
            }
        }
    }
}

// Thread untuk menemukan peer lain di jaringan (UDP Multicast)
class DiscoveryThread extends Thread {
    private static final String MULTICAST_ADDRESS = "230.0.0.1";
    private static final int MULTICAST_PORT = 4446;
    private P2PNode node;

    public DiscoveryThread(P2PNode node) {
        this.node = node;
        setDaemon(true);
    }

    @Override
    public void run() {
        try (MulticastSocket socket = new MulticastSocket(MULTICAST_PORT)) {
            InetAddress group = InetAddress.getByName(MULTICAST_ADDRESS);
            socket.joinGroup(group);

            new Thread(() -> {
                while (true) {
                    try {
                        String message = String.format("DISCOVERY::%s::%s:%d", node.getPeerId(), node.getHostIp(), node.getP2pPort());
                        byte[] buf = message.getBytes();
                        DatagramPacket packet = new DatagramPacket(buf, buf.length, group, MULTICAST_PORT);
                        socket.send(packet);
                        Thread.sleep(5000);
                    } catch (Exception e) { e.printStackTrace(); }
                }
            }).start();

            while (true) {
                byte[] buf = new byte[256];
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                socket.receive(packet);
                String received = new String(packet.getData(), 0, packet.getLength());
                String[] parts = received.split("::");
                if ("DISCOVERY".equals(parts[0])) {
                    String peerId = parts[1];
                    String address = parts[2];
                    if (!peerId.equals(node.getPeerId()) && node.getDiscoveredPeers().put(peerId, address) == null) {
                        System.out.println("Peer baru ditemukan: " + peerId + " di " + address);
                        node.getWsServer().broadcastState();
                    }
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}

// Server WebSocket untuk komunikasi real-time dengan UI Web
class MyWebSocketServer extends WebSocketServer {
    private P2PNode node;

    public MyWebSocketServer(InetSocketAddress address, P2PNode node) {
        super(address);
        this.node = node;
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        System.out.println("Koneksi WebSocket baru dari: " + conn.getRemoteSocketAddress().getAddress().getHostAddress());
        broadcastState();
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        System.out.println("Koneksi WebSocket ditutup: " + conn.getRemoteSocketAddress().getAddress().getHostAddress());
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        JSONObject json = new JSONObject(message);
        String type = json.getString("type");

        switch(type) {
            case "SEARCH":
                node.searchFile(json.getString("filename"));
                break;
            case "REQUEST_PERMISSION":
                node.requestPermission(json.getString("ownerId"), json.getString("filename"));
                break;
            case "PERMISSION_RESPONSE":
                node.respondToPermission(json.getString("requesterId"), json.getString("filename"), json.getBoolean("grant"));
                break;
        }
    }
    
    @Override
    public void onError(WebSocket conn, Exception ex) {
        ex.printStackTrace();
    }
    
    @Override
    public void onStart() {
        System.out.println("Server WebSocket dimulai di port " + getPort());
    }

    public void broadcastState() {
        JSONObject state = new JSONObject();
        state.put("type", "STATE_UPDATE");
        state.put("peerId", node.getPeerId());
        state.put("localFiles", new JSONArray(node.getLocalFiles()));
        
        JSONObject peers = new JSONObject();
        node.getDiscoveredPeers().forEach(peers::put);
        state.put("discoveredPeers", peers);
        
        broadcast(state.toString());
    }

    public void logToUI(String message) {
        JSONObject logMsg = new JSONObject();
        logMsg.put("type", "LOG");
        logMsg.put("message", message);
        broadcast(logMsg.toString());
    }
    
    public void foundFile(String ownerId, String filename) {
        JSONObject msg = new JSONObject();
        msg.put("type", "FILE_FOUND");
        msg.put("ownerId", ownerId);
        msg.put("filename", filename);
        broadcast(msg.toString());
    }

    public void permissionRequested(String requesterId, String filename) {
        JSONObject msg = new JSONObject();
        msg.put("type", "PERMISSION_REQUEST");
        msg.put("requesterId", requesterId);
        msg.put("filename", filename);
        broadcast(msg.toString());
    }
}
