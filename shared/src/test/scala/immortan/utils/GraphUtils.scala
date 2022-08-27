package immortan.utils

import scoin.Crypto.PublicKey
import scoin.{Block, ByteVector32, ByteVector64}
import scoin.ln._
import immortan.router.Graph.GraphStructure.GraphEdge
import immortan.router.Router.{
  ChannelDesc,
  RouteParams,
  RouteRequest,
  RouterConf
}
import immortan.router.{Announcements, ChannelUpdateExt, Sync}
import scoin.ln.{
  ChannelAnnouncement,
  ChannelUpdate,
  FullPaymentTag,
  PaymentTagTlv
}
import immortan.crypto.Tools._
import immortan.sqlite.SQLiteNetwork
import scodec.bits.ByteVector

object GraphUtils {
  val PlaceHolderSig: ByteVector64 = ByteVector64(ByteVector.fill(64)(0xaa))

  val (aP, bP, cP, dP, sP, eP) =
    (randomKey, randomKey, randomKey, randomKey, randomKey, randomKey)
  val (a, b, c, d, s, e) = (
    aP.publicKey,
    bP.publicKey,
    cP.publicKey,
    dP.publicKey,
    sP.publicKey,
    eP.publicKey
  )

  var routerConf: RouterConf =
    RouterConf(
      routeMaxCltv = CltvExpiryDelta(2016),
      initRouteMaxLength = 6,
      maxRemoteAttempts = 12,
      maxDirectionFailures = 12,
      maxStrangeNodeFailures = 12
    )

  val offChainFeeRatio = 0.01 // %

  def makeUpdate(
      shortChannelId: Long,
      nodeId1: PublicKey,
      nodeId2: PublicKey,
      feeBase: MilliSatoshi,
      feeProportionalMillionth: Int,
      cltvDelta: CltvExpiryDelta,
      minHtlc: MilliSatoshi,
      maxHtlc: MilliSatoshi
  ): ChannelUpdate = {

    val isNode1 = Announcements.isNode1(nodeId1, nodeId2)
    ChannelUpdate(
      signature = PlaceHolderSig,
      chainHash = Block.RegtestGenesisBlock.hash,
      shortChannelId = shortChannelId,
      timestamp = System.currentTimeMillis,
      messageFlags = 1,
      channelFlags = if (isNode1) 0 else 1,
      cltvExpiryDelta = cltvDelta,
      htlcMinimumMsat = minHtlc,
      feeBaseMsat = feeBase,
      feeProportionalMillionths = feeProportionalMillionth,
      htlcMaximumMsat = Some(maxHtlc)
    )
  }

  def makeEdge(
      shortChannelId: Long,
      nodeId1: PublicKey,
      nodeId2: PublicKey,
      feeBase: MilliSatoshi,
      feeProportionalMillionth: Int,
      minHtlc: MilliSatoshi,
      maxHtlc: MilliSatoshi,
      cltvDelta: CltvExpiryDelta = CltvExpiryDelta(0),
      score: Int = 1
  ): GraphEdge = {

    val update = makeUpdate(
      shortChannelId,
      nodeId1,
      nodeId2,
      feeBase,
      feeProportionalMillionth,
      cltvDelta,
      minHtlc,
      maxHtlc
    )
    val updateExt = ChannelUpdateExt(
      update,
      Sync.getChecksum(update),
      score,
      useHeuristics = true
    )
    GraphEdge(ChannelDesc(shortChannelId, nodeId1, nodeId2), updateExt)
  }

  def makeAnnouncement(
      shortChannelId: Long,
      nodeIdA: PublicKey,
      nodeIdB: PublicKey
  ): ChannelAnnouncement = {

    val isNode1 = Announcements.isNode1(nodeIdA, nodeIdB)
    val (nodeId1, nodeId2) =
      if (isNode1) (nodeIdA, nodeIdB) else (nodeIdB, nodeIdA)

    ChannelAnnouncement(
      PlaceHolderSig,
      PlaceHolderSig,
      PlaceHolderSig,
      PlaceHolderSig,
      Features.empty,
      Block.RegtestGenesisBlock.hash,
      shortChannelId,
      nodeId1,
      nodeId2,
      randomKey.publicKey,
      randomKey.publicKey
    )
  }

  def getParams(
      conf: RouterConf,
      amount: MilliSatoshi,
      feeRatio: Double
  ): RouteParams = {
    RouteParams(
      feeReserve = amount * feeRatio,
      routeMaxLength = conf.initRouteMaxLength,
      routeMaxCltv = conf.routeMaxCltv
    )
  }

  def makeRouteRequest(
      amount: MilliSatoshi,
      params: RouteParams,
      fromNode: PublicKey,
      fromLocalEdge: GraphEdge
  ): RouteRequest = {
    val fullTag = FullPaymentTag(
      paymentHash = randomBytes32,
      paymentSecret = randomBytes32,
      tag = PaymentTagTlv.LOCALLY_SENT
    )
    RouteRequest(
      fullTag,
      partId = ByteVector32.Zeroes.bytes,
      fromNode,
      target = d,
      amount,
      fromLocalEdge,
      params
    )
  }

