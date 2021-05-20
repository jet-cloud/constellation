package org.constellation.infrastructure.endpoints

import cats.effect.Concurrent
import cats.syntax.all._
import io.circe.syntax._
import org.constellation.domain.p2p.PeerHealthCheck
import org.constellation.domain.p2p.PeerHealthCheck.PeerHealthCheckStatus
import org.constellation.domain.trust.TrustData
import org.constellation.p2p.{Cluster, JoinedHeight, PeerUnregister, SetNodeStatus}
import org.constellation.schema.{Id, NodeState}
import org.constellation.trust.TrustManager
import org.http4s.HttpRoutes
import org.http4s.circe._
import org.http4s.dsl.Http4sDsl
import org.constellation.p2p.Cluster.ClusterNode._
import SetNodeStatus._
import PeerHealthCheckStatus._
import JoinedHeight._
import PeerUnregister._
import TrustData._
import Id._
import org.constellation.schema.observation.ObservationEvent
import org.constellation.session.Registration.`X-Id`

class ClusterEndpoints[F[_]](implicit F: Concurrent[F]) extends Http4sDsl[F] {

  def publicEndpoints(cluster: Cluster[F], trustManager: TrustManager[F]) =
    infoEndpoint(cluster) <+> trustEndpoint(trustManager)

  def peerEndpoints(cluster: Cluster[F], trustManager: TrustManager[F]) =
    infoEndpoint(cluster) <+>
      setNodeStatusEndpoint(cluster) <+>
      setJoiningHeightEndpoint(cluster) <+>
      deregisterEndpoint(cluster) <+>
      trustEndpoint(trustManager) <+>
      getActiveFullNodesEndpoint(cluster) <+>
      sendJoiningNotificationEndpoint(cluster)

  private def infoEndpoint(cluster: Cluster[F]): HttpRoutes[F] =
    HttpRoutes.of[F] {
      case GET -> Root / "cluster" / "info" =>
        cluster.clusterNodes().map(_.asJson).flatMap(Ok(_))
    }

  private def setNodeStatusEndpoint(cluster: Cluster[F]): HttpRoutes[F] = HttpRoutes.of[F] {
    case req @ POST -> Root / "status" =>
      (for {
        sns <- req.decodeJson[SetNodeStatus]
        _ <- if (sns.nodeStatus == NodeState.Offline) {
          cluster.markOfflinePeer(sns.id)
        } else {
          cluster.setNodeStatus(sns.id, sns.nodeStatus)
        }
      } yield ()) >> Ok()
  }

  private def setJoiningHeightEndpoint(cluster: Cluster[F]): HttpRoutes[F] = HttpRoutes.of[F] {
    case req @ POST -> Root / "joinedHeight" =>
      (for {
        joiningHeight <- req.decodeJson[JoinedHeight]
        _ <- cluster.updateJoinedHeight(joiningHeight)
      } yield ()) >> Ok()
  }

  private def deregisterEndpoint(cluster: Cluster[F]): HttpRoutes[F] = HttpRoutes.of[F] {
    case req @ POST -> Root / "deregister" =>
      (for {
        peerUnregister <- req.decodeJson[PeerUnregister]
        _ <- cluster.deregister(peerUnregister)
      } yield ()) >> Ok()
  }

  private def trustEndpoint(trustManager: TrustManager[F]): HttpRoutes[F] = HttpRoutes.of[F] {
    case GET -> Root / "trust" =>
      trustManager.getPredictedReputation.flatMap { predicted =>
        if (predicted.isEmpty) trustManager.getStoredReputation.map(TrustData(_))
        else TrustData(predicted).pure[F]
      }.map(_.asJson).flatMap(Ok(_))
  }

  private def getActiveFullNodesEndpoint(cluster: Cluster[F]): HttpRoutes[F] = HttpRoutes.of[F] {
    case GET -> Root / "cluster" / "active-full-nodes" =>
      cluster.getActiveFullNodes(true).map {
        case activeFullNodes if activeFullNodes.isEmpty => none[Set[Id]]
        case activeFullNodes => activeFullNodes.some
      }
        .flatMap(payload => Ok(payload.asJson))
  }

  private def sendJoiningNotificationEndpoint(cluster: Cluster[F]): HttpRoutes[F] = HttpRoutes.of[F] {
    case req @ POST -> Root / "cluster" / "join-notification" =>
      for {
        maybeId <- F.delay(req.headers.get(`X-Id`).map(_.value).map(Id(_)))
        response <- {
          maybeId match {
            case Some(id) =>
              cluster.handleJoiningClusterNotification(id) >> // TODO: shouldn't we have observation service available here?
                Ok()
            case None => BadRequest()
          }
        }
      } yield response
  }
}

object ClusterEndpoints {

  def publicEndpoints[F[_]: Concurrent](cluster: Cluster[F], trustManager: TrustManager[F]): HttpRoutes[F] =
    new ClusterEndpoints[F].publicEndpoints(cluster, trustManager)

  def peerEndpoints[F[_]: Concurrent](
    cluster: Cluster[F],
    trustManager: TrustManager[F]
  ): HttpRoutes[F] =
    new ClusterEndpoints[F].peerEndpoints(cluster, trustManager)

}
