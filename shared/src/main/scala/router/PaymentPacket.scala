package immortan.router

import scala.reflect.ClassTag
import scodec.bits.ByteVector
import scodec.{Attempt, DecodeResult}
import scoin._
import scoin.Crypto.{PrivateKey, PublicKey}
import scoin.ln._

import immortan.router.Router.{ChannelHop, Hop, NodeHop}

sealed trait IncomingPacket

object IncomingPacket {

  /** We are the final recipient. */
  case class FinalPacket(add: UpdateAddHtlc, payload: PaymentOnion.FinalPayload)
      extends IncomingPacket

  /** We are an intermediate node. */
  sealed trait RelayPacket extends IncomingPacket

  /** We must relay the payment to a direct peer. */
  case class ChannelRelayPacket(
      add: UpdateAddHtlc,
      payload: PaymentOnion.ChannelRelayPayload,
      nextPacket: OnionRoutingPacket
  ) extends RelayPacket {
    val relayFeeMsat: MilliSatoshi = add.amountMsat - payload.amountToForward
    val expiryDelta: CltvExpiryDelta = add.cltvExpiry - payload.outgoingCltv
  }

  /** We must relay the payment to a remote node. */
  case class NodeRelayPacket(
      add: UpdateAddHtlc,
      outerPayload: PaymentOnion.FinalPayload,
      innerPayload: PaymentOnion.NodeRelayPayload,
      nextPacket: OnionRoutingPacket
  ) extends RelayPacket

  case class DecodedOnionPacket[T <: PaymentOnion.PacketType](
      payload: T,
      next: OnionRoutingPacket
  )

  private def decryptOnion[T <: PaymentOnion.PacketType: ClassTag](
      add: UpdateAddHtlc,
      privateKey: PrivateKey
  )(
      packet: OnionRoutingPacket,
      packetType: Sphinx.OnionRoutingPacket[T]
  ): Either[FailureMessage, DecodedOnionPacket[T]] =
    packetType.peel(privateKey, add.paymentHash, packet) match {
      case Right(p @ Sphinx.DecryptedPacket(payload, nextPacket, _)) =>
        OnionCodecs
          .perHopPayloadCodecByPacketType(packetType, p.isLastPacket)
          .decode(payload.bits) match {
          case Attempt.Successful(DecodeResult(perHopPayload: T, _)) =>
            Right(DecodedOnionPacket(perHopPayload, nextPacket))
          case Attempt.Failure(e: OnionCodecs.MissingRequiredTlv) =>
            Left(e.failureMessage)
          // Onion is correctly encrypted but the content of the per-hop payload couldn't be parsed.
          // It's hard to provide tag and offset information from scodec failures, so we currently don't do it.
          case Attempt.Failure(_) => Left(InvalidOnionPayload(UInt64(0), 0))
        }
      case Left(badOnion) => Left(badOnion)
    }

  /** Decrypt the onion packet of a received htlc. If we are the final
    * recipient, we validate that the HTLC fields match the onion fields (this
    * prevents intermediate nodes from sending an invalid amount or expiry).
    *
    * NB: we can't fully validate RelayPackets because it requires knowing the
    * channel/route we'll be using, which we don't know yet. Such validation is
    * the responsibility of downstream components.
    *
    * @param add
    *   incoming htlc
    * @param privateKey
    *   this node's private key
    * @return
    *   whether the payment is to be relayed or if our node is the final
    *   recipient (or an error).
    */
  def decrypt(
      add: UpdateAddHtlc,
      privateKey: PrivateKey
  ): Either[FailureMessage, IncomingPacket] = {
    decryptOnion(add, privateKey)(
      add.onionRoutingPacket,
      Sphinx.PaymentPacket
    ) match {
      case Left(failure) => Left(failure)
      // NB: we don't validate the ChannelRelayPacket here because its fees and cltv depend on what channel we'll choose to use.
      case Right(
            DecodedOnionPacket(payload: Onion.ChannelRelayPayload, next)
          ) =>
        Right(ChannelRelayPacket(add, payload, next))
      case Right(DecodedOnionPacket(payload: Onion.FinalTlvPayload, _)) =>
        payload.records.get[OnionTlv.TrampolineOnion] match {
          case Some(OnionTlv.TrampolineOnion(trampolinePacket)) =>
            decryptOnion(add, privateKey)(
              trampolinePacket,
              Sphinx.TrampolinePacket
            ) match {
              case Left(failure) => Left(failure)
              case Right(
                    DecodedOnionPacket(
                      innerPayload: Onion.NodeRelayPayload,
                      next
                    )
                  ) =>
                validateNodeRelay(add, payload, innerPayload, next)
              case Right(
                    DecodedOnionPacket(innerPayload: Onion.FinalPayload, _)
                  ) =>
                validateFinal(add, payload, innerPayload)
            }
          case None => validateFinal(add, payload)
        }
    }
  }

