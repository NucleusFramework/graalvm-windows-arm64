import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.graalvm.nativeimage.IsolateThread;
import org.graalvm.nativeimage.c.function.CEntryPoint;

/** Exercises what differs on Windows on ARM64: exceptions, stack overflows, faults, the GC, threads, the JDK's natives. */
public final class Hello {
    @CEntryPoint(name = "hello_run")
    static int run(IsolateThread thread) {
        try {
            System.out.println("hello from " + System.getProperty("os.name") + " " + System.getProperty("os.arch"));
            check("exceptions", Hello::exceptions);
            check("stack overflow", Hello::stackOverflow);
            check("null pointer", Hello::nullPointer);
            check("arithmetic", Hello::arithmetic);
            check("gc", Hello::gc);
            check("threads", Hello::threads);
            check("time zones", () -> ZonedDateTime.now(ZoneId.of("Asia/Jerusalem")).getZone().getId());
            check("files", Hello::files);
            check("string format", () -> String.format("%.3f|%08x|%s", Math.PI, 0xCAFE, List.of(1, 2, 3)));
            System.out.println("smoke ok");
            return 0;
        } catch (Throwable t) {
            t.printStackTrace();
            return 1;
        }
    }

    interface Check {
        Object run() throws Exception;
    }

    static void check(String name, Check check) throws Exception {
        long start = System.nanoTime();
        Object result = check.run();
        System.out.printf("  %-14s %s (%d ms)%n", name, result, (System.nanoTime() - start) / 1_000_000);
    }

    static Object exceptions() {
        int caught = 0;
        for (int i = 0; i < 10_000; i++) {
            try {
                deep(20, i);
            } catch (IllegalStateException e) {
                caught += e.getStackTrace().length > 0 ? 1 : 0;
            }
        }
        if (caught != 10_000) {
            throw new AssertionError("caught " + caught);
        }
        return caught;
    }

    static int deep(int depth, int i) {
        if (depth == 0) {
            throw new IllegalStateException("at " + i);
        }
        return deep(depth - 1, i) + 1;
    }

    static Object stackOverflow() {
        try {
            recurse(0);
            throw new AssertionError("no StackOverflowError");
        } catch (StackOverflowError e) {
            return "caught";
        }
    }

    static int recurse(int n) {
        return recurse(n + 1) + 1;
    }

    static Object nullPointer() {
        Object[] holder = {null};
        try {
            return holder[0].hashCode();
        } catch (NullPointerException e) {
            return "caught";
        }
    }

    static Object arithmetic() {
        int zero = Integer.parseInt("0");
        try {
            return 1 / zero;
        } catch (ArithmeticException e) {
            return "caught, " + Long.MAX_VALUE / 3 + ", " + Math.sqrt(2) + ", " + Double.toString(1e-300 * 1e-300);
        }
    }

    static Object gc() {
        long total = 0;
        List<byte[]> retained = new ArrayList<>();
        for (int i = 0; i < 20_000; i++) {
            byte[] chunk = new byte[64 * 1024];
            chunk[i % chunk.length] = (byte) i;
            total += chunk.length;
            if (i % 100 == 0) {
                retained.add(chunk);
            }
        }
        System.gc();
        return total / (1024 * 1024) + " MB allocated, " + retained.size() + " retained";
    }

    static Object threads() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<Long>> futures = new ArrayList<>();
            for (int t = 0; t < 32; t++) {
                int seed = t;
                futures.add(pool.submit(() -> {
                    long sum = 0;
                    for (int i = 0; i < 2_000_000; i++) {
                        sum += (i ^ seed) % 7;
                    }
                    return sum;
                }));
            }
            long sum = 0;
            for (Future<Long> f : futures) {
                sum += f.get();
            }
            return sum;
        } finally {
            pool.shutdown();
        }
    }

    static Object files() throws IOException {
        Path dir = Files.createTempDirectory("hello");
        Path file = dir.resolve("data.txt");
        Files.writeString(file, "x".repeat(100_000));
        long size = Files.size(file);
        String back = Files.readString(file);
        Files.delete(file);
        Files.delete(dir);
        if (back.length() != size) {
            throw new AssertionError(back.length() + " != " + size);
        }
        return size + " bytes";
    }

    public static void main(String[] args) {
        System.exit(run(null));
    }
}
