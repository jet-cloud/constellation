package org.constellation.util

import cats.data.OptionT
import cats.effect.Concurrent
import cats.implicits._
import io.chrisdavenport.log4cats.Logger
import org.constellation.primitives.Schema._
import org.constellation.storage.RecentSnapshot

import scala.util.Random

class MajorityStateChooser[F[_]: Concurrent: Logger] {

  type NodeSnapshots = (Id, Seq[RecentSnapshot])
  type SnapshotNodes = (RecentSnapshot, Seq[Id])

  private final val differenceInSnapshotHeightToReDownloadFromLeader = 10

  def chooseMajorityState(
    nodeSnapshots: List[NodeSnapshots],
    ownHeight: Long
  ): OptionT[F, (Seq[RecentSnapshot], Set[Id])] =
    for {
      majorState <- chooseMajoritySnapshot(nodeSnapshots, ownHeight)
      nodeId <- OptionT.fromOption[F](chooseNodeId(majorState))
      node: (Id, Seq[RecentSnapshot]) <- OptionT.fromOption[F](findNode(nodeSnapshots, nodeId))
      _ <- OptionT.liftF(Logger[F].debug(s"Re-download from node : ${node}"))
    } yield dropToCurrentState(node, majorState)

  private def chooseMajoritySnapshot(nodeSnapshots: Seq[NodeSnapshots], ownHeight: Long) =
    for {
      highestSnapshot <- OptionT.fromOption[F](getHighest(nodeSnapshots))
      useHighest <- OptionT.liftF(shouldUseHighest(highestSnapshot, ownHeight))
      majorSnapshot <- OptionT.fromOption[F](chooseMajor(nodeSnapshots))

      _ <- OptionT.liftF(
        Logger[F].debug(
          s"The highest snapshot : $highestSnapshot : major snapshot : $majorSnapshot : use highest = $majorSnapshot"
        )
      )
    } yield if (useHighest) highestSnapshot else majorSnapshot

  private def dropToCurrentState(nodeSnapshot: NodeSnapshots, major: SnapshotNodes) =
    (nodeSnapshot._2.sortBy(_.height).reverse.dropWhile(_ != major._1), Set(nodeSnapshot._1))

  private def findNode(nodeSnapshots: Seq[NodeSnapshots], nodeId: Id) =
    nodeSnapshots.find(_._1 == nodeId)

  private def chooseNodeId(snapshotNodes: SnapshotNodes) =
    Random.shuffle(snapshotNodes._2) match {
      case Nil    => None
      case x :: _ => Some(x)
    }

  private def shouldUseHighest(snapshotNodes: SnapshotNodes, ownHeight: Long) =
    ((snapshotNodes._1.height - ownHeight) >= differenceInSnapshotHeightToReDownloadFromLeader).pure[F]

  private def chooseMajor(nodeSnapshots: Seq[NodeSnapshots]) =
    sortByHeightAndHash(nodeSnapshots).filter(_._2.lengthCompare(nodeSnapshots.count(_._2.nonEmpty) / 2) > 0) match {
      case Nil    => None
      case x :: _ => Some(x)
    }

  private def getHighest(nodeSnapshots: Seq[NodeSnapshots]) =
    sortByHeightAndHash(nodeSnapshots) match {
      case Nil    => None
      case x :: _ => Some(x)
    }

  private def sortByHeightAndHash(nodeSnapshots: Seq[NodeSnapshots]) =
    nodeSnapshots
      .flatMap(s => s._2.map((s._1, _)))
      .groupBy(_._2)
      .toList
      .sortBy(_._1.height)
      .reverse
      .map(s => (s._1, s._2.map(_._1)))
}
