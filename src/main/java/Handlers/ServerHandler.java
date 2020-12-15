package Handlers;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;

public class ServerHandler {
    public static final int CONNECTING = 1;
    public static final int FORWARDING = 2;

    private ByteBuffer buffer = ByteBuffer.allocate(MainHandler.BUFFER_SIZE);
    private int state;
    private SelectionKey server, client;
    private SocketChannel channel;
    private boolean eof = false;

    public ServerHandler(SelectionKey server, SelectionKey client) {
        this.server = server;
        this.client = client;
        state = CONNECTING;
        channel = (SocketChannel) server.channel();
    }

    public int getState() {
        return state;
    }

    public void input() throws IOException {
        buffer.clear();
        int readBytes = channel.read(buffer);
        if(readBytes == -1) {
            eof = true;
            ClientHandler clientHandler = (ClientHandler) client.attachment();
            clientHandler.shutdown();
            server.interestOps(server.interestOps() & ~SelectionKey.OP_READ);
            if(clientHandler.isEof()) {
                server.channel().close();
                client.channel().close();
            }
            return;
        }
        buffer.flip();
        server.interestOps(server.interestOps() & ~SelectionKey.OP_READ);
        client.interestOps(client.interestOps() | SelectionKey.OP_WRITE);
    }

    public void output() throws IOException {
        ClientHandler clientHandler = (ClientHandler) client.attachment();
        channel.write(clientHandler.getBuffer());
        if(!clientHandler.getBuffer().hasRemaining()) {
            server.interestOps(server.interestOps() & ~SelectionKey.OP_WRITE);
            client.interestOps(client.interestOps() | SelectionKey.OP_READ);
        }
    }

    public void nextState() throws IOException {
        if(state == ServerHandler.CONNECTING && server.isConnectable()) {
            if(!channel.finishConnect()) {
                throw new IOException();
            }
            state = FORWARDING;
            ((ClientHandler)client.attachment()).sendStatus(ClientHandler.ERR_SUCCESS);
            server.interestOps(SelectionKey.OP_READ);
            buffer.clear();
            buffer.flip();
        }
    }

    public ByteBuffer getBuffer() {
        return buffer;
    }

    public void shutdown() throws IOException {
        channel.shutdownOutput();
    }

    public boolean isEof() {
        return eof;
    }

    public void close() throws IOException {
        server.channel().close();
        client.channel().close();
    }
}
