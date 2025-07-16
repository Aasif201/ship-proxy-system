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
                int reqLen = in.readInt();
                byte[] reqBytes = new byte[reqLen];
                in.readFully(reqBytes);

                // Parse HTTP request to extract host/port
                String reqStr = new String(reqBytes, "ISO-8859-1");
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

                    InputStream destIn = destSocket.getInputStream();
                    ByteArrayOutputStream response = new ByteArrayOutputStream();
                    byte[] headerBuf = new byte[8192];
                    int headerLen = 0;
                    boolean headerEnd = false;

                    // Read headers
                    while (!headerEnd) {
                        int b = destIn.read();
                        if (b == -1) break;
                        headerBuf[headerLen++] = (byte)b;
                        if (headerLen >= 4 &&
                            headerBuf[headerLen-4] == '\r' &&
                            headerBuf[headerLen-3] == '\n' &&
                            headerBuf[headerLen-2] == '\r' &&
                            headerBuf[headerLen-1] == '\n') {
                            headerEnd = true;
                        }
                        if (headerLen == headerBuf.length) break; // very large or malformed headers!
                    }
                    response.write(headerBuf, 0, headerLen);

                    String headerStr = new String(headerBuf, 0, headerLen, "ISO-8859-1");
                    int contentLength = -1;
                    boolean chunked = false;
                    for (String line : headerStr.split("\r\n")) {
                        if (line.toLowerCase().startsWith("content-length:")) {
                            contentLength = Integer.parseInt(line.substring(15).trim());
                        }
                        if (line.toLowerCase().startsWith("transfer-encoding:") &&
                            line.toLowerCase().contains("chunked")) {
                            chunked = true;
                        }
                    }

                    // Read body as per headers
                    if (contentLength >= 0) {
                        byte[] buf = new byte[4096];
                        int read = 0;
                        while (read < contentLength) {
                            int n = destIn.read(buf, 0, Math.min(buf.length, contentLength-read));
                            if (n == -1) break;
                            response.write(buf, 0, n);
                            read += n;
                        }
                    } else if (chunked) {
                        ByteArrayOutputStream chunkedData = new ByteArrayOutputStream();
                        BufferedReader reader = new BufferedReader(new InputStreamReader(destIn, "ISO-8859-1"));
                        while (true) {
                            String chunkSizeLine = reader.readLine();
                            if (chunkSizeLine == null) break;
                            int chunkSize = Integer.parseInt(chunkSizeLine.trim(), 16);
                            response.write((chunkSizeLine + "\r\n").getBytes("ISO-8859-1"));
                            if (chunkSize == 0) {
                                // End of chunks
                                String trailer;
                                // Read and forward trailing headers
                                while ((trailer = reader.readLine()) != null && !trailer.isEmpty()) {
                                    response.write((trailer + "\r\n").getBytes("ISO-8859-1"));
                                }
                                response.write("\r\n".getBytes("ISO-8859-1"));
                                break;
                            }
                            int got = 0;
                            while (got < chunkSize) {
                                int needed = chunkSize - got;
                                char[] buf = new char[needed];
                                int n = reader.read(buf, 0, needed);
                                if (n == -1) break;
                                response.write(new String(buf, 0, n).getBytes("ISO-8859-1"));
                                got += n;
                            }
                            // Read and write chunk ending \r\n
                            String ending = reader.readLine();
                            response.write((ending + "\r\n").getBytes("ISO-8859-1"));
                        }
                    } else {
                        // Fallback: read until end of stream
                        byte[] buf = new byte[4096];
                        int n;
                        while ((n = destIn.read(buf)) != -1) {
                            response.write(buf, 0, n);
                        }
                    }

                    byte[] respBytes = response.toByteArray();
                    out.writeInt(respBytes.length);
                    out.write(respBytes);
                    out.flush();
                } catch (Exception e) {
                    out.writeInt(0);
                }
            } else if (type.startsWith("CONNECT")) {
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
                }
            }
        }
    }

    private void tunnel(Socket shipSocket, Socket destSocket) {
        try {
            Thread t1 = new Thread(() -> forward(shipSocket, destSocket));
            Thread t2 = new Thread(() -> forward(destSocket, shipSocket));
            t1.start();
            t2.start();
            t1.join();
            t2.join();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void forward(Socket inSocket, Socket outSocket) {
        try (
            InputStream in = inSocket.getInputStream();
            OutputStream out = outSocket.getOutputStream()
        ) {
            byte[] buf = new byte[4096];
            int len;
            while ((len = in.read(buf)) != -1) {
                out.write(buf, 0, len);
                out.flush();
            }
        } catch (IOException ignored) {}
    }

    public static void main(String[] args) throws IOException {
        new OffshoreProxyServer().start();
    }
}
