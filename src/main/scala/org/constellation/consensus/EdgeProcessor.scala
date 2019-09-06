package org.constellation.consensus

import java.io.IOException
import java.nio.file.Path

import better.files.File
import cats.data.NonEmptyList
import cats.data.Validated.{Invalid, Valid}
import cats.effect.{ContextShift, IO}
import cats.implicits._
import com.typesafe.scalalogging.StrictLogging
import constellation._
import org.constellation.p2p.PeerData
import org.constellation.primitives.Schema._
import org.constellation.primitives._
import org.constellation.serializer.KryoSerializer
import org.constellation.util.Validation.EnrichedFuture
import org.constellation.util._
import org.constellation.{ConfigUtil, ConstellationExecutionContext, DAO}

import scala.async.Async.{async, await}
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future}
import scala.util.{Failure, Try}

case class CreateCheckpointEdgeResponse(
  checkpointEdge: CheckpointEdge,
  transactionsUsed: Set[String],
  // filteredValidationTips: Seq[SignedObservationEdge],
  updatedTransactionMemPoolThresholdMet: Set[String]
)

case class SignatureRequest(checkpointBlock: CheckpointBlock, facilitators: Set[Id])

case class SignatureResponse(signature: Option[HashSignature], reRegister: Boolean = false)
case class FinishedCheckpoint(checkpointCacheData: CheckpointCache, facilitators: Set[Id])

case class FinishedCheckpointResponse(isSuccess: Boolean = false)

object EdgeProcessor extends StrictLogging {

  private def requestBlockSignature(
    checkpointBlock: CheckpointBlock,
    finalFacilitators: Set[
      Id
    ],
    data: PeerData
  )(implicit dao: DAO, ec: ExecutionContext) =
    async {
      val sigResp = await(
        data.client.postNonBlocking[SignatureResponse](
          "request/signature",
          SignatureRequest(checkpointBlock, finalFacilitators + dao.id),
          15.seconds
        )
      )

      if (sigResp.reRegister) {
        // PeerManager.attemptRegisterPeer() TODO : Finish
      }

      sigResp.signature
    }

  private def processSignedBlock(
    cache: CheckpointCache,
    finalFacilitators: Set[
      Id
    ]
  )(implicit dao: DAO, ec: ExecutionContext) = {

    val responses = dao.peerInfo.unsafeRunSync().values.toList.map { peer =>
      wrapFutureWithMetric(
        peer.client.postNonBlockingUnit(
          "finished/checkpoint",
          FinishedCheckpoint(cache, finalFacilitators),
          timeout = 8.seconds,
          Map(
            "ReplyTo" -> APIClient(dao.nodeConfig.hostName, dao.nodeConfig.peerHttpPort)
              .base("finished/reply")
          )
        ),
        "finishedCheckpointBroadcast"
      )
    }

    responses.traverse(_.toValidatedNel).map(_.sequence)
  }

  def formCheckpoint(messages: Seq[ChannelMessage] = Seq())(
    implicit dao: DAO
  ) = {

    implicit val ec: ExecutionContextExecutor = ConstellationExecutionContext.bounded

    val transactions = dao.transactionService
      .pullForConsensus(dao.processingConfig.maxCheckpointFormationThreshold)
      .map(_.map(_.transaction))
      .unsafeRunSync()

    dao.metrics.incrementMetric("attemptFormCheckpointCalls")

    if (transactions.isEmpty) {
      dao.metrics.incrementMetric("attemptFormCheckpointNoTX")
    }
    val readyFacilitators = dao.readyFacilitatorsAsync.unsafeRunSync()

    if (readyFacilitators.isEmpty) {
      dao.metrics.incrementMetric("attemptFormCheckpointInsufficientTipsOrFacilitators")
      if (dao.nodeConfig.isGenesisNode) {
        val maybeTips = dao.concurrentTipService.pull(Map.empty)(dao.metrics).unsafeRunSync()
        if (maybeTips.isEmpty) {
          dao.metrics.incrementMetric("attemptFormCheckpointNoGenesisTips")
        }
        maybeTips.foreach { pulledTip =>
          val checkpointBlock =
            CheckpointBlock.createCheckpointBlock(transactions, pulledTip.tipSoe.soe.map { soe =>
              TypedEdgeHash(soe.hash, EdgeHashType.CheckpointHash)
            }, messages)(dao.keyPair)

          val cache =
            CheckpointCache(
              Some(checkpointBlock),
              height = checkpointBlock.calculateHeight()
            )

          dao.checkpointService.accept(cache).unsafeRunSync()
          dao.threadSafeMessageMemPool.release(messages)

        }
      }

    }

    val result = dao.concurrentTipService.pull(readyFacilitators)(dao.metrics).unsafeRunSync().map { pulledTip =>
      // Change to method on TipsReturned // abstract for reuse.
      val checkpointBlock = CheckpointBlock.createCheckpointBlock(transactions, pulledTip.tipSoe.soe.map { soe =>
        TypedEdgeHash(soe.hash, EdgeHashType.CheckpointHash)
      }, messages)(dao.keyPair)
      dao.metrics.incrementMetric("checkpointBlocksCreated")

      val finalFacilitators = pulledTip.peers.keySet

      val signatureResults = pulledTip.peers.values.toList.traverse { peerData =>
        requestBlockSignature(checkpointBlock, finalFacilitators, peerData).toValidatedNel
      }.flatMap { signatureResultList =>
        signatureResultList.sequence.map { signatures =>
          // Unsafe flatten -- revisit during consensus updates
          signatures.flatten.foldLeft(checkpointBlock) {
            case (cb, hs) =>
              cb.plus(hs)
          }
        }.ensure(NonEmptyList.one(new Throwable("Invalid CheckpointBlock")))(
            _.simpleValidation()
          )
          .traverse { finalCB =>
            val cache = CheckpointCache(finalCB.some, height = finalCB.calculateHeight())
            dao.checkpointService.accept(cache).unsafeRunSync()
            processSignedBlock(
              cache,
              finalFacilitators
            )
          }
      }

      wrapFutureWithMetric(signatureResults, "checkpointBlockFormation")

      signatureResults.foreach {
        case Valid(_) =>
        case Invalid(failures) =>
          failures.toList.foreach { e =>
            dao.metrics.incrementMetric(
              "formCheckpointSignatureResponseError"
            )
            logger.warn("Failure gathering signature", e)
          }

      }

      // using transform kind of like a finally for Future.
      // I want to ensure the locks get cleaned up
      signatureResults.transform { res =>
        // Cleanup locks

        dao.threadSafeMessageMemPool.release(messages)
        res.map(_ => true)
      }
    }
    result.sequence
  }

