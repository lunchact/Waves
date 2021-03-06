package com.wavesplatform.state.diffs

import java.util.concurrent.ThreadLocalRandom

import com.wavesplatform.BlockGen
import com.wavesplatform.account.PrivateKeyAccount
import com.wavesplatform.block.Block
import com.wavesplatform.db.WithState
import com.wavesplatform.lagonaki.mocks.TestBlock
import com.wavesplatform.mining.MiningConstraint
import com.wavesplatform.settings.FunctionalitySettings
import com.wavesplatform.state.{Blockchain, Diff, EitherExt2}
import com.wavesplatform.transaction.{GenesisTransaction, ValidationError}
import org.scalatest.{FreeSpecLike, Matchers}
import com.wavesplatform.crypto._

class BlockDifferTest extends FreeSpecLike with Matchers with BlockGen with WithState {

  private val TransactionFee = 10

  def randomPrivateKeyAccount(): PrivateKeyAccount = {
    val seed = Array.ofDim[Byte](KeyLength)
    ThreadLocalRandom.current().nextBytes(seed)
    PrivateKeyAccount(seed)
  }

  private val signerA, signerB = randomPrivateKeyAccount()

  private val testChain: Seq[Block] = {
    val master, recipient = randomPrivateKeyAccount()
    getTwoMinersBlockChain(master, recipient, 9)
  }

  "BlockDiffer" - {
    "enableMicroblocksAfterHeight" - {
      /*
      | N | fee | signer | A receive | A balance | B receive | B balance |
      |--:|:---:|:------:|----------:|----------:|----------:|-----------|
      |1  |0    |A       |0          |0          |0          |0          | <- genesis
      |2  |10   |B       |0          |0          |10         |+10        |
      |3  |10   |A       |10         |+10        |0          |0          |
      |4  |10   |B       |0          |10         |+10        |10+10=20   |
      |5  |10   |A       |10         |10+10=20   |0          |20         |
      |6  |10   |B       |0          |20         |+10        |20+10=30   |
      |7  |10   |A       |10         |20+10=30   |0          |30         |
      |8  |10   |B       |0          |30         |+10        |30+10=40   |
      |9  |10   |A       |10         |30+10=40   |0          |40         | <- 1st check
      |10 |10   |B       |0          |40         |+10        |40+10=50   | <- 2nd check
       */
      "height < enableMicroblocksAfterHeight - a miner should receive 100% of the current block's fee" in {
        assertDiff(testChain.init, 1000) {
          case (_, s) =>
            s.portfolio(signerA).balance shouldBe 40
        }

        assertDiff(testChain, 1000) {
          case (_, s) =>
            s.portfolio(signerB).balance shouldBe 50
        }
      }

      /*
      | N | fee | signer | A receive | A balance | B receive | B balance |
      |--:|:---:|:------:|----------:|----------:|----------:|-----------|
      |1  |0    |A       |0          |0          |0          |0          | <- genesis
      |2  |10   |B       |0          |0          |10         |+10        |
      |3  |10   |A       |10         |+10        |0          |0          |
      |4  |10   |B       |0          |10         |+10        |10+10=20   |
      |5  |10   |A       |10         |10+10=20   |0          |20         |
      |6  |10   |B       |0          |20         |+10        |20+10=30   |
      |7  |10   |A       |10         |20+10=30   |0          |30         |
      |8  |10   |B       |0          |30         |+10        |30+10=40   |
      |9  |10   |A       |10         |30+10=40   |0          |40         |
      |-------------------------- Enable NG -----------------------------|
      |10 |10   |B       |0          |40         |+4         |40+4=44    | <- check
       */
      "height = enableMicroblocksAfterHeight - a miner should receive 40% of the current block's fee only" in {
        assertDiff(testChain, 9) {
          case (_, s) =>
            s.portfolio(signerB).balance shouldBe 44
        }
      }

      /*
      | N | fee | signer | A receive | A balance | B receive | B balance |
      |--:|:---:|:------:|----------:|----------:|----------:|-----------|
      |1  |0    |A       |0          |0          |0          |0          | <- genesis
      |2  |10   |B       |0          |0          |10         |+10        |
      |3  |10   |A       |10         |+10        |0          |0          |
      |4  |10   |B       |0          |10         |+10        |10+10=20   |
      |-------------------------- Enable NG -----------------------------|
      |5  |10   |A       |4          |10+4=14    |0          |20         |
      |6  |10   |B       |0          |14         |+4+6=10    |20+10=30   |
      |7  |10   |A       |4+6=10     |14+10=24   |0          |30         |
      |8  |10   |B       |0          |24         |+4+6=10    |30+10=40   |
      |9  |10   |A       |4+6=10     |24+10=34   |0          |40         | <- 1st check
      |10 |10   |B       |0          |34         |+4+6=10    |40+10=50   | <- 2nd check
       */
      "height > enableMicroblocksAfterHeight - a miner should receive 60% of previous block's fee and 40% of the current one" in {
        assertDiff(testChain.init, 4) {
          case (_, s) =>
            s.portfolio(signerA).balance shouldBe 34
        }

        assertDiff(testChain, 4) {
          case (_, s) =>
            s.portfolio(signerB).balance shouldBe 50
        }
      }
    }
  }

