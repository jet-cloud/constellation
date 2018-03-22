package org.constellation.primitives

import java.security.{PrivateKey, PublicKey}

import constellation._
import org.constellation.wallet.KeyUtils

object Transaction {

  case class Transaction(sequenceNum: Long,
                         senderPubKey: PublicKey,
                         counterPartyPubKey: PublicKey,
                         amount: Long,
                         senderSignature: String = "",
                         counterPartySignature: String = "",
                         time: Long = System.currentTimeMillis())

  // TODO: fill these out and replace usage where applicable
  case class SenderSignedTransaction()
  case class CounterPartySignedTransaction()
  case class FullySignedTransaction()

  private def senderSignInput(t: Transaction): Array[Byte] =
    Seq(t.sequenceNum, t.senderPubKey, t.counterPartyPubKey, t.amount).json.getBytes()

  def senderSign(transaction: Transaction, privateKey: PrivateKey): Transaction = {
    transaction.copy(
      senderSignature = KeyUtils.base64(KeyUtils.signData(
        senderSignInput(transaction)
      )(privateKey))
    )
  }

  private def counterPartySignInput(t: Transaction) =
    Seq(t.sequenceNum, t.senderPubKey,
      t.counterPartyPubKey, t.amount, t.senderSignature).json.getBytes()

  def counterPartySign(transaction: Transaction, privateKey: PrivateKey): Transaction = transaction.copy(
    counterPartySignature = KeyUtils.base64(KeyUtils.signData(
      counterPartySignInput(transaction)
    )(privateKey))
  )

  // Hash of TX info
  def hash(t: Transaction): String = Seq(
    t.sequenceNum, t.senderPubKey, t.counterPartyPubKey, t.amount, t.senderSignature, t.counterPartySignature
  ).json.sha256

  def validCounterPartySignature(transaction: Transaction): Boolean = {
    KeyUtils.verifySignature(
      counterPartySignInput(transaction), KeyUtils.fromBase64(transaction.counterPartySignature)
    )(transaction.counterPartyPubKey)
  }

  def validSenderSignature(transaction: Transaction): Boolean = {
    KeyUtils.verifySignature(
      senderSignInput(transaction), KeyUtils.fromBase64(transaction.senderSignature)
    )(transaction.senderPubKey)
  }

  def valid(transaction: Transaction): Boolean =
    validSenderSignature(transaction) && validCounterPartySignature(transaction)

}


