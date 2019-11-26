package org.constellation.domain.transaction

import cats.effect.{Bracket, Concurrent}
import cats.effect.concurrent.Semaphore
import cats.implicits._
import org.constellation.primitives.TransactionCacheData
import org.constellation.storage.PendingMemPool

class PendingTransactionsMemPool[F[_]: Concurrent](
  transactionChainService: TransactionChainService[F],
  semaphore: Semaphore[F]
) extends PendingMemPool[F, String, TransactionCacheData](semaphore) {

  // TODO: Rethink - use queue
  def pull(maxCount: Int): F[Option[List[TransactionCacheData]]] =
    Bracket[F, Throwable].bracket(ref.acquire)(
      _ =>
        ref.getUnsafe
          .flatMap(
            txs =>
              if (txs.isEmpty) {
                none[List[TransactionCacheData]].pure[F]
              } else {
                sortAndFilterForPull(txs.values.toList).flatMap { sorted =>
                  val (toUse, _) = sorted.splitAt(maxCount)
                  val hashesToUse = toUse.map(_.hash)
                  val leftTxs = txs.filterKeys(!hashesToUse.contains(_))
                  ref.unsafeModify(_ => (leftTxs, Option(toUse).filter(_.nonEmpty)))
                }
              }
          )
    )(_ => ref.release)

  private def sortAndFilterForPull(txs: List[TransactionCacheData]): F[List[TransactionCacheData]] =
    txs
      .groupBy(_.transaction.src.address)
      .mapValues(_.sortBy(_.transaction.ordinal))
      .toList
      .pure[F]
      .flatMap { t =>
        t.traverse {
          case (hash, txs) =>
            transactionChainService
              .getLastAcceptedTransactionRef(hash)
              .map(txs.headOption.map(_.transaction.lastTxRef).contains)
              .ifM(
                txs.pure[F],
                List.empty[TransactionCacheData].pure[F]
              )
        }
      }
      .map(
        _.sortBy(_.map(-_.transaction.fee.getOrElse(0L)).sum).flatten
      )
}

object PendingTransactionsMemPool {

  def apply[F[_]: Concurrent](transactionChainService: TransactionChainService[F], semaphore: Semaphore[F]) =
    new PendingTransactionsMemPool[F](transactionChainService, semaphore)
}