/*
 * Copyright 2016 MovingBlocks
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.terasology.irc.system;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terasology.entitySystem.Component;
import org.terasology.entitySystem.entity.EntityManager;
import org.terasology.entitySystem.entity.EntityRef;
import org.terasology.entitySystem.event.ReceiveEvent;
import org.terasology.entitySystem.systems.BaseComponentSystem;
import org.terasology.entitySystem.systems.RegisterSystem;
import org.terasology.exception.SandboxException;
import org.terasology.logic.chat.ChatMessageEvent;
import org.terasology.logic.common.DisplayNameComponent;
import org.terasology.logic.delay.DelayManager;
import org.terasology.logic.delay.PeriodicActionTriggeredEvent;
import org.terasology.network.ClientComponent;
import org.terasology.registry.In;
import org.terasology.socket.SocketManager;
import org.terasology.socket.TCPSocket;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.io.OutputStreamWriter;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Implements IRC connection.
 */
@RegisterSystem
public class IRCSystem extends BaseComponentSystem {
    // These are hardcoded because of the lack of module configs.
    private static final String IRC_HOST = "irc.freenode.net";
    private static final int IRC_PORT = 6667;
    private static final String IRC_CHANNEL = "#Terasology";
    private static final String IRC_NICK = "TeraIRC";
    private static final String IRC_USER = "Terasology";
    private static final String IRC_REALNAME = "Terasology IRC";

    private static final Logger logger = LoggerFactory.getLogger(IRCSystem.class);
    @In
    private SocketManager socketManager;

    @In
    private EntityManager entityManager;

    @In
    private DelayManager delayManager;

    private BlockingQueue<String> toSend = new LinkedBlockingQueue<>();
    private Queue<String> toReceive = new ConcurrentLinkedQueue<>();

    private Thread ircReadThread;
    private Thread ircWriteThread;

    private EntityRef anEntity;

    /**
     * Sends a chat message to IRC.
     *
     * @param event  The event.
     * @param entity The entity.
     */
    @ReceiveEvent
    public void sendChatMessageToIRC(ChatMessageEvent event, EntityRef entity) {
        if (event.getFrom().equals(anEntity)) {
            return;
        }
        String name = event.getFrom().getComponent(DisplayNameComponent.class).name.replace('\n', ' ').replace('\r', ' ');
        String message = event.getMessage().replace('\n', ' ').replace('\r', ' ');
        toSend.add("PRIVMSG " + IRC_CHANNEL + " :<" + name + "> " + message + "\r\n");
    }

    @Override
    public void postBegin() {
        DisplayNameComponent displayNameComponent = new DisplayNameComponent();
        displayNameComponent.name = "[IRC]";
        displayNameComponent.description = "The IRC chat";
        anEntity = entityManager.create(new AnEntityComponent(), displayNameComponent);
        try {
            final TCPSocket socket;
            try {
                socket = socketManager.openTCPConnection(IRC_HOST, IRC_PORT);
            } catch (SandboxException e) {
                logger.error("Host/port combination not allowed. Please add to whitelist.", e);
                return; // don't continue if the host is not allowed.
            }
            ircWriteThread = new Thread(() -> {
                try {
                    BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));
                    while (!Thread.interrupted()) {
                        String next = toSend.take();
                        if (next != null) {
                            writer.write(next);
                            writer.flush();
                        }
                    }
                } catch (InterruptedException | InterruptedIOException e) {
                    /* ignored */
                } catch (IOException e) {
                    logger.warn("IOException", e);
                }
            });
            ircWriteThread.setDaemon(true);
            ircWriteThread.start();
            ircReadThread = new Thread(() -> {
                try {

                    BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));

                    // Log on to the server.
                    toSend.add("NICK " + IRC_NICK + "\r\n"
                            + "USER " + IRC_USER + " 8 * :" + IRC_REALNAME + "\r\n");

                    // Read lines from the server until it tells us we have connected.
                    String line = null;
                    while ((line = reader.readLine()) != null) {
                        String[] splitLine = line.split(" ");
                        String cmd = splitLine[splitLine[0].startsWith(":") ? 1 : 0];
                        if (cmd.equals("004")) {
                            // We are now logged in.
                            break;
                        } else if (cmd.equals("433")) {
                            logger.error("Nickname is already in use.");
                            ircWriteThread.interrupt();
                            return;
                        }
                    }

                    // Join the channel.
                    toSend.add("JOIN " + IRC_CHANNEL + "\r\n");

                    // Keep reading lines from the server.
                    while ((line = reader.readLine()) != null) {
                        logger.info(line);
                        int last = line.indexOf(" :");
                        String[] splitLine = (last < 0 ? line : line.substring(0, last + 2)).split(" ");
                        if (last >= 0) {
                            splitLine[splitLine.length - 1] = line.substring(last + 2);
                        }
                        int cmdidx = splitLine[0].startsWith(":") ? 1 : 0;
                        String cmd = splitLine[cmdidx];
                        if (cmd.equals("PING")) {
                            // We must respond to PINGs to avoid being disconnected.
                            StringBuilder sb = new StringBuilder();
                            sb.append("PONG");
                            for (int i = cmdidx + 1; i < splitLine.length; i++) {
                                sb.append(" ").append(splitLine[i]);
                            }
                            toSend.add(sb.toString());
                        } else if (cmd.equals("PRIVMSG")) {
                            if (splitLine[cmdidx + 1].equalsIgnoreCase(IRC_CHANNEL.toLowerCase())) {
                                String sender = splitLine[0].substring(1, splitLine[0].indexOf('!'));
                                toReceive.add("<" + sender + "> " + splitLine[splitLine.length - 1]);
                            }
                        }
                    }
                } catch (InterruptedIOException e) {
                    /* ignore it */
                } catch (IOException e) {
                    logger.warn("IOException", e);
                }
            });
            ircReadThread.setDaemon(true);
            ircReadThread.start();
        } catch (IOException e) {
            /*ignored*/
        }
        delayManager.addPeriodicAction(anEntity, "chatupdate", 50, 50);
    }

    /**
     * Updates the IRC queues.
     *
     * @param event  The event.
     * @param entity The entity.
     */
    @ReceiveEvent(components = AnEntityComponent.class)
    public void onUpdateEvent(PeriodicActionTriggeredEvent event, EntityRef entity) {
        if (!entity.equals(anEntity)) {
            return;
        }
        if (!event.getActionId().equals("chatupdate")) {
            return;
        }
        if (ircReadThread.isAlive() && ircWriteThread.isAlive()) {
            String message = toReceive.poll();
            if (message != null) {
                for (EntityRef client : entityManager.getEntitiesWith(ClientComponent.class)) {
                    client.send(new ChatMessageEvent(message, anEntity));
                }
            }
        } else {
            delayManager.cancelPeriodicAction(anEntity, "chatupdate");
            logger.error("IRC is dead.");
        }
    }

    @Override
    public void shutdown() {
        if (ircWriteThread != null) {
            toSend.add("QUIT :Shutdown\r\n");
            while (true) {
                if (toSend.isEmpty()) {
                    break;
                }
            }
            ircWriteThread.interrupt();
        }
        if (ircReadThread != null) {
            ircReadThread.interrupt();
        }
        anEntity.destroy();
    }

    //@DoNotAutoRegister
    public static final class AnEntityComponent implements Component {

    }
}

