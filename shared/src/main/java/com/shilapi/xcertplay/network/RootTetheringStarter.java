package com.shilapi.xcertplay.network;

import android.os.IBinder;
import android.os.ResultReceiver;

import java.lang.reflect.Method;

/**
 * Minimal entry point launched with Magisk's root shell through app_process.
 *
 * The regular application UID cannot hold TETHER_PRIVILEGED on this firmware. Running this tiny
 * helper as root lets Android's own ConnectivityService perform the operation; it does not invoke
 * hostapd directly or replace the firmware's saved SoftAP configuration.
 */
public final class RootTetheringStarter {
    private static final int TETHERING_WIFI = 0;

    private RootTetheringStarter() {
    }

    public static void main(String[] args) throws Exception {
        final String operation = args.length == 0 ? "start" : args[0];
        final Class<?> serviceManager = Class.forName("android.os.ServiceManager");
        final IBinder binder = (IBinder) serviceManager
                .getMethod("getService", String.class)
                .invoke(null, "connectivity");
        if (binder == null) {
            throw new IllegalStateException("ConnectivityService is not ready");
        }

        final Class<?> stub = Class.forName("android.net.IConnectivityManager$Stub");
        final Class<?> contract = Class.forName("android.net.IConnectivityManager");
        final Object connectivity = stub
                .getMethod("asInterface", IBinder.class)
                .invoke(null, binder);
        if ("probe".equals(operation)) {
            requireOperation(contract, "startTethering");
            requireOperation(contract, "stopTethering");
        } else if ("stop".equals(operation)) {
            invokeStop(contract, connectivity);
        } else if ("start".equals(operation)) {
            invokeStart(contract, connectivity);
        } else {
            throw new IllegalArgumentException("Unknown operation: " + operation);
        }
        System.out.println("OK");
    }

    private static void requireOperation(Class<?> contract, String name) throws Exception {
        for (Method method : contract.getMethods()) {
            if (name.equals(method.getName())) return;
        }
        throw new NoSuchMethodException("ConnectivityService." + name);
    }

    private static void invokeStart(Class<?> contract, Object connectivity) throws Exception {
        for (Method method : contract.getMethods()) {
            if (!"startTethering".equals(method.getName())) continue;
            final Class<?>[] types = method.getParameterTypes();
            if (types.length == 4 && types[0] == int.class &&
                    ResultReceiver.class.isAssignableFrom(types[1]) &&
                    types[2] == boolean.class && types[3] == String.class) {
                method.invoke(connectivity, TETHERING_WIFI, new ResultReceiver(null), false,
                        "com.shilapi.xcertplay");
                return;
            }
            if (types.length == 3 && types[0] == int.class &&
                    ResultReceiver.class.isAssignableFrom(types[1]) && types[2] == boolean.class) {
                method.invoke(connectivity, TETHERING_WIFI, new ResultReceiver(null), false);
                return;
            }
        }
        throw new NoSuchMethodException("ConnectivityService.startTethering");
    }

    private static void invokeStop(Class<?> contract, Object connectivity) throws Exception {
        for (Method method : contract.getMethods()) {
            if (!"stopTethering".equals(method.getName())) continue;
            final Class<?>[] types = method.getParameterTypes();
            if (types.length == 2 && types[0] == int.class && types[1] == String.class) {
                method.invoke(connectivity, TETHERING_WIFI, "com.shilapi.xcertplay");
                return;
            }
            if (types.length == 1 && types[0] == int.class) {
                method.invoke(connectivity, TETHERING_WIFI);
                return;
            }
        }
        throw new NoSuchMethodException("ConnectivityService.stopTethering");
    }
}
