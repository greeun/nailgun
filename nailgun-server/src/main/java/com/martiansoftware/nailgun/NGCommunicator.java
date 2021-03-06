/*
 * Copyright 2012-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.martiansoftware.nailgun;

import java.io.*;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Once initial handshaking is complete, handles all reads and writes to the socket with the client
 * using underlying socket streams. Also handles client disconnect events based on client
 * heartbeats.
 */
public class NGCommunicator implements Closeable {

    private static final Logger LOG = Logger.getLogger(NGCommunicator.class.getName());
    private final ExecutorService orchestratorExecutor;
    private final ExecutorService readExecutor;
    private final DataInputStream in;
    private final DataOutputStream out;
    private final Object readLock = new Object();
    private final Object writeLock = new Object();
    private final Object orchestratorEvent = new Object();
    private boolean shutdown = false;
    private InputStream stdin = null;
    private boolean eof = false;
    private boolean closed = false;
    private int remaining = 0;
    private AtomicBoolean clientConnected = new AtomicBoolean(true);
    private final Set<NGClientListener> clientListeners = new HashSet<>();
    private final Set<NGHeartbeatListener> heartbeatListeners = new HashSet<>();
    private static final long TERMINATION_TIMEOUT_MS = 1000;

    /**
     * Creates a new NGCommunicator wrapping the specified InputStream. Also sets up a timer to
     * periodically consume heartbeats sent from the client and call registered NGClientListeners if
     * a client disconnection is detected.
     *
     * @param in Socket read stream, will be closed with NGCommunicator's close()
     * @param out Socket write stream, will be closed with NGCommunicator's close()
     * @param heartbeatTimeoutMillis the interval between heartbeats before considering the client
     * disconnected
     */
    public NGCommunicator(
        DataInputStream in,
        DataOutputStream out,
        final int heartbeatTimeoutMillis) {
        this.in = in;
        this.out = out;

        /** Thread factory that overrides name and priority for executor threads */
        final class NamedThreadFactory implements ThreadFactory {

            private final String threadName;

            public NamedThreadFactory(String threadName) {
                this.threadName = threadName;
            }

            @Override
            public Thread newThread(Runnable r) {
                SecurityManager s = System.getSecurityManager();
                ThreadGroup group =
                    (s != null) ? s.getThreadGroup() : Thread.currentThread().getThreadGroup();
                Thread t = new Thread(group, r, this.threadName, 0);
                if (t.isDaemon()) {
                    t.setDaemon(false);
                }
                if (t.getPriority() != Thread.MAX_PRIORITY) {
                    // warning - it may actually set lower priority if current thread group does not allow
                    // higher priorities
                    t.setPriority(Thread.MAX_PRIORITY);
                }
                return t;
            }
        }

        Thread mainThread = Thread.currentThread();
        this.orchestratorExecutor = Executors.newSingleThreadExecutor(
            new NamedThreadFactory(mainThread.getName() + " (NGCommunicator orchestrator)"));
        this.readExecutor = Executors.newSingleThreadExecutor(
            new NamedThreadFactory(mainThread.getName() + " (NGCommunicator reader)"));

        // Read timeout, including heartbeats, should be handled by socket.
        // However Java Socket/Stream API does not enforce that. To stay on safer side,
        // use timeout on a future

        // let socket timeout first, set rough timeout to 110% of original
        long futureTimeout = heartbeatTimeoutMillis + heartbeatTimeoutMillis / 10;

        orchestratorExecutor.submit(() -> {
            try {
                LOG.log(Level.FINE, "Orchestrator thread started");
                while (true) {
                    Future<Byte> readFuture;
                    synchronized (orchestratorEvent) {
                        if (shutdown) {
                            break;
                        }
                        readFuture = readExecutor.submit(() -> {
                            try {
                                return readChunk();
                            } catch (IOException e) {
                                throw new ExecutionException(e);
                            }
                        });
                    }

                    byte chunkType = readFuture.get(futureTimeout, TimeUnit.MILLISECONDS);

                    if (chunkType == NGConstants.CHUNKTYPE_HEARTBEAT) {
                        notifyHeartbeat();
                    }
                }
            } catch (InterruptedException e) {
                LOG.log(Level.WARNING, "NGCommunicator orchestrator was interrupted", e);
            } catch (ExecutionException e) {
                Throwable cause = getCause(e);
                if (cause instanceof EOFException) {
                    // DataInputStream throws EOFException if stream is terminated
                    // just do nothing and exit main orchestrator thread loop
                } else if (cause instanceof SocketTimeoutException) {
                    LOG.log(Level.WARNING,
                        "Nailgun client socket timed out after " + heartbeatTimeoutMillis
                            + " ms",
                        cause);
                } else {
                    LOG.log(Level.WARNING, "Nailgun client read future raised an exception",
                        cause);
                }
            } catch (TimeoutException e) {
                LOG.log(Level.WARNING,
                    "Nailgun client read future timed out after " + futureTimeout + " ms",
                    e);
            } catch (Throwable e) {
                LOG.log(Level.WARNING,
                    "Nailgun orchestrator gets an exception ",
                    e);
            }

            LOG.log(Level.FINE, "Nailgun client disconnected");

            // set client disconnected flag
            clientConnected.set(false);

            // notify stream readers there will be no more data
            setEof();

            // keep orchestrator thread running until signalled to shut up from close()
            // it is still responsible to notify about client disconnects if listener is
            // attached after disconnect had really happened
            waitTerminationAndNotifyClients();

            LOG.log(Level.FINE, "Orchestrator thread finished");
        });
    }

