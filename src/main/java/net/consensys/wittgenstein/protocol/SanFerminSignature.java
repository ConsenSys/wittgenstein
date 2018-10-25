package net.consensys.wittgenstein.protocol;

import net.consensys.wittgenstein.core.*;
import net.consensys.wittgenstein.core.utils.MoreMath;
import net.consensys.wittgenstein.core.utils.StatsHelper;
import net.consensys.wittgenstein.tools.Graph;
import net.consensys.wittgenstein.tools.SanFerminHelper;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;


/**
 * San Fermin's protocol adapted to BLS signature aggregation.
 * <p>
 * San Fermin is a protocol for distributed aggregation of a large data set over a large set of
 * nodes. It imposes a special binomial tree structures on the communication patterns of the node so
 * that each node only needs to contact O(log(n)) other nodes to get the final aggregated result.
 * SanFerminNode{nodeId=1000000001, doneAt=4860, sigs=874, msgReceived=272, msgSent=275,
 * KBytesSent=13, KBytesReceived=13, outdatedSwaps=0}
 */
@SuppressWarnings("WeakerAccess")
public class SanFerminSignature implements Protocol {

  /**
   * The number of nodes in the network
   */
  final int nodeCount;

  /**
   * exponent to represent @nodeCount in base 2 nodeCount = 2 ** powerOfTwo; It is used to represent
   * the length of the binary string of a node's id.
   */
  final int powerOfTwo;
  /**
   * The number of signatures to reach to finish the protocol
   */
  final int threshold;

  /**
   * The time it takes to do a pairing for a node i.e. simulation of the most heavy computation
   */
  final int pairingTime;

  /**
   * Size of a BLS signature (can be aggregated or not)
   */
  final int signatureSize;

  /**
   * how much time to wait for a reply upon a given request
   */
  final int replyTimeout;

  /**
   * Do we print logging information from nodes or not
   */
  boolean verbose;

  /**
   * Should we shuffle the list of the candidate sets at each peers
   */
  boolean shuffledLists;

  /**
   * how many candidate do we try to reach at the same time for a given level
   */
  int candidateCount;


  /**
   * allNodes represent the full list of nodes present in the system. NOTE: This assumption that a
   * node gets access to the full list can be dismissed, as they do in late sections in the paper.
   * For a first version and sake of simplicity, full knowledge of the peers is assumed here. A
   * partial knowledge graph can be used, where nodes will send requests to unknown ID "hoping" some
   * unknown peers yet can answer. This technique works because it uses Pastry ID allocation
   * mechanism. Here no underlying DHT or p2p special structure is assumed.
   */
  public final List<SanFerminNode> allNodes;

  public final List<SanFerminNode> finishedNodes;


  public SanFerminSignature(int nodeCount, int threshold, int pairingTime, int signatureSize,
      int replyTimeout, int candidateCount, boolean shuffledLists) {
    this.nodeCount = nodeCount;
    this.powerOfTwo = MoreMath.log2(nodeCount);
    this.threshold = threshold;
    this.pairingTime = pairingTime;
    this.signatureSize = signatureSize;
    this.replyTimeout = replyTimeout;
    this.candidateCount = candidateCount;
    this.shuffledLists = shuffledLists;

    this.network = new Network<>();
    this.nb = new Node.NodeBuilderWithRandomPosition(network.rd);

    this.allNodes = new ArrayList<>(nodeCount);
    for (int i = 0; i < nodeCount; i++) {
      final SanFerminNode n = new SanFerminNode(this.nb);
      this.allNodes.add(n);
      this.network.addNode(n);
    }

    // register the sanfermin helper with all the nodes
    this.allNodes
        .forEach(n -> n.candidateTree = new SanFerminHelper<>(n, allNodes, this.network.rd));

    finishedNodes = new ArrayList<>();
  }

  public SanFerminSignature copy() {
    return new SanFerminSignature(nodeCount, threshold, pairingTime, signatureSize, replyTimeout,
        candidateCount, shuffledLists);
  }

  final Network<SanFerminNode> network;
  final Node.NodeBuilder nb;

  /**
   * init makes each node starts swapping with each other when the network starts
   */
  public void init() {
    for (SanFerminNode n : allNodes)
      network.registerTask(n::goNextLevel, 1, n);
  }