  def fillBasicGraph(store: SQLiteNetwork): Unit = {
    val channelAB: ChannelAnnouncement = makeAnnouncement(1L, a, b)
    val channelAC: ChannelAnnouncement = makeAnnouncement(2L, a, c)
    val channelBD: ChannelAnnouncement = makeAnnouncement(3L, b, d)
    val channelCD: ChannelAnnouncement = makeAnnouncement(4L, c, d)

    //     / b \
    //    a     d
    //     \ c /

    val updateABFromA: ChannelUpdate = makeUpdate(
      1L,
      a,
      b,
      MilliSatoshi(1),
      10,
      cltvDelta = CltvExpiryDelta(144),
      minHtlc = MilliSatoshi(10L),
      maxHtlc = MilliSatoshi(500000)
    )
    val updateABFromB: ChannelUpdate = makeUpdate(
      1L,
      b,
      a,
      MilliSatoshi(1),
      10,
      cltvDelta = CltvExpiryDelta(144),
      minHtlc = MilliSatoshi(10L),
      maxHtlc = MilliSatoshi(500000)
    )

    val updateACFromA: ChannelUpdate = makeUpdate(
      2L,
      a,
      c,
      MilliSatoshi(1),
      10,
      cltvDelta = CltvExpiryDelta(134),
      minHtlc = MilliSatoshi(10L),
      maxHtlc = MilliSatoshi(500000)
    )
    val updateACFromC: ChannelUpdate = makeUpdate(
      2L,
      c,
      a,
      MilliSatoshi(1),
      10,
      cltvDelta = CltvExpiryDelta(134),
      minHtlc = MilliSatoshi(10L),
      maxHtlc = MilliSatoshi(500000)
    )

    val updateBDFromB: ChannelUpdate = makeUpdate(
      3L,
      b,
      d,
      MilliSatoshi(1),
      10,
      cltvDelta = CltvExpiryDelta(144),
      minHtlc = MilliSatoshi(10L),
      maxHtlc = MilliSatoshi(500000)
    )
    val updateBDFromD: ChannelUpdate = makeUpdate(
      3L,
      d,
      b,
      MilliSatoshi(1),
      10,
      cltvDelta = CltvExpiryDelta(144),
      minHtlc = MilliSatoshi(10L),
      maxHtlc = MilliSatoshi(500000)
    )

    val updateCDFromC: ChannelUpdate = makeUpdate(
      4L,
      c,
      d,
      MilliSatoshi(1),
      10,
      cltvDelta = CltvExpiryDelta(144),
      minHtlc = MilliSatoshi(10L),
      maxHtlc = MilliSatoshi(500000)
    )
    val updateCDFromD: ChannelUpdate = makeUpdate(
      4L,
      d,
      c,
      MilliSatoshi(1),
      10,
      cltvDelta = CltvExpiryDelta(144),
      minHtlc = MilliSatoshi(10L),
      maxHtlc = MilliSatoshi(500000)
    )

    val addChannelAnnouncementNewSqlPQ =
      store.db.makePreparedQuery(store.announceTable.newSql)

    store.db txWrap {
      store.addChannelAnnouncement(channelAB, addChannelAnnouncementNewSqlPQ)
      store.addChannelAnnouncement(channelAC, addChannelAnnouncementNewSqlPQ)
      store.addChannelAnnouncement(channelBD, addChannelAnnouncementNewSqlPQ)
      store.addChannelAnnouncement(channelCD, addChannelAnnouncementNewSqlPQ)

      store.addChannelUpdateByPosition(updateABFromA)
      store.addChannelUpdateByPosition(updateABFromB)

      store.addChannelUpdateByPosition(updateACFromA)
      store.addChannelUpdateByPosition(updateACFromC)

      store.addChannelUpdateByPosition(updateBDFromB)
      store.addChannelUpdateByPosition(updateBDFromD)

      store.addChannelUpdateByPosition(updateCDFromC)
      store.addChannelUpdateByPosition(updateCDFromD)
    }

    addChannelAnnouncementNewSqlPQ.close()
  }

  def fillDirectGraph(store: SQLiteNetwork): Unit = {
    val channelAD1: ChannelAnnouncement = makeAnnouncement(1L, a, d)
    val channelAD2: ChannelAnnouncement = makeAnnouncement(2L, a, d)

    //    a  =  d

    val updateAD1FromA: ChannelUpdate = makeUpdate(
      1L,
      a,
      d,
      MilliSatoshi(1),
      10,
      cltvDelta = CltvExpiryDelta(144),
      minHtlc = MilliSatoshi(10L),
      maxHtlc = MilliSatoshi(500000)
    )
    val updateAD1FromD: ChannelUpdate = makeUpdate(
      1L,
      d,
      a,
      MilliSatoshi(1),
      10,
      cltvDelta = CltvExpiryDelta(144),
      minHtlc = MilliSatoshi(10L),
      maxHtlc = MilliSatoshi(500000)
    )

    val updateAD2FromA: ChannelUpdate = makeUpdate(
      2L,
      a,
      d,
      MilliSatoshi(1),
      10,
      cltvDelta = CltvExpiryDelta(134),
      minHtlc = MilliSatoshi(10L),
      maxHtlc = MilliSatoshi(500000)
    )
    val updateAD2FromD: ChannelUpdate = makeUpdate(
      2L,
      d,
      a,
      MilliSatoshi(1),
      10,
      cltvDelta = CltvExpiryDelta(134),
      minHtlc = MilliSatoshi(10L),
      maxHtlc = MilliSatoshi(500000)
    )

    val addChannelAnnouncementNewSqlPQ =
      store.db.makePreparedQuery(store.announceTable.newSql)

    store.db txWrap {
      store.addChannelAnnouncement(channelAD1, addChannelAnnouncementNewSqlPQ)
      store.addChannelAnnouncement(channelAD2, addChannelAnnouncementNewSqlPQ)

      store.addChannelUpdateByPosition(updateAD1FromA)
      store.addChannelUpdateByPosition(updateAD1FromD)

      store.addChannelUpdateByPosition(updateAD2FromA)
      store.addChannelUpdateByPosition(updateAD2FromD)
    }

    addChannelAnnouncementNewSqlPQ.close()
  }
}