    private void waitTerminationAndNotifyClients() {
        while(true) {
            List<NGClientListener> listeners = new ArrayList<>();
            synchronized (orchestratorEvent) {
                // if shutdown is signalled from close, do not notify client listeners
                // this can only happen when NGSession has finished processing a nail and
                // closing
                if (shutdown) {
                    return;
                }

                if (!clientListeners.isEmpty()) {
                    listeners.addAll(clientListeners);
                    clientListeners.clear();
                }
            }

            // release the lock and notify clients about disconnect
            for (NGClientListener listener : listeners) {
                listener.clientDisconnected();
            }

            synchronized (orchestratorEvent) {
                if (shutdown || !clientListeners.isEmpty()) {
                    continue;
                }
                try {
                    // wait for any new other client listener to register, or shutdown
                    // signal
                    orchestratorEvent.wait();
                } catch (InterruptedException e) {
                    // this thread can only be interrupted from terminateExecutor(), which
                    // should not ever happen given the normal code flow, so
                    // just do nothing and quit
                    return;
                }
            }
        }
    }

    private static Throwable getCause(Throwable e) {
        Throwable cause = e.getCause();
        if (cause == null) {
            return e;
        }
        if (cause instanceof ExecutionException) {
            return getCause(cause);
        }
        return cause;
    }

    /**
     * Stop the thread reading from the NailGun client
     */

    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        // unblock all waiting readers
        setEof();

        // signal orchestrator thread it is ok to quit now
        synchronized (orchestratorEvent) {
            shutdown = true;
            orchestratorEvent.notifyAll();
        }

        // close underlying socket streams - that will cause readExecutor to throw EOFException
        // and exit orchestrator thread main loop
        in.close();
        out.close();

