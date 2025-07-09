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
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;
import org.json.JSONObject;
import org.json.JSONArray;

public class P2PNode {
    private String peerId;
    private String userName;
    private int httpPort;
    private int p2pPort;
    private int wsPort;
    private String hostIp;
    private boolean p2pServicesStarted = false;

    private final Set<String> localFiles = new ConcurrentHashMap<>().newKeySet();
    private final Map<String, byte[]> fileStorage = new ConcurrentHashMap<>(); // Menyimpan konten file
    private final Map<String, String> discoveredPeers = new ConcurrentHashMap<>(); // peerId -> host:p2pPort
    private final MyWebSocketServer wsServer;

    public P2PNode(int port) {
        this.httpPort = port;
        this.p2pPort = port + 1;
        this.wsPort = port + 2;
        try {
            this.hostIp = InetAddress.getLocalHost().getHostAddress();
        } catch (UnknownHostException e) {
            this.hostIp = "127.0.0.1";
            System.err.println("Tidak dapat menemukan IP lokal, menggunakan 127.0.0.1");
        }
        this.wsServer = new MyWebSocketServer(new InetSocketAddress(wsPort), this);
    }

    public void start() {
        startHttpServer();
        wsServer.start();
        System.out.println("=====================================================");
        System.out.println("Server HTTP berjalan!");
        System.out.println("Buka http://" + hostIp + ":" + httpPort + " di browser Anda untuk login.");
        System.out.println("=====================================================");
    }

    private synchronized void startP2PServices() {
        if (p2pServicesStarted) return;
        
        this.peerId = this.userName + "@" + hostIp + ":" + p2pPort;
        
        new DiscoveryThread(this).start();
        new P2PListener(this, p2pPort).start();
        
        p2pServicesStarted = true;
        System.out.println("Peer '" + this.peerId + "' telah aktif dan layanan P2P dimulai.");
    }