  /**
   * SanFerminNode is a node that carefully selects the peers he needs to contact to get the final
   * aggregated result
   */
  public class SanFerminNode extends Node {
    /**
     * The node's id in binary string form
     */
    public final String binaryId;

    /**
     * This node needs to exchange with another node having a current common prefix length of
     * currentPrefixLength. A node starts by the highest possible prefix length and works towards a
     * prefix length of 0.
     */
    public int currentPrefixLength;

    SanFerminHelper<SanFerminNode> candidateTree;

    /**
     * BitSet to save which node have we sent swaprequest so far
     */
    HashMap<Integer, BitSet> usedCandidates;

    /**
     * Set of aggregated signatures saved. Since it's only a logarithmic number it's not much to
     * save (log_2(20k) = 15)
     */
    HashMap<Integer, Integer> signatureCache;

    /**
     * Ids of the nodes we have sent a SwapRequest to. Different strategies to pick the nodes are
     * detailed in `pickNextNodes`
     */
    Set<Integer> pendingNodes;

    HashMap<Integer, Integer> futurSigs;


    /**
     * isSwapping indicates whether we are in a state where we can swap at the current level or not.
     * This is acting as a Lock, since between the time we receive a valid swap and the time we
     * aggregate it, we need to verify it. In the meantime, another valid swap can come and thus we
     * would end up swapping twice at a given level.
     */
    boolean isSwapping;


    /**
     * Integer field that simulate an aggregated signature badly. It keeps increasing as the node do
     * more swaps. It assumes each node has the value "1" and the aggregation operation is the
     * addition.
     */
    int aggValue;

    /**
     * time when threshold of signature is reached
     */
    long thresholdAt;
    /**
     * have we reached the threshold or not
     */
    boolean thresholdDone;
    /**
     * time when this node has finished the protocol entirely
     */
    long doneAt;
    /**
     * Are we done yet or not
     */
    boolean done;

    /**
     * number of swap "request" this node sent (vs optimistic reply etc)
     */
    int sentRequests;
    /**
     * number of swap requests received by this node
     */
    int receivedRequests;


    public SanFerminNode(NodeBuilder nb) {
      super(nb);

      this.binaryId = SanFerminHelper.toBinaryID(this, nodeCount);
      this.usedCandidates = new HashMap<>();
      this.done = false;
      this.thresholdDone = false;
      this.sentRequests = 0;
      this.receivedRequests = 0;
      this.aggValue = 1;
      // node should start at n-1 with N = 2^n
      // this counter gets decreased with `goNextLevel`.
      this.currentPrefixLength = powerOfTwo;
      this.signatureCache = new HashMap<>();
      this.futurSigs = new HashMap<>();
    }

    /**
     * onSwapRequest checks if it is a request for the same current level, and if it is, swap and go
     * to the next level. onSwapRequest is the fastest way to swap, since the value is already
     * embedded in the message.
     */
    public void onSwapRequest(SanFerminNode node, SwapRequest request) {
      receivedRequests++;
      if (done || request.level != this.currentPrefixLength) {
        if (this.signatureCache.containsKey(request.level)) {
          print("sending back CACHED signature at level " + request.level + " to node "
              + node.binaryId);
          // OPTIMISTIC REPLY
          this.sendSwapReply(node, Status.OK, request.level,
              this.signatureCache.get(request.level));
        } else {
          this.sendSwapReply(node, Status.NO, 0);
          // it's a value we might want to keep for later!
          boolean isCandidate = candidateTree.getCandidateSet(request.level).contains(node);
          boolean isValidSig = true; // as always :)
          if (isCandidate && isValidSig) {
            // it is a good request we can save for later!
            this.signatureCache.put(request.level, request.aggValue);
          }
        }
        return;
      }

      // just send the value but don't aggregate it
      // OPTIMISTIC reply
      if (isSwapping) {
        this.sendSwapReply(node, Status.OK, request.level, this.aggValue);
        return;
      }

      // accept if it is a valid swap !
      boolean isCandidate = candidateTree.getCandidateSet(currentPrefixLength).contains(node);
      boolean goodLevel = request.level == currentPrefixLength;
      boolean isValidSig = true; // as always :)
      if (isCandidate && goodLevel && isValidSig) {
        transition("valid swap REQUEST", node.binaryId, request.level, request.aggValue);
      } else {
        print(" received  INVALID Swap" + "from " + node.binaryId + " at level " + request.level);
      }

    }