        terminateExecutor(readExecutor, "read");
        terminateExecutor(orchestratorExecutor, "orchestrator");
    }

    private static void terminateExecutor(ExecutorService service, String which) {
        LOG.log(Level.FINE, "Shutting down {0} ExecutorService", which);
        service.shutdown();

        boolean terminated;
        try {
            terminated = service
                .awaitTermination(TERMINATION_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            // It can happen if a thread calling close() is already interrupted
            // do not do anything here but do hard shutdown later with shutdownNow()
            // It is calling thread's responsibility to not be in interrupted state
            LOG.log(Level.WARNING,
                "Interruption is signaled in close(), terminating a thread forcefully");
            service.shutdownNow();
            return;
        }

        if (!terminated) {
            // something went wrong, executor task did not receive a signal and did not complete on time
            // shot executor in the head then
            LOG.log(Level.WARNING,
                "{0} thread did not unblock on a signal within timeout and will be"
                    + " forcefully terminated",
                which);
            service.shutdownNow();
        }
    }

    /**
     * Reads a NailGun chunk payload from {@link #in} and returns an InputStream that reads from
     * that chunk.
     *
     * @param in the InputStream to read the chunk payload from.
     * @param len the size of the payload chunk read from the chunkHeader.
     * @return an InputStream containing the read data.
     * @throws IOException if thrown by the underlying InputStream
     * @throws EOFException if EOF is reached by underlying stream before the payload has been read,
     * or if underlying stream was closed
     */
    private InputStream readPayload(InputStream in, int len) throws IOException {
        byte[] receiveBuffer = new byte[len];
        int totalRead = 0;
        while (totalRead < len) {
            int currentRead = in.read(receiveBuffer, totalRead, len - totalRead);
            if (currentRead < 0) {
                // server may forcefully close the socket/stream and this will cause InputStream to
                // return -1. Throw EOFException (same what DataInputStream does) to signal up
                // that we are in client disconnect mode
                throw new EOFException("stdin EOF before payload read.");
            }
            totalRead += currentRead;
        }
        return new ByteArrayInputStream(receiveBuffer);
    }

    /**
     * Reads a NailGun chunk header from the underlying InputStream.
     *
     * @return type of chunk received
     * @throws EOFException if underlying stream / socket is closed which happens on client
     * disconnection
     * @throws IOException if thrown by the underlying InputStream, or if an unexpected NailGun
     * chunk type is encountered.
     */
    private byte readChunk() throws IOException {
        int chunkLen = in.readInt();
        byte chunkType = in.readByte();

        switch (chunkType) {
            case NGConstants.CHUNKTYPE_STDIN:
                LOG.log(Level.FINEST, "Got stdin chunk, len {0}", chunkLen);
                InputStream chunkStream = readPayload(in, chunkLen);
                setInput(chunkStream, chunkLen);
                break;

            case NGConstants.CHUNKTYPE_STDIN_EOF:
                LOG.log(Level.FINEST, "Got stdin closed chunk");
                setEof();
                break;

            case NGConstants.CHUNKTYPE_HEARTBEAT:
                LOG.log(Level.FINEST, "Got client heartbeat");
                break;

            default:
                LOG.log(Level.WARNING, "Unknown chunk type: {0}", (char) chunkType);
                throw new IOException("Unknown stream type: " + (char) chunkType);
        }
        return chunkType;
    }

    private void setInput(InputStream chunkStream, int chunkLen) throws IOException {
        synchronized (readLock) {
            if (remaining != 0) {
                throw new IOException("Data received before stdin stream was emptied");
            }
            stdin = chunkStream;
            remaining = chunkLen;
            readLock.notifyAll();
        }
    }

    /**
     * Notify threads waiting in read() on either EOF chunk read or client disconnection.
     */
    private void setEof() {
        synchronized (readLock) {
            eof = true;
            readLock.notifyAll();
        }
    }

    /**
     * Read data from client's stdin. This function blocks till input is received from the
     * client.
     *
     * @return number of bytes read or -1 if no more data is available
     * @throws IOException in case of socket error
     */
    public int receive(byte[] b, int offset, int length)
        throws IOException, InterruptedException {

        synchronized (readLock) {
            if (remaining > 0) {
                int bytesToRead = Math.min(remaining, length);
                int result = stdin.read(b, offset, bytesToRead);
                remaining -= result;
                return result;
            }
            if (eof) {
                return -1;
            }
        }

        // make client know we want more data!
        sendSendInput();

        synchronized (readLock) {
            if (remaining == 0 && !eof) {
                readLock.wait();
            }

            // at this point we should have data so call itself recursively to utilize
            // reentrant lock
            return receive(b, offset, length);
        }
    }

    /**
     * Send data to the client
     */
    public void send(byte streamCode, byte[] b, int offset, int len) throws IOException {
        synchronized (writeLock) {
            out.writeInt(len);
            out.writeByte(streamCode);
            out.write(b, offset, len);
        }
        out.flush();
    }

    private void sendSendInput() throws IOException {
        synchronized (writeLock) {
            out.writeInt(0);
            out.writeByte(NGConstants.CHUNKTYPE_SENDINPUT);
        }
        out.flush();
    }

    /**
     * @return true if interval since last read is less than heartbeat timeout interval.
     */
    public boolean isClientConnected() {
        return clientConnected.get();
    }

    /**
     * @return number of bytes in internal stdin buffer
     */
    public int available() {
        synchronized (readLock) {
            return remaining;
        }
    }

    /**
     * Registers a new NGClientListener to be called on client disconnection
     *
     * @param listener the {@link NGClientListener} to be notified of client events.
     */
    public void addClientListener(NGClientListener listener) {
        synchronized (orchestratorEvent) {
            clientListeners.add(listener);

            // all notifications are sent from orchestrator thread, so in case if listener is
            // registered by the time client already disconnected - let orchestrator know it has
            // new customer
            orchestratorEvent.notifyAll();
        }
    }

    /**
     * @param listener the {@link NGClientListener} to no longer be notified of client events
     */
    public void removeClientListener(NGClientListener listener) {
        synchronized (orchestratorEvent) {
            clientListeners.remove(listener);
        }
    }

    /**
     * Do not notify anymore about client disconnects
     */
    public void removeAllClientListeners() {
        synchronized (orchestratorEvent) {
            clientListeners.clear();
        }
    }

    /**
     * @param listener the {@link NGHeartbeatListener} to be notified of heartbeats
     */
    public void addHeartbeatListener(NGHeartbeatListener listener) {
        synchronized (heartbeatListeners) {
            heartbeatListeners.add(listener);
        }
    }

    /**
     * @param listener the {@link NGHeartbeatListener} to no longer be notified of heartbeats
     */
    public void removeHeartbeatListener(NGHeartbeatListener listener) {
        synchronized (heartbeatListeners) {
            heartbeatListeners.remove(listener);
        }
    }

    /**
     * Calls heartbeatReceived method on all registered NGHeartbeatListeners.
     */
    private void notifyHeartbeat() {
        ArrayList<NGHeartbeatListener> listeners;
        synchronized (heartbeatListeners) {
            if (heartbeatListeners.isEmpty()) {
                return;
            }
            // copy collection to avoid executing callbacks under lock
            listeners = new ArrayList<>(heartbeatListeners);
        }

        for (NGHeartbeatListener listener : listeners) {
            listener.heartbeatReceived();
        }
    }
}
