package net.consensys.wittgenstein.core;

import net.consensys.wittgenstein.core.messages.ConditionalTask;
import net.consensys.wittgenstein.core.messages.Message;
import net.consensys.wittgenstein.core.messages.PeriodicTask;
import net.consensys.wittgenstein.core.messages.Task;
import java.util.*;
import java.util.stream.Collectors;

/**
 * There is a single network for a simulation.
 * <p>
 * Nothing is executed in parallel, so the code does not have to be multithread safe.
 */
@SuppressWarnings({"WeakerAccess", "unused", "UnusedReturnValue"})
public class Network<TN extends Node> {
  final static int duration = 60 * 1000;

  /**
   * The messages in transit. Sorted by their arrival time.
   */
  public final MessageStorage msgs = new MessageStorage();

  /**
   * In parallel of the messages, we have tasks. It's mixed with messages (some tasks are managed as
   * special messages). Conditional tasks are in a specific list.
   */
  public final List<ConditionalTask<TN>> conditionalTasks = new LinkedList<>();


  /**
   * Internal variable. Nodes id are sequential & start at zero, so we can we index them in an
   * array.
   */
  public final List<TN> allNodes = new ArrayList<>(2048);

  /**
   * By using a single random generator, we have repeatable runs.
   */
  public final Random rd = new Random(0);

  final List<Integer> partitionsInX = new ArrayList<>();

  /**
   * We can decide to discard messages that would take too long to arrive. This limit the memory
   * consumption of the simulator as well.
   */
  int msgDiscardTime = Integer.MAX_VALUE;

  /**
   * The network latency. The default one is for a WAN
   */
  public NetworkLatency networkLatency = new NetworkLatency.IC3NetworkLatency();

  /**
   * Time in ms. Using an int limits us to ~555 hours of simulation, it's acceptable, and as we have
   * billions of dated objects it saves some memory...
   */
  public int time = 0;

  public TN getNodeById(int id) {
    return allNodes.get(id);
  }

  @SuppressWarnings("UnusedReturnValue")
  public Network<TN> setMsgDiscardTime(int l) {
    this.msgDiscardTime = l;
    return this;
  }

  /**
   * A desperate attempt to have something less memory consuming than a PriorityQueue or a guava
   * multimap, by using raw array. The idea is to optimize the case when there are multiple messages
   * in the same millisecond, but the global time range for all messages is limited.
   * <p>
   * The second idea is to have a repeatable run when there are multiple messages arriving at the
   * same millisecond.
   */
  final class MsgsSlot {
    final int startTime;
    final int endTime;
    final Envelope<?>[] msgsByMs = new Envelope[duration];

    public MsgsSlot(int startTime) {
      this.startTime = startTime - (startTime % duration);
      this.endTime = startTime + duration;
    }

    private int getPos(int aTime) {
      if (aTime < startTime || aTime >= startTime + duration) {
        throw new IllegalArgumentException(
            "aTime=" + aTime + ", startTime=" + startTime + ", duration=" + duration);
      }
      return (aTime % duration);
    }

    public void addMsg(Envelope<?> m) {
      int aTime = m.nextArrivalTime(Network.this);
      if (aTime < time) {
        throw new IllegalStateException("Can't add a message arriving in the past! time=" + time
            + ", arriving at " + aTime + ", m=" + m);
      }
      int pos = getPos(aTime);
      m.setNextSameTime(msgsByMs[pos]);
      msgsByMs[pos] = m;
    }

    public Envelope<?> peek(int time) {
      int pos = getPos(time);
      return msgsByMs[pos];
    }

    public Envelope<?> poll(int time) {
      int pos = getPos(time);
      Envelope<?> m = msgsByMs[pos];
      if (m != null) {
        msgsByMs[pos] = m.getNextSameTime();
      }
      return m;
    }

    public int size() {
      int size = 0;
      for (int i = 0; i < duration; i++) {
        int ss = 0;
        Envelope m = msgsByMs[i];
        while (m != null) {
          ss++;
          m = m.getNextSameTime();
        }
        size += ss;
      }
      return size;
    }

    Envelope<?> peekFirst() {
      for (int i = 0; i < duration; i++) {
        if (msgsByMs[i] != null) {
          return msgsByMs[i];
        }
      }
      return null;
    }
  }


