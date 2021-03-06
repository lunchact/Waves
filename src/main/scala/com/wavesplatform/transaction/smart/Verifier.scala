package com.wavesplatform.transaction.smart

import cats.implicits._
import com.wavesplatform.crypto
import com.wavesplatform.state._
import com.wavesplatform.transaction.ValidationError.{GenericError, ScriptExecutionError, TransactionNotAllowedByScript}
import com.wavesplatform.transaction._
import com.wavesplatform.transaction.assets._
import com.wavesplatform.transaction.assets.exchange.{ExchangeTransaction, Order}
import com.wavesplatform.transaction.smart.script.{Script, ScriptRunner}
import com.wavesplatform.transaction.transfer._
import shapeless.{:+:, CNil, Coproduct}

import scala.util.Try

object Verifier {

  private type TxOrd = Transaction :+: Order :+: CNil

  def apply(blockchain: Blockchain, currentBlockHeight: Int)(tx: Transaction): Either[ValidationError, Transaction] =
    (tx match {
      case _: GenesisTransaction => Right(tx)
      case pt: ProvenTransaction =>
        (pt, blockchain.accountScript(pt.sender)) match {
          case (et: ExchangeTransaction, scriptOpt) => verifyExchange(et, blockchain, scriptOpt, currentBlockHeight)
          case (_, Some(script))                    => verifyTx(blockchain, script, currentBlockHeight, pt, false)
          case (stx: SignedTransaction, None)       => stx.signaturesValid()
          case _                                    => verifyAsEllipticCurveSignature(pt)
        }
    }).flatMap(tx => {
      for {
        assetId <- tx match {
          case t: TransferTransaction     => t.assetId
          case t: MassTransferTransaction => t.assetId
          case t: BurnTransaction         => Some(t.assetId)
          case t: ReissueTransaction      => Some(t.assetId)
          case _                          => None
        }

        script <- blockchain.assetDescription(assetId).flatMap(_.script)
      } yield verifyTx(blockchain, script, currentBlockHeight, tx, true)
    }.getOrElse(Either.right(tx)))

  def verifyTx(blockchain: Blockchain,
               script: Script,
               height: Int,
               transaction: Transaction,
               isTokenScript: Boolean): Either[ValidationError, Transaction] =
    Try {
      ScriptRunner[Boolean](height, Coproduct[TxOrd](transaction), blockchain, script) match {
        case (ctx, Left(execError)) => Left(ScriptExecutionError(execError, script.text, ctx.letDefs, isTokenScript))
        case (ctx, Right(false)) =>
          Left(TransactionNotAllowedByScript(ctx.letDefs, script.text, isTokenScript))
        case (_, Right(true)) => Right(transaction)
      }
    }.getOrElse(Left(ScriptExecutionError(
      """
      |Uncaught execution error.
      |Probably script does not return boolean, and can't validate any transaction or order.
    """.stripMargin,
      script.text,
      Map.empty,
      isTokenScript
    )))

  def verifyOrder(blockchain: Blockchain, script: Script, height: Int, order: Order): Either[ValidationError, Order] =
    Try {
      ScriptRunner[Boolean](height, Coproduct[TxOrd](order), blockchain, script) match {
        case (ctx, Left(execError)) => Left(ScriptExecutionError(execError, script.text, ctx.letDefs, false))
        case (ctx, Right(false)) =>
          Left(TransactionNotAllowedByScript(ctx.letDefs, script.text, false))
        case (_, Right(true)) => Right(order)
      }
    }.getOrElse(Left(ScriptExecutionError(
      """
      |Uncaught execution error.
      |Probably script does not return boolean, and can't validate any transaction or order.
    """.stripMargin,
      script.text,
      Map.empty,
      false
    )))

  def verifyExchange(et: ExchangeTransaction,
                     blockchain: Blockchain,
                     matcherScriptOpt: Option[Script],
                     height: Int): Either[ValidationError, Transaction] = {

    val sellOrder = et.sellOrder
    val buyOrder  = et.buyOrder

    lazy val matcherTxVerification =
      matcherScriptOpt
        .map(verifyTx(blockchain, _, height, et, false))
        .getOrElse(verifyAsEllipticCurveSignature(et))

    lazy val sellerOrderVerification =
      blockchain
        .accountScript(sellOrder.sender.toAddress)
        .map(verifyOrder(blockchain, _, height, sellOrder))
        .getOrElse(verifyAsEllipticCurveSignature(sellOrder))

    lazy val buyerOrderVerification =
      blockchain
        .accountScript(buyOrder.sender.toAddress)
        .map(verifyOrder(blockchain, _, height, buyOrder))
        .getOrElse(verifyAsEllipticCurveSignature(buyOrder))

    for {
      _ <- matcherTxVerification
      _ <- sellerOrderVerification
      _ <- buyerOrderVerification
    } yield et
  }

  def verifyAsEllipticCurveSignature[T <: Proven with Authorized](pt: T): Either[ValidationError, T] =
    pt.proofs.proofs match {
      case p :: Nil =>
        Either.cond(crypto.verify(p.arr, pt.bodyBytes(), pt.sender.publicKey),
                    pt,
                    GenericError(s"Script doesn't exist and proof doesn't validate as signature for $pt"))
      case _ => Left(GenericError("Transactions from non-scripted accounts must have exactly 1 proof"))
    }
}