    private void startHttpServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(httpPort), 0);
            server.createContext("/", new RootHandler());
            server.createContext("/login", new LoginHandler(this));
            server.createContext("/app", new AppHandler());
            server.createContext("/upload", new UploadHandler(this));
            server.createContext("/download", new DownloadHandler(this));
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
            String response = getLoginPageHtml();
            sendHtmlResponse(t, response);
        }
    }

    static class LoginHandler implements HttpHandler {
        private P2PNode node;
        public LoginHandler(P2PNode node) { this.node = node; }
        @Override
        public void handle(HttpExchange t) throws IOException {
            if ("POST".equals(t.getRequestMethod())) {
                InputStreamReader isr = new InputStreamReader(t.getRequestBody(), "utf-8");
                BufferedReader br = new BufferedReader(isr);
                String query = br.readLine();
                Map<String, String> params = parseQuery(query);
                String username = params.getOrDefault("username", "User" + (new Random().nextInt(1000)));
                username = URLDecoder.decode(username, "UTF-8");
                node.userName = username;
                node.startP2PServices();
                t.getResponseHeaders().set("Location", "/app");
                t.sendResponseHeaders(302, -1);
            }
        }
    }

    static class AppHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            String response = getAppPageHtml();
            sendHtmlResponse(t, response);
        }
    }

    // === UPLOAD HANDLER YANG TELAH DIPERBAIKI SECARA MENYELURUH ===
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
            byte[] fileContent = null;
            try {
                String contentType = t.getRequestHeaders().getFirst("Content-Type");
                String boundary = "--" + contentType.substring(contentType.indexOf("boundary=") + 9);
                byte[] boundaryBytes = boundary.getBytes(StandardCharsets.UTF_8);

                InputStream is = t.getRequestBody();
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int read;
                while ((read = is.read(buffer)) != -1) {
                    baos.write(buffer, 0, read);
                }
                byte[] bodyBytes = baos.toByteArray();

                int start = indexOf(bodyBytes, "filename=\"".getBytes(StandardCharsets.UTF_8), 0) + 10;
                int end = indexOf(bodyBytes, "\"".getBytes(StandardCharsets.UTF_8), start);
                filename = new String(Arrays.copyOfRange(bodyBytes, start, end), StandardCharsets.UTF_8);

                byte[] separator = "\r\n\r\n".getBytes(StandardCharsets.UTF_8);
                int contentStart = indexOf(bodyBytes, separator, end) + 4;
                int contentEnd = lastIndexOf(bodyBytes, boundaryBytes, bodyBytes.length) - 2; // -2 for \r\n
                
                if (contentEnd < contentStart) { // Fallback for last part boundary
                    contentEnd = lastIndexOf(bodyBytes, (boundary + "--").getBytes(StandardCharsets.UTF_8), bodyBytes.length) -2;
                }

                fileContent = Arrays.copyOfRange(bodyBytes, contentStart, contentEnd);

            } catch (Exception e) {
                e.printStackTrace();
            }
            String response;
            if (filename != null && fileContent != null) {
                filename = new File(filename).getName();
                node.addFile(filename, fileContent);
                node.wsServer.broadcastState();
                response = "File '" + filename + "' berhasil diunggah.";
                t.sendResponseHeaders(200, response.length());
            } else {
                response = "Gagal mengunggah file.";
                t.sendResponseHeaders(400, response.length());
            }
            OutputStream os = t.getResponseBody();
            os.write(response.getBytes(StandardCharsets.UTF_8));
            os.close();
        }
    }
    
    // === DOWNLOAD HANDLER YANG TELAH DIPERBAIKI ===
    static class DownloadHandler implements HttpHandler {
        private P2PNode node;
        public DownloadHandler(P2PNode node) { this.node = node; }
        @Override
        public void handle(HttpExchange t) throws IOException {
            Map<String, String> params = parseQuery(t.getRequestURI().getQuery());
            String filename = URLDecoder.decode(params.get("file"), "UTF-8");

            byte[] contentBytes = node.getFileContent(filename);

            if (contentBytes != null) {
                t.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + filename + "\"");
                t.getResponseHeaders().set("Content-Type", getMimeType(filename));
                t.sendResponseHeaders(200, contentBytes.length);
                
                OutputStream os = t.getResponseBody();
                os.write(contentBytes);
                os.close();
            } else {
                String response = "File tidak ditemukan.";
                t.sendResponseHeaders(404, response.length());
                OutputStream os = t.getResponseBody();
                os.write(response.getBytes(StandardCharsets.UTF_8));
                os.close();
            }
        }
    }
    
    private static void sendHtmlResponse(HttpExchange t, String html) throws IOException {
        t.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
        byte[] responseBytes = html.getBytes(StandardCharsets.UTF_8);
        t.sendResponseHeaders(200, responseBytes.length);
        OutputStream os = t.getResponseBody();
        os.write(responseBytes);
        os.close();
    }

    private static Map<String, String> parseQuery(String query) {
        Map<String, String> params = new HashMap<>();
        if (query != null) {
            for (String param : query.split("&")) {
                String[] pair = param.split("=");
                if (pair.length > 1) {
                    params.put(pair[0], pair[1]);
                } else {
                    params.put(pair[0], "");
                }
            }
        }
        return params;
    }

    // Menyimpan file dan kontennya
    public void addFile(String filename, byte[] content) {
        localFiles.add(filename);
        fileStorage.put(filename, content);
        System.out.println("File disimpan: " + filename + " (" + content.length + " bytes)");
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
    
    // === RESPONS IZIN SEKARANG MENGIRIM KONTEN FILE ===
    public void respondToPermission(String requesterId, String filename, boolean grant) {
        String type = grant ? "PERMISSION_GRANTED" : "PERMISSION_DENIED";
        String message;
        if (grant) {
            byte[] fileContent = getFileContent(filename);
            String base64Content = Base64.getEncoder().encodeToString(fileContent);
            message = String.format("%s::%s::%s::%s", type, this.peerId, filename, base64Content);
        } else {
            message = String.format("%s::%s::%s", type, this.peerId, filename);
        }
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
    
    public String getPeerId() { return peerId; }
    public String getHostIp() { return hostIp; }
    public int getP2pPort() { return p2pPort; }
    public Map<String, String> getDiscoveredPeers() { return discoveredPeers; }
    public Set<String> getLocalFiles() { return localFiles; }
    public byte[] getFileContent(String filename) { return fileStorage.get(filename); }
    public MyWebSocketServer getWsServer() { return wsServer; }
    public boolean hasFile(String filename) { return localFiles.contains(filename); }

    // Helper methods
    private static String getMimeType(String filename) {
        try {
            return Files.probeContentType(Paths.get(filename));
        } catch (IOException e) {
            return "application/octet-stream";
        }
    }
    private static int indexOf(byte[] source, byte[] match, int fromIndex) {
        for (int i = fromIndex; i <= source.length - match.length; i++) {
            if (source[i] == match[0]) {
                boolean found = true;
                for (int j = 1; j < match.length; j++) {
                    if (source[i + j] != match[j]) {
                        found = false;
                        break;
                    }
                }
                if (found) return i;
            }
        }
        return -1;
    }
    private static int lastIndexOf(byte[] source, byte[] match, int fromIndex) {
        for (int i = fromIndex - match.length; i >= 0; i--) {
            if (source[i] == match[0]) {
                boolean found = true;
                for (int j = 1; j < match.length; j++) {
                    if (source[i + j] != match[j]) {
                        found = false;
                        break;
                    }
                }
                if (found) return i;
            }
        }
        return -1;
    }

    private static String getLoginPageHtml() {
        return "<!DOCTYPE html>"
             + "<html lang='id'>"
             + "<head>"
             + "<meta charset='UTF-8'><title>Login P2P</title>"
             + "<script src='https://cdn.tailwindcss.com'></script>"
             + "</head>"
             + "<body class='bg-gray-100 flex items-center justify-center h-screen'>"
             + "<div class='w-full max-w-xs'>"
             + "<form action='/login' method='post' class='bg-white shadow-md rounded px-8 pt-6 pb-8 mb-4'>"
             + "<h1 class='text-center text-2xl font-bold mb-6'>Masuk ke Jaringan P2P</h1>"
             + "<div class='mb-4'>"
             + "<label class='block text-gray-700 text-sm font-bold mb-2' for='username'>Nama Anda</label>"
             + "<input class='shadow appearance-none border rounded w-full py-2 px-3 text-gray-700 leading-tight focus:outline-none focus:shadow-outline' id='username' name='username' type='text' placeholder='Masukkan nama...'>"
             + "</div>"
             + "<div class='flex items-center justify-between'>"
             + "<button class='bg-blue-500 hover:bg-blue-700 text-white font-bold py-2 px-4 rounded focus:outline-none focus:shadow-outline' type='submit'>Masuk</button>"
             + "</div>"
             + "</form>"
             + "</div>"
             + "</body></html>";
    }

    private static String getAppPageHtml() {
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
             + "<table class='w-full text-sm text-left text-gray-500'><thead class='text-xs text-gray-700 uppercase bg-gray-50'><tr><th scope='col' class='px-4 py-2'>Nama File</th><th scope='col' class='px-4 py-2'>Aksi</th></tr></thead><tbody id='localFilesTableBody'></tbody></table>"
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
             + "const localFilesTableBody = document.getElementById('localFilesTableBody');"
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
             + "  localFilesTableBody.innerHTML = ''; data.localFiles.forEach(f => { "
             + "    const row = `<tr><td class='px-4 py-2'>${f}</td><td class='px-4 py-2'><a href='/download?file=${encodeURIComponent(f)}' class='text-blue-600 hover:underline' download>Download</a></td></tr>`;"
             + "    localFilesTableBody.innerHTML += row;"
             + "  });"
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
            
            String[] parts = message.split("::", 4); // Diubah menjadi 4 untuk menangani konten
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
                // === PENANGANAN UNDUHAN ANTAR-PEER YANG SEBENARNYA ===
                case "PERMISSION_GRANTED":
                    node.getWsServer().logToUI("Izin DIBERIKAN oleh " + senderId + " untuk file '" + payload + "'.");
                    String base64Content = parts[3];
                    byte[] fileContent = Base64.getDecoder().decode(base64Content);
                    node.addFile(payload, fileContent);
                    node.getWsServer().logToUI("Unduhan '" + payload + "' dari peer lain selesai.");
                    node.getWsServer().broadcastState();
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
                        if (node.getPeerId() == null) { // Jangan broadcast sebelum login
                            Thread.sleep(1000);
                            continue;
                        }
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
                    if (node.getPeerId() != null && !peerId.equals(node.getPeerId()) && node.getDiscoveredPeers().put(peerId, address) == null) {
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
        if(node.getPeerId() != null) {
            broadcastState();
        }
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        System.out.println("Koneksi WebSocket ditutup: " + conn.getRemoteSocketAddress().getAddress().getHostAddress());
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        if (node.getPeerId() == null) return; // Abaikan pesan jika belum login
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
        if (node.getPeerId() == null) return;
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
        if (node.getPeerId() == null) return;
        JSONObject logMsg = new JSONObject();
        logMsg.put("type", "LOG");
        logMsg.put("message", message);
        broadcast(logMsg.toString());
    }
    
    public void foundFile(String ownerId, String filename) {
        if (node.getPeerId() == null) return;
        JSONObject msg = new JSONObject();
        msg.put("type", "FILE_FOUND");
        msg.put("ownerId", ownerId);
        msg.put("filename", filename);
        broadcast(msg.toString());
    }

    public void permissionRequested(String requesterId, String filename) {
        if (node.getPeerId() == null) return;
        JSONObject msg = new JSONObject();
        msg.put("type", "PERMISSION_REQUEST");
        msg.put("requesterId", requesterId);
        msg.put("filename", filename);
        broadcast(msg.toString());
    }
}
