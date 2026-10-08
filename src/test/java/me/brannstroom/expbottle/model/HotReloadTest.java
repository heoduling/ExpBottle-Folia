package me.brannstroom.expbottle.model;

import java.lang.invoke.*;
import java.lang.reflect.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HotReloadTest {
    static StackTraceElement[] trace;
    public static int managerCommand() { trace = new Throwable().getStackTrace(); return 1; }
    public static int rejectedCommand() { throw new IllegalArgumentException("fixture"); }

    @Test void managerCallbackHasNoOldPluginStackFrame() throws Exception {
        Object invocation = invocation("managerCommand");
        callback(invocation).accept(null);
        assertTrue(finished(invocation).get());
        for (var frame : trace) assertFalse(frame.getClassName().equals(HotReload.class.getName()), frame.toString());
    }

    @Test void nativeExceptionStillCompletesRecoverySignal() throws Exception {
        Object invocation = invocation("rejectedCommand");
        assertThrows(IllegalArgumentException.class, () -> callback(invocation).accept(null));
        assertTrue(finished(invocation).get());
    }

    private Object invocation(String method) throws Exception {
        Method factory = HotReload.class.getDeclaredMethod("nativeInvocation", MethodHandle.class);
        factory.setAccessible(true);
        return factory.invoke(null, MethodHandles.lookup().findStatic(getClass(), method, MethodType.methodType(int.class)));
    }
    @SuppressWarnings("unchecked")
    private Consumer<Object> callback(Object invocation) throws Exception {
        Method getter = invocation.getClass().getDeclaredMethod("callback"); getter.setAccessible(true);
        return (Consumer<Object>) getter.invoke(invocation);
    }
    private AtomicBoolean finished(Object invocation) throws Exception {
        Method getter = invocation.getClass().getDeclaredMethod("finished"); getter.setAccessible(true);
        return (AtomicBoolean) getter.invoke(invocation);
    }
}
