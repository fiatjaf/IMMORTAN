package fr.acinq.eclair.blockchain.electrum

import java.util.concurrent.atomic.AtomicLong

import fr.acinq.bitcoin.{BlockHeader, ByteVector32, Transaction}
import fr.acinq.eclair.blockchain._
import fr.acinq.eclair.blockchain.electrum.ElectrumClient.computeScriptHash
import fr.acinq.eclair.channel.{
  BITCOIN_FUNDING_DEPTHOK,
  BITCOIN_PARENT_TX_CONFIRMED
}
import fr.acinq.eclair.transactions.Scripts

import scala.collection.immutable.{Queue, SortedMap}
import scala.util.{Failure, Success}

class ElectrumWatcher(blockCount: AtomicLong, pool: ElectrumClientPool)(implicit
    ac: castor.Context
) extends CastorStateMachineActorWithSetState[Any] { self =>

  EventStream.subscribe {
    case _: ElectrumReady => onElectrumReady()
    case _: ElectrumDisconnected =>
      state match {
        case s: Running =>
          // we remember watches and keep track of tx that have not yet been published
          // we also re-send the txes that we previously sent but hadn't yet received the confirmation
          setState(
            Disconnected(
              s.watches,
              s.sent.map(PublishAsap),
              s.block2tx
            )
          )
        case _ =>
      }
  }

  def onElectrumReady(): Unit =
    if (state.isInstanceOf[Disconnected])
      pool.subscribeToHeaders("watcher", self.send(_)).foreach(self.send(_))

  pool.getReady match {
    case None    =>
    case Some(_) => onElectrumReady()
  }

  def stay = state
  def initialState =
    Disconnected(Set.empty, Queue.empty, SortedMap.empty)

  case class Disconnected(
      watches: Set[Watch],
      publishQueue: Queue[PublishAsap],
      block2tx: SortedMap[Long, Seq[Transaction]]
  ) extends State({
        case ElectrumClient.HeaderSubscriptionResponse(_, height, header) => {
          watches.foreach(self.send(_))
          publishQueue.foreach(self.send(_))
          Running(
            height,
            header,
            Set(),
            Map(),
            block2tx,
            Queue.empty
          )
        }

        case watch: Watch => {
          Disconnected(
            watches + watch,
            publishQueue,
            block2tx
          )
        }

        case publish: PublishAsap => {
          Disconnected(
            watches,
            publishQueue :+ publish,
            block2tx
          )
        }
      })

  case class Running(
      height: Int,
      tip: BlockHeader,
      watches: Set[Watch],
      scriptHashStatus: Map[ByteVector32, String],
      block2tx: SortedMap[Long, Seq[Transaction]],
      sent: Queue[Transaction]
  ) extends State({
        case ElectrumClient.HeaderSubscriptionResponse(_, _, newtip)
            if tip == newtip =>
          stay

        case ElectrumClient
              .HeaderSubscriptionResponse(_, newheight, newtip) => {
          watches collect { case watch: WatchConfirmed =>
            val scriptHash = computeScriptHash(watch.publicKeyScript)
            getScriptHashHistory(scriptHash)
          }
          val toPublish = block2tx.view.filterKeys(_ <= newheight)
          toPublish.values.flatten.foreach(publish(_))
          Running(
            newheight,
            newtip,
            watches,
            scriptHashStatus,
            block2tx -- toPublish.keys,
            sent ++ toPublish.values.flatten
          )
        }

        case watch: Watch if watches.contains(watch) => stay

        case watch @ WatchSpent(
              _,
              txid,
              outputIndex,
              publicKeyScript,
              _,
              _
            ) => {
          val scriptHash = computeScriptHash(publicKeyScript)
          System.err.println(
            s"[info] added watch-spent on output=$txid:$outputIndex scriptHash=$scriptHash"
          )
          trackScriptHash(scriptHash)

          Running(
            height,
            tip,
            watches + watch,
            scriptHashStatus,
            block2tx,
            sent
          )
        }

        case watch @ WatchConfirmed(_, txid, publicKeyScript, _, _) => {
          val scriptHash = computeScriptHash(publicKeyScript)
          System.err.println(
            s"[info] added watch-confirmed on txid=$txid scriptHash=$scriptHash"
          )
          trackScriptHash(scriptHash)

          Running(
            height,
            tip,
            watches + watch,
            scriptHashStatus,
            block2tx,
            sent
          )
        }

        case PublishAsap(tx) => {
          val blockCount = this.blockCount.get()
          val cltvTimeout = Scripts.cltvTimeout(tx)
          val csvTimeouts = Scripts.csvTimeouts(tx)
          if (csvTimeouts.nonEmpty) {
            // watcher supports txs with multiple csv-delayed inputs: we watch all delayed parents and try to publish every
            // time a parent's relative delays are satisfied, so we will eventually succeed.
            csvTimeouts.foreach { case (parentTxId, csvTimeout) =>
              System.err.println(
                s"[info] txid=${tx.txid} has a relative timeout of $csvTimeout blocks, watching parentTxId=$parentTxId tx={}",
                tx
              )
              val parentPublicKeyScript = WatchConfirmed.extractPublicKeyScript(
                tx.txIn.find(_.outPoint.txid == parentTxId).get.witness
              )
              self.send(
                WatchConfirmed(
                  self,
                  parentTxId,
                  parentPublicKeyScript,
                  minDepth = csvTimeout,
                  BITCOIN_PARENT_TX_CONFIRMED(tx)
                )
              )
            }
            stay
          } else if (cltvTimeout > blockCount) {
            System.err.println(
              s"[info] delaying publication of txid=${tx.txid} until block=$cltvTimeout (curblock=$blockCount)"
            )
            val block2tx1 = block2tx.updated(
              cltvTimeout,
              block2tx.getOrElse(cltvTimeout, Seq.empty[Transaction]) :+ tx
            )
            Running(
              height,
              tip,
              watches,
              scriptHashStatus,
              block2tx1,
              sent
            )
          } else {
            publish(tx)
            Running(
              height,
              tip,
              watches,
              scriptHashStatus,
              block2tx,
              sent :+ tx
            )
          }
        }

        case WatchEventConfirmed(BITCOIN_PARENT_TX_CONFIRMED(tx), _, _) => {
          System.err.println(
            s"[info] parent tx of txid=${tx.txid} has been confirmed"
          )
          val blockCount = this.blockCount.get()
          val cltvTimeout = Scripts.cltvTimeout(tx)
          if (cltvTimeout > blockCount) {
            System.err.println(
              s"[info] delaying publication of txid=${tx.txid} until block=$cltvTimeout (curblock=$blockCount)"
            )
            val block2tx1 = block2tx.updated(
              cltvTimeout,
              block2tx.getOrElse(cltvTimeout, Seq.empty) :+ tx
            )
            Running(
              height,
              tip,
              watches,
              scriptHashStatus,
              block2tx1,
              sent
            )
          } else {
            publish(tx)
            Running(
              height,
              tip,
              watches,
              scriptHashStatus,
              block2tx,
              sent :+ tx
            )
          }
        }
      })

  def trackScriptHash(scriptHash: ByteVector32): Unit =
    pool.subscribeToScriptHash(scriptHash) {
      case ElectrumClient
            .ScriptHashSubscriptionResponse(scriptHash, status) =>
        state match {
          case Running(
                height,
                tip,
                watches,
                scriptHashStatus,
                block2tx,
                sent
              ) =>
            scriptHashStatus.get(scriptHash) match {
              case Some(s) if s == status =>
                System.err.println(
                  s"[debug] already have status=$status for scriptHash=$scriptHash"
                )
              case _ if status.isEmpty =>
                System.err
                  .println(s"[info] empty status for scriptHash=$scriptHash")
              case _ => {
                System.err.println(
                  s"[info] new status=$status for scriptHash=$scriptHash"
                )
                getScriptHashHistory(scriptHash)
              }
            }
            setState(
              Running(
                height,
                tip,
                watches,
                scriptHashStatus + (scriptHash -> status),
                block2tx,
                sent
              )
            )

          case _ =>
        }
    }

  def getScriptHashHistory(scriptHash: ByteVector32): Unit = {
    pool
      .request[ElectrumClient.GetScriptHashHistoryResponse](
        ElectrumClient.GetScriptHashHistory(scriptHash)
      )
      .onComplete {
        case Success(ElectrumClient.GetScriptHashHistoryResponse(_, history)) =>
          // we retrieve the transaction before checking watches
          // NB: height=-1 means that the tx is unconfirmed and at least one of its inputs is also unconfirmed. we need to take them into consideration if we want to handle unconfirmed txes (which is the case for turbo channels)
          history.filter(_.height >= -1).foreach(getTransaction)

        case Failure(err) =>
          System.err.println(
            s"[error] failed to call electrum server for GetScriptHashHistoryResponse: $err"
          )
      }
  }

  def getTransaction(item: ElectrumClient.TransactionHistoryItem): Unit = pool
    .request[ElectrumClient.GetTransactionResponse](
      ElectrumClient.GetTransaction(item.txHash, Some(item))
    )
    .onComplete {
      case Success(
            ElectrumClient.GetTransactionResponse(
              tx,
              Some(item: ElectrumClient.TransactionHistoryItem)
            )
          ) =>
        state match {
          case running @ Running(
                height,
                tip,
                watches,
                scriptHashStatus,
                block2tx,
                sent
              ) => {
            // this is for WatchSpent/WatchSpentBasic
            val watchSpentTriggered = tx.txIn
              .map(_.outPoint)
              .flatMap(outPoint =>
                watches.collect {
                  case WatchSpent(channel, txid, pos, _, event, _)
                      if txid == outPoint.txid && pos == outPoint.index.toInt =>
                    // NB: WatchSpent are permanent because we need to detect multiple spending of the funding tx
                    // They are never cleaned up but it is not a big deal for now (1 channel == 1 watch)
                    System.err.println(
                      s"[info] output $txid:$pos spent by transaction ${tx.txid}"
                    )
                    channel.send(WatchEventSpent(event, tx))
                    None
                }
              )
              .flatten

            // this is for WatchConfirmed
            val watchConfirmedTriggered = watches.collect {
              case w @ WatchConfirmed(
                    channel,
                    txid,
                    _,
                    minDepth,
                    BITCOIN_FUNDING_DEPTHOK
                  ) if txid == tx.txid && minDepth == 0 =>
                // special case for mempool watches (min depth = 0)
                val (dummyHeight, dummyTxIndex) =
                  ElectrumWatcher.makeDummyShortChannelId(txid)
                channel.send(
                  WatchEventConfirmed(
                    BITCOIN_FUNDING_DEPTHOK,
                    TxConfirmedAt(dummyHeight, tx),
                    dummyTxIndex
                  )
                )
                Some(w)
              case WatchConfirmed(_, txid, _, minDepth, _)
                  if txid == tx.txid && minDepth > 0 && item.height > 0 =>
                // min depth > 0 here
                val txheight = item.height
                val confirmations = height - txheight + 1
                System.err.println(
                  s"[info] txid=$txid was confirmed at height=$txheight and now has confirmations=$confirmations (currentHeight=$height)"
                )
                if (confirmations >= minDepth) {
                  // we need to get the tx position in the block
                }
                getMerkle(tx, txheight)
                None
            }.flatten

            setState(
              running.copy(watches =
                running.watches -- watchSpentTriggered -- watchConfirmedTriggered
              )
            )
          }
        }

      case Success(
            ElectrumClient.GetTransactionResponse(tx, _)
          ) =>
        System.err.println(
          s"[error] GetTransaction response didn't have the context"
        )

      case Failure(err) =>
        System.err.println(
          s"[error] failed to call electrum server for GetTransaction: $err"
        )
    }

  def getMerkle(tx: Transaction, height: Int): Unit =
    pool
      .request[ElectrumClient.GetMerkleResponse](
        ElectrumClient.GetMerkle(tx.txid, height, Some(tx))
      )
      .onComplete {
        case Success(
              ElectrumClient.GetMerkleResponse(
                _,
                tx_hash,
                _,
                txheight,
                pos,
                Some(tx: Transaction)
              )
            ) =>
          state match {
            case running: Running => {
              val confirmations = height - txheight + 1
              val triggered = running.watches.collect {
                case w @ WatchConfirmed(channel, txid, _, minDepth, event)
                    if txid == tx_hash && confirmations >= minDepth =>
                  System.err.println(
                    s"[info] txid=$txid had confirmations=$confirmations in block=$txheight pos=$pos"
                  )
                  channel.send(
                    WatchEventConfirmed(
                      event,
                      TxConfirmedAt(txheight.toInt, tx),
                      pos
                    )
                  )
                  w
              }
              setState(running.copy(watches = running.watches -- triggered))
            }
          }

        case Success(
              ElectrumClient.GetMerkleResponse(_, _, _, _, _, _)
            ) =>
          System.err.println(
            s"[error] GetMerkle response didn't have the context"
          )

        case Failure(err) =>
          System.err.println(
            s"[error] failed to call electrum server for GetMerkle: $err"
          )
      }

  def publish(tx: Transaction): Unit = {
    pool
      .request[ElectrumClient.BroadcastTransactionResponse](
        ElectrumClient.BroadcastTransaction(tx)
      )
      .onComplete {
        case Success(
              ElectrumClient.BroadcastTransactionResponse(tx, error_opt)
            ) =>
          state match {
            case running: Running => {
              error_opt match {
                case None =>
                  System.err.println(
                    s"[info] broadcast succeeded for txid=${tx.txid} tx={}",
                    tx
                  )
                case Some(error)
                    if error.message
                      .contains("transaction already in block chain") =>
                  System.err.println(
                    s"[info] broadcast ignored for txid=${tx.txid} tx={} (tx was already in blockchain)",
                    tx
                  )
                case Some(error) =>
                  System.err.println(
                    s"[error] broadcast failed for txid=${tx.txid} tx=$tx with error=$error"
                  )
              }

              setState(
                running.copy(sent = running.sent diff Seq(tx))
              )
            }
          }

        case Failure(err) =>
          System.err.println(
            s"[error] failed to call electrum server for BroadcastTransaction: $err"
          )
      }
  }
}

object ElectrumWatcher {
  // A (blockHeight, txIndex) tuple that is extracted from the input source
  def makeDummyShortChannelId(txid: ByteVector32): (Int, Int) = {
    val txIndex = txid.bits.sliceToInt(0, 16, signed = false)
    (0, txIndex)
  }
}
