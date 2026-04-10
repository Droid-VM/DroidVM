package cn.classfun.droidvm.lib.daemon;

import org.json.JSONObject;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

public final class PendingRequest {
    final CountDownLatch latch = new CountDownLatch(1);
    final AtomicReference<JSONObject> response = new AtomicReference<>();
    final AtomicReference<Exception> error = new AtomicReference<>();
}
