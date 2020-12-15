import Handlers.ClientHandler;
import Handlers.MainHandler;
import Handlers.ResolveHandler;
import Handlers.ServerHandler;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.channels.*;
import java.nio.channels.spi.SelectorProvider;
import java.util.ArrayList;

public class SOCKS5Server {
    private Selector selector;
    private ResolveHandler resolver;

    public SOCKS5Server(int port) {
        try {
            ServerSocketChannel mainSocket = ServerSocketChannel.open();
            mainSocket.bind(new InetSocketAddress(InetAddress.getLocalHost(), port));
            mainSocket.configureBlocking(false);
            selector = SelectorProvider.provider().openSelector();
            mainSocket.register(selector, mainSocket.validOps(), new MainHandler());
            System.out.println(mainSocket.getLocalAddress());

            DatagramChannel dnsSocket = DatagramChannel.open();
            dnsSocket.configureBlocking(false);
            SelectionKey key = dnsSocket.register(selector, 0);
            resolver = new ResolveHandler(key);
            key.attach(resolver);
        }
        catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void run() {
        while (true) {
            try {
                if (!(selector.select() > -1)) break;
                ArrayList<SelectionKey> selectionKeysToRemove = new ArrayList<>();
                for (SelectionKey key : selector.selectedKeys()) {
                    selectionKeysToRemove.add(key);
                    if(!key.isValid()) {
                        continue;
                    }
                    Object attachment = key.attachment();
                    if(attachment instanceof MainHandler) {
                        if(key.isAcceptable()) {
                            accept(key);
                        }
                    }
                    else if(attachment instanceof ClientHandler) {
                        try {
                            processClient(key);
                        }
                        catch (IOException ioe) {
                            //ioe.printStackTrace();
                            ((ClientHandler) attachment).close();
                        }
                    }
                    else if(attachment instanceof ServerHandler) {
                        try {
                            processServer(key);
                        }
                        catch (IOException ioe) {
                            //ioe.printStackTrace();
                            ((ServerHandler) attachment).close();
                        }
                    }
                    else if(attachment instanceof ResolveHandler) {
                        try {
                            processResolve(key);
                        }
                        catch (IOException ioe) {
                            //ioe.printStackTrace();
                        }
                    }
                }
                selector.selectedKeys().removeAll(selectionKeysToRemove);
            }
            catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private void accept(SelectionKey key) throws IOException {
        SocketChannel channel = ((ServerSocketChannel)key.channel()).accept();
        channel.configureBlocking(false);
        SelectionKey newKey = channel.register(selector, SelectionKey.OP_READ);
        newKey.attach(new ClientHandler(newKey, resolver));
    }

    private void processClient(SelectionKey clientKey) throws IOException {
        ClientHandler clientHandler = (ClientHandler) clientKey.attachment();
        int state = clientHandler.getState();
        if(state == ClientHandler.WAIT_CL_AUTH || state == ClientHandler.WAIT_CL_RQ) {
            clientHandler.read();
        }
        else if(state == ClientHandler.SEND_CL_AUTH || state == ClientHandler.SEND_CL_RESP || state == ClientHandler.SEND_ERR) {
            clientHandler.write();
        }
        else if(state == ClientHandler.FORWARDING && clientKey.isReadable()) {
            clientHandler.input();
        }
        else if(state == ClientHandler.FORWARDING && clientKey.isWritable()) {
            clientHandler.output();
        }
        clientHandler.nextState();
    }

    private void processServer(SelectionKey serverKey) throws IOException {
        ServerHandler serverHandler = (ServerHandler) serverKey.attachment();
        int state = serverHandler.getState();
        if(state == ServerHandler.FORWARDING) {
            if(serverKey.isReadable()) {
                serverHandler.input();
            }
            else if(serverKey.isWritable()) {
                serverHandler.output();
            }
        }
        serverHandler.nextState();
    }

    private void processResolve(SelectionKey resolveKey) throws IOException {
        ResolveHandler resolveHandler = (ResolveHandler) resolveKey.attachment();
        if(resolveKey.isReadable()) {
            resolveHandler.receiveResolve();
        }
        else if(resolveKey.isWritable()) {
            resolveHandler.sendResolve();
        }
    }
}