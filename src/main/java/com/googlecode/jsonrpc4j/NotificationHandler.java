package com.googlecode.jsonrpc4j;

import java.util.concurrent.Executor;

public interface NotificationHandler {
    Executor getExecutor();
}
