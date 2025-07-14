import java.io.*;
import java.net.*;
import java.util.concurrent.*;

public class ShipProxy {
    private static final int LOCAL_PORT = 8080;
    private final String offshoreHost;
    private final int offshorePort;

    public ShipProxy(String offshoreHost, int offshorePort) {
        this.offshoreHost = offshoreHost;
        this.offshorePort = offshorePort;
    }

    public void start() throws IOException {
        ServerSocket serverSocket = new ServerSocket(LOCAL_PORT);
        System.out.println("Ship Proxy listening on port " + LOCAL_PORT);

        Socket offshoreSocket = new Socket(offshoreHost, offshorePort);
        System.out.println("Connected to Offshore Proxy Server at " + offshoreHost + ":" + offshorePort);

        // For Sequential requests
        BlockingQueue<ProxyTask> queue = new LinkedBlockingQueue<>();

        // Process requests sequentially
        new Thread(() -> {
            try {
                DataOutputStream offshoreOut = new DataOutputStream(offshoreSocket.getOutputStream());
                DataInputStream offshoreIn = new DataInputStream(offshoreSocket.getInputStream());
                while (true) {
                    ProxyTask task = queue.take();
                    if (task.isConnectMethod()) {
                        // Handle HTTPS CONNECT
                        String destHost = task.getConnectHost();
                        int destPort = task.getConnectPort();
                        
                        offshoreOut.writeUTF("CONNECT " + destHost + " " + destPort);
                        offshoreOut.flush();
                        String offshoreResp = offshoreIn.readUTF();
                        OutputStream clientOut = task.clientSocket.getOutputStream();
                        if ("OK".equals(offshoreResp)) {
                            
                            clientOut.write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes());
                            clientOut.flush();
                            
                            tunnel(task.clientSocket, offshoreSocket, offshoreIn, offshoreOut);
                        } else {
                            clientOut.write("HTTP/1.1 502 Bad Gateway\r\n\r\n".getBytes());
                            clientOut.flush();
                        }
                        task.clientSocket.close();
                    } else {
                        // For regular HTTP
                        ByteArrayOutputStream baos = new ByteArrayOutputStream();
                        task.copyRequestTo(baos);
                        byte[] requestBytes = baos.toByteArray();
                        offshoreOut.writeUTF("HTTP");
                        offshoreOut.writeInt(requestBytes.length);
                        offshoreOut.write(requestBytes);
                        offshoreOut.flush();

                        int respLen = offshoreIn.readInt();
                        byte[] respBytes = new byte[respLen];
                        offshoreIn.readFully(respBytes);

                        task.sendResponse(respBytes);
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();

        while (true) {
            Socket clientSocket = serverSocket.accept();
            new Thread(() -> {
                try {
                    ProxyTask task = new ProxyTask(clientSocket);
                    queue.put(task);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }).start();
        }
    }

    // Helper class for proxy request/response
    static class ProxyTask {
        private final Socket clientSocket;
        private final ByteArrayOutputStream requestBuffer = new ByteArrayOutputStream();
        private String connectHost = null;
        private int connectPort = -1;
        private boolean isConnect = false;

        ProxyTask(Socket clientSocket) throws IOException {
            this.clientSocket = clientSocket;
            InputStream in = clientSocket.getInputStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(in));
            String firstLine = reader.readLine();
            if (firstLine != null && firstLine.startsWith("CONNECT")) {
                isConnect = true;
                String[] parts = firstLine.split(" ");
                String[] hostPort = parts[1].split(":");
                connectHost = hostPort[0];
                connectPort = Integer.parseInt(hostPort[1]);
            }
            requestBuffer.write((firstLine + "\r\n").getBytes());
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                requestBuffer.write((line + "\r\n").getBytes());
            }
            requestBuffer.write("\r\n".getBytes());
        }

        boolean isConnectMethod() {
            return isConnect;
        }

        String getConnectHost() {
            return connectHost;
        }

        int getConnectPort() {
            return connectPort;
        }

        void copyRequestTo(OutputStream out) throws IOException {
            out.write(requestBuffer.toByteArray());
        }

        void sendResponse(byte[] response) throws IOException {
            OutputStream out = clientSocket.getOutputStream();
            out.write(response);
            out.flush();
            clientSocket.close();
        }
    }

    // Tunnel method for HTTPS
    private void tunnel(Socket clientSocket, Socket offshoreSocket, DataInputStream offshoreIn, DataOutputStream offshoreOut) {
        try {
            InputStream clientIn = clientSocket.getInputStream();
            OutputStream clientOut = clientSocket.getOutputStream();
            OutputStream offshoreDataOut = offshoreSocket.getOutputStream();
            InputStream offshoreDataIn = offshoreSocket.getInputStream();

            Thread t1 = new Thread(() -> {
                try {
                    byte[] buf = new byte[4096];
                    int len;
                    while ((len = clientIn.read(buf)) != -1) {
                        offshoreDataOut.write(buf, 0, len);
                        offshoreDataOut.flush();
                    }
                } catch (IOException ignored) {}
            });
            Thread t2 = new Thread(() -> {
                try {
                    byte[] buf = new byte[4096];
                    int len;
                    while ((len = offshoreDataIn.read(buf)) != -1) {
                        clientOut.write(buf, 0, len);
                        clientOut.flush();
                    }
                } catch (IOException ignored) {}
            });
            t1.start();
            t2.start();
            t1.join();
            t2.join();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("offshore-host offshore-port");
            return;
        }
        new ShipProxy(args[0], Integer.parseInt(args[1])).start();
    }
}
