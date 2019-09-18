package net.consensys.wittgenstein.protocols.handeleth2;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import java.util.*;
import net.consensys.wittgenstein.core.json.ListNodeConverter;

class HLevel {
  private final transient HNode hNode;

  final int level;
  final int peersCount;

  // peers, sorted in emission order
  @JsonSerialize(converter = ListNodeConverter.class)
  private final List<HNode> peers;

  final Map<Integer, Attestation> incoming;
  private final Map<Integer, BitSet> indIncoming;
  final Map<Integer, Attestation> outgoing;

  int incomingCardinality = 0;
  int outgoingCardinality = 0;

  // The aggregate signatures to verify in this level
  final List<AggToVerify> toVerifyAgg = new LinkedList<>();

  // all our peers are complete: no need to send anything for this level
  public boolean outgoingFinished = false;

  /**
   * We're going to contact all nodes, one after the other. That's our position in the peers' list.
   */
  private int posInLevel = 0;

  /** Build a level 0 object. At level 0 we need (and have) only our own signature. */
  HLevel(HNode hNode, Attestation l0) {
    this.hNode = hNode;
    level = 0;
    peers = Collections.emptyList(); // no peers at level 0
    peersCount = 1; // only our own sig;
    incomingCardinality = 1;
    incoming = Collections.singletonMap(l0.hash, l0);
    outgoing = Collections.emptyMap();
    outgoingFinished = true; // nothing to send.
    indIncoming = Collections.singletonMap(l0.hash, new BitSet());
    indIncoming.get(l0.hash).set(hNode.nodeId);
  }

  /** Build a level on top of the previous one. */
  HLevel(HLevel previousLevel, List<HNode> peers) {
    this.hNode = previousLevel.hNode;
    this.level = previousLevel.level + 1;
    this.peersCount = 1 << (level - 1);
    this.peers = peers;
    if (peers.size() != peersCount) {
      throw new IllegalStateException("size=" + peersCount + ", peers.size()=" + peers.size());
    }

    this.incoming = new HashMap<>();
    this.outgoing = new HashMap<>();
    this.indIncoming = new HashMap<>();
  }

  void doCycle(int ownhash, BitSet finishedPeers) {
    if (!isOpen()) {
      return;
    }

    List<HNode> dest = getRemainingPeers(finishedPeers, 1);
    if (!dest.isEmpty()) {
      SendAggregation ss =
          new SendAggregation(
              level, ownhash, isIncomingComplete(), new ArrayList<>(outgoing.values()));
      this.hNode.handelEth2.network().send(ss, this.hNode, dest.get(0));
    }
  }

  /** We start a level if we reached the time out or if we have all the contributions. */
  boolean isOpen() {
    if (outgoingFinished) {
      return false;
    }

    if (hNode.handelEth2.network().time >= (level - 1) * hNode.handelEth2.params.levelWaitTime) {
      return true;
    }

    if (isOutgoingComplete()) {
      return true;
    }

    return false;
  }

  /**
   * @return the next 'peersCt' peers to contact. Skips the nodes blacklisted or already full for
   *     this level. If there are no peers left, sets 'outgoingFinished' to true.
   */
  private int lastMessageCardinality = 0;

  private int lastNode = 0;

  List<HNode> getRemainingPeers(BitSet finishedPeers, int peersCt) {
    List<HNode> res = new ArrayList<>(peersCt);

    int start = posInLevel;
    while (peersCt > 0 && !outgoingFinished) {

      HNode p = peers.get(posInLevel++);
      if (posInLevel >= peers.size()) {
        posInLevel = 0;
      }

      if (!finishedPeers.get(p.nodeId) && !hNode.blacklist.get(p.nodeId)) {
        res.add(p);
        peersCt--;
      } else {
        if (posInLevel == start) {
          outgoingFinished = true;
        }
      }

      if (lastMessageCardinality == outgoingCardinality && p.nodeId == lastNode) {
        return res;
      }
    }

    // todo this is an attempt to limit the number of message we send
    //  for the first levels: don't send twice the same message to the same node.
    if (!res.isEmpty()) {
      if (outgoingCardinality > lastMessageCardinality) {
        lastMessageCardinality = outgoingCardinality;
        lastNode = res.get(0).nodeId;
      }
    }

    return res;
  }

