package Handlers;

public class Resolve {
    private String address;
    private ClientHandler clientHandler;

    Resolve(String address, ClientHandler clientHandler) {
        this.address = address;
        this.clientHandler = clientHandler;
    }

    String getAddress() {
        return address;
    }

    ClientHandler getClientHandler() {
        return clientHandler;
    }
}
