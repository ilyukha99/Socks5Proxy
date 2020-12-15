package Handlers;

import org.xbill.DNS.*;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.util.HashMap;
import java.util.LinkedList;

public class ResolveHandler {
    private static final int DNS_PORT = 53;
    private static final int MAX_REQUEST = 65535;
    private static String dnsServer = ResolverConfig.getCurrentConfig().servers()[0];

    private int requestIndex = 0;
    private LinkedList<Resolve> resolvesToSend = new LinkedList<>();
    private HashMap<Integer, Resolve> sentResolves = new HashMap<>();

    private SelectionKey key;
    private DatagramChannel channel;
    private ByteBuffer buffer = ByteBuffer.allocate(MainHandler.BUFFER_SIZE);

    public ResolveHandler(SelectionKey key) {
        this.key = key;
        channel = (DatagramChannel) key.channel();
        key.interestOps(SelectionKey.OP_READ);
        System.out.println("DNS server: " + dnsServer);
    }

    private int getNextRequestID() {
        if(requestIndex > MAX_REQUEST) {
            requestIndex = 0;
        }
        return requestIndex++;
    }

    public void addRequest(String address, ClientHandler clientHandler) {
        key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
        resolvesToSend.add(new Resolve(address, clientHandler));
    }

    public void sendResolve() throws IOException {
        if(resolvesToSend.isEmpty()) {
            key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE);
            return;
        }
        Resolve resolve = resolvesToSend.pop();
        int requestID = getNextRequestID();
        sentResolves.put(requestID, resolve);

        Message message = new Message();
        Header header = message.getHeader();
        header.setOpcode(Opcode.QUERY);
        header.setID(requestID);
        header.setRcode(Rcode.NOERROR);
        header.setFlag(Flags.RD);
        message.addRecord(Record.newRecord(new Name(resolve.getAddress() + "."), Type.A, DClass.IN), Section.QUESTION);

        byte[] messageData = message.toWire();
        ByteBuffer byteBuffer = ByteBuffer.wrap(messageData);
        channel.send(byteBuffer, new InetSocketAddress(dnsServer, DNS_PORT));
    }

    public void receiveResolve() throws IOException {
        buffer.clear();
        channel.receive(buffer);
        buffer.flip();

        Message message = new Message(buffer.array());
        int requestID = message.getHeader().getID();
        if(!sentResolves.containsKey(requestID)) {
            return;
        }
        Record[] questions = message.getSectionArray(Section.QUESTION);
        Record[] answers = message.getSectionArray(Section.ANSWER);
        if(questions.length > 1) {
            return;
        }
        ARecord aRecord = null;
        for(Record answer : answers) {
            if(answer instanceof ARecord) {
                aRecord = (ARecord) answer;
                break;
            }
        }
        InetAddress address = aRecord.getAddress();
        Resolve resolve = sentResolves.get(requestID);
        resolve.getClientHandler().dnsConnect(address);
        sentResolves.remove(requestID);
    }
}