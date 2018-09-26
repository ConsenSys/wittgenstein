package net.consensys.wittgenstein.core;

import org.jetbrains.annotations.NotNull;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class TestNetwork {
    private Network<Node> network = new Network<>();
    private Node.NodeBuilder nb = new Node.NodeBuilder();
    private Node n0 = new Node(nb);
    private Node n1 = new Node(nb);
    private Node n2 = new Node(nb);
    private Node n3 = new Node(nb);

    @Before
    public void before() {
        network.removeNetworkLatency();
        network.addNode(n0);
        network.addNode(n1);
        network.addNode(n2);
        network.addNode(n3);
    }

    @Test
    public void testSimpleMessage() {
        AtomicInteger a1 = new AtomicInteger(-1);
        AtomicInteger a2 = new AtomicInteger(-1);

        Network.MessageContent<Node> act = new Network.MessageContent<Node>() {
            @Override
            public void action(@NotNull Node from, @NotNull Node to) {
                a1.set(from.nodeId);
                a2.set(to.nodeId);
            }
        };

        network.send(act, 1, n1, n2);
        Assert.assertEquals(1, network.msgs.size());
        Assert.assertEquals(-1, a1.get());

        network.run(5);
        Assert.assertEquals(1, a1.get());
        Assert.assertEquals(2, a2.get());
    }

    @Test
    public void testRegisterTask() {
        AtomicBoolean ab = new AtomicBoolean();
        network.registerTask(() -> ab.set(true), 100, n0);

        network.runMs(99);
        Assert.assertFalse(ab.get());

        network.runMs(1);
        Assert.assertTrue(ab.get());
        Assert.assertEquals(0, network.msgs.size());
    }

    @Test
    public void testAllFavorsOfSend() {
        AtomicInteger a1 = new AtomicInteger(0);
        AtomicInteger a2 = new AtomicInteger(0);

        Network.MessageContent<Node> act = new Network.MessageContent<Node>() {
            @Override
            public void action(@NotNull Node from, @NotNull Node to) {
                a1.addAndGet(from.nodeId);
                a2.addAndGet(to.nodeId);
            }
        };
        List<Node> dests = new ArrayList<>();
        dests.add(n2);
        dests.add(n3);

        network.send(act, n1, n2);
        network.send(act, 1, n1, n2);
        network.send(act, 1, n1, dests);
        network.send(act, n1, dests);

        Assert.assertEquals(4, network.msgs.size());
        network.run(1);
        Assert.assertEquals(0, network.msgs.size());
        Assert.assertEquals(6, a1.get());
        Assert.assertEquals(14, a2.get());
    }
}