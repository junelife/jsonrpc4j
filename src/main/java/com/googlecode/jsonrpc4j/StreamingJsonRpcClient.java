package com.googlecode.jsonrpc4j;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.Vector;

import static com.googlecode.jsonrpc4j.JsonRpcBasicServer.ID;

public class StreamingJsonRpcClient extends JsonRpcClient implements Runnable {

    private InputStream input;
    private OutputStream output;
    private NotificationHandler notificationHandler;
    private Vector<JsonNode> incoming = new Vector<>();

    public StreamingJsonRpcClient(InputStream input, OutputStream output, NotificationHandler notificationHandler) throws IOException {
        super();
        this.input = input;
        this.output = output;
        this.notificationHandler = notificationHandler;

        new Thread(this).start();

    }

    @Override
    public void run() {
        try {
            readContext = ReadContext.getReadContext(input, mapper);
            while (true) {
                JsonNode node = readContext.nextValue();
                if (false == sendNotification(node)) {
                    synchronized (incoming) {
                        incoming.add(node);
                        incoming.notify();
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Caught exception while handling incoming messages: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @Override
    protected JsonNode readResponseNode(ReadContext context) throws IOException {
        try {
            synchronized (incoming) {
                while (incoming.isEmpty()) {
                    incoming.wait();
                }
                return incoming.remove(0);
            }
        } catch (InterruptedException e) {
            System.err.println("Thread interrupted while waiting for response");
            e.printStackTrace();
        }
        return null;
    }



    private boolean sendNotification(JsonNode jsonNode) throws IOException {
        if (notificationHandler == null
                || jsonNode.has(ID))
        {
            return false;
        }

        String methodName = jsonNode.get("method").textValue();

        JsonNode paramsNode = jsonNode.get("params");
        if (methodName == null || methodName.isEmpty()
                || paramsNode == null || false == paramsNode.isArray())
        {
            return false;
        }

        Class notificationHandlerClass = notificationHandler.getClass();
        Method[] methods = notificationHandlerClass.getMethods();
        Method method = null;
        for (Method aMethod : methods) {
            if (getMethodName(aMethod).equals(methodName)) {
                method = aMethod;
                break;
            }
        }

        if (method == null) {
            // TODO: report method not found, or call generic handler?
            return false;
        }

        Class[] paramTypes = method.getParameterTypes();
        final Method finalMethod = method;
        final Object[] params = new Object[paramTypes.length];

        int i = 0;
        for (Class paramType : paramTypes) {
            if (i >= paramsNode.size()) {
                //TODO: report wrong number of params
                return false;
            }
            JsonNode paramNode = paramsNode.get(i);

            Object param = mapParam(paramType, paramNode);
            params[i] = param;
            i++;
        }

        notificationHandler.getExecutor().execute(new Runnable() {
            @Override
            public void run() {
                try {
                    finalMethod.invoke(notificationHandler, params);
                } catch (Exception e) {
                    //TODO report error
                    e.printStackTrace();
                }
            }
        });
        return true;
    }


    private Object mapParam(Type paramType, JsonNode node) {
        JsonParser jsonParser = mapper.treeAsTokens(node);
        JavaType javaType = mapper.getTypeFactory().constructType(paramType);
        try {

            return mapper.readValue(jsonParser, javaType);
        } catch (IOException e) {
            System.err.println("Error mapping JSON value: " + e.getMessage());
            e.printStackTrace();
        }
        return null;
    }

    private static String getMethodName(Method method) {
        final JsonRpcMethod jsonRpcMethod = ReflectionUtil.getAnnotation(method, JsonRpcMethod.class);
        if (jsonRpcMethod == null) {
            return method.getName();
        } else {
            return jsonRpcMethod.value();
        }
    }
}
