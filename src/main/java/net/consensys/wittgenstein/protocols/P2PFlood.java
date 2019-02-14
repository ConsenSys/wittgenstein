package net.consensys.wittgenstein.protocols;

import net.consensys.wittgenstein.core.*;
import net.consensys.wittgenstein.core.messages.FloodMessage;
import net.consensys.wittgenstein.core.utils.StatsHelper;
import net.consensys.wittgenstein.server.WParameter;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * A simple flood protocol. When a node receives a message it has not yet received, it sends it to
 * all its peer. That's the "Flood routing" protocol documented here:
 * https://github.com/libp2p/specs/tree/master/pubsub/gossipsub
 */
public class P2PFlood implements Protocol {
  /**
   * The total number of nodes in the network
   */
  private final int nodeCount;

  /**
   * The total number of dead nodes: they won't be connected.
   */
  private final int deadNodeCount;

  /**
   * The time a node wait before resending a message to its peers. This can be used to represent the
   * validation time or an extra delay if the message is supposed to be big (i.e. a block in a block
   * chain for example
   */
  private final int delayBeforeResent;

  /**
   * The number of nodes sending a message (each one sending a different message)
   */
  private final int msgCount;

  /**
   * The number of messages to receive for a node to consider the protocol finished
   */
  private final int msgToReceive;

  /**
   * Average number of peers
   */
  private final int peersCount;

  /**
   * How long we wait between peers when we forward a message to our peers.
   */
  private final int delayBetweenSends;

  private final P2PNetwork<P2PFloodNode> network;
  private final NodeBuilder nb;


  class P2PFloodNode extends P2PNode<P2PFloodNode> {
    boolean extern;

    /**
     * @param down - if the node is marked down, it won't send/receive messages, but will still be
     *        included in the peers. As such it's a byzantine behavior: officially available but
     *        actually not participating.
     */
    P2PFloodNode(NodeBuilder nb, boolean down) {
      super(network.rd, nb, down);
      this.down = down;
    }

    @Override
    public void onFlood(P2PFloodNode from, FloodMessage floodMessage) {
      if (getMsgReceived(floodMessage.msgId()).size() == msgCount) {
        doneAt = network.time;
      }
    }
  }

  public static class P2PFloodParameters extends WParameter {
    final int nodeCount;
    final int deadNodeCount;
    final int delayBeforeResent;
    final int msgCount;
    final int msgToReceive;
    final int peersCount;
    final int delayBetweenSends;
    final String nodeBuilderName;
    final String networkLatencyName;

    public P2PFloodParameters() {
      this.nodeCount = 100;
      this.deadNodeCount = 10;
      this.delayBeforeResent = 10;
      this.msgCount = 1;
      this.msgToReceive = 1;
      this.peersCount = 15;
      this.delayBetweenSends = 0;
      this.nodeBuilderName = null;
      this.networkLatencyName = null;
    }

    public P2PFloodParameters(int nodeCount, int deadNodeCount, int delayBeforeResent, int msgCount,
        int msgToReceive, int peersCount, int delayBetweenSends, String nodeBuilderName,
        String networkLatencyName) {
      this.nodeCount = nodeCount;
      this.deadNodeCount = deadNodeCount;
      this.delayBeforeResent = delayBeforeResent;
      this.msgCount = msgCount;
      this.msgToReceive = msgToReceive;
      this.peersCount = peersCount;
      this.delayBetweenSends = delayBetweenSends;
      this.nodeBuilderName = nodeBuilderName;
      this.networkLatencyName = networkLatencyName;
    }
  }

  public P2PFlood(P2PFloodParameters params) {
    this(params.nodeCount, params.deadNodeCount, params.delayBeforeResent, params.msgCount,
        params.msgToReceive, params.peersCount, params.delayBetweenSends,
        new RegistryNodeBuilders().getByName(params.nodeBuilderName),
        new RegistryNetworkLatencies().getByName(params.networkLatencyName));
  }

  P2PFlood(int nodeCount, int deadNodeCount, int delayBeforeResent, int msgCount, int msgToReceive,
      int peersCount, int delayBetweenSends, NodeBuilder nb, NetworkLatency nl) {
    this.nodeCount = nodeCount;
    this.deadNodeCount = deadNodeCount;
    this.delayBeforeResent = delayBeforeResent;
    this.msgCount = msgCount;
    this.msgToReceive = msgToReceive;
    this.peersCount = peersCount;
    this.delayBetweenSends = delayBetweenSends;
    this.network = new P2PNetwork<>(peersCount, true);
    this.nb = nb;
    this.network.setNetworkLatency(nl);
  }

  @Override
  public String toString() {
    return "nodes=" + nodeCount + ", deadNodes=" + deadNodeCount + ", delayBeforeResent="
        + delayBeforeResent + "ms, msgSent=" + msgCount + ", msgToReceive=" + msgToReceive
        + ", peers(minimum)=" + peersCount + ", peers(avg)=" + network.avgPeers()
        + ", delayBetweenSends=" + delayBetweenSends + "ms, latency="
        + network.networkLatency.getClass().getSimpleName();
  }

  @Override
  public P2PFlood copy() {
    return new P2PFlood(nodeCount, deadNodeCount, delayBeforeResent, msgCount, msgToReceive,
        peersCount, delayBetweenSends, nb.copy(), network.networkLatency);
  }

  public void init() {
    for (int i = 0; i < nodeCount; i++) {
      network.addNode(new P2PFloodNode(nb, i < deadNodeCount));
    }
    network.setPeers();

    Set<Integer> senders = new HashSet<>(msgCount);
    while (senders.size() < msgCount) {
      int nodeId = network.rd.nextInt(nodeCount);
      P2PFloodNode from = network.getNodeById(nodeId);
      if (!from.down && senders.add(nodeId)) {
        FloodMessage<P2PFloodNode> m = new FloodMessage<>(1, delayBeforeResent, delayBetweenSends);
        network.sendPeers(m, from);
        if (msgCount == 1) {
          from.doneAt = 1;
        }
      }
    }
  }

  @Override
  public Network<P2PFloodNode> network() {
    return network;
  }

  private static void floodTime() {
    NetworkLatency nl = new NetworkLatency.IC3NetworkLatency();
    NodeBuilder nb = new NodeBuilder.NodeBuilderWithRandomPosition();

    int liveNodes = 2000;
    final int threshold = (int) (0.99 * liveNodes);
    P2PFlood p = new P2PFlood(liveNodes, 0, 1, 2000, threshold, 15, 1, nb, nl);

    Predicate<Protocol> contIf = p1 -> {
      if (p1.network().time > 50000) {
        return false;
      }

      for (Node n : p1.network().allNodes) {
        if (!n.down && n.getDoneAt() == 0) {
          return true;
        }
      }
      return false;
    };

    StatsHelper.StatsGetter sg = new StatsHelper.StatsGetter() {
      final List<String> fields = new StatsHelper.Counter(0).fields();

      @Override
      public List<String> fields() {
        return fields;
      }

      @Override
      public StatsHelper.Stat get(List<? extends Node> liveNodes) {
        return new StatsHelper.Counter(liveNodes.stream().filter(n -> n.getDoneAt() > 0).count());
      }
    };

    new ProgressPerTime(p, "", "node count", sg, 1, null).run(contIf);
  }

  public static void main(String... args) {
    floodTime();
  }
}