  public final class MessageStorage {
    public final ArrayList<MsgsSlot> msgsBySlot = new ArrayList<>();

    public int size() {
      int size = 0;
      for (MsgsSlot ms : msgsBySlot) {
        size += ms.size();
      }
      return size;
    }

    public int sizeAt(int time) {
      int size = 0;
      Envelope<?> cur = peek(time);
      while (cur != null) {
        size++;
        cur = cur.getNextSameTime();
      }
      return size;
    }

    void cleanup() {
      while (!msgsBySlot.isEmpty() && time >= msgsBySlot.get(0).endTime) {
        msgsBySlot.remove(0);
      }
      if (msgsBySlot.isEmpty()) {
        msgsBySlot.add(new MsgsSlot(time));
      }
    }

    void ensureSize(int aTime) {
      while (msgsBySlot.get(msgsBySlot.size() - 1).endTime <= aTime) {
        msgsBySlot.add(new MsgsSlot(msgsBySlot.get(msgsBySlot.size() - 1).endTime));
      }
    }

    MsgsSlot findSlot(int aTime) {
      cleanup();
      ensureSize(aTime);
      int pos = (aTime - msgsBySlot.get(0).startTime) / duration;
      return msgsBySlot.get(pos);
    }

    void addMsg(Envelope<?> m) {
      int na = m.nextArrivalTime(Network.this);
      if (na < time) {
        throw new IllegalStateException(
            "Arriving in the past: arrival=" + na + ", time=" + time + ", msg=" + m);
      }
      MsgsSlot slot = findSlot(na);
      slot.addMsg(m);
    }

    Envelope<?> peek(int time) {
      return findSlot(time).peek(time);
    }

    Envelope<?> poll(int time) {
      return findSlot(time).poll(time);
    }

    public void clear() {
      msgsBySlot.clear();
      cleanup();
    }

    /**
     * @return the first message in the queue, null if the queue is empty.
     */
    Envelope<?> peekFirst() {
      for (MsgsSlot ms : msgsBySlot) {
        Envelope<?> m = ms.peekFirst();
        if (m != null)
          return m;
      }
      return null;
    }


    /**
     * For tests: they can find their message content.
     */
    public Message<?> peekFirstMessageContent() {
      Envelope<?> m = peekFirst();
      return m == null ? null : m.getMessage();
    }

    /**
     * @return the first message in the queue, null if the queue is empty.
     */
    public Envelope<?> pollFirst() {
      Envelope<?> m = peekFirst();
      return m == null ? null : poll(m.nextArrivalTime(Network.this));
    }
  }


  public interface Condition {
    boolean check();
  }


  /**
   * Simulate for x seconds. Can be called multiple time.
   */
  public void run(int seconds) {
    runMs(seconds * 1000);
  }

  public void runMs(int ms) {
    int endAt = time + ms;
    receiveUntil(endAt);
    time = endAt;
  }


  /**
   * Send a message to all nodes.
   */
  public void sendAll(Message<? extends TN> m, int sendTime, TN fromNode) {
    send(m, sendTime, fromNode, allNodes);
  }

  public void sendAll(Message<? extends TN> m, TN fromNode) {
    send(m, time + 1, fromNode, allNodes);
  }

  /**
   * Send a message to a collection of nodes. The message is considered as sent immediately, and
   * will arrive at a time depending on the network latency.
   */
  public void send(Message<? extends TN> m, TN fromNode, List<TN> dests) {
    send(m, time + 1, fromNode, dests);
  }

  public void send(Message<? extends TN> m, TN fromNode, TN toNode) {
    send(m, time + 1, fromNode, toNode);
  }

  /**
   * Send a message to a single node.
   */
  public void send(Message<? extends TN> mc, int sendTime, TN fromNode, TN toNode) {
    MessageArrival ms = createMessageArrival(mc, fromNode, toNode, sendTime, rd.nextInt());
    if (ms != null) {
      Envelope<?> m = new Envelope.SingleDestEnvelope<>(mc, fromNode, toNode, ms.arrival);
      msgs.addMsg(m);
    }
  }

