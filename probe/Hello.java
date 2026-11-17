import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
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
        return runChecks();
    }

    static int runChecks() {
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
            check("ffm downcall", Hello::ffmDowncall);
            check("ffm varargs", Hello::ffmVarargs);
            check("ffm upcall", Hello::ffmUpcall);
            check("string format", () -> String.format("%.3f|%08x|%s", Math.PI, 0xCAFE, List.of(1, 2, 3)));
            System.out.println("smoke ok");
            return 0;
        } catch (Throwable t) {
            t.printStackTrace();
            return 1;
        }
    }

    interface Check {
        Object run() throws Throwable;
    }

    static void check(String name, Check check) throws Throwable {
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

    // The foreign functions' descriptors, registered by HelloFeature
    static final FunctionDescriptor STRLEN = FunctionDescriptor.of(JAVA_LONG, ADDRESS);
    static final FunctionDescriptor TICKS = FunctionDescriptor.of(JAVA_LONG);
    static final FunctionDescriptor SPRINTF = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_DOUBLE, JAVA_INT, JAVA_DOUBLE);
    static final Linker.Option SPRINTF_VARIADIC = Linker.Option.firstVariadicArg(2);
    static final FunctionDescriptor QSORT = FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG, JAVA_LONG, ADDRESS);
    static final FunctionDescriptor COMPARE = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS);

    static Object ffmDowncall() throws Throwable {
        Linker linker = Linker.nativeLinker();
        MethodHandle strlen = linker.downcallHandle(linker.defaultLookup().find("strlen").orElseThrow(), STRLEN);
        try (Arena arena = Arena.ofConfined()) {
            long length = (long) strlen.invokeExact(arena.allocateFrom("hello, foreign world"));
            if (length != 20) {
                throw new AssertionError("strlen = " + length);
            }
            MethodHandle ticks = linker.downcallHandle(SymbolLookup.libraryLookup("kernel32.dll", arena).find("GetTickCount64").orElseThrow(), TICKS);
            return "strlen " + length + ", GetTickCount64 " + ((long) ticks.invokeExact() > 0);
        }
    }

    static Object ffmVarargs() throws Throwable {
        Linker linker = Linker.nativeLinker();
        try (Arena arena = Arena.ofConfined()) {
            // Windows on ARM64 passes variadic floating-point arguments in general purpose registers
            MethodHandle sprintf = linker.downcallHandle(SymbolLookup.libraryLookup("msvcrt.dll", arena).find("sprintf").orElseThrow(), SPRINTF, SPRINTF_VARIADIC);
            MemorySegment buffer = arena.allocate(128);
            int n = (int) sprintf.invokeExact(buffer, arena.allocateFrom("%.3f %d %.1f"), Math.PI, 42, -2.5);
            String result = buffer.getString(0);
            if (!result.equals("3.142 42 -2.5") || n != result.length()) {
                throw new AssertionError("sprintf = '" + result + "' (" + n + ")");
            }
            return result;
        }
    }

    static int compare(MemorySegment a, MemorySegment b) {
        return Integer.compare(a.reinterpret(4).get(JAVA_INT, 0), b.reinterpret(4).get(JAVA_INT, 0));
    }

    static Object ffmUpcall() throws Throwable {
        Linker linker = Linker.nativeLinker();
        try (Arena arena = Arena.ofConfined()) {
            MethodHandle qsort = linker.downcallHandle(SymbolLookup.libraryLookup("msvcrt.dll", arena).find("qsort").orElseThrow(), QSORT);
            MethodHandle compare = MethodHandles.lookup().findStatic(Hello.class, "compare", MethodType.methodType(int.class, MemorySegment.class, MemorySegment.class));
            MemorySegment comparator = linker.upcallStub(compare, COMPARE, arena);
            int[] values = {9, -3, 7, 0, 42, 5, -11, 8};
            MemorySegment array = arena.allocateFrom(JAVA_INT, values);
            qsort.invokeExact(array, (long) values.length, 4L, comparator);
            int[] sorted = array.toArray(JAVA_INT);
            if (!java.util.Arrays.equals(sorted, new int[]{-11, -3, 0, 5, 7, 8, 9, 42})) {
                throw new AssertionError(java.util.Arrays.toString(sorted));
            }
            return java.util.Arrays.toString(sorted);
        }
    }

    public static void main(String[] args) {
        System.exit(runChecks());
    }
}
