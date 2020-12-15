public class Main {
    public static void main(String[] args) {
        SOCKS5Server server = new SOCKS5Server(Integer.parseInt(args[0]));
        server.run();
    }
}

