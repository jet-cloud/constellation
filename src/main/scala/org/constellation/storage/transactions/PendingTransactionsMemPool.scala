package org.constellation.storage.transactions

import cats.effect.{Concurrent, Sync}
import cats.implicits._
import org.constellation.primitives.TransactionCacheData
import org.constellation.primitives.concurrency.SingleRef
import org.constellation.storage.PendingMemPool

class PendingTransactionsMemPool[F[_]: Concurrent]() extends PendingMemPool[F, TransactionCacheData] {

  private val txRef: SingleRef[F, Map[String, TransactionCacheData]] =
    SingleRef[F, Map[String, TransactionCacheData]](Map.empty)

  def put(key: String, value: TransactionCacheData): F[TransactionCacheData] =
    txRef.modify(txs => (txs + (key -> value), value))

  def update(key: String, fn: TransactionCacheData => TransactionCacheData): F[Unit] =
    txRef.update { txs =>
      txs.get(key).map(fn).map(t => txs ++ List(key -> t)).getOrElse(txs)
    }

  def lookup(key: String): F[Option[TransactionCacheData]] =
    txRef.get.map(_.find(_._2.hash == key).map(_._2))

  def contains(key: String): F[Boolean] =
    txRef.get.map(_.exists(_._2.hash == key))

  // TODO: Rethink - use queue
  def pull(minCount: Int): F[Option[List[TransactionCacheData]]] =
    txRef.modify { txs =>
      if (txs.size < minCount) {
        (txs, none[List[TransactionCacheData]])
      } else {
        val sorted = txs.toList.sortWith(_._2.transaction.edge.data.fee > _._2.transaction.edge.data.fee)
        val (left, right) = sorted.splitAt(minCount)
        (right.toMap, left.map(_._2).some)
      }
    }

  def size(): F[Long] =
    txRef.get.map(_.size.toLong)

}