  private def validateFinal(
      add: UpdateAddHtlc,
      payload: Onion.FinalPayload
  ): Either[FailureMessage, IncomingPacket] = {
    if (add.amountMsat != payload.amount) {
      Left(FinalIncorrectHtlcAmount(add.amountMsat))
    } else if (add.cltvExpiry != payload.expiry) {
      Left(FinalIncorrectCltvExpiry(add.cltvExpiry))
    } else {
      Right(FinalPacket(add, payload))
    }
  }

  private def validateFinal(
      add: UpdateAddHtlc,
      outerPayload: Onion.FinalPayload,
      innerPayload: Onion.FinalPayload
  ): Either[FailureMessage, IncomingPacket] = {
    if (add.amountMsat != outerPayload.amount) {
      Left(FinalIncorrectHtlcAmount(add.amountMsat))
    } else if (add.cltvExpiry != outerPayload.expiry) {
      Left(FinalIncorrectCltvExpiry(add.cltvExpiry))
    } else if (outerPayload.expiry != innerPayload.expiry) {
      Left(
        FinalIncorrectCltvExpiry(add.cltvExpiry)
      ) // previous trampoline didn't forward the right expiry
    } else if (outerPayload.totalAmount != innerPayload.amount) {
      Left(
        FinalIncorrectHtlcAmount(outerPayload.totalAmount)
      ) // previous trampoline didn't forward the right amount
    } else if (innerPayload.paymentSecret.isEmpty) {
      Left(
        InvalidOnionPayload(UInt64(8), 0)
      ) // trampoline recipients always provide a payment secret in the invoice
    } else {
      // We merge contents from the outer and inner payloads.
      // We must use the inner payload's total amount and payment secret because the payment may be split between multiple trampoline payments (#reckless).
      Right(
        FinalPacket(
          add,
          Onion.createMultiPartPayload(
            outerPayload.amount,
            innerPayload.totalAmount,
            outerPayload.expiry,
            innerPayload.paymentSecret
          )
        )
      )
    }
  }

  private def validateNodeRelay(
      add: UpdateAddHtlc,
      outerPayload: Onion.FinalPayload,
      innerPayload: Onion.NodeRelayPayload,
      next: OnionRoutingPacket
  ): Either[FailureMessage, IncomingPacket] = {
    if (add.amountMsat < outerPayload.amount) {
      Left(FinalIncorrectHtlcAmount(add.amountMsat))
    } else if (add.cltvExpiry != outerPayload.expiry) {
      Left(FinalIncorrectCltvExpiry(add.cltvExpiry))
    } else {
      Right(NodeRelayPacket(add, outerPayload, innerPayload, next))
    }
  }

}

/** Helpers to create outgoing payment packets. */
object OutgoingPacket {

  /** Build an encrypted onion packet from onion payloads and node public keys.
    */
  def buildOnion[T <: Onion.PacketType](
      packetType: Sphinx.OnionRoutingPacket[T]
  )(
      sessionKey: PrivateKey,
      nodes: Seq[PublicKey],
      payloads: Seq[Onion.PerHopPayload],
      associatedData: ByteVector32
  ): Sphinx.PacketAndSecrets = {
    require(nodes.size == payloads.size)
    val payloadsBin: Seq[ByteVector] = payloads
      .map {
        case p: Onion.FinalPayload =>
          OnionCodecs.finalPerHopPayloadCodec.encode(p)
        case p: Onion.ChannelRelayPayload =>
          OnionCodecs.channelRelayPerHopPayloadCodec.encode(p)
        case p: Onion.NodeRelayPayload =>
          OnionCodecs.nodeRelayPerHopPayloadCodec.encode(p)
      }
      .map {
        case Attempt.Successful(bitVector) => bitVector.bytes
        case Attempt.Failure(cause) =>
          throw new RuntimeException(s"serialization error: $cause")
      }
    packetType.create(sessionKey, nodes, payloadsBin, associatedData)
  }

  /** Build the onion payloads for each hop.
    *
    * @param hops
    *   the hops as computed by the router + extra routes from payment request
    * @param finalPayload
    *   payload data for the final node (amount, expiry, etc)
    * @return
    *   a (firstAmount, firstExpiry, payloads) tuple where:
    *   - firstAmount is the amount for the first htlc in the route
    *   - firstExpiry is the cltv expiry for the first htlc in the route
    *   - a sequence of payloads that will be used to build the onion
    */
  def buildPayloads(
      hops: Seq[Hop],
      finalPayload: Onion.FinalPayload
  ): (MilliSatoshi, CltvExpiry, Seq[Onion.PerHopPayload]) = {
    hops.reverse.foldLeft(
      (
        finalPayload.amount,
        finalPayload.expiry,
        Seq[Onion.PerHopPayload](finalPayload)
      )
    ) { case ((amount, expiry, payloads), hop) =>
      val payload = hop match {
        // Since we don't have any scenario where we add tlv data for intermediate hops, we use legacy payloads.
        case hop: ChannelHop =>
          Onion.RelayLegacyPayload(
            hop.edge.updExt.update.shortChannelId,
            amount,
            expiry
          )
        case hop: NodeHop =>
          Onion.createNodeRelayPayload(amount, expiry, hop.nextNodeId)
      }
      (
        amount + hop.fee(amount),
        expiry + hop.cltvExpiryDelta,
        payload +: payloads
      )
    }
  }

