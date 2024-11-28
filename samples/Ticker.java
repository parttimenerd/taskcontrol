/** Print "Tick n" every second */
public class Ticker {

    public static void main(String[] args) throws InterruptedException {
        int n = 0;
        while (true) {
            Thread.sleep(1000);
            System.out.println("Tick " + n++);
        }
    }
}
