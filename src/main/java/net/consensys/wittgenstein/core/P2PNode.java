package net.consensys.wittgenstein.core;

import net.consensys.wittgenstein.core.messages.FloodMessage;
import java.util.*;

public class P2PNode<TN extends P2PNode> extends Node {
  public final List<TN> peers = new ArrayList<>();
  private Map<Long, Set<FloodMessage>> received = new HashMap<>();

  public Set<FloodMessage> getMsgReceived(long id) {
    return received.computeIfAbsent(id, k -> new HashSet<>());
  }

  public P2PNode(Random rd, NodeBuilder nb) {
    this(rd, nb, false);
  }

  public P2PNode(Random rd, NodeBuilder nb, boolean byzantine) {
    super(rd, nb, byzantine);
  }

  public void onFlood(TN from, FloodMessage floodMessage) {}
}


