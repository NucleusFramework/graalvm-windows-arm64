import org.graalvm.nativeimage.hosted.Feature;
import org.graalvm.nativeimage.hosted.RuntimeForeignAccess;

/** Registers the probe's foreign downcalls and upcalls. */
public final class HelloFeature implements Feature {
    @Override
    public void duringSetup(DuringSetupAccess access) {
        RuntimeForeignAccess.registerForDowncall(Hello.STRLEN);
        RuntimeForeignAccess.registerForDowncall(Hello.TICKS);
        RuntimeForeignAccess.registerForDowncall(Hello.SPRINTF, Hello.SPRINTF_VARIADIC);
        RuntimeForeignAccess.registerForDowncall(Hello.QSORT);
        RuntimeForeignAccess.registerForUpcall(Hello.COMPARE);
    }
}