    public void onSwapReply(SanFerminNode from, SwapReply reply) {
      if (reply.level != this.currentPrefixLength || done) {
        // TODO optimization here to potentially save the value for
        // future usage if it can be useful and is valid
        return;
      }
      if (isSwapping)
        return;

      switch (reply.status) {
        case OK:
          // We must verify it contains a valid
          // aggregated signature, that it comes from a peer
          // present in your candidate set at this prefix level,
          // and that the prefix level is the same as this node's.
          // we dont want to aggregate twice so we have to check
          // pendingNode (acts like a lock).
          if (!this.pendingNodes.contains(from.nodeId)) {
            boolean isCandidate = candidateTree.getCandidateSet(currentPrefixLength).contains(from);
            boolean goodLevel = reply.level == currentPrefixLength;
            boolean isValidSig = true; // as always :)
            if (isCandidate && goodLevel && isValidSig) {
              transition("UNEXPECTED swap REPLY", from.binaryId, reply.level, reply.aggValue);
            } else {
              print(" received UNEXPECTED - WRONG swap reply " + "from " + from.binaryId
                  + " at level " + reply.level);
            }
            return;
          }
          // good valid honest answer !
          transition("valid swap REPLY", from.binaryId, reply.level, reply.aggValue);
          // 1946, doneAt=2545, sigs=1024, msgReceived=27
          // doneAt=219, sigs=527, msgReceived=283, msgSent=134,
          break;
        case NO:
          print(" received SwapReply NO from " + from.binaryId);
          // only try the next one if this is an expected reply
          if (this.pendingNodes.contains(from.nodeId)) {
            List<SanFerminNode> nodes =
                this.candidateTree.pickNextNodes(this.currentPrefixLength, candidateCount);
            sendToNodes(nodes);
          } else {
            print(" UNEXPECTED NO reply from " + from.binaryId);
          }
          break;
        default:
          throw new Error("That should never happen");
      }
    }

    /**
     * sendToNodes sends a swap request to the given nodes. It attaches a timeout to the request. If
     * no SwapReply has been received before timeout, sendToNodes() will be called again.
     */
    private void sendToNodes(List<SanFerminNode> candidates) {
      // TODO move to fully multiple node mode ! but git commit before
      if (candidates.size() == 0) {
        // when malicious actors are introduced or some nodes are
        // failing this case can happen. In that case, the node
        // should go to the next level since he has nothing better to
        // do. The final aggregated signature will miss this level
        // but it may still reach the threshold and can be completed
        // later on, through the help of a bit field.
        print(" is OUT (no more " + "nodes to pick)");
        return;
      }

      // add ids to the set of pending nodes
      this.pendingNodes.addAll(candidates.stream().map(n -> n.nodeId).collect(Collectors.toList()));
      this.sentRequests += candidates.size();

      SwapRequest r = new SwapRequest(this.currentPrefixLength, this.aggValue);
      print(" send SwapRequest to " + String.join(" - ",
          candidates.stream().map(n -> n.binaryId).collect(Collectors.toList())));
      network.send(r, this, candidates);

      int currLevel = this.currentPrefixLength;
      network.registerTask(() -> {
        // If we are still waiting on an answer for this level, we
        // try a new one.
        if (!SanFerminNode.this.done && SanFerminNode.this.currentPrefixLength == currLevel) {
          print("TIMEOUT of SwapRequest at level " + currLevel);
          // that means we haven't got a successful reply for that
          // level so we try other nodes
          List<SanFerminNode> newList =
              this.candidateTree.pickNextNodes(this.currentPrefixLength, candidateCount);
          sendToNodes(newList);
        }
      }, network.time + replyTimeout, SanFerminNode.this);

    }