  public void sendArriveAt(Message<? extends TN> mc, int arriveAt, TN fromNode, TN toNode) {
    if (arriveAt <= time) {
      throw new IllegalArgumentException(
          "wrong arrival time: arriveAt=" + arriveAt + ", time=" + time);
    }
    msgs.addMsg(new Envelope.SingleDestEnvelope<>(mc, fromNode, toNode, arriveAt));
  }

  final static class MessageArrival implements Comparable<MessageArrival> {
    final Node dest;
    final int arrival;

    public MessageArrival(Node dest, int arrival) {
      this.dest = dest;
      this.arrival = arrival;
    }

    @Override
    public String toString() {
      return "MessageArrival{" + "dest=" + dest + ", arrival=" + arrival + '}';
    }

    @Override
    public int compareTo(Network.MessageArrival o) {
      return Long.compare(arrival, o.arrival);
    }
  }

  public boolean hasMessage() {
    return msgs.size() != 0;
  }

  public void send(Message<? extends TN> m, int sendTime, TN fromNode, List<? extends Node> dests) {
    send(m, sendTime, fromNode, dests, 0);
  }

  public void send(Message<? extends TN> m, int sendTime, TN fromNode, List<? extends Node> dests,
      int delaysBetweenMessage) {
    int randomSeed = rd.nextInt();

    List<MessageArrival> da =
        createMessageArrivals(m, sendTime, fromNode, dests, randomSeed, delaysBetweenMessage);

    if (!da.isEmpty()) {
      Envelope<?> msg;
      if (da.size() == 1) {
        MessageArrival ms = da.get(0);
        msg = new Envelope.SingleDestEnvelope<>(m, fromNode, ms.dest, ms.arrival);
      } else if (delaysBetweenMessage == 0) {
        msg = new Envelope.MultipleDestEnvelope<>(m, fromNode, da, sendTime, randomSeed);
      } else {
        msg = new Envelope.MultipleDestWithDelayEnvelope<>(m, fromNode, da);
      }
      msgs.addMsg(msg);

    }
  }

  List<MessageArrival> createMessageArrivals(Message<? extends TN> m, int sendTime, TN fromNode,
      List<? extends Node> dests, int randomSeed, int delaysBetweenMessage) {
    ArrayList<MessageArrival> da = new ArrayList<>(dests.size());
    for (Node n : dests) {
      MessageArrival ma = createMessageArrival(m, fromNode, n, sendTime, randomSeed);
      sendTime += delaysBetweenMessage + (delaysBetweenMessage > 0 ? 1 : 0);
      if (ma != null) {
        da.add(ma);
      }
    }
    Collections.sort(da);

    return da;
  }

  private MessageArrival createMessageArrival(Message<?> m, Node fromNode, Node toNode,
      int sendTime, int randomSeed) {
    if (sendTime <= time) {
      throw new IllegalStateException("" + m + ", sendTime=" + sendTime + ", time=" + time);
    }

    assert !(m instanceof Task);
    fromNode.msgSent++;
    fromNode.bytesSent += m.size();
    if (partitionId(fromNode) == partitionId(toNode) && !fromNode.down && !toNode.down) {
      int nt =
          networkLatency.getLatency(fromNode, toNode, getPseudoRandom(toNode.nodeId, randomSeed));
      if (nt < msgDiscardTime) {
        return new MessageArrival(toNode, sendTime + nt);
      }
    }

    return null;
  }

  /**
   * @return always the same number for the same parameters, between 0 and 99, uniformly
   *         distributed.
   */
  public static int getPseudoRandom(int nodeId, int randomSeed) {
    int x = hash(nodeId) ^ randomSeed;
    return Math.abs(x % 100);
  }

  private static int hash(int a) {
    a ^= (a << 13);
    a ^= (a >>> 17);
    a ^= (a << 5);
    return a;
  }

  public void registerTask(final Runnable task, int startAt, TN fromNode) {
    Task<TN> sw = new Task<>(task);
    msgs.addMsg(new Envelope.SingleDestEnvelope<>(sw, fromNode, fromNode, startAt));
  }

  public void registerPeriodicTask(final Runnable task, int startAt, int period, TN fromNode) {
    PeriodicTask<TN> sw = new PeriodicTask<>(task, fromNode, period);
    msgs.addMsg(new Envelope.SingleDestEnvelope<>(sw, fromNode, fromNode, startAt));
  }