  def handleSignatureRequest(
    sr: SignatureRequest
  )(implicit dao: DAO): Future[Try[SignatureResponse]] =
    futureTryWithTimeoutMetric(
      {
        dao.metrics.incrementMetric("peerApiRXSignatureRequest")

        val hashes = sr.checkpointBlock.checkpoint.edge.data.hashes
        dao.metrics.incrementMetric(
          "signatureRequestAllHashesKnown_" + hashes.forall { h =>
            dao.transactionService.lookup(h).map(_.nonEmpty).unsafeRunSync()
          }
        )

        val updated = if (sr.checkpointBlock.simpleValidation()) {
          Some(hashSign(sr.checkpointBlock.baseHash, dao.keyPair))
        } else {
          None
        }
        SignatureResponse(updated)
      },
      "handleSignatureRequest"
    )(ConstellationExecutionContext.bounded, dao)

}

case class TipData(checkpointBlock: CheckpointBlock, numUses: Int, height: Height)

case class SnapshotInfo(
  snapshot: Snapshot,
  acceptedCBSinceSnapshot: Seq[String] = Seq(),
  acceptedCBSinceSnapshotCache: Seq[CheckpointCache] = Seq(),
  lastSnapshotHeight: Int = 0,
  snapshotHashes: Seq[String] = Seq(),
  addressCacheData: Map[String, AddressCacheData] = Map(),
  tips: Map[String, TipData] = Map(),
  snapshotCache: Seq[CheckpointCache] = Seq()
)

case object GetMemPool

case class Snapshot(lastSnapshot: String, checkpointBlocks: Seq[String]) extends Signable

case class StoredSnapshot(snapshot: Snapshot, checkpointCache: Seq[CheckpointCache])

case class DownloadComplete(latestSnapshot: Snapshot)

import java.nio.file.{Files, Paths}

object Snapshot extends StrictLogging {

  def writeSnapshot(storedSnapshot: StoredSnapshot)(implicit dao: DAO): Try[Path] = {
    val serialized = KryoSerializer.serializeAnyRef(storedSnapshot)
    val write = writeSnapshot(storedSnapshot, serialized)
    logger.debug(s"[${dao.id.short}] written snapshot at path ${write.map(_.toAbsolutePath.toString)}")
    write
  }

  private def writeSnapshot(storedSnapshot: StoredSnapshot, serialized: Array[Byte], trialNumber: Int = 0)(
    implicit dao: DAO
  ): Try[Path] =
    trialNumber match {
      case x if x >= 3 => Failure(new IOException(s"Unable to write snapshot"))
      case _ if isOverDiskCapacity(serialized.length) =>
        removeOldSnapshots()
        writeSnapshot(storedSnapshot, serialized, trialNumber + 1)
      case _ =>
        tryWithMetric(
          {
            Files.write(Paths.get(dao.snapshotPath.pathAsString, storedSnapshot.snapshot.hash), serialized)
          },
          "writeSnapshot"
        )
    }

