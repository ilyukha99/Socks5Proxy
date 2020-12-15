package Handlers;

import java.io.EOFException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;

public class ClientHandler {
    /* STATES */
    public static final int WAIT_CL_AUTH = 1;
    public static final int SEND_CL_AUTH = 2;
    public static final int WAIT_CL_RQ = 3;
    public static final int SEND_CL_RESP = 4;
    public static final int FORWARDING = 5;
    public static final int SEND_ERR = 6;

    private static final byte AUTH_NO = 0x00;
    private static final byte AUTH_NOTFOUND = (byte) 0xFF;

    private static final byte SOCKS5 = 0x05;
    private static final byte ADDR_IPV4 = 0x01;
    private static final byte ADDR_DOMAIN = 0x03;
    private static final byte CMD_TCP_CONN = 0x01;

    public static final byte ERR_SUCCESS = 0x00;
    private static final byte ERR_HOST_UNREACHABLE = 0x04;
    private static final byte ERR_CMD_NOT_SUP = 0x07;
    private static final byte ERR_ADDR_TYPE_NOT_SUP = 0x08;

    private static final byte RESERVED_BYTE = 0x00;

    private SelectionKey key, server;
    private ByteBuffer buffer = ByteBuffer.allocate(MainHandler.BUFFER_SIZE);
    private int state;
    private int savedPort;
    private ResolveHandler resolver;
    private SocketChannel channel;
    private boolean eof = false;

    public ClientHandler(SelectionKey key, ResolveHandler resolver) {
        this.key = key;
        this.state = WAIT_CL_AUTH;
        this.resolver = resolver;
        this.channel = (SocketChannel) key.channel();
    }

    public void read() throws IOException {
        channel.read(buffer);
    }

    public void write() throws IOException {
        channel.write(buffer);
    }

    public void input() throws IOException {
        buffer.clear();
        int readBytes = channel.read(buffer);
        if(readBytes == -1) {
            eof = true;
            ServerHandler serverHandler = (ServerHandler) server.attachment();
            serverHandler.shutdown();
            key.interestOps(key.interestOps() & ~SelectionKey.OP_READ);
            if(serverHandler.isEof()) {
                channel.close();
                server.channel().close();
            }
            return;
        }
        buffer.flip();
        key.interestOps(key.interestOps() & ~SelectionKey.OP_READ);
        server.interestOps(server.interestOps() | SelectionKey.OP_WRITE);
    }

    public void output() throws IOException {
        ServerHandler serverHandler = (ServerHandler) server.attachment();
        channel.write(serverHandler.getBuffer());
        if(!serverHandler.getBuffer().hasRemaining()) {
            key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE);
            server.interestOps(server.interestOps() | SelectionKey.OP_READ);
        }
    }

    public void nextState() throws IOException {
        if(state == WAIT_CL_AUTH) {
            processAuthMethods();
        }
        else if(state == WAIT_CL_RQ) {
            processRequest();
        }
        else if(state == SEND_CL_AUTH && !buffer.hasRemaining()) {
            state = WAIT_CL_RQ;
            buffer.clear();
        }
        else if(state == SEND_CL_RESP && !buffer.hasRemaining()) {
            state = FORWARDING;
            key.interestOps(SelectionKey.OP_READ);
        }
        else if(state == SEND_ERR && !buffer.hasRemaining()) {
            key.channel().close();
            if(server != null) {
                server.channel().close();
            }
            buffer.clear();
        }
    }

    private void processAuthMethods() {
        int methodsCount = buffer.get(1);
        byte method = AUTH_NOTFOUND;
        for(int i = 0; i < methodsCount; i++) {
            byte currentMethod = buffer.get(i + 2);
            if(currentMethod == AUTH_NO) {
                method = currentMethod;
            }
        }
        buffer.clear();
        buffer.put(SOCKS5);
        buffer.put(method);
        buffer.flip();
        key.interestOps(SelectionKey.OP_WRITE);
        if(method == AUTH_NOTFOUND) {
            state = SEND_ERR;
        }
        else {
            state = SEND_CL_AUTH;
        }
    }

    private void processRequest() throws IOException {
        int bufferSize = buffer.position();
        if(bufferSize < 4) {
            return;
        }
        byte cmd = buffer.get(1);
        if(cmd != CMD_TCP_CONN) {
            sendStatus(ERR_CMD_NOT_SUP);
            return;
        }
        byte addressType = buffer.get(3);
        if(addressType == ADDR_IPV4) {
            if(bufferSize < 10) {
                return;
            }
            byte[] address = new byte[4];
            buffer.position(4);
            buffer.get(address);
            int port = buffer.getShort(8);
            InetAddress inetAddress = InetAddress.getByAddress(address);
            connectHost(inetAddress, port);
            key.interestOps(0);
            state = FORWARDING;
        }
        else if(addressType == ADDR_DOMAIN) {
            int addressLength = buffer.get(4);
            if(bufferSize < 6 + addressLength) {
                return;
            }
            byte[] address = new byte[addressLength];
            buffer.position(5);
            buffer.get(address, 0, addressLength);
            String addressStr = new String(address);

            key.interestOps(0);
            state = FORWARDING;
            resolver.addRequest(addressStr, this);
            savedPort = buffer.getShort(5 + addressLength);
        }
        else {
            sendStatus(ERR_ADDR_TYPE_NOT_SUP);
        }
    }

    private void connectHost(InetAddress inetAddress, int port) throws IOException {
        SocketChannel serverChannel = SocketChannel.open();
        serverChannel.configureBlocking(false);
        server = serverChannel.register(key.selector(), SelectionKey.OP_CONNECT);
        server.attach(new ServerHandler(server, key));
        serverChannel.connect(new InetSocketAddress(inetAddress, port));
    }

    void dnsConnect(InetAddress inetAddress) {
        if(inetAddress == null) {
            sendStatus(ERR_HOST_UNREACHABLE);
        }
        else {
            try {
                connectHost(inetAddress, savedPort);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public void close() throws IOException {
        key.channel().close();
        if(server != null) {
            server.channel().close();
        }
    }

    public ByteBuffer getBuffer() {
        return buffer;
    }

    public int getState() {
        return state;
    }

    public boolean isEof() {
        return eof;
    }

    public void shutdown() throws IOException {
        channel.shutdownOutput();
    }

    public void sendStatus(byte error) {
        buffer.clear();
        buffer.put(SOCKS5);
        buffer.put(error);
        buffer.put(RESERVED_BYTE);
        buffer.put(ADDR_IPV4);
        for(int i = 0; i < 4 + 2; i++) {
            buffer.put(RESERVED_BYTE);
        }
        buffer.flip();
        state = (error == ERR_SUCCESS) ? SEND_CL_RESP : SEND_ERR;
        key.interestOps(SelectionKey.OP_WRITE);
    }
}