  public void registerPeriodicTask(final Runnable task, int startAt, int period, TN fromNode,
      Condition c) {
    PeriodicTask<TN> sw = new PeriodicTask<>(task, fromNode, period, c);
    msgs.addMsg(new Envelope.SingleDestEnvelope<>(sw, fromNode, fromNode, startAt));
  }

  public void registerConditionalTask(final Runnable task, int startAt, int duration, TN fromNode,
      Condition startIf, Condition repeatIf) {
    ConditionalTask<TN> ct = new ConditionalTask<>(startIf, repeatIf, task, startAt, duration);
    conditionalTasks.add(ct);
  }

  private Envelope<?> nextMessage(int until) {
    while (time <= until) {
      Envelope<?> m = msgs.poll(time);
      if (m != null) {
        return m;
      } else {
        time++;
      }
    }
    return null;
  }

  void receiveUntil(int until) {
    int previousTime = time;
    Envelope<?> next = nextMessage(until);
    while (next != null) {
      Envelope<?> m = next;
      int na = m.nextArrivalTime(this);
      if (na != previousTime) {
        if (time > na) {
          throw new IllegalStateException("time:" + time + ", arrival=" + na + ", m:" + m);
        }

        Iterator<ConditionalTask<TN>> it = conditionalTasks.iterator();
        while (it.hasNext()) {
          ConditionalTask ct = it.next();
          if (!ct.repeatIf.check()) {
            it.remove();
          } else {
            if (time >= ct.minStartTime && ct.startIf.check()) {
              ct.r.run();
              ct.minStartTime = time + ct.duration;
            }
          }
        }
      }

      TN from = allNodes.get(m.getFromId());
      TN to = allNodes.get(m.getNextDestId());
      if (partitionId(from) == partitionId(to)) {
        if (!(m.getMessage() instanceof Task<?>)) {
          if (m.getMessage().size() == 0) {
            throw new IllegalStateException("Message size should be greater than zero: " + m);
          }
          to.msgReceived++;
          to.bytesReceived += m.getMessage().size();
        }
        @SuppressWarnings("unchecked")
        Message<TN> mc = (Message<TN>) m.getMessage();
        mc.action(this, from, to);
      }

      m.markRead();
      if (m.hasNextReader()) {
        msgs.addMsg(m);
      }
      previousTime = time;
      next = nextMessage(until);
    }
  }

  int partitionId(Node to) {
    int pId = 0;
    for (Integer x : partitionsInX) {
      if (x > to.x) {
        return pId;
      } else {
        pId++;
      }
    }
    return pId;
  }

  public void addNode(TN node) {
    while (allNodes.size() <= node.nodeId) {
      allNodes.add(null);
    }
    if (allNodes.get(node.nodeId) != null) {
      throw new IllegalStateException("There is already a node with this id (" + node.nodeId + ")");
    }
    allNodes.set(node.nodeId, node);
  }

  public List<TN> liveNodes() {
    return allNodes.stream().filter(n -> !n.down).collect(Collectors.toList());
  }

  public Network<TN> setNetworkLatency(int[] distribProp, int[] distribVal) {
    return setNetworkLatency(new NetworkLatency.MeasuredNetworkLatency(distribProp, distribVal));
  }

  public Network<TN> setNetworkLatency(NetworkLatency networkLatency) {
    if (msgs.size() != 0) {
      throw new IllegalStateException(
          "You can't change the latency while the system as on going messages");
    }

    this.networkLatency = networkLatency;
    return this;
  }

  public void printNetworkLatency() {
    System.out.println("" + networkLatency);
  }

  /**
   * It's possible to cut the network in multiple points. The partition takes the node positions
   * into account On a map, a partition is a vertical cut on the X axe (eg. the earth is not round
   * for these partitions)
   *
   * @param part - the x point (in %) where we cut.
   */
  public void partition(float part) {
    if (part <= 0 || part >= 1) {
      throw new IllegalArgumentException("part needs to be a percentage between 0 & 100 excluded");
    }
    int xPoint = (int) (Node.MAX_X * part);
    if (partitionsInX.contains(xPoint)) {
      throw new IllegalArgumentException("this partition exists already");
    }
    partitionsInX.add(xPoint);
    Collections.sort(partitionsInX);
  }

  public void endPartition() {
    partitionsInX.clear();
  }

}