    /**
     * goNextLevel reduces the required length of common prefix of one, computes the new set of
     * potential nodes and sends an invitation to the "next" one. There are many ways to select the
     * order of which node to choose. In case the number of nodes is 2^n, there is a 1-to-1 mapping
     * that exists so that each node has exactly one unique node to swap with. In case it's not,
     * there are going to be some nodes who will be unable to continue the protocol since the
     * "chosen" node will likely already have swapped. See `pickNextNode` for more information.
     */
    private void goNextLevel() {

      if (done) {
        return;
      }

      boolean enoughSigs = this.aggValue >= threshold;
      boolean noMoreSwap = this.currentPrefixLength == 0;

      if (enoughSigs && !thresholdDone) {
        print(" --- THRESHOLD REACHED --- ");
        thresholdDone = true;
        thresholdAt = network.time + pairingTime * 2;
      }

      if (noMoreSwap && !done) {
        print(" --- FINISHED ---- protocol");
        doneAt = network.time + pairingTime * 2;
        finishedNodes.add(this);
        done = true;
        return;
      }
      this.currentPrefixLength--;
      this.signatureCache.put(this.currentPrefixLength, this.aggValue);
      this.isSwapping = false;
      this.pendingNodes = new HashSet<>();
      if (futurSigs.containsKey(currentPrefixLength)) {
        print(" FUTURe value at new level" + currentPrefixLength + " "
            + "saved. Moving on directly !");
        this.aggValue += futurSigs.get(currentPrefixLength);
        // directly go to the next level !
        goNextLevel();
        return;
      }
      List<SanFerminNode> newList =
          this.candidateTree.pickNextNodes(currentPrefixLength, candidateCount);
      this.sendToNodes(newList);
    }

    private void sendSwapReply(SanFerminNode n, Status s, int value) {
      sendSwapReply(n, s, this.currentPrefixLength, value);
    }

    private void sendSwapReply(SanFerminNode n, Status s, int level, int value) {
      SwapReply r = new SwapReply(s, level, value);
      network.send(r, SanFerminNode.this, Collections.singleton(n));
    }



    /**
     * Transition prevents any more aggregation at this level, and launch the "verification routine"
     * and move on to the next level. The first three parameters are only here for logging purposes.
     */
    private void transition(String type, String fromId, int level, int toAggregate) {
      this.isSwapping = true;
      network.registerTask(() -> {
        int before = this.aggValue;
        this.aggValue += toAggregate;
        print(" received " + type + " lvl=" + level + " from " + fromId + " aggValue " + before
            + " -> " + this.aggValue);
        this.goNextLevel();
      }, network.time + pairingTime, this);
    }

    private void timeout(int level) {
      // number of potential candidates at a given level
      int diff = powerOfTwo - currentPrefixLength;
      int potentials = (int) Math.pow(2, diff) - 1;
    }

    /**
     * simple helper method to print node info + message
     */
    private void print(String s) {
      if (verbose)
        System.out.println("t=" + network.time + ", id=" + this.nodeId + ", lvl="
            + this.currentPrefixLength + ", sent=" + this.msgSent + " -> " + s);
    }

    @Override
    public String toString() {
      return "SanFerminNode{" + "nodeId=" + binaryId + ", thresholdAt=" + thresholdAt + ", doneAt="
          + doneAt + ", sigs=" + aggValue + ", msgReceived=" + msgReceived + ", msgSent=" + msgSent
          + ", sentRequests=" + sentRequests + ", receivedRequests=" + receivedRequests
          + ", KBytesSent=" + bytesSent / 1024 + ", KBytesReceived=" + bytesReceived / 1024 + '}';
    }

    public long getThresholdAt() {
      return thresholdAt;
    }

    public long getDoneAt() {
      return doneAt;
    }
  }

  enum Status {
    OK,
    NO
  }

  class SwapReply extends Network.Message<SanFerminNode> {

    Status status;
    final int level;
    int aggValue; // see Reply.aggValue
    // String data -- no need to specify it, but only in the size() method


    public SwapReply(Status s, int level, int aggValue) {
      this.level = level;
      this.status = s;
      this.aggValue = aggValue;
    }

    @Override
    public void action(SanFerminNode from, SanFerminNode to) {
      to.onSwapReply(from, this);
    }

    @Override
    public int size() {
      // uint32 + sig size
      return 4 + signatureSize;
    }
  }


  class SwapRequest extends Network.Message<SanFerminNode> {
    final int level;
    final int aggValue; // see Reply.aggValue
    // String data -- no need to specify it, but only in the size() method


