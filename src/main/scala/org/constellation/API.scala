package org.constellation

import java.net.InetSocketAddress
import java.security.{KeyPair, PublicKey}

import akka.actor.ActorRef
import akka.http.scaladsl.marshalling.Marshaller._
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives.{entity, path, _}
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.unmarshalling.{FromEntityUnmarshaller, PredefinedFromEntityUnmarshallers}
import akka.pattern.ask
import akka.util.Timeout
import com.typesafe.scalalogging.Logger
import constellation._
import de.heikoseeberger.akkahttpjson4s.Json4sSupport
import org.constellation.crypto.Wallet
import org.constellation.primitives.Schema._
import org.constellation.primitives.{Schema, Transaction}
import org.constellation.state.ChainStateManager.{CurrentChainStateUpdated, GetCurrentChainState}
import org.constellation.state.MemPoolManager.AddTransaction
import org.constellation.util.ServeUI
import org.json4s.native
import org.json4s.native.Serialization

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

class API(
           chainStateActor: ActorRef,
           val peerToPeerActor: ActorRef,
           memPoolManagerActor: ActorRef,
           consensusActor: ActorRef,
           udpAddress: InetSocketAddress,
           val data: Data = null,
           val jsPrefix: String = "./ui/target/scala-2.11/ui"
         )
         (implicit executionContext: ExecutionContext, val timeout: Timeout) extends Json4sSupport
  with Wallet
  with ServeUI {

  import data._

  implicit val serialization: Serialization.type = native.Serialization

  implicit val stringUnmarshaller: FromEntityUnmarshaller[String] =
    PredefinedFromEntityUnmarshallers.stringUnmarshaller

  val logger = Logger(s"RPCInterface")

  val routes: Route =
    get {
      path("submitTX") {
        parameter('address, 'amount) { (address, amount) =>
          logger.error(s"SubmitTX : $address $amount")
          handleSendRequest(SendToAddress(Address(address), amount.toLong))
        }
      } ~
      pathPrefix("address") {
        get {
          extractUnmatchedPath { p =>
            logger.debug(s"Unmatched path on address result $p")
            val ps = p.toString().tail
            val balance = validLedger.getOrElse(ps, 0).toString
            complete(s"Balance: $balance")
          }
        }
      } ~
      pathPrefix("txHash") {
        get {
          extractUnmatchedPath { p =>
            logger.debug(s"Unmatched path on address result $p")
            val ps = p.toString().tail
            complete(transactionData(ps).prettyJson)
          }
        }
      } ~
      path("setKeyPair") {
        parameter('keyPair) { kpp =>
          logger.debug("Set key pair " + kpp)
          val res = if (kpp.length > 10) {
            val rr = Try {
              data.updateKeyPair(kpp.x[KeyPair])
              StatusCodes.OK
            }.getOrElse(StatusCodes.BadRequest)
            rr
          } else StatusCodes.BadRequest
          complete(res)
        }
      } ~
        path("metrics") {
          complete(Metrics(Map(
            "address" -> selfAddress.address,
            "balance" -> (selfIdBalance.getOrElse(0L) / Schema.NormalizationFactor).toString,
            "id" -> id.b58,
            "keyPair" -> keyPair.json,
            "shortId" -> id.short,
            "numValidTransactions" -> validTX.size.toString,
            "memPoolSize" -> memPoolTX.size.toString,
            "totalNumGossipMessages" -> totalNumGossipMessages.toString,
            "numPeers" -> peers.size.toString,
            "peers" -> peers.map{ z =>
              val addr = s"http://${z.data.apiAddress.getHostName}:${z.data.apiAddress.getPort}"
              s"${z.data.id.short} API: $addr "
            }.mkString(" --- "),
            "last10TXHash" -> sentTX.reverse.slice(0, 10).map{_.hash}.mkString(","),
            "z_peers" -> peers.map{_.data}.json,
            "z_UTXO" -> validLedger.toMap.json
            /*"z_Bundles" -> bundles.sorted.map{
              b =>
                s"tx:${b.extractTX.size},"

            }.mkString("\n")*/
          )))
        } ~
        path("validTX") {
          complete(validTX)
        } ~
        path("walletAddressInfo") {
          complete(walletAddressInfo)
        } ~
        path("balances") {
          complete(outputBalances)
        } ~
        path("makeKeyPair") {
          val pair = constellation.makeKeyPair()
          wallet :+= pair
          complete(pair)
        } ~
        path("genesis" / LongNumber) { numCoins =>

          val debtAddress = walletPair
          //   val dstAddress = walletPair

          val amount = numCoins * Schema.NormalizationFactor
          val tx = TX(
            TXData(
              Seq(debtAddress.address.copy(balance = -1*amount)),
              selfAddress,
              amount,
              genesisTXHash = None,
              isGenesis = true
            ).signed()(debtAddress)
          )

          peerToPeerActor ! tx
          complete(tx.json)
        } ~
        path("makeKeyPairs" / IntNumber) { numPairs =>
          val pair = Seq.fill(numPairs){constellation.makeKeyPair()}
          wallet ++= pair
          complete(pair)
        } ~
        path("wallet") {
          complete(wallet)
        } ~
        path("selfAddress") {
          //  val pair = constellation.makeKeyPair()
          //  wallet :+= pair
          //  complete(constellation.pubKeyToAddress(pair.getPublic))
          complete(id.address)
        } ~
        path("id") {
          complete(id)
        } ~
        path("nodeKeyPair") {
          complete(keyPair)
        } ~
        // TODO: revisit
        path("health") {
          complete(StatusCodes.OK)
        } ~
        path("blocks") {
          val future = chainStateActor ? GetCurrentChainState

          val responseFuture: Future[CurrentChainStateUpdated] = future.mapTo[CurrentChainStateUpdated]

          val chainStateUpdated = Await.result(responseFuture, timeout.duration)

          val chain = chainStateUpdated.chain

          val blocks = chain.chain

          complete(blocks)
        } ~
        path("peers") {
          complete(Peers(peerIPs.toSeq))
        } ~
        path("peerids") {
          complete(peers.map{_.data})
        } ~
        path("actorPath") {
          complete(peerToPeerActor.path.toSerializationFormat)
        } ~
        path("balance") {
          entity(as[PublicKey]) { account =>
            logger.debug(s"Received request to query account $account balance")

            // TODO: update balance
            // complete((chainStateActor ? GetBalance(account)).mapTo[Balance])

            complete(StatusCodes.OK)
          }
        } ~
        jsRequest ~
        serveMainPage
    } ~
      post {
        path("setTXValid") {
          entity(as[TX]) { tx =>
            acceptTransaction(tx)
            tx.updateLedger(memPoolLedger)
            complete(StatusCodes.OK)
          }
        } ~
        path ("sendToAddress") {
          entity(as[SendToAddress]) { s =>
            handleSendRequest(s)
          }
        } ~
          path("db") {
            entity(as[String]){ e: String =>
              import constellation.EasyFutureBlock
              val cleanStr = e.replaceAll('"'.toString, "")
              val res = db.get(cleanStr)
              complete(res)
            }
          } ~
          path ("tx") {
            entity(as[TX]) { tx =>
              peerToPeerActor ! tx
              complete(StatusCodes.OK)
            }
          } ~
          path("transaction") {
            entity(as[Transaction]) { transaction =>
              //  logger.debug(s"Received request to submit a new transaction $transaction")

              val tx = AddTransaction(transaction)
              peerToPeerActor ! tx
              memPoolManagerActor ! tx
              complete(transaction)
            }
          } ~
          path("peer") {
            entity(as[String]) { peerAddress =>

              Try {
                //    logger.debug(s"Received request to add a new peer $peerAddress")
                val result = Try {
                  peerAddress.replaceAll('"'.toString, "").split(":") match {
                    case Array(ip, port) => new InetSocketAddress(ip, port.toInt)
                    case a@_ => logger.debug(s"Unmatched Array: $a"); throw new RuntimeException(s"Bad Match: $a");
                  }
                }.toOption match {
                  case None =>
                    StatusCodes.BadRequest
                  case Some(v) =>
                    val fut = (peerToPeerActor ? AddPeerFromLocal(v)).mapTo[StatusCode]
                    val res = Try {
                      Await.result(fut, timeout.duration)
                    }.toOption
                    res match {
                      case None =>
                        StatusCodes.RequestTimeout
                      case Some(f) =>
                        if (f == StatusCodes.Accepted) {
                          var attempts = 0
                          var peerAdded = false
                          while (attempts < 5) {
                            attempts += 1
                            Thread.sleep(1500)
                            //peerAdded = peers.exists(p => v == p.data.externalAddress)
                            peerAdded = peerLookup.contains(v)
                          }
                          if (peerAdded) StatusCodes.OK else StatusCodes.NetworkConnectTimeout
                        } else f
                    }
                }

                logger.debug(s"New peer request $peerAddress statusCode: $result")
                result
              } match {
                case Failure(e) => e.printStackTrace()
                  complete(StatusCodes.InternalServerError)
                case Success(x) => complete(x)
              }
            }
          } ~
          path("ip") {
            entity(as[String]) { externalIp =>
              var ipp : String = ""
              val addr = externalIp.replaceAll('"'.toString,"").split(":") match {
                case Array(ip, port) =>
                  ipp = ip
                  new InetSocketAddress(ip, port.toInt)
                case a@_ => { logger.debug(s"Unmatched Array: $a"); throw new RuntimeException(s"Bad Match: $a"); }
              }
              logger.debug(s"Set external IP RPC request $externalIp $addr")
              data.externalAddress = addr
              if (ipp.nonEmpty) data.apiAddress = new InetSocketAddress(ipp, addr.getPort)
              complete(StatusCodes.OK)
            }
          } ~
          path("reputation") {
            entity(as[Seq[UpdateReputation]]) { ur =>
              secretReputation = ur.flatMap{ r => r.secretReputation.map{id -> _}}.toMap
              publicReputation = ur.flatMap{ r => r.publicReputation.map{id -> _}}.toMap
              complete(StatusCodes.OK)
            }
          }
      }
}