  /** Build an encrypted onion packet with the given final payload.
    *
    * @param hops
    *   the hops as computed by the router + extra routes from payment request,
    *   including ourselves in the first hop
    * @param finalPayload
    *   payload data for the final node (amount, expiry, etc)
    * @return
    *   a (firstAmount, firstExpiry, onion) tuple where:
    *   - firstAmount is the amount for the first htlc in the route
    *   - firstExpiry is the cltv expiry for the first htlc in the route
    *   - the onion to include in the HTLC
    */
  def buildPacket[T <: Onion.PacketType](
      packetType: Sphinx.OnionRoutingPacket[T]
  )(
      sessionKey: PrivateKey,
      paymentHash: ByteVector32,
      hops: Seq[Hop],
      finalPayload: Onion.FinalPayload
  ): (MilliSatoshi, CltvExpiry, Sphinx.PacketAndSecrets) = {
    val (firstAmount, firstExpiry, payloads) =
      buildPayloads(hops.drop(1), finalPayload)
    val nodes = hops.map(_.nextNodeId)
    // BOLT 2 requires that associatedData == paymentHash
    val onion = buildOnion(packetType)(sessionKey, nodes, payloads, paymentHash)
    (firstAmount, firstExpiry, onion)
  }

  /** Build an encrypted trampoline onion packet when the final recipient
    * doesn't support trampoline. The next-to-last trampoline node payload will
    * contain instructions to convert to a legacy payment.
    *
    * @param invoice
    *   Bolt 11 invoice (features and routing hints will be provided to the
    *   next-to-last node).
    * @param hops
    *   the trampoline hops (including ourselves in the first hop, and the
    *   non-trampoline final recipient in the last hop).
    * @param finalPayload
    *   payload data for the final node (amount, expiry, etc)
    * @return
    *   a (firstAmount, firstExpiry, onion) tuple where:
    *   - firstAmount is the amount for the trampoline node in the route
    *   - firstExpiry is the cltv expiry for the first trampoline node in the
    *     route
    *   - the trampoline onion to include in final payload of a normal onion
    */
  def buildTrampolineToLegacyPacket(
      sessionKey: PrivateKey,
      invoice: PaymentRequest,
      hops: Seq[NodeHop],
      finalPayload: Onion.FinalPayload
  ): (MilliSatoshi, CltvExpiry, Sphinx.PacketAndSecrets) = {
    val (firstAmount, firstExpiry, payloads) = hops
      .drop(1)
      .reverse
      .foldLeft(
        (
          finalPayload.amount,
          finalPayload.expiry,
          Seq[Onion.PerHopPayload](finalPayload)
        )
      ) { case ((amount, expiry, payloads1), hop) =>
        // The next-to-last trampoline hop must include invoice data to indicate the conversion to a legacy payment.
        val payload = if (payloads1.length == 1) {
          Onion.createNodeRelayToNonTrampolinePayload(
            finalPayload.amount,
            finalPayload.totalAmount,
            finalPayload.expiry,
            hop.nextNodeId,
            invoice
          )
        } else {
          Onion.createNodeRelayPayload(amount, expiry, hop.nextNodeId)
        }
        (
          amount + hop.fee(amount),
          expiry + hop.cltvExpiryDelta,
          payload +: payloads1
        )
      }
    val nodes = hops.map(_.nextNodeId)
    val onion = buildOnion(Sphinx.TrampolinePacket)(
      sessionKey,
      nodes,
      payloads,
      invoice.paymentHash
    )
    (firstAmount, firstExpiry, onion)
  }

  import immortan.crypto.Tools._
  def buildHtlcFailure(
      cmd: CMD_FAIL_HTLC,
      theirAdd: UpdateAddHtlc
  ): UpdateMessage = {
    (
      Sphinx.PaymentPacket.peel(
        cmd.nodeSecret,
        theirAdd.paymentHash,
        theirAdd.onionRoutingPacket
      ),
      cmd.reason
    ) match {
      case Right(packet) ~ Left(forwarded) =>
        UpdateFailHtlc(
          theirAdd.channelId,
          reason = Sphinx.FailurePacket.wrap(forwarded, packet.sharedSecret),
          id = cmd.theirAdd.id
        )
      case Right(packet) ~ Right(failure) =>
        UpdateFailHtlc(
          theirAdd.channelId,
          reason = Sphinx.FailurePacket.create(packet.sharedSecret, failure),
          id = cmd.theirAdd.id
        )
      case Left(fail) ~ _ =>
        UpdateFailMalformedHtlc(
          theirAdd.channelId,
          cmd.theirAdd.id,
          fail.onionHash,
          fail.code
        )
    }
  }
}
