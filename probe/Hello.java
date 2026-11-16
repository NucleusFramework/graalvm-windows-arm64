import org.graalvm.nativeimage.IsolateThread;
import org.graalvm.nativeimage.c.function.CEntryPoint;

public final class Hello {
    @CEntryPoint(name = "hello_run")
    static int run(IsolateThread thread) {
        System.out.println("hello from " + System.getProperty("os.name") + " " + System.getProperty("os.arch"));
        System.out.println("smoke ok");
        return 0;
    }

    public static void main(String[] args) {
        run(null);
    }
}
