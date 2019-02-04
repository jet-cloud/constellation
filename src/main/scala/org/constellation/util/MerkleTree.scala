package org.constellation.util

/** Documentation. */
case class MerkleNode(hash: String, leftChild: String, rightChild: String) {

  /** Documentation. */
  def children = Seq(leftChild, rightChild)

  /** Documentation. */
  def isParentOf(other: String): Boolean = children.contains(other)

  /** Documentation. */
  def valid: Boolean = MerkleTree.merkleHashFunc(leftChild, rightChild) == hash
}

/** Documentation. */
case class MerkleProof(input: String, nodes: Seq[MerkleNode], root: String) {

  /** Documentation. */
  def verify(): Boolean = {
    val childToParent = MerkleTree.childToParent(nodes)
    val parents = MerkleTree.collectParents(Seq(), childToParent(input), childToParent)
    parents.last.hash == root && parents.head.isParentOf(input) && parents.forall{_.valid}
  }
}

/** Documentation. */
case class MerkleResult(inputs: Seq[String], nodes: Seq[MerkleNode]) {

  /** Documentation. */
  def createProof(startingPoint: String): MerkleProof = {
    val parentMap = MerkleTree.childToParent(nodes)
    val firstParent = parentMap(startingPoint)
    MerkleProof(startingPoint, MerkleTree.collectParents(Seq(), firstParent, parentMap), nodes.last.hash)
  }
}

import com.typesafe.scalalogging.Logger
import constellation.SHA256Ext

// This should be changed to an actual tree structure in memory. Just skipping that for now
// Either that or replace this with a pre-existing implementation
// Couldn't find any libs that were easy drop ins so just doing this for now

/** Documentation. */
object MerkleTree {

  val logger = Logger("MerkleTree")

  /** Documentation. */
  def childToParent(nodes: Seq[MerkleNode]): Map[String, MerkleNode] = nodes.flatMap{ n =>
    n.children.map{
      _ -> n
    }
  }.toMap

  /** Documentation. */
  def collectParents(
                       parents: Seq[MerkleNode],
                       activeNode: MerkleNode,
                       childToParent: Map[String, MerkleNode]
                     ): Seq[MerkleNode] = {

    val newParents = parents :+ activeNode
    childToParent.get(activeNode.hash) match {
      case None =>
        newParents
      case Some(parent) =>
        collectParents(newParents, parent, childToParent)
    }
  }

  /** Documentation. */
  def apply(hashes: List[String]): MerkleResult = {

    if (hashes.isEmpty) {
      throw new Exception("Merkle function call on empty collection of hashes")
    }
    val even = if (hashes.size % 2 != 0) hashes :+ hashes.last else hashes
    logger.debug(s"Creating Merkle tree on ${even.length} hashes")

    val zero = applyRound(even)
    MerkleResult(hashes, merkleIteration(Seq(), zero))
  }

  /** Documentation. */
  def merkleHashFunc(left: String, right: String): String = {
    (left + right).sha256
  }

  /** Documentation. */
  def applyRound(level: Seq[String]): Seq[MerkleNode] = {
    logger.debug(s"Applying Merkle round on ${level.length} level length")
    level.grouped(2).toSeq.map{
      case Seq(l, r) =>
        MerkleNode(merkleHashFunc(l,r), l, r)
      case Seq(l) =>
        MerkleNode(merkleHashFunc(l,l), l, l)
    }
  }

  /** Documentation. */
  def merkleIteration(agg: Seq[MerkleNode], currentLevel: Seq[MerkleNode]): Seq[MerkleNode] = {
    if (currentLevel.size == 1) {
      agg ++ currentLevel
    } else {
      val nextLevel = applyRound(currentLevel.map{_.hash})
      merkleIteration(agg ++ currentLevel, nextLevel)
    }
  }

}