  /** @return the size the resulting aggregation if we merge the signature to our current best. */
  private int sizeIfMerged(AggToVerify sig) {
    Map<Integer, Attestation> aggMap = new HashMap<>(incoming);

    int size = 0;
    for (Attestation av : sig.attestations) {
      Attestation our = aggMap.remove(av.hash);
      if (our == null) {
        // We did not have an attestation for this hash. We will be able
        //  to integrate directly the aggregate sig.
        size += av.who.cardinality();
      } else if (!our.who.intersects(av.who)) {
        // We had it, but it does not intersect. We can add it as well.
        size += our.who.cardinality() + av.who.cardinality();
      } else {
        // It overlaps. We need to choose the best option. Existing, or
        //  new if we merge with the individual contributions kept.
        BitSet indivs = indIncoming.get(our.hash);
        BitSet merged = av.who;
        if (indivs != null) {
          merged = (BitSet) indivs.clone();
          merged.or(av.who);
        }
        size += Math.max(merged.cardinality(), our.who.cardinality());
      }
    }

    for (Attestation our : aggMap.values()) {
      size += our.who.cardinality();
    }

    if (size > this.peersCount) {
      throw new IllegalStateException("bad size: " + size + ", level=" + this);
    }

    return size;
  }

  /**
   * Merge the incoming aggregation into our current best, and update the 'incomingCardinality'
   * field accordingly.
   */
  void mergeIncoming(AggToVerify aggv) {
    // Add the individual contributions to the list
    BitSet indivs = indIncoming.computeIfAbsent(aggv.ownHash, x -> new BitSet());
    indivs.set(aggv.from);

    // Merge the aggregate contributions when possible. Take the best one when it's not possible
    for (Attestation av : aggv.attestations) {
      Attestation our = incoming.get(av.hash);
      if (our == null) {
        incoming.put(av.hash, av);
        incomingCardinality += av.who.cardinality();
      } else if (!our.who.intersects(av.who)) {
        Attestation both = new Attestation(our, our.who);
        both.who.or(av.who);
        incoming.replace(both.hash, both);
        incomingCardinality += av.who.cardinality();
      } else {
        BitSet indivsH = indIncoming.get(our.hash);
        BitSet merged = av.who;
        if (indivsH != null) {
          merged = (BitSet) indivsH.clone();
          merged.or(av.who);
        }
        if (merged.cardinality() > our.who.cardinality()) {
          incomingCardinality -= our.who.cardinality();
          Attestation both = new Attestation(our, merged);
          incoming.replace(both.hash, both);
          incomingCardinality += both.who.cardinality();
        }
      }
    }

    if (incomingCardinality > this.peersCount) {
      throw new IllegalStateException(
          "bad incomingCardinality: " + incomingCardinality + ", level=" + this);
    }
  }

  /** @return true if we have received all the contributions we need for this level. */
  boolean isIncomingComplete() {
    return incomingCardinality == peersCount;
  }

  /** @return true if we have all the contributions we're supposed to send for this level. */
  boolean isOutgoingComplete() {
    return outgoingCardinality == peersCount;
  }

  /**
   * This method uses a window that has a variable size depending on whether the node has received
   * invalid contributions or not. Within the window, it evaluates with a scoring function. Outside
   * it evaluates with the rank.
   */
  AggToVerify bestToVerify(int currWindowSize, BitSet blacklist) {
    if (currWindowSize < 1) {
      throw new IllegalStateException();
    }

    if (toVerifyAgg.isEmpty()) {
      return null;
    }

    if (isIncomingComplete()) {
      toVerifyAgg.clear();
      return null;
    }

    int windowIndex = hNode.handelEth2.params.nodeCount;
    AggToVerify bestOutside = null; // best signature outside the window - rank based decision
    AggToVerify bestInside = null; // best signature inside the window - ranking
    int bestScoreOutside = 0; // score associated to the best sig. inside the window
    int bestScoreInside = 0; // score associated to the best sig. inside the window

    Iterator<AggToVerify> it = toVerifyAgg.iterator();
    while (it.hasNext()) {
      AggToVerify atv = it.next();
      int s = sizeIfMerged(atv);

      // only add signatures that can result in a better aggregate signature
      // select the high priority one from the low priority on
      if (blacklist.get(atv.from) || s <= incomingCardinality) {
        it.remove();
        continue;
      }

      if (atv.rank < windowIndex) {
        windowIndex = atv.rank;
      }

      if (s > bestScoreOutside) {
        bestScoreOutside = s;
        bestOutside = atv;
      }
    }

    // todo: we're not respecting the window's limits

    AggToVerify toVerify;
    if (bestInside != null) {
      toVerify = bestInside;
    } else if (bestOutside != null) {
      toVerify = bestOutside;
    } else {
      return null;
    }

    return toVerify;
  }

  @Override
  public String toString() {
    return "level:"
        + level
        + ", ic:"
        + isIncomingComplete()
        + ", oc:"
        + isOutgoingComplete()
        + ", is:"
        + incomingCardinality
        + ", os:"
        + outgoingCardinality;
  }
}