  def removeOldSnapshots()(implicit dao: DAO): Unit =
    removeSnapshots(
      snapshotHashes().diff(dao.snapshotBroadcastService.getRecentSnapshots.map(_.map(_.hash)).unsafeRunSync()),
      dao.snapshotPath.pathAsString
    )

  def removeSnapshots(snapshots: List[String], snapshotPath: String)(implicit dao: DAO): Unit = {
    if (shouldSendSnapshotsToCloud(snapshotPath)) {
      sendSnapshotsToCloud(snapshots)(dao, IO.contextShift(ConstellationExecutionContext.bounded)).unsafeRunSync()
    }

    snapshots.foreach { snapId =>
      tryWithMetric(
        {
          logger.debug(
            s"[${dao.id.short}] removing snapshot at path ${Paths.get(snapshotPath, snapId).toAbsolutePath.toString}"
          )
          Files.delete(Paths.get(snapshotPath, snapId))
        },
        "deleteSnapshot"
      )
    }
  }

  private def sendSnapshotsToCloud(snapshotsHash: List[String])(implicit dao: DAO, cs: ContextShift[IO]): IO[Unit] =
    for {
      files <- cs.evalOn(ConstellationExecutionContext.unbounded)(
        getFiles(snapshotsHash, dao.snapshotPath.pathAsString)
      )
      blobsNames <- cs.evalOn(ConstellationExecutionContext.unbounded)(dao.cloudStorage.upload(files))
      _ <- IO.delay(logger.debug(s"Snapshots send to cloud amount : ${blobsNames.size}"))
    } yield ()

  private def getFiles(snapshotsHash: List[String], snapshotPath: String): IO[List[File]] =
    snapshotsHash.traverse(hash => IO.delay(File(snapshotPath, hash)))

  private def shouldSendSnapshotsToCloud(snapshotsPath: String): Boolean =
    ConfigUtil.getOrElse("constellation.storage.enabled", default = false)

  def isOverDiskCapacity(bytesLengthToAdd: Long)(implicit dao: DAO): Boolean = {
    val sizeDiskLimit = ConfigUtil.snapshotSizeDiskLimit
    if (sizeDiskLimit == 0) return false

    val storageDir = new java.io.File(dao.snapshotPath.pathAsString)
    val usableSpace = storageDir.getUsableSpace
    val occupiedSpace = dao.snapshotPath.size
    val isOver = occupiedSpace + bytesLengthToAdd > sizeDiskLimit || usableSpace < bytesLengthToAdd
    if (isOver) {
      logger.warn(
        s"[${dao.id.short}] isOverDiskCapacity bytes to write ${bytesLengthToAdd} configured space: ${ConfigUtil.snapshotSizeDiskLimit} occupied space: $occupiedSpace usable space: $usableSpace"
      )
    }
    isOver
  }

  def loadSnapshot(snapshotHash: String)(implicit dao: DAO): Try[StoredSnapshot] =
    tryWithMetric(
      {
        KryoSerializer.deserializeCast[StoredSnapshot] {
          val byteArray = Files.readAllBytes(Paths.get(dao.snapshotPath.pathAsString, snapshotHash))
          //   val f = File(dao.snapshotPath, snapshotHash)
          byteArray
        }
      },
      "loadSnapshot"
    )

  def loadSnapshotBytes(snapshotHash: String)(implicit dao: DAO): Try[Array[Byte]] =
    tryWithMetric(
      {
        val path = Paths.get(dao.snapshotPath.pathAsString, snapshotHash)
        if (Files.exists(path)) {
          val byteArray = Files.readAllBytes(path)
          //   val f = File(dao.snapshotPath, snapshotHash)
          byteArray
        } else throw new RuntimeException(s"${dao.id.short} No snapshot found at $path")
      },
      "loadSnapshot"
    )

  def snapshotHashes()(implicit dao: DAO): List[String] =
    dao.snapshotPath.list.map { _.name }.toList

  def findLatestMessageWithSnapshotHash(
    depth: Int,
    lastMessage: Option[ChannelMessageMetadata],
    maxDepth: Int = 100
  )(implicit dao: DAO): Option[ChannelMessageMetadata] = {

    def findLatestMessageWithSnapshotHashInner(
      depth: Int,
      lastMessage: Option[ChannelMessageMetadata]
    ): Option[ChannelMessageMetadata] =
      if (depth > maxDepth) None
      else {
        lastMessage.flatMap { m =>
          if (m.snapshotHash.nonEmpty) Some(m)
          else {
            findLatestMessageWithSnapshotHashInner(
              depth + 1,
              dao.messageService.memPool
                .lookup(
                  m.channelMessage.signedMessageData.data.previousMessageHash
                )
                .unsafeRunSync()
            )
          }
        }
      }

    findLatestMessageWithSnapshotHashInner(depth, lastMessage)
  }

  val snapshotZero = Snapshot("", Seq())
  val snapshotZeroHash: String = Snapshot("", Seq()).hash

}
