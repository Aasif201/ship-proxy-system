import java.io.*;
import java.net.*;

public class OffshoreProxyServer {
    private static final int SERVER_PORT = 9090;

    public void start() throws IOException {
        ServerSocket serverSocket = new ServerSocket(SERVER_PORT);
        System.out.println("Offshore Proxy Server listening on port " + SERVER_PORT);

        Socket shipSocket = serverSocket.accept();
        System.out.println("Accepted connection from Ship Proxy");

        DataInputStream in = new DataInputStream(shipSocket.getInputStream());
        DataOutputStream out = new DataOutputStream(shipSocket.getOutputStream());

        while (true) {
            String type;
            try {
                type = in.readUTF();
            } catch (EOFException e) {
                break;
            }
            if ("HTTP".equals(type)) {
                // Handle HTTP
                int reqLen = in.readInt();
                byte[] reqBytes = new byte[reqLen];
                in.readFully(reqBytes);

                // Parse HTTP request to get host and port
                String reqStr = new String(reqBytes);
                String host = null;
                int port = 80;
                for (String line : reqStr.split("\r\n")) {
                    if (line.toLowerCase().startsWith("host:")) {
                        host = line.split(":", 2)[1].trim();
                        if (host.contains(":")) {
                            String[] parts = host.split(":");
                            host = parts[0];
                            port = Integer.parseInt(parts[1]);
                        }
                        break;
                    }
                }
                if (host == null) {
                    out.writeInt(0);
                    continue;
                }

                try (Socket destSocket = new Socket(host, port)) {
                    OutputStream destOut = destSocket.getOutputStream();
                    destOut.write(reqBytes);
                    destOut.flush();

                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    InputStream destIn = destSocket.getInputStream();
                    byte[] buffer = new byte[4096];
                    int read;
                    while ((read = destIn.read(buffer)) != -1) {
                        baos.write(buffer, 0, read);
                        if (read < buffer.length) break;
                    }
                    byte[] respBytes = baos.toByteArray();

                    out.writeInt(respBytes.length);
                    out.write(respBytes);
                    out.flush();
                } catch (Exception e) {
                    out.writeInt(0);
                }
            } else if (type.startsWith("CONNECT")) {
                // Handle HTTPS tunnel
                String[] parts = type.split(" ");
                String destHost = parts[1];
                int destPort = Integer.parseInt(parts[2]);
                try {
                    Socket destSocket = new Socket(destHost, destPort);
                    out.writeUTF("OK");
                    out.flush();

                    tunnel(shipSocket, destSocket);
                } catch (Exception e) {
                    out.writeUTF("FAIL");
                    out.flush();
                    e.printStackTrace();
                }
            }
        }
    }

    // Tunnel method for HTTPS
    private void tunnel(Socket shipSocket, Socket destSocket) {
        try {
            InputStream shipIn = shipSocket.getInputStream();
            OutputStream shipOut = shipSocket.getOutputStream();
            InputStream destIn = destSocket.getInputStream();
            OutputStream destOut = destSocket.getOutputStream();

            Thread t1 = new Thread(() -> {
                try {
                    byte[] buf = new byte[4096];
                    int len;
                    while ((len = shipIn.read(buf)) != -1) {
                        destOut.write(buf, 0, len);
                        destOut.flush();
                    }
                } catch (IOException ignored) {}
            });
            Thread t2 = new Thread(() -> {
                try {
                    byte[] buf = new byte[4096];
                    int len;
                    while ((len = destIn.read(buf)) != -1) {
                        shipOut.write(buf, 0, len);
                        shipOut.flush();
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
        new OffshoreProxyServer().start();
    }
}