  //TODO: Use functionality settings and activate NG in preactivated features

  private def assertDiff(blocks: Seq[Block], ngAtHeight: Int)(assertion: (Diff, Blockchain) => Unit): Unit = {
    val fs = FunctionalitySettings(
      featureCheckBlocksPeriod = ngAtHeight / 2,
      blocksForFeatureActivation = 1,
      allowTemporaryNegativeUntil = 0L,
      requireSortedTransactionsAfter = 0L,
      generationBalanceDepthFrom50To1000AfterHeight = 0,
      minimalGeneratingBalanceAfter = 0L,
      allowTransactionsFromFutureUntil = Long.MaxValue,
      allowUnissuedAssetsUntil = 0L,
      allowInvalidReissueInSameBlockUntilTimestamp = 0L,
      allowMultipleLeaseCancelTransactionUntilTimestamp = 0L,
      resetEffectiveBalancesAtHeight = 0,
      blockVersion3AfterHeight = 0,
      preActivatedFeatures = Map[Short, Int]((2, ngAtHeight)),
      doubleFeaturesPeriodsAfterHeight = Int.MaxValue
    )
    assertDiffEiWithPrev(blocks.init, blocks.last, fs)(assertion)
  }

  private def assertDiffEiWithPrev(preconditions: Seq[Block], block: Block, fs: FunctionalitySettings)(assertion: (Diff, Blockchain) => Unit): Unit =
    withStateAndHistory(fs) { bc =>
      def differ(prev: Option[Block], b: Block): Either[ValidationError, Diff] =
        BlockDiffer.fromBlock(fs, bc, prev, b, MiningConstraint.Unlimited).map(_._1)

      zipWithPrev(preconditions).foreach {
        case (prev, b) =>
          bc.append(differ(prev, b).explicitGet(), b)
      }

      val totalDiff1 = differ(preconditions.lastOption, block).explicitGet()
      bc.append(totalDiff1, block)
      assertion(totalDiff1, bc)
    }

  private def getTwoMinersBlockChain(from: PrivateKeyAccount, to: PrivateKeyAccount, numPayments: Int): Seq[Block] = {
    val ts                   = System.currentTimeMillis() - 100000
    val genesisTx            = GenesisTransaction.create(from, Long.MaxValue - 1, ts).explicitGet()
    val features: Set[Short] = Set[Short](2)

    val paymentTxs = (1 to numPayments).map { i =>
      createWavesTransfer(
        from,
        to,
        amount = 10000,
        TransactionFee,
        timestamp = ts + i * 1000
      ).explicitGet()
    }

    (genesisTx +: paymentTxs).zipWithIndex.map {
      case (x, i) =>
        val signer = if (i % 2 == 0) signerA else signerB
        TestBlock.create(signer, Seq(x), features)
    }
  }
}