    public SwapRequest(int level, int aggValue) {
      this.level = level;
      this.aggValue = aggValue;
    }

    @Override
    public void action(SanFerminNode from, SanFerminNode to) {
      to.onSwapRequest(from, this);
    }

    @Override
    public int size() {
      // uint32 + sig size
      return 4 + signatureSize;
    }
  }



  public static void sigsPerTime() {
    NetworkLatency.NetworkLatencyByDistance nl = new NetworkLatency.NetworkLatencyByDistance();
    int nodeCt = 32768 / 2;
    SanFerminSignature ps1 = new SanFerminSignature(nodeCt, nodeCt, 2, 48, 300, 1, false);

    ps1.network.setNetworkLatency(nl);

    Graph graph = new Graph("number of sig per time", "time in ms", "sig count");
    Graph.Series series1min = new Graph.Series("sig count - worse node");
    Graph.Series series1max = new Graph.Series("sig count - best node");
    Graph.Series series1avg = new Graph.Series("sig count - avg");
    graph.addSerie(series1min);
    graph.addSerie(series1max);
    graph.addSerie(series1avg);

    ps1.init();

    StatsHelper.SimpleStats s;
    final long limit = 6000;
    do {
      ps1.network.runMs(10);
      s = StatsHelper.getStatsOn(ps1.allNodes, n -> ((SanFerminNode) n).aggValue);
      series1min.addLine(new Graph.ReportLine(ps1.network.time, s.min));
      series1max.addLine(new Graph.ReportLine(ps1.network.time, s.max));
      series1avg.addLine(new Graph.ReportLine(ps1.network.time, s.avg));
    } while (ps1.network.time < limit);

    try {
      graph.save(new File("/tmp/graph.png"));
    } catch (IOException e) {
      System.err.println("Can't generate the graph: " + e.getMessage());
    }

    System.out.println("bytes sent: " + StatsHelper.getStatsOn(ps1.allNodes, Node::getBytesSent));
    System.out
        .println("bytes rcvd: " + StatsHelper.getStatsOn(ps1.allNodes, Node::getBytesReceived));
    System.out.println("msg sent: " + StatsHelper.getStatsOn(ps1.allNodes, Node::getMsgSent));
    System.out.println("msg rcvd: " + StatsHelper.getStatsOn(ps1.allNodes, Node::getMsgReceived));
    System.out.println("done at: " + StatsHelper.getStatsOn(ps1.network.allNodes, n -> {
      long val = ((SanFerminNode) n).doneAt;
      return val == 0 ? limit : val;
    }));

  }

  public static void main(String... args) {

    if (false) {
      sigsPerTime();
      return;
    }


    int[] distribProp = {1, 33, 17, 12, 8, 5, 4, 3, 3, 1, 1, 2, 1, 1, 8};
    int[] distribVal = {12, 15, 19, 32, 35, 37, 40, 42, 45, 87, 155, 160, 185, 297, 1200};


    SanFerminSignature p2ps;
    //p2ps = new SanFerminSignature(1024, 10,512, 2,48,300,1,false);
    //p2ps = new SanFerminSignature(512, 9,256, 2,48,300,1,false);
    p2ps = new SanFerminSignature(4096, 2048, 2, 48, 300, 1, false);

    //p2ps = new SanFerminSignature(8, 3,4,48,300,1,false);


    //p2ps.verbose = true;
    p2ps.network.setNetworkLatency(distribProp, distribVal);
    //p2ps.network.removeNetworkLatency();

    p2ps.init();
    p2ps.network.run(30);
    int max = 10;
    // print results first reached threshold
    System.out.println(" --- First reaching threshold ---");
    p2ps.finishedNodes
        .stream()
        .sorted(Comparator.comparingLong(SanFerminNode::getThresholdAt))
        .limit(max)
        .forEach(System.out::println);

    System.out.println(" --- First reaching full sig ---");
    p2ps.finishedNodes
        .stream()
        .sorted(Comparator.comparingLong(SanFerminNode::getDoneAt))
        .limit(max)
        .forEach(System.out::println);


    System.out.println(" --- Unfinished nodes ---");
    p2ps.allNodes.stream().filter(n -> !n.done).limit(max).forEach(System.out::println);
    p2ps.network.printNetworkLatency();
  }
}
