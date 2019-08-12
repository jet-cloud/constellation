package org.constellation.primitives
import cats.effect.{ContextShift, IO}
import cats.implicits._
import com.typesafe.scalalogging.Logger
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import org.constellation.consensus.RandomData
import org.constellation.primitives.Schema.{EdgeHashType, TypedEdgeHash}
import org.constellation.storage.SOEService
import org.constellation.util.Metrics
import org.constellation.{ConstellationContextShift, DAO, Fixtures}
import org.mockito.{ArgumentMatchersSugar, IdiomaticMockito}
import org.scalatest.{FunSpecLike, Matchers}

class TipServiceTest extends FunSpecLike with IdiomaticMockito with ArgumentMatchersSugar with Matchers {

  implicit val dao: DAO = prepareDAO()
  implicit val ioContextShift: ContextShift[IO] = ConstellationContextShift.global
  implicit val unsafeLogger = Slf4jLogger.getLogger[IO]

  def prepareDAO(): DAO = {
    val dao = mock[DAO]

    val metrics = mock[Metrics]
    metrics.incrementMetricAsync[IO](*)(*) shouldReturn IO.unit
    metrics.updateMetricAsync[IO](*, any[Int])(*) shouldReturn IO.unit

    metrics.updateMetricAsync[IO]("activeTips", 2).unsafeRunSync()

    dao.metrics shouldReturn metrics
    dao.miscLogger shouldReturn Logger("MiscLogger")
    dao.soeService shouldReturn new SOEService[IO]()
    dao
  }

  describe("TrieBasedTipService") {

    it("limits maximum number of tips") {
      val limit = 6
      val concurrentTipService = new ConcurrentTipService[IO](limit, 10, 2, 2, 30, dao)

      val cbs = createIndexedCBmocks(limit * 3, { i =>
        createCBMock(i.toString)
      })

      val tasks = createShiftedTasks(cbs.toList, { cb =>
        concurrentTipService.update(cb)
      })
      tasks.par.foreach(_.unsafeRunAsyncAndForget)
      Thread.sleep(2000)
      concurrentTipService.size.unsafeRunSync() shouldBe limit
    }

    it("safely updates a tip ") {
      val maxTipUsage = 40
      val concurrentTipService = new ConcurrentTipService[IO](6, 10, maxTipUsage, 2, 30, dao)

      RandomData.go.initialDistribution
        .storeSOE()
        .flatMap(_ => RandomData.go.initialDistribution2.storeSOE())
        .unsafeRunSync()

      List(RandomData.go.initialDistribution, RandomData.go.initialDistribution2)
        .map(concurrentTipService.update)
        .sequence
        .unsafeRunSync()

      val cb = CheckpointBlock.createCheckpointBlock(Seq(Fixtures.tx), RandomData.startingTips.map { s =>
        TypedEdgeHash(s.hash, EdgeHashType.CheckpointHash)
      })(Fixtures.tempKey)

      val tasks = createShiftedTasks(List.fill(maxTipUsage)(cb), { cb =>
        concurrentTipService.update(cb)
      })
      tasks.par.foreach(_.unsafeRunAsyncAndForget)
      Thread.sleep(2000)
      concurrentTipService.toMap
        .unsafeRunSync()(RandomData.go.initialDistribution.baseHash)
        .numUses shouldBe maxTipUsage
    }

  }

  private def prepareTransactions(): Seq[Transaction] = {
    val tx1 = mock[Transaction]
    tx1.hash shouldReturn "tx1"
    val tx2 = mock[Transaction]
    tx2.hash shouldReturn "tx2"
    Seq(tx1, tx2)
  }

  private def createCBMock(hash: String) = {
    val cb = mock[CheckpointBlock]
    cb.parentSOEBaseHashes shouldReturn Seq.empty
//    cb.transactions shouldReturn Seq.empty
    cb.baseHash shouldReturn hash
    cb
  }

  def createIndexedCBmocks(size: Int, func: Int => CheckpointBlock) =
    (1 to size).map(func)

  def createShiftedTasks(
    cbs: List[CheckpointBlock],
    func: CheckpointBlock => IO[Any]
  ) =
    cbs.map(IO.shift *> func(_